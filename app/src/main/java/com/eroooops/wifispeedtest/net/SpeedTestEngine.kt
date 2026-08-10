package com.eroooops.wifispeedtest.net

import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.random.Random

private const val SAMPLE_INTERVAL_MS = 200L
private const val BUFFER_BYTES = 64 * 1024
private const val UPLOAD_REQUEST_BYTES = 64L * 1024 * 1024
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 10_000
private const val PING_TIMEOUT_MS = 4_000
private const val PROBE_TIMEOUT_MS = 5_000
private const val PROBE_CONCURRENCY = 5
private const val PROBE_UPLOAD_BYTES = 128 * 1024
private const val MAX_SAMPLES = 120
private const val BROWSE_ASSETS = 16
private const val BROWSE_ASSET_BYTES = 48L * 1024
private const val BROWSE_IDLE_MS = 400L
private const val IDLE_SAMPLES = 5

/**
 * Runs a ping / download / upload test and reports progress as a flow.
 *
 * All sockets are opened through the supplied Wi-Fi [Network], so the numbers
 * describe the Wi-Fi link even when the device also has mobile data up and the
 * system would otherwise prefer it.
 */
class SpeedTestEngine(private val network: Network?) {

    fun run(
        config: TestConfig,
        candidates: List<Endpoint> = listOf(Endpoints.DEFAULT),
    ): Flow<TestUpdate> = channelFlow {
        var snapshot = TestUpdate(phase = Phase.PROBE)

        suspend fun emit(transform: (TestUpdate) -> TestUpdate) {
            snapshot = transform(snapshot)
            send(snapshot)
        }

        try {
            // --- Pick a server that actually answers -------------------------
            emit {
                it.copy(
                    phase = Phase.PROBE,
                    phaseProgress = 0f,
                    probes = candidates.map { candidate ->
                        ProbeResult(
                            name = candidate.name,
                            host = URL(candidate.downloadUrl).host,
                            scheme = URL(candidate.downloadUrl).protocol,
                        )
                    },
                )
            }
            val selection = selectEndpoint(candidates) { done, live ->
                emit {
                    it.copy(
                        phaseProgress = done.toFloat() / candidates.size,
                        probes = live,
                    )
                }
            }

            val chosen = selection.download
                ?: throw IOException(selection.describeFailure())

            val downloadUrl = URL(chosen.downloadUrl)
            val uploadTarget = selection.upload
            emit {
                it.copy(
                    server = chosen.name,
                    notice = if (uploadTarget == null) {
                        "No reachable upload server — measuring download only."
                    } else {
                        null
                    },
                )
            }

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

            // --- Simulated profiles -------------------------------------------
            if (!config.profile.isBulk) {
                runProfile(config, downloadUrl) { transform -> emit(transform) }
                return@channelFlow
            }

            // --- Download ----------------------------------------------------
            emit {
                it.copy(
                    phase = Phase.DOWNLOAD,
                    phaseProgress = 0f,
                    instantMbps = 0.0,
                    samples = emptyList(),
                )
            }
            val download = measureThroughput(
                label = "Download",
                seconds = config.downloadSeconds,
                connections = config.connections,
                onSample = { progress, instant, average, bytes ->
                    emit {
                        it.copy(
                            phase = Phase.DOWNLOAD,
                            phaseProgress = progress,
                            instantMbps = instant,
                            downloadMbps = average,
                            bytesUsed = bytes,
                            samples = (it.samples + instant).takeLast(MAX_SAMPLES),
                        )
                    }
                },
                worker = { deadline, counter, failure ->
                    downloadWorker(downloadUrl, deadline, counter, failure)
                },
            )
            emit {
                it.copy(
                    downloadMbps = download.averageMbps,
                    instantMbps = 0.0,
                    phaseProgress = 1f,
                    bytesUsed = download.bytes,
                )
            }

            // --- Upload ------------------------------------------------------
            // A missing upload server downgrades the test rather than failing
            // it: a download figure alone still answers most questions.
            val upload = if (uploadTarget == null) {
                null
            } else {
                emit {
                    it.copy(
                        phase = Phase.UPLOAD,
                        phaseProgress = 0f,
                        instantMbps = 0.0,
                        samples = emptyList(),
                    )
                }
                val uploadUrl = URL(uploadTarget)
                measureThroughput(
                    label = "Upload",
                    seconds = config.uploadSeconds,
                    connections = config.connections,
                    onSample = { progress, instant, average, bytes ->
                        emit {
                            it.copy(
                                phase = Phase.UPLOAD,
                                phaseProgress = progress,
                                instantMbps = instant,
                                uploadMbps = average,
                                bytesUsed = download.bytes + bytes,
                                samples = (it.samples + instant).takeLast(MAX_SAMPLES),
                            )
                        }
                    },
                    worker = { deadline, counter, failure ->
                        uploadWorker(uploadUrl, deadline, counter, failure)
                    },
                )
            }

            emit {
                it.copy(
                    phase = Phase.DONE,
                    phaseProgress = 1f,
                    instantMbps = 0.0,
                    downloadMbps = download.averageMbps,
                    uploadMbps = upload?.averageMbps,
                    bytesUsed = download.bytes + (upload?.bytes ?: 0L),
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

    /** Drives whichever non-bulk profile was chosen and finishes the run. */
    private suspend fun runProfile(
        config: TestConfig,
        downloadUrl: URL,
        emit: suspend ((TestUpdate) -> TestUpdate) -> Unit,
    ) {
        emit {
            it.copy(
                phase = Phase.DOWNLOAD,
                phaseProgress = 0f,
                instantMbps = 0.0,
                samples = emptyList(),
            )
        }

        when (config.profile) {
            TrafficProfile.STREAM -> {
                val (quality, bytes) = measureStream(
                    downloadUrl,
                    config.downloadSeconds,
                ) { progress, instant, rung ->
                    emit {
                        it.copy(
                            phaseProgress = progress,
                            instantMbps = instant,
                            bytesUsed = it.bytesUsed + 0,
                            samples = (it.samples + instant).takeLast(MAX_SAMPLES),
                            outcome = it.outcome.copy(sustainedQuality = rung),
                        )
                    }
                }
                emit {
                    it.copy(
                        phase = Phase.DONE,
                        phaseProgress = 1f,
                        instantMbps = 0.0,
                        bytesUsed = bytes,
                        downloadMbps = it.samples.maxOrNull() ?: 0.0,
                        outcome = it.outcome.copy(sustainedQuality = quality),
                    )
                }
            }

            TrafficProfile.BROWSE -> {
                val (median, bytes) = measureBrowse(
                    downloadUrl,
                    config.downloadSeconds,
                ) { progress, instant, burstMs ->
                    emit {
                        it.copy(
                            phaseProgress = progress,
                            instantMbps = instant,
                            samples = (it.samples + instant).takeLast(MAX_SAMPLES),
                            outcome = it.outcome.copy(burstMillis = burstMs),
                        )
                    }
                }
                emit {
                    it.copy(
                        phase = Phase.DONE,
                        phaseProgress = 1f,
                        instantMbps = 0.0,
                        bytesUsed = bytes,
                        downloadMbps = it.samples.maxOrNull() ?: 0.0,
                        outcome = it.outcome.copy(burstMillis = median),
                    )
                }
            }

            TrafficProfile.RESPONSIVENESS -> {
                val (idle, loaded, bytes) = measureResponsiveness(
                    downloadUrl,
                    config.downloadSeconds,
                    config.connections,
                ) { progress, instant, latency ->
                    emit {
                        it.copy(
                            phaseProgress = progress,
                            instantMbps = instant,
                            samples = (it.samples + instant).takeLast(MAX_SAMPLES),
                            outcome = it.outcome.copy(loadedLatencyMs = latency),
                        )
                    }
                }
                emit {
                    it.copy(
                        phase = Phase.DONE,
                        phaseProgress = 1f,
                        instantMbps = 0.0,
                        bytesUsed = bytes,
                        downloadMbps = it.samples.maxOrNull() ?: 0.0,
                        outcome = it.outcome.copy(
                            idleLatencyMs = idle,
                            loadedLatencyMs = loaded,
                        ),
                    )
                }
            }

            TrafficProfile.BULK -> Unit
        }
    }

    // -- simulated traffic ---------------------------------------------------

    /**
     * Climbs a bitrate ladder, holding each rung for a couple of segments.
     *
     * A player asks for a fixed slice of video on a clock and must have it
     * before playback reaches it. So the question is not "how fast" but "did
     * this segment arrive in time", and the answer is the highest rung where it
     * kept doing so.
     */
    private suspend fun measureStream(
        url: URL,
        seconds: Int,
        onSample: suspend (progress: Float, instantMbps: Double, rung: String) -> Unit,
    ): Pair<String?, Long> {
        val segmentSeconds = 2.0
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        var sustained: String? = null
        var totalBytes = 0L

        for ((quality, mbps) in BITRATE_LADDER) {
            if (System.nanoTime() >= deadline) break
            val segmentBytes = (mbps * 1_000_000 * segmentSeconds / 8).toLong()
            var delivered = 0

            repeat(2) {
                if (System.nanoTime() >= deadline) return@repeat
                val start = System.nanoTime()
                val got = fetchRange(url, 0, segmentBytes - 1)
                val tookSeconds = (System.nanoTime() - start) / 1e9
                totalBytes += got

                if (got >= segmentBytes / 2 && tookSeconds <= segmentSeconds) delivered++

                val instant = if (tookSeconds > 0) got * 8.0 / tookSeconds / 1e6 else 0.0
                val progress = 1f - ((deadline - System.nanoTime()).toFloat() /
                    (seconds * 1_000_000_000L))
                onSample(progress.coerceIn(0f, 1f), instant, quality)
            }

            // Both segments on time means the link genuinely holds this rung.
            if (delivered == 2) sustained = quality else break
        }
        return sustained to totalBytes
    }

    /**
     * Fires bursts of small parallel requests, the shape of a page load.
     *
     * Bandwidth barely matters here; round trips do. Returns the median burst
     * duration, which is what "feels slow" actually measures.
     */
    private suspend fun measureBrowse(
        url: URL,
        seconds: Int,
        onSample: suspend (progress: Float, instantMbps: Double, burstMs: Double) -> Unit,
    ): Pair<Double?, Long> = coroutineScope {
        val start = System.nanoTime()
        val deadline = start + seconds * 1_000_000_000L
        val durations = mutableListOf<Double>()
        var totalBytes = 0L

        while (System.nanoTime() < deadline && coroutineContext.isActive) {
            val burstStart = System.nanoTime()
            val assets = (0 until BROWSE_ASSETS).map { index ->
                async(Dispatchers.IO) {
                    val offset = index.toLong() * BROWSE_ASSET_BYTES
                    fetchRange(url, offset, offset + BROWSE_ASSET_BYTES - 1)
                }
            }
            val got = assets.sumOf { it.await() }
            val tookMs = (System.nanoTime() - burstStart) / 1e6
            totalBytes += got
            durations += tookMs

            val instant = if (tookMs > 0) got * 8.0 / (tookMs / 1000) / 1e6 else 0.0
            val progress = ((System.nanoTime() - start).toFloat() /
                (seconds * 1_000_000_000L)).coerceIn(0f, 1f)
            onSample(progress, instant, tookMs)

            delay(BROWSE_IDLE_MS)
        }

        val median = durations.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
        median to totalBytes
    }

    /**
     * Measures latency while the link is saturated.
     *
     * The gap between idle and loaded latency is bufferbloat: a queue somewhere
     * absorbing the flood and delaying everything behind it. It is why a call
     * falls apart while something downloads, and a bulk test cannot see it.
     */
    private suspend fun measureResponsiveness(
        url: URL,
        seconds: Int,
        connections: Int,
        onSample: suspend (progress: Float, instantMbps: Double, latencyMs: Double) -> Unit,
    ): Triple<Double?, Double?, Long> = coroutineScope {
        val port = if (url.port != -1) url.port else url.defaultPort
        val address = withContext(Dispatchers.IO) {
            network?.getByName(url.host) ?: InetAddress.getByName(url.host)
        }

        // Baseline first, with nothing else running.
        val idle = (1..IDLE_SAMPLES).mapNotNull { tcpHandshakeMs(address, port) }
            .takeIf { it.isNotEmpty() }?.average()

        val counter = AtomicLong()
        val failure = AtomicReference<String?>(null)
        val start = System.nanoTime()
        val deadline = start + seconds * 1_000_000_000L
        val loaders = List(connections) {
            launch(Dispatchers.IO) { downloadWorker(url, deadline, counter, failure) }
        }

        val loaded = mutableListOf<Double>()
        var windowBytes = 0L
        var windowAt = start

        while (System.nanoTime() < deadline && coroutineContext.isActive) {
            val rtt = tcpHandshakeMs(address, port)
            if (rtt != null) loaded += rtt

            val now = System.nanoTime()
            val bytes = counter.get()
            val instant = mbps(bytes - windowBytes, now - windowAt)
            windowBytes = bytes
            windowAt = now

            val progress = ((now - start).toFloat() / (seconds * 1_000_000_000L)).coerceIn(0f, 1f)
            onSample(progress, instant, rtt ?: 0.0)
            delay(150)
        }

        loaders.forEach { it.cancel() }
        loaders.joinAll()

        Triple(idle, loaded.takeIf { it.isNotEmpty() }?.average(), counter.get())
    }

    /** One ranged GET, returning how many bytes actually arrived. */
    private suspend fun fetchRange(url: URL, from: Long, to: Long): Long =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = open(url, "GET").apply {
                    setRequestProperty("Range", "bytes=$from-$to")
                }
                if (connection.responseCode !in 200..299) return@withContext 0L
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0L
                connection.inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                    }
                }
                total
            } catch (error: IOException) {
                0L
            } finally {
                runCatching { connection?.disconnect() }
            }
        }

    // -- server selection ----------------------------------------------------

    private data class Selection(
        val download: Endpoint? = null,
        val upload: String? = null,
        val failures: List<Pair<String, String>> = emptyList(),
        val probes: List<ProbeResult> = emptyList(),
    ) {
        /** Names the first few casualties; twenty reasons would not fit on screen. */
        fun describeFailure(): String {
            val shown = failures.take(4).joinToString("; ") { (name, reason) -> "$name: $reason" }
            val rest = (failures.size - 4).coerceAtLeast(0)
            return "None of the ${failures.size} test servers could be reached. " +
                shown + if (rest > 0) "; and $rest more" else ""
        }
    }

    /**
     * Finds the first candidate that really serves bytes, then an upload target.
     *
     * A reachability check has to pull actual payload, not just open a socket:
     * carriers that filter on the TLS server name let the TCP handshake through
     * and drop what follows, so a connect-only probe would happily pick a host
     * that cannot serve a single byte.
     */
    private suspend fun selectEndpoint(
        candidates: List<Endpoint>,
        onProgress: suspend (done: Int, live: List<ProbeResult>) -> Unit,
    ): Selection = coroutineScope {
        val prober = Prober(network)
        val live = candidates.map { candidate ->
            ProbeResult(
                name = candidate.name,
                host = URL(candidate.downloadUrl).host,
                scheme = URL(candidate.downloadUrl).protocol,
            )
        }.toMutableList()

        val failures = mutableListOf<Pair<String, String>>()
        var chosen: Endpoint? = null
        var probed = 0

        // Probed a batch at a time rather than one by one: a blocked host costs
        // a full timeout, and twenty of those in series is two minutes of
        // staring at a spinner. Preference order is still honoured, because the
        // winner is the earliest survivor within a batch, and later batches only
        // run if the whole batch before them failed.
        for (batch in candidates.chunked(PROBE_CONCURRENCY).map { it.toList() }) {
            val offset = probed
            batch.indices.forEach { i ->
                live[offset + i] = live[offset + i].copy(state = ProbeState.RUNNING)
            }
            onProgress(probed, live.toList())

            val results = batch
                .map { candidate ->
                    candidate to async {
                        prober.probe(candidate.name, candidate.downloadUrl, PROBE_TIMEOUT_MS)
                    }
                }
                .map { (candidate, deferred) -> candidate to deferred.await() }

            results.forEachIndexed { i, (candidate, result) ->
                live[offset + i] = result
                if (result.state != ProbeState.OK) {
                    failures += candidate.name to (result.error ?: "unreachable")
                }
            }

            probed += batch.size
            onProgress(probed, live.toList())

            chosen = results.firstOrNull { (_, result) -> result.state == ProbeState.OK }?.first
            if (chosen != null) break
        }

        val download = chosen
            ?: return@coroutineScope Selection(failures = failures, probes = live.toList())

        // The chosen host's own upload endpoint first, then the shared list.
        val uploadCandidates = buildList {
            download.uploadUrl?.let { add(it) }
            Endpoints.UPLOAD_TARGETS.forEach { if (it !in this) add(it) }
        }
        val upload = uploadCandidates.firstOrNull { probeUpload(it) == null }

        Selection(
            download = download,
            upload = upload,
            failures = failures,
            probes = live.toList(),
        )
    }

    private suspend fun probeUpload(url: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val payload = ByteArray(PROBE_UPLOAD_BYTES)
            connection = open(URL(url), "POST").apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setFixedLengthStreamingMode(payload.size.toLong())
            }
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            if (status in 200..299) null else "HTTP $status"
        } catch (error: IOException) {
            error.describe()
        } catch (error: SecurityException) {
            error.describe()
        } finally {
            runCatching { connection?.disconnect() }
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
    private data class Throughput(val averageMbps: Double, val bytes: Long)

    private suspend fun measureThroughput(
        label: String,
        seconds: Int,
        connections: Int,
        onSample: suspend (
            progress: Float,
            instantMbps: Double,
            averageMbps: Double,
            bytes: Long,
        ) -> Unit,
        worker: suspend (
            deadlineNanos: Long,
            counter: AtomicLong,
            failure: AtomicReference<String?>,
        ) -> Unit,
    ): Throughput = coroutineScope {
        val counter = AtomicLong()
        val failure = AtomicReference<String?>(null)
        val start = System.nanoTime()
        val durationNanos = seconds * 1_000_000_000L
        val deadline = start + durationNanos
        val warmupNanos = minOf(1_500_000_000L, durationNanos / 4)

        val workers = List(connections) {
            launch(Dispatchers.IO) { worker(deadline, counter, failure) }
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
            onSample(progress, instant, average, bytes)
            if (now >= deadline) break
        }

        workers.forEach { it.cancel() }
        workers.joinAll()

        // The server answered the handshake but never gave us any payload —
        // a wrong path or a rejected method. Report it instead of showing 0 Mbps.
        val total = counter.get()
        if (total == 0L) {
            val reason = failure.get() ?: "check the $label URL"
            throw IOException("$label failed: $reason")
        }
        Throughput(average, total)
    }

    private suspend fun downloadWorker(
        url: URL,
        deadlineNanos: Long,
        counter: AtomicLong,
        failure: AtomicReference<String?>,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (coroutineContext.isActive && System.nanoTime() < deadlineNanos) {
            var connection: HttpURLConnection? = null
            try {
                connection = open(url, "GET")
                val status = connection.responseCode
                if (status !in 200..299) {
                    failure.set("server returned HTTP $status")
                    if (System.nanoTime() < deadlineNanos) delay(300)
                    continue
                }
                connection.inputStream.use { input ->
                    while (coroutineContext.isActive && System.nanoTime() < deadlineNanos) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        counter.addAndGet(read.toLong())
                    }
                }
            } catch (error: IOException) {
                failure.set(error.describe())
                if (System.nanoTime() < deadlineNanos) delay(200)
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }

    private suspend fun uploadWorker(
        url: URL,
        deadlineNanos: Long,
        counter: AtomicLong,
        failure: AtomicReference<String?>,
    ) {
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
                val status = runCatching { connection.responseCode }.getOrNull()
                if (status != null && status !in 200..299) {
                    failure.set("server returned HTTP $status")
                }
            } catch (error: IOException) {
                failure.set(error.describe())
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

/** Exception text worth showing a user: the message, or the class when it has none. */
private fun Exception.describe(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

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
