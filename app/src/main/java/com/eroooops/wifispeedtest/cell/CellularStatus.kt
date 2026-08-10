package com.eroooops.wifispeedtest.cell

import android.net.Network

/**
 * A snapshot of the mobile data connection.
 *
 * As with Wi-Fi, every field is nullable: the carrier name is free, but the
 * radio technology and signal strength depend on READ_PHONE_STATE and on what
 * the OEM's telephony stack chooses to report.
 */
data class CellularStatus(
    val connected: Boolean = false,
    val carrier: String? = null,
    /** Radio technology in use, e.g. "LTE" or "5G NR". */
    val networkType: String? = null,
    val signalDbm: Int? = null,
    /** Signal quality as reported by the platform, 0..4. */
    val signalLevel: Int? = null,
    val roaming: Boolean = false,
    val metered: Boolean = true,
    /** The platform's own estimate of the link, not a measurement. */
    val downstreamKbps: Int? = null,
    val ipv4: String? = null,
    val network: Network? = null,
) {

    val signalLabel: String?
        get() = when (signalLevel) {
            4 -> "Excellent"
            3 -> "Good"
            2 -> "Fair"
            1 -> "Weak"
            0 -> "Very weak"
            else -> null
        }

    /** Reported downlink estimate in Mbps, for display next to the measured rate. */
    val downstreamMbps: Int?
        get() = downstreamKbps?.takeIf { it > 0 }?.let { it / 1000 }?.takeIf { it > 0 }
}
