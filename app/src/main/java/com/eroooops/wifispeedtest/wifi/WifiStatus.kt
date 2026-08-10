package com.eroooops.wifispeedtest.wifi

import android.net.Network

/**
 * A snapshot of the currently connected Wi-Fi network.
 *
 * Every field is nullable: depending on the API level, the granted permissions
 * and whether location services are enabled, the system hands back only part of
 * this information. The UI degrades one field at a time rather than all at once.
 */
data class WifiStatus(
    val connected: Boolean = false,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssiDbm: Int? = null,
    val txLinkSpeedMbps: Int? = null,
    val rxLinkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val standard: String? = null,
    val ipv4: String? = null,
    /** The Wi-Fi [Network] handle, used to pin test sockets to Wi-Fi. */
    val network: Network? = null,
) {

    /** "2.4 GHz", "5 GHz" or "6 GHz", derived from the channel centre frequency. */
    val band: String?
        get() = frequencyMhz?.let { f ->
            when {
                f >= 5925 -> "6 GHz"
                f >= 4900 -> "5 GHz"
                f >= 2400 -> "2.4 GHz"
                else -> null
            }
        }

    /** IEEE channel number for the current centre frequency. */
    val channel: Int?
        get() = frequencyMhz?.let { f ->
            when {
                f == 2484 -> 14
                f in 2412..2472 -> (f - 2407) / 5
                f == 5935 -> 2
                f >= 5955 -> (f - 5950) / 5
                f in 5000..5895 -> (f - 5000) / 5
                else -> null
            }
        }

    /** Signal quality bucketed to 0..4 bars. */
    val signalBars: Int?
        get() = rssiDbm?.let { rssi ->
            when {
                rssi >= -55 -> 4
                rssi >= -66 -> 3
                rssi >= -77 -> 2
                rssi >= -88 -> 1
                else -> 0
            }
        }

    /** Plain-language read on the signal, shown next to the dBm value. */
    val signalLabel: String?
        get() = when (signalBars) {
            4 -> "Excellent"
            3 -> "Good"
            2 -> "Fair"
            1 -> "Weak"
            0 -> "Very weak"
            else -> null
        }
}
