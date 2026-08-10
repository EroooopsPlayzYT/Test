package com.eroooops.wifispeedtest.ui

import android.app.Application
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eroooops.wifispeedtest.cell.CellularInfoProvider
import com.eroooops.wifispeedtest.cell.CellularStatus
import com.eroooops.wifispeedtest.data.ResultStore
import com.eroooops.wifispeedtest.data.TestResult
import com.eroooops.wifispeedtest.net.Endpoint
import com.eroooops.wifispeedtest.net.Endpoints
import com.eroooops.wifispeedtest.net.Phase
import com.eroooops.wifispeedtest.net.SpeedTestEngine
import com.eroooops.wifispeedtest.net.TestConfig
import com.eroooops.wifispeedtest.net.TestUpdate
import com.eroooops.wifispeedtest.net.ProbeResult
import com.eroooops.wifispeedtest.net.ProfileOutcome
import com.eroooops.wifispeedtest.net.TrafficProfile
import com.eroooops.wifispeedtest.net.Transport
import com.eroooops.wifispeedtest.wifi.WifiInfoProvider
import com.eroooops.wifispeedtest.wifi.WifiStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(
    val transport: Transport = Transport.WIFI,
    val phase: Phase = Phase.IDLE,
    val phaseProgress: Float = 0f,
    val instantMbps: Double = 0.0,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val packetLossPct: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val bytesUsed: Long = 0L,
    val server: String? = null,
    val notice: String? = null,
    val samples: List<Double> = emptyList(),
    val probes: List<ProbeResult> = emptyList(),
    val outcome: ProfileOutcome = ProfileOutcome(),
    val showDiagnostics: Boolean = false,
    val error: String? = null,
    val config: TestConfig = TestConfig(),
    val history: List<TestResult> = emptyList(),
    val locationPermissionGranted: Boolean = false,
    val phonePermissionGranted: Boolean = false,
    val cellularSupported: Boolean = true,
) {
    val running: Boolean
        get() = phase == Phase.PROBE || phase == Phase.PING ||
            phase == Phase.DOWNLOAD || phase == Phase.UPLOAD
}

class SpeedTestViewModel(application: Application) : AndroidViewModel(application) {

    private val wifiProvider = WifiInfoProvider(application)
    private val cellularProvider = CellularInfoProvider(application)
    private val store = ResultStore(application)

    val wifi: StateFlow<WifiStatus> = wifiProvider.status()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WifiStatus())

    val cellular: StateFlow<CellularStatus> = cellularProvider.status()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CellularStatus())

    private val _state = MutableStateFlow(
        UiState(
            transport = store.loadTransport(),
            config = store.loadConfig(),
            history = store.load(),
            locationPermissionGranted = wifiProvider.hasLocationPermission(),
            phonePermissionGranted = cellularProvider.hasPhoneStatePermission(),
            cellularSupported = cellularProvider.isSupported(),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var testJob: Job? = null

    fun onPermissionResult() {
        _state.value = _state.value.copy(
            locationPermissionGranted = wifiProvider.hasLocationPermission(),
            phonePermissionGranted = cellularProvider.hasPhoneStatePermission(),
        )
    }

    fun selectTransport(transport: Transport) {
        if (_state.value.running || _state.value.transport == transport) return
        store.saveTransport(transport)
        // Results from the other radio would be misleading under a new heading.
        _state.value = _state.value.copy(
            transport = transport,
            phase = Phase.IDLE,
            phaseProgress = 0f,
            instantMbps = 0.0,
            pingMs = null,
            jitterMs = null,
            packetLossPct = null,
            downloadMbps = null,
            uploadMbps = null,
            bytesUsed = 0L,
            server = null,
            notice = null,
            samples = emptyList(),
            probes = emptyList(),
            outcome = ProfileOutcome(),
            error = null,
        )
    }

    fun start() {
        if (testJob?.isActive == true) return
        val transport = _state.value.transport
        _state.value = _state.value.copy(
            phase = Phase.PROBE,
            phaseProgress = 0f,
            instantMbps = 0.0,
            pingMs = null,
            jitterMs = null,
            packetLossPct = null,
            downloadMbps = null,
            uploadMbps = null,
            bytesUsed = 0L,
            server = null,
            notice = null,
            samples = emptyList(),
            probes = emptyList(),
            outcome = ProfileOutcome(),
            error = null,
        )

        testJob = viewModelScope.launch {
            try {
                when (transport) {
                    Transport.WIFI -> runOn(wifi.value.network, transport)
                    // Mobile data is usually idle while Wi-Fi is up, so the
                    // radio has to be requested and held for the whole test.
                    Transport.CELLULAR -> cellularProvider.withCellularNetwork { network ->
                        runOn(network, transport)
                    }
                }
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    phase = Phase.ERROR,
                    instantMbps = 0.0,
                    error = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private suspend fun runOn(network: Network?, transport: Transport) {
        val config = _state.value.config
        SpeedTestEngine(network).run(config, candidatesFor(config)).collect { update ->
            applyUpdate(update)
            if (update.phase == Phase.DONE) record(update, transport)
        }
    }

    /**
     * Falling back is only safe while the built-in URLs are untouched. Someone
     * who aimed the app at a LAN box wants that box or an error, not a silent
     * hop onto the internet reported as if it were their local link.
     */
    private fun candidatesFor(config: TestConfig): List<Endpoint> =
        if (config.usesDefaultServer) {
            Endpoints.BUILT_IN
        } else {
            listOf(Endpoint("Custom", config.downloadUrl, config.uploadUrl.ifBlank { null }))
        }

    fun stop() {
        testJob?.cancel()
        testJob = null
        _state.value = _state.value.copy(
            phase = Phase.IDLE,
            phaseProgress = 0f,
            instantMbps = 0.0,
        )
    }

    fun updateConfig(config: TestConfig) {
        store.saveConfig(config)
        _state.value = _state.value.copy(config = config)
    }

    fun selectProfile(profile: TrafficProfile) {
        if (_state.value.running) return
        updateConfig(_state.value.config.copy(profile = profile))
    }

    fun toggleDiagnostics() {
        _state.value = _state.value.copy(showDiagnostics = !_state.value.showDiagnostics)
    }

    fun clearHistory() {
        store.save(emptyList())
        _state.value = _state.value.copy(history = emptyList())
    }

    private fun applyUpdate(update: TestUpdate) {
        _state.value = _state.value.copy(
            phase = update.phase,
            phaseProgress = update.phaseProgress,
            instantMbps = update.instantMbps,
            pingMs = update.pingMs ?: _state.value.pingMs,
            jitterMs = update.jitterMs ?: _state.value.jitterMs,
            packetLossPct = update.packetLossPct ?: _state.value.packetLossPct,
            downloadMbps = update.downloadMbps ?: _state.value.downloadMbps,
            uploadMbps = update.uploadMbps ?: _state.value.uploadMbps,
            bytesUsed = maxOf(update.bytesUsed, _state.value.bytesUsed),
            server = update.server ?: _state.value.server,
            notice = update.notice ?: _state.value.notice,
            samples = update.samples,
            probes = update.probes.ifEmpty { _state.value.probes },
            outcome = update.outcome,
            error = update.error,
        )
    }

    private fun record(update: TestUpdate, transport: Transport) {
        val result = when (transport) {
            Transport.WIFI -> wifi.value.let { snapshot ->
                TestResult(
                    timestamp = System.currentTimeMillis(),
                    transport = transport,
                    networkLabel = snapshot.ssid,
                    detail = listOfNotNull(
                        snapshot.band,
                        snapshot.channel?.let { "ch $it" },
                    ).joinToString(" · ").takeIf { it.isNotEmpty() },
                    signalDbm = snapshot.rssiDbm,
                    linkSpeedMbps = snapshot.txLinkSpeedMbps,
                    downloadMbps = update.downloadMbps ?: 0.0,
                    uploadMbps = update.uploadMbps ?: 0.0,
                    pingMs = update.pingMs ?: 0.0,
                    jitterMs = update.jitterMs ?: 0.0,
                    bytesUsed = update.bytesUsed,
                    server = update.server,
                )
            }

            Transport.CELLULAR -> cellular.value.let { snapshot ->
                TestResult(
                    timestamp = System.currentTimeMillis(),
                    transport = transport,
                    networkLabel = snapshot.carrier,
                    detail = snapshot.networkType,
                    signalDbm = snapshot.signalDbm,
                    linkSpeedMbps = null,
                    downloadMbps = update.downloadMbps ?: 0.0,
                    uploadMbps = update.uploadMbps ?: 0.0,
                    pingMs = update.pingMs ?: 0.0,
                    jitterMs = update.jitterMs ?: 0.0,
                    bytesUsed = update.bytesUsed,
                    server = update.server,
                )
            }
        }

        val history = (listOf(result) + _state.value.history).take(50)
        store.save(history)
        _state.value = _state.value.copy(history = history)
    }
}
