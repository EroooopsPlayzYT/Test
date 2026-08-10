package com.eroooops.wifispeedtest.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicReference

private const val UNKNOWN_SSID = "<unknown ssid>"
private const val POLL_INTERVAL_MS = 2_000L

/**
 * Observes the Wi-Fi network the device is attached to.
 *
 * A [ConnectivityManager.NetworkCallback] supplies the [Network] handle and the
 * link addresses; a slow poll on top of it keeps the volatile values (RSSI, link
 * speed) fresh, since those change continuously while the callback only fires on
 * larger transitions.
 */
class WifiInfoProvider(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // Nullable: the manifest marks Wi-Fi hardware optional, so a device without
    // a radio must not crash on construction.
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Cold flow of Wi-Fi snapshots; re-emits whenever anything observable changes. */
    fun status(): Flow<WifiStatus> = callbackFlow {
        val state = AtomicReference(WifiStatus())

        fun publish(transform: (WifiStatus) -> WifiStatus) {
            trySend(state.updateAndGet(transform))
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                publish { it.copy(connected = true, network = network) }
            }

            override fun onLost(network: Network) {
                state.set(WifiStatus())
                trySend(WifiStatus())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val info = wifiInfoFrom(capabilities)
                publish { it.copy(connected = true, network = network).merge(info) }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                publish {
                    it.copy(
                        connected = true,
                        network = network,
                        ipv4 = linkProperties.ipv4Address() ?: it.ipv4,
                    )
                }
            }
        }

        val request = NetworkRequest.Builder()
            // Deliberately no NET_CAPABILITY_INTERNET: a router-only network with
            // no upstream is still a valid target for a LAN throughput test.
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)
        trySend(state.get())

        val poller = launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val network = state.get().network ?: continue
                val info = currentWifiInfo(network)
                val ipv4 = connectivityManager.getLinkProperties(network)?.ipv4Address()
                publish { it.merge(info).copy(ipv4 = ipv4 ?: it.ipv4) }
            }
        }

        awaitClose {
            poller.cancel()
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }

    private fun currentWifiInfo(network: Network): WifiInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiInfoFrom(connectivityManager.getNetworkCapabilities(network))
        } else {
            legacyWifiInfo()
        }

    private fun wifiInfoFrom(capabilities: NetworkCapabilities?): WifiInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            capabilities?.transportInfo as? WifiInfo
        } else {
            legacyWifiInfo()
        }

    @Suppress("DEPRECATION")
    private fun legacyWifiInfo(): WifiInfo? = runCatching { wifiManager?.connectionInfo }.getOrNull()
}

private fun LinkProperties.ipv4Address(): String? =
    linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress

/** Folds a fresh [WifiInfo] into a snapshot, keeping previous values for fields the system withheld. */
private fun WifiStatus.merge(info: WifiInfo?): WifiStatus {
    if (info == null) return this

    val ssid = info.ssid
        ?.removeSurrounding("\"")
        ?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
    val bssid = info.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
    val rssi = info.rssi.takeIf { it != -127 && it in -120..0 }
    val txSpeed = info.linkSpeed.takeIf { it > 0 }
    val rxSpeed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        info.rxLinkSpeedMbps.takeIf { it > 0 }
    } else {
        null
    }
    val frequency = info.frequency.takeIf { it > 0 }
    val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        wifiStandardLabel(info.wifiStandard)
    } else {
        null
    }

    return copy(
        ssid = ssid ?: this.ssid,
        bssid = bssid ?: this.bssid,
        rssiDbm = rssi ?: this.rssiDbm,
        txLinkSpeedMbps = txSpeed ?: this.txLinkSpeedMbps,
        rxLinkSpeedMbps = rxSpeed ?: this.rxLinkSpeedMbps,
        frequencyMhz = frequency ?: this.frequencyMhz,
        standard = standard ?: this.standard,
    )
}

@RequiresApi(Build.VERSION_CODES.R)
private fun wifiStandardLabel(standard: Int): String? = when (standard) {
    ScanResult.WIFI_STANDARD_LEGACY -> "802.11 a/b/g"
    ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (11n)"
    ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (11ac)"
    ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (11ax)"
    ScanResult.WIFI_STANDARD_11AD -> "802.11ad"
    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        standard == ScanResult.WIFI_STANDARD_11BE
    ) {
        "Wi-Fi 7 (11be)"
    } else {
        null
    }
}
