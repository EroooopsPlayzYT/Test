package com.eroooops.wifispeedtest.net

import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.random.Random

private const val SAMPLE_INTERVAL_MS = 200L
private const val BUFFER_BYTES = 64 * 1024
private const val UPLOAD_REQUEST_BYTES = 64L * 1024 * 1024
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 10_000
private const val PING_TIMEOUT_MS = 4_000

/**
 * Runs a ping / download / upload test and reports progress as a flow.
 *
 * All sockets are opened through the supplied Wi-Fi [Network], so the numbers
 * describe the Wi-Fi link even when the device also has mobile data up and the
 * system would otherwise prefer it.
 */
class SpeedTestEngine(private val network: Network?) {

    fun run(config: TestConfig): Flow<TestUpdate> = channelFlow {
        var snapshot = TestUpdate(phase = Phase.PING)

        suspend fun emit(transform: (TestUpdate) -> TestUpdate) {
            snapshot = transform(snapshot)
            send(snapshot)
        }

        try {
            val downloadUrl = URL(config.downloadUrl)
            val uploadUrl = URL(config.uploadUrl)

            // --- Latency -----------------------------------------------------
            val ping = measureLatency(downloadUrl, config.pingCount) { done, stats ->
                emit {
                    it.copy(
                        phase = Phase.PING,
                        phaseProgress = done.toFloat() / config.pingCount,
                        pingMs = stats.averageMs,
                        jitterMs = stats.jitterMs,
                        packetLossPct = stats.lossPct,
                    )
                }
            }
            if (ping.averageMs == null) {
                throw IOException("No response from ${downloadUrl.host}")
            }

            // --- Download ----------------------------------------------------
            emit { it.copy(phase = Phase.DOWNLOAD, phaseProgress = 0f, instantMbps = 0.0) }
            val download = measureThroughput(
                label = "Download",
                seconds = config.downloadSeconds,
                connections = config.connections,
                onSample = { progress, instant, average ->
                    emit {
                        it.copy(
                            phase = Phase.DOWNLOAD,
                            phaseProgress = progress,
                            instantMbps = instant,
                            downloadMbps = average,
                        )
                    }
                },
                worker = { deadline, counter -> downloadWorker(downloadUrl, deadline, counter) },
            )
            emit { it.copy(downloadMbps = download, instantMbps = 0.0, phaseProgress = 1f) }

            // --- Upload ------------------------------------------------------
            emit { it.copy(phase = Phase.UPLOAD, phaseProgress = 0f, instantMbps = 0.0) }
            val upload = measureThroughput(
                label = "Upload",
                seconds = config.uploadSeconds,
                connections = config.connections,
                onSample = { progress, instant, average ->
                    emit {
                        it.copy(
                            phase = Phase.UPLOAD,
                            phaseProgress = progress,
                            instantMbps = instant,
                            uploadMbps = average,
                        )
                    }
                },
                worker = { deadline, counter -> uploadWorker(uploadUrl, deadline, counter) },
            )

            emit {
                it.copy(
                    phase = Phase.DONE,
                    phaseProgress = 1f,
                    instantMbps = 0.0,
                    downloadMbps = download,
                    uploadMbps = upload,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            emit {
                it.copy(
                    phase = Phase.ERROR,
                    instantMbps = 0.0,
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    // -- latency -------------------------------------------------------------

    private data class PingStats(
        val averageMs: Double? = null,
        val jitterMs: Double? = null,
        val lossPct: Double = 0.0,
    )

    /**
     * Times TCP handshakes against the test server. The name is resolved once up
     * front so DNS does not leak into the samples, and the first sample is
     * dropped from the average because it also pays for ARP and route setup.
     */
    private suspend fun measureLatency(
        url: URL,
        count: Int,
        onProgress: suspend (done: Int, stats: PingStats) -> Unit,
    ): PingStats {
        val port = if (url.port != -1) url.port else url.defaultPort
        val address = withContext(Dispatchers.IO) {
            network?.getByName(url.host) ?: InetAddress.getByName(url.host)
        }

        val samples = ArrayList<Double>(count)
        var lost = 0
        var stats = PingStats()

        repeat(count) { index ->
            val rtt = tcpHandshakeMs(address, port)
            if (rtt == null) lost++ else samples += rtt
            stats = PingStats(
                averageMs = samples.forAverage().takeIf { it.isNotEmpty() }?.average(),
                jitterMs = samples.jitter(),
                lossPct = lost * 100.0 / (index + 1),
            )
            onProgress(index + 1, stats)
            if (index < count - 1) delay(100)
        }
        return stats
    }

    private suspend fun tcpHandshakeMs(address: InetAddress, port: Int): Double? =
        withContext(Dispatchers.IO) {
            val socket = network?.socketFactory?.createSocket() ?: Socket()
            try {
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(address, port), PING_TIMEOUT_MS)
                (System.nanoTime() - start) / 1_000_000.0
            } catch (error: IOException) {
                null
            } finally {
                runCatching { socket.close() }
            }
        }

    // -- throughput ----------------------------------------------------------

    /**
     * Saturates the link with [connections] parallel transfers for [seconds] and
     * returns the average rate in Mbit/s.
     *
     * The first quarter of the run (capped at 1.5 s) is treated as warm-up and
     * excluded from the average, so TCP slow start does not drag the result down.
     */
    private suspend fun measureThroughput(
        label: String,
        seconds: Int,
        connections: Int,
        onSample: suspend (progress: Float, instantMbps: Double, averageMbps: Double) -> Unit,
        worker: suspend (deadlineNanos: Long, counter: AtomicLong) -> Unit,
    ): Double = coroutineScope {
        val counter = AtomicLong()
        val start = System.nanoTime()
        val durationNanos = seconds * 1_000_000_000L
        val deadline = start + durationNanos
        val warmupNanos = minOf(1_500_000_000L, durationNanos / 4)

        val workers = List(connections) {
            launch(Dispatchers.IO) { worker(deadline, counter) }
        }

        var windowBytes = 0L
        var windowAt = start
        var warmupBytes = -1L
        var warmupAt = start
        var average = 0.0

        while (coroutineContext.isActive) {
            delay(SAMPLE_INTERVAL_MS)
            val now = System.nanoTime()
            val bytes = counter.get()

            val instant = mbps(bytes - windowBytes, now - windowAt)
            windowBytes = bytes
            windowAt = now

            if (warmupBytes < 0 && now - start >= warmupNanos) {
                warmupBytes = bytes
                warmupAt = now
            }
            average = if (warmupBytes >= 0 && now > warmupAt) {
                mbps(bytes - warmupBytes, now - warmupAt)
            } else {
                instant
            }

            val progress = ((now - start).toDouble() / durationNanos).coerceIn(0.0, 1.0).toFloat()
            onSample(progress, instant, average)
            if (now >= deadline) break
        }

        workers.forEach { it.cancel() }
        workers.joinAll()

        // The server answered the handshake but never gave us any payload —
        // a wrong path or a rejected method. Report it instead of showing 0 Mbps.
        if (counter.get() == 0L) {
            throw IOException("$label transferred no data — check the $label URL")
        }
        average
    }

    private suspend fun downloadWorker(url: URL, deadlineNanos: Long, counter: AtomicLong) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (coroutineContext.isActive && System.nanoTime() < deadlineNanos) {
            var connection: HttpURLConnection? = null
            try {
                connection = open(url, "GET")
                connection.inputStream.use { input ->
                    while (coroutineContext.isActive && System.nanoTime() < deadlineNanos) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        counter.addAndGet(read.toLong())
                    }
                }
            } catch (error: IOException) {
                if (System.nanoTime() < deadlineNanos) delay(200)
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }

    private suspend fun uploadWorker(url: URL, deadlineNanos: Long, counter: AtomicLong) {
        // Random payload: an incompressible body keeps any transparent
        // compression on the path from inflating the result.
        val buffer = Random.nextBytes(BUFFER_BYTES)
        while (coroutineContext.isActive && System.nanoTime() < deadlineNanos) {
            var connection: HttpURLConnection? = null
            try {
                connection = open(url, "POST").apply {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/octet-stream")
                    setFixedLengthStreamingMode(UPLOAD_REQUEST_BYTES)
                }
                connection.outputStream.use { output ->
                    var sent = 0L
                    while (coroutineContext.isActive &&
                        sent < UPLOAD_REQUEST_BYTES &&
                        System.nanoTime() < deadlineNanos
                    ) {
                        output.write(buffer)
                        output.flush()
                        sent += buffer.size
                        counter.addAndGet(buffer.size.toLong())
                    }
                }
                runCatching { connection.responseCode }
            } catch (error: IOException) {
                // Expected when the deadline cuts a fixed-length request short.
                if (System.nanoTime() < deadlineNanos) delay(200)
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }

    private fun open(url: URL, method: String): HttpURLConnection {
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        // Ask for the bytes as-is; a gzipped stream of zeroes would measure the
        // CPU, not the link.
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Cache-Control", "no-cache, no-store")
        return connection
    }
}

private fun mbps(bytes: Long, nanos: Long): Double =
    if (nanos <= 0 || bytes <= 0) 0.0 else bytes * 8.0 / (nanos / 1_000_000_000.0) / 1_000_000.0

/** Drops the first sample once there are enough of them to spare it. */
private fun List<Double>.forAverage(): List<Double> = if (size > 2) drop(1) else this

/** Mean absolute difference between consecutive samples. */
private fun List<Double>.jitter(): Double? {
    if (size < 2) return null
    var total = 0.0
    for (i in 1 until size) total += abs(this[i] - this[i - 1])
    return total / (size - 1)
}
