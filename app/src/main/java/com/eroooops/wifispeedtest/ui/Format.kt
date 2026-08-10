package com.eroooops.wifispeedtest.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps three significant-ish digits so the gauge does not jitter between widths. */
fun formatSpeed(mbps: Double): String = when {
    mbps >= 100 -> String.format(Locale.getDefault(), "%.0f", mbps)
    mbps >= 10 -> String.format(Locale.getDefault(), "%.1f", mbps)
    mbps > 0 -> String.format(Locale.getDefault(), "%.2f", mbps)
    else -> "0.00"
}

fun formatMillis(ms: Double?): String =
    if (ms == null) "—" else String.format(Locale.getDefault(), "%.0f ms", ms)

fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
