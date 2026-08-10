package com.eroooops.wifispeedtest.cell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

private const val POLL_INTERVAL_MS = 2_000L

/**
 * Observes the mobile data connection, and can bring it up on demand.
 *
 * The observing callback is deliberately passive — it reports cellular only if
 * the system already has it up. Testing mobile data *while connected to Wi-Fi*
 * is the common case though, and there the radio is usually idle, so
 * [withCellularNetwork] files an explicit request that holds the network open
 * for the duration of a test and releases it afterwards.
 */
class CellularInfoProvider(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Nullable for the same reason as Wi-Fi: tablets without a modem exist.
    private val telephonyManager =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /** True when the device has a mobile radio at all. */
    fun isSupported(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    fun status(): Flow<CellularStatus> = callbackFlow {
        val state = AtomicReference(CellularStatus())

        fun publish(transform: (CellularStatus) -> CellularStatus) {
            trySend(state.updateAndGet(transform))
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                publish { it.copy(connected = true, network = network) }
            }

            override fun onLost(network: Network) {
                state.set(CellularStatus())
                trySend(CellularStatus())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                publish {
                    it.copy(
                        connected = true,
                        network = network,
                        metered = !capabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_NOT_METERED
                        ),
                        downstreamKbps = capabilities.linkDownstreamBandwidthKbps
                            .takeIf { kbps -> kbps > 0 },
                    )
                }
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
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)
        publish { it.merge(telephony()) }

        val poller = launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                publish { it.merge(telephony()) }
            }
        }

        awaitClose {
            poller.cancel()
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }

    /**
     * Holds a mobile data network open while [block] runs.
     *
     * Without this, a test started while Wi-Fi is connected would find no
     * cellular network to bind to on most devices.
     */
    suspend fun <T> withCellularNetwork(
        timeoutMs: Long = 20_000,
        block: suspend (Network) -> T,
    ): T {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            val network = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Network> { continuation ->
                    val pending = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (continuation.isActive) continuation.resume(network)
                        }
                    }
                    callback = pending
                    try {
                        connectivityManager.requestNetwork(request, pending)
                    } catch (error: SecurityException) {
                        continuation.cancel(error)
                    }
                }
            } ?: throw IOException(
                "Mobile data did not come up — check that it is enabled and not restricted"
            )

            return block(network)
        } finally {
            callback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        }
    }

    /** Everything that comes from the telephony stack rather than connectivity. */
    private fun telephony(): CellularStatus? {
        val manager = telephonyManager ?: return null
        return CellularStatus(
            carrier = runCatching { manager.networkOperatorName }.getOrNull()
                ?.takeIf { it.isNotBlank() },
            networkType = dataNetworkTypeLabel(manager),
            signalDbm = signalDbm(manager),
            signalLevel = signalLevel(manager),
            roaming = runCatching { manager.isNetworkRoaming }.getOrDefault(false),
        )
    }

    private fun dataNetworkTypeLabel(manager: TelephonyManager): String? {
        // Throws SecurityException without READ_PHONE_STATE on most builds.
        val type = runCatching { manager.dataNetworkType }.getOrNull() ?: return null
        return networkTypeLabel(type)
    }

    private fun signalLevel(manager: TelephonyManager): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching { manager.signalStrength?.level }.getOrNull()?.takeIf { it in 0..4 }
    }

    private fun signalDbm(manager: TelephonyManager): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            manager.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm
        }.getOrNull()?.takeIf { it < 0 && it > -160 }
    }
}

private fun LinkProperties.ipv4Address(): String? =
    linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress

/** Folds telephony values in, keeping connectivity-derived fields untouched. */
private fun CellularStatus.merge(fresh: CellularStatus?): CellularStatus {
    if (fresh == null) return this
    return copy(
        carrier = fresh.carrier ?: carrier,
        networkType = fresh.networkType ?: networkType,
        signalDbm = fresh.signalDbm ?: signalDbm,
        signalLevel = fresh.signalLevel ?: signalLevel,
        roaming = fresh.roaming,
    )
}

@Suppress("InlinedApi")
private fun networkTypeLabel(type: Int): String? = when (type) {
    TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
    TelephonyManager.NETWORK_TYPE_IWLAN -> "Wi-Fi calling"
    TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
    TelephonyManager.NETWORK_TYPE_HSPA,
    TelephonyManager.NETWORK_TYPE_HSDPA,
    TelephonyManager.NETWORK_TYPE_HSUPA,
    TelephonyManager.NETWORK_TYPE_UMTS,
    TelephonyManager.NETWORK_TYPE_EVDO_0,
    TelephonyManager.NETWORK_TYPE_EVDO_A,
    TelephonyManager.NETWORK_TYPE_EVDO_B,
    TelephonyManager.NETWORK_TYPE_EHRPD,
    -> "3G"

    TelephonyManager.NETWORK_TYPE_EDGE,
    TelephonyManager.NETWORK_TYPE_GPRS,
    TelephonyManager.NETWORK_TYPE_CDMA,
    TelephonyManager.NETWORK_TYPE_1xRTT,
    TelephonyManager.NETWORK_TYPE_GSM,
    -> "2G"

    else -> null
}
