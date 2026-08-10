package com.eroooops.wifispeedtest.net

/**
 * How the test loads the link.
 *
 * A saturating bulk transfer answers "what is the ceiling", which is the one
 * question real usage almost never asks. The other profiles reproduce shapes of
 * traffic people actually generate, and they fail in ways a bulk test cannot
 * see: a link that peaks at 300 Mbps can still stall a video or feel sluggish to
 * browse if its buffers are deep or its latency collapses under load.
 */
enum class TrafficProfile(
    val label: String,
    val blurb: String,
) {
    BULK(
        label = "Bulk",
        blurb = "Saturate the link with parallel transfers. The classic speed test — " +
            "measures the ceiling.",
    ),
    STREAM(
        label = "Video",
        blurb = "Request fixed segments on a clock, climbing a bitrate ladder while they " +
            "arrive on time. Reports the highest quality the link actually sustains.",
    ),
    BROWSE(
        label = "Browsing",
        blurb = "Bursts of many small parallel requests, like loading a page. Measures how " +
            "long a burst takes, which latency dominates more than bandwidth.",
    ),
    RESPONSIVENESS(
        label = "Under load",
        blurb = "Ping continuously while a transfer runs. The gap between idle and loaded " +
            "latency is bufferbloat — why calls stutter while something downloads.",
    ),
    ;

    val isBulk: Boolean get() = this == BULK
}

/** Rungs of a typical adaptive-streaming ladder, in Mbit/s. */
val BITRATE_LADDER: List<Pair<String, Double>> = listOf(
    "240p" to 0.4,
    "360p" to 0.8,
    "480p" to 1.5,
    "720p" to 3.0,
    "1080p" to 6.0,
    "1440p" to 12.0,
    "4K" to 25.0,
    "4K HDR" to 45.0,
)

/** Extra findings a profile produces that a single Mbps figure cannot carry. */
data class ProfileOutcome(
    /** STREAM: highest ladder rung sustained, e.g. "1080p". */
    val sustainedQuality: String? = null,
    /** BROWSE: median time to complete one burst of requests. */
    val burstMillis: Double? = null,
    /** RESPONSIVENESS: latency with the link idle. */
    val idleLatencyMs: Double? = null,
    /** RESPONSIVENESS: latency while saturated. */
    val loadedLatencyMs: Double? = null,
) {
    /** Loaded latency divided by idle: the bufferbloat multiplier. */
    val bloatFactor: Double?
        get() {
            val idle = idleLatencyMs ?: return null
            val loaded = loadedLatencyMs ?: return null
            return if (idle <= 0) null else loaded / idle
        }

    val bloatGrade: String?
        get() = bloatFactor?.let {
            when {
                it < 1.3 -> "A · excellent"
                it < 2.0 -> "B · good"
                it < 4.0 -> "C · noticeable"
                it < 8.0 -> "D · poor"
                else -> "F · severe"
            }
        }

    val isEmpty: Boolean
        get() = sustainedQuality == null && burstMillis == null &&
            idleLatencyMs == null && loadedLatencyMs == null
}
