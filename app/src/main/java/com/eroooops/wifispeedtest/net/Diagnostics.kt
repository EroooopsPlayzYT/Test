package com.eroooops.wifispeedtest.net

import android.net.Network
import com.eroooops.wifispeedtest.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class ProbeStage { DNS, TCP, TLS, HTTP, BODY, DONE }

enum class ProbeState { PENDING, RUNNING, OK, FAILED }

/**
 * What happened when we tried one server, broken down by stage.
 *
 * The stages matter more than the verdict. A host that resolves and connects
 * but dies during the TLS handshake is being filtered on the server name; one
 * that returns an HTTP status is merely misconfigured. Those need opposite
 * fixes and are indistinguishable from "it didn't work".
 */
data class ProbeResult(
    val name: String,
    val host: String,
    val scheme: String,
    val state: ProbeState = ProbeState.PENDING,
    val reachedStage: ProbeStage? = null,
    val dnsMs: Long? = null,
    val tcpMs: Long? = null,
    val tlsMs: Long? = null,
    val firstByteMs: Long? = null,
    val httpStatus: Int? = null,
    val bytes: Long = 0L,
    val error: String? = null,
) {
    val totalMs: Long?
        get() = listOfNotNull(dnsMs, tcpMs, tlsMs, firstByteMs).takeIf { it.isNotEmpty() }?.sum()

    /** One-line verdict for the diagnostics list. */
    val summary: String
        get() = when (state) {
            ProbeState.PENDING -> "waiting"
            ProbeState.RUNNING -> "testing…"
            ProbeState.OK -> buildString {
                append("ok")
                httpStatus?.let { append(" · HTTP $it") }
                totalMs?.let { append(" · ${it}ms") }
            }

            ProbeState.FAILED -> buildString {
                append("failed at ")
                append(reachedStage?.name?.lowercase() ?: "start")
                error?.let { append(" · $it") }
            }
        }
}

/**
 * Runs a staged probe by hand rather than through [java.net.HttpURLConnection].
 *
 * The stock client collapses DNS, connect, handshake and request into one call
 * and one exception, which is exactly the information we need separated. Doing
 * it over a raw socket costs a hand-written request line and buys a precise
 * answer to "how far did we get".
 */
class Prober(private val network: Network?) {

    suspend fun probe(name: String, rawUrl: String, timeoutMs: Int): ProbeResult =
        withContext(Dispatchers.IO) {
            val url = URL(rawUrl)
            val secure = url.protocol.equals("https", ignoreCase = true)
            val port = if (url.port != -1) url.port else url.defaultPort
            var result = ProbeResult(
                name = name,
                host = url.host,
                scheme = url.protocol,
                state = ProbeState.RUNNING,
            )

            var socket: Socket? = null
            try {
                // --- DNS ---
                val dnsStart = System.nanoTime()
                val address: InetAddress = network?.getByName(url.host)
                    ?: InetAddress.getByName(url.host)
                result = result.copy(
                    dnsMs = elapsedMs(dnsStart),
                    reachedStage = ProbeStage.DNS,
                )

                // --- TCP ---
                val tcpStart = System.nanoTime()
                val plain = network?.socketFactory?.createSocket() ?: Socket()
                socket = plain
                plain.connect(InetSocketAddress(address, port), timeoutMs)
                plain.soTimeout = timeoutMs
                result = result.copy(
                    tcpMs = elapsedMs(tcpStart),
                    reachedStage = ProbeStage.TCP,
                )

                // --- TLS ---
                val stream: Socket = if (secure) {
                    val tlsStart = System.nanoTime()
                    val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(plain, url.host, port, true) as SSLSocket
                    tls.soTimeout = timeoutMs
                    tls.startHandshake()
                    socket = tls
                    result = result.copy(
                        tlsMs = elapsedMs(tlsStart),
                        reachedStage = ProbeStage.TLS,
                    )
                    tls
                } else {
                    plain
                }

                // --- HTTP ---
                val requestStart = System.nanoTime()
                val path = url.file.ifEmpty { "/" }
                val request = buildString {
                    append("GET $path HTTP/1.1\r\n")
                    append("Host: ${url.host}\r\n")
                    append("User-Agent: SpeedTest/${BuildConfig.VERSION_NAME}\r\n")
                    append("Accept: */*\r\n")
                    append("Accept-Encoding: identity\r\n")
                    append("Range: bytes=0-65535\r\n")
                    append("Connection: close\r\n\r\n")
                }
                stream.getOutputStream().apply {
                    write(request.toByteArray(Charsets.US_ASCII))
                    flush()
                }

                val input = BufferedInputStream(stream.getInputStream())
                val statusLine = input.readLine() ?: throw IOException("no response")
                result = result.copy(
                    firstByteMs = elapsedMs(requestStart),
                    httpStatus = statusLine.parseStatus(),
                    reachedStage = ProbeStage.HTTP,
                )

                // Drain headers, then confirm a body really arrives.
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val body = ByteArray(16 * 1024)
                val read = input.read(body)
                result = result.copy(
                    bytes = read.coerceAtLeast(0).toLong(),
                    reachedStage = ProbeStage.BODY,
                )

                val status = result.httpStatus
                when {
                    status == null -> result.copy(
                        state = ProbeState.FAILED,
                        error = "unparseable status line",
                    )

                    status !in 200..299 -> result.copy(
                        state = ProbeState.FAILED,
                        error = "HTTP $status",
                    )

                    read <= 0 -> result.copy(
                        state = ProbeState.FAILED,
                        error = "headers but no body",
                    )

                    else -> result.copy(state = ProbeState.OK, reachedStage = ProbeStage.DONE)
                }
            } catch (error: Exception) {
                result.copy(
                    state = ProbeState.FAILED,
                    error = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
                )
            } finally {
                runCatching { socket?.close() }
            }
        }
}

private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

/** Reads a CRLF-terminated line without pulling in a full HTTP client. */
private fun InputStream.readLine(): String? {
    val builder = StringBuilder()
    while (true) {
        val byte = read()
        if (byte == -1) return if (builder.isEmpty()) null else builder.toString()
        if (byte == '\n'.code) return builder.toString().removeSuffix("\r")
        builder.append(byte.toChar())
    }
}

/** "HTTP/1.1 206 Partial Content" -> 206 */
private fun String.parseStatus(): Int? =
    split(' ').getOrNull(1)?.toIntOrNull()
