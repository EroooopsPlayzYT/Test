package com.eroooops.wifispeedtest.net

/**
 * Where and how hard to test.
 *
 * The defaults point at Cloudflare's public speed-test endpoints, which serve an
 * arbitrary number of bytes from `__down` and swallow anything POSTed to `__up`.
 * Both URLs are editable so the app can be aimed at a box on the local network
 * instead, which is what you want when measuring the Wi-Fi link rather than the
 * internet connection behind it.
 */
data class TestConfig(
    val downloadUrl: String = DEFAULT_DOWNLOAD_URL,
    val uploadUrl: String = DEFAULT_UPLOAD_URL,
    val connections: Int = 4,
    val downloadSeconds: Int = 10,
    val uploadSeconds: Int = 10,
    val pingCount: Int = 10,
) {
    companion object {
        const val DEFAULT_DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=200000000"
        const val DEFAULT_UPLOAD_URL = "https://speed.cloudflare.com/__up"

        val CONNECTION_RANGE = 1..8
        val DURATION_RANGE = 5..30
    }
}

enum class Phase { IDLE, PING, DOWNLOAD, UPLOAD, DONE, ERROR }

/** One frame of test state; the engine emits a full snapshot every ~200 ms. */
data class TestUpdate(
    val phase: Phase = Phase.IDLE,
    /** Progress through the current phase, 0..1. */
    val phaseProgress: Float = 0f,
    /** Throughput over the last sampling window, for the live gauge. */
    val instantMbps: Double = 0.0,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val packetLossPct: Double? = null,
    /** Bytes moved so far. Worth watching when the test runs on mobile data. */
    val bytesUsed: Long = 0L,
    val error: String? = null,
)
