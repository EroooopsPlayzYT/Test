package com.eroooops.wifispeedtest.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eroooops.wifispeedtest.data.ResultStore
import com.eroooops.wifispeedtest.data.TestResult
import com.eroooops.wifispeedtest.net.Phase
import com.eroooops.wifispeedtest.net.SpeedTestEngine
import com.eroooops.wifispeedtest.net.TestConfig
import com.eroooops.wifispeedtest.net.TestUpdate
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
    val phase: Phase = Phase.IDLE,
    val phaseProgress: Float = 0f,
    val instantMbps: Double = 0.0,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val packetLossPct: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val error: String? = null,
    val config: TestConfig = TestConfig(),
    val history: List<TestResult> = emptyList(),
    val locationPermissionGranted: Boolean = false,
) {
    val running: Boolean
        get() = phase == Phase.PING || phase == Phase.DOWNLOAD || phase == Phase.UPLOAD
}

class SpeedTestViewModel(application: Application) : AndroidViewModel(application) {

    private val wifiProvider = WifiInfoProvider(application)
    private val store = ResultStore(application)

    val wifi: StateFlow<WifiStatus> = wifiProvider.status()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WifiStatus())

    private val _state = MutableStateFlow(
        UiState(
            config = store.loadConfig(),
            history = store.load(),
            locationPermissionGranted = wifiProvider.hasLocationPermission(),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var testJob: Job? = null

    fun onPermissionResult() {
        _state.value = _state.value.copy(
            locationPermissionGranted = wifiProvider.hasLocationPermission(),
        )
    }

    fun start() {
        if (testJob?.isActive == true) return
        val network = wifi.value.network
        _state.value = _state.value.copy(
            phase = Phase.PING,
            phaseProgress = 0f,
            instantMbps = 0.0,
            pingMs = null,
            jitterMs = null,
            packetLossPct = null,
            downloadMbps = null,
            uploadMbps = null,
            error = null,
        )
        testJob = viewModelScope.launch {
            SpeedTestEngine(network).run(_state.value.config).collect { update ->
                applyUpdate(update)
                if (update.phase == Phase.DONE) record(update)
            }
        }
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
            error = update.error,
        )
    }

    private fun record(update: TestUpdate) {
        val snapshot = wifi.value
        val result = TestResult(
            timestamp = System.currentTimeMillis(),
            ssid = snapshot.ssid,
            band = snapshot.band,
            rssiDbm = snapshot.rssiDbm,
            linkSpeedMbps = snapshot.txLinkSpeedMbps,
            downloadMbps = update.downloadMbps ?: 0.0,
            uploadMbps = update.uploadMbps ?: 0.0,
            pingMs = update.pingMs ?: 0.0,
            jitterMs = update.jitterMs ?: 0.0,
        )
        val history = (listOf(result) + _state.value.history).take(50)
        store.save(history)
        _state.value = _state.value.copy(history = history)
    }
}
