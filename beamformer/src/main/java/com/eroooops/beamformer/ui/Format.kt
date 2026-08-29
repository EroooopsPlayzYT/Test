package com.eroooops.beamformer.ui

import com.eroooops.beamformer.audio.SILENCE_DB
import java.util.Locale

fun formatDb(value: Double): String = when {
    value <= SILENCE_DB + 1 -> "--"
    else -> String.format(Locale.US, "%.0f dB", value)
}

fun formatSignedDb(value: Double): String =
    String.format(Locale.US, "%+.1f dB", value)

fun formatDegrees(value: Double?): String =
    if (value == null) "--" else String.format(Locale.US, "%.0f°", value)

fun formatHz(value: Double): String = when {
    !value.isFinite() -> "--"
    value >= 1000 -> String.format(Locale.US, "%.2f kHz", value / 1000)
    else -> String.format(Locale.US, "%.0f Hz", value)
}

fun formatMillimetres(metres: Double): String =
    String.format(Locale.US, "%.0f mm", metres * 1000)

fun formatSeconds(seconds: Double): String {
    val whole = seconds.toInt()
    return String.format(Locale.US, "%d:%02d", whole / 60, whole % 60)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f kB", bytes / 1_000.0)
    else -> "$bytes B"
}

/** Maps a level in dB onto 0..1 for a meter, over a fixed useful range. */
fun meterFraction(db: Double, floorDb: Double = -70.0): Float {
    if (!db.isFinite()) return 0f
    return ((db - floorDb) / (0.0 - floorDb)).coerceIn(0.0, 1.0).toFloat()
}
