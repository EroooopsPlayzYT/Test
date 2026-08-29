package com.eroooops.beamformer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eroooops.beamformer.audio.ArrayGeometry
import com.eroooops.beamformer.audio.AudioEngine
import com.eroooops.beamformer.audio.BeamParams
import com.eroooops.beamformer.audio.CaptureProbe
import com.eroooops.beamformer.audio.CaptureService
import com.eroooops.beamformer.audio.CaptureSource
import com.eroooops.beamformer.audio.CaptureVerdict
import com.eroooops.beamformer.audio.EngineConfig
import com.eroooops.beamformer.audio.EngineState
import com.eroooops.beamformer.audio.MicGeometry
import com.eroooops.beamformer.audio.ProbeReport
import com.eroooops.beamformer.data.BeamSettings
import com.eroooops.beamformer.data.Recording
import com.eroooops.beamformer.data.Recordings
import com.eroooops.beamformer.data.SettingsStore
import com.eroooops.beamformer.dsp.BeamformerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val probing: Boolean = false,
    val report: ProbeReport? = null,
    val settings: BeamSettings = BeamSettings(),
    val source: CaptureSource = CaptureSource.PHONE_ARRAY,
    val geometry: ArrayGeometry? = null,
    val recordings: List<Recording> = emptyList(),
    val permissionGranted: Boolean = false,
    val bluetoothAvailable: Boolean = false,
    val headsetName: String? = null,
    val showProbeDetail: Boolean = false,
    val message: String? = null,
) {
    val verdict: CaptureVerdict? get() = report?.verdict

    /** True when this device can actually be beamformed rather than simulated. */
    val canBeamformLive: Boolean
        get() = report?.verdict == CaptureVerdict.REAL_MULTICHANNEL

    val simulating: Boolean get() = source == CaptureSource.SIMULATION
}

class BeamViewModel(application: Application) : AndroidViewModel(application) {

    private val probe = CaptureProbe(application)
    private val engine = AudioEngine(application)
    private val store = SettingsStore(application)
    private val recordings = Recordings(application)

    val engineState: StateFlow<EngineState> = engine.state

    private val _state = MutableStateFlow(
        UiState(
            settings = store.load(),
            permissionGranted = probe.hasPermission(),
            recordings = recordings.list(),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        applyParams()
        if (_state.value.permissionGranted) runProbe()
    }

    fun onPermissionResult() {
        val granted = probe.hasPermission()
        _state.value = _state.value.copy(
            permissionGranted = granted,
            // Also refreshes after a Bluetooth permission grant, which is the
            // other permission routed through this callback.
            headsetName = engine.headsetName(),
        )
        if (granted && _state.value.report == null) runProbe()
    }

    /**
     * Measures what this device will actually hand over, then picks a starting
     * mode from the answer rather than from an assumption.
     */
    fun runProbe() {
        if (_state.value.probing) return
        _state.value = _state.value.copy(probing = true)
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { probe.run() }
            val settings = _state.value.settings
            val geometry = geometryFor(report, settings)

            // A device that cannot give two real channels cannot be beamformed,
            // so it starts in simulation rather than showing a live display
            // built on one microphone.
            val source = if (report.verdict == CaptureVerdict.REAL_MULTICHANNEL) {
                CaptureSource.PHONE_ARRAY
            } else {
                CaptureSource.SIMULATION
            }

            _state.value = _state.value.copy(
                probing = false,
                report = report,
                geometry = geometry,
                source = source,
                bluetoothAvailable = engine.hasBluetoothMicrophone(),
                headsetName = engine.headsetName(),
                showProbeDetail = report.verdict != CaptureVerdict.REAL_MULTICHANNEL,
            )
        }
    }

    private fun geometryFor(report: ProbeReport?, settings: BeamSettings): ArrayGeometry {
        if (report == null || report.verdict != CaptureVerdict.REAL_MULTICHANNEL) {
            return MicGeometry.simulated(settings.spacingMetres)
        }
        return MicGeometry.fromReport(
            microphones = report.microphones,
            channels = report.chosen?.channels ?: 2,
            assumedSpacingMetres = settings.spacingMetres,
        )
    }

    fun selectSource(source: CaptureSource) {
        if (_state.value.source == source) return
        val wasRunning = engineState.value.running
        if (wasRunning) stop()
        _state.value = _state.value.copy(source = source, message = null)
        if (wasRunning) start()
    }

    fun start() {
        val current = _state.value
        val report = current.report
        val settings = current.settings

        val config = when (current.source) {
            CaptureSource.SIMULATION -> EngineConfig(
                source = CaptureSource.SIMULATION,
                sampleRate = 48000,
                channels = 2,
                spacingMetres = settings.spacingMetres,
            )

            CaptureSource.BLUETOOTH_REFERENCE -> EngineConfig(
                source = CaptureSource.BLUETOOTH_REFERENCE,
                // The hands-free link is mono and narrowband whatever we ask
                // for, so ask for what it actually is.
                audioSource = android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate = 16000,
                channels = 1,
                spacingMetres = settings.spacingMetres,
            )

            CaptureSource.PHONE_ARRAY -> {
                val chosen = report?.chosen
                EngineConfig(
                    source = CaptureSource.PHONE_ARRAY,
                    audioSource = audioSourceFor(chosen?.sourceName),
                    sampleRate = chosen?.sampleRate ?: 48000,
                    channels = chosen?.channels ?: 2,
                    spacingMetres = settings.spacingMetres,
                )
            }
        }

        val geometry = when (current.source) {
            CaptureSource.SIMULATION -> MicGeometry.simulated(settings.spacingMetres)
            // Capture here is one mono channel from the headset's own DSP,
            // whatever the phone's array looks like — reusing the phone's
            // geometry would size the beamformer's weights for channels that
            // are not being captured and crash on the first frame.
            CaptureSource.BLUETOOTH_REFERENCE -> MicGeometry.single(
                "Bluetooth reference: one processed mono channel from the headset, " +
                    "so there is nothing to beamform.",
            )
            CaptureSource.PHONE_ARRAY -> current.geometry ?: geometryFor(report, settings)
        }

        _state.value = current.copy(geometry = geometry)
        applyParams()
        engine.start(config, geometry)

        // The foreground service is declared with the microphone type, and
        // Android enforces that a service holding it is actually using the
        // microphone — starting it under simulation, which touches no
        // microphone at all, risks exactly the SecurityException that type is
        // meant to prevent apps from working around.
        if (current.source != CaptureSource.SIMULATION) {
            CaptureService.start(getApplication())
        }
    }

    fun stop() {
        engine.stop()
        CaptureService.stop(getApplication())
        _state.value = _state.value.copy(recordings = recordings.list())
    }

    fun toggleRunning() {
        if (engineState.value.running) stop() else start()
    }

    private fun audioSourceFor(name: String?): Int = when (name) {
        "UNPROCESSED" -> android.media.MediaRecorder.AudioSource.UNPROCESSED
        "CAMCORDER" -> android.media.MediaRecorder.AudioSource.CAMCORDER
        "VOICE_RECOGNITION" -> android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
        else -> android.media.MediaRecorder.AudioSource.MIC
    }

    // --- Beam controls -------------------------------------------------------

    fun steerTo(degrees: Double) = updateSettings {
        it.copy(steerDeg = degrees.coerceIn(0.0, 180.0), followDirection = false)
    }

    fun setBeamformerType(type: BeamformerType) = updateSettings { it.copy(beamformerType = type) }

    fun setWhiteNoiseFloor(db: Double) = updateSettings {
        it.copy(minWhiteNoiseGainDb = db.coerceIn(BeamSettings.WNG_RANGE))
    }

    fun setMonitorGain(gain: Double) = updateSettings {
        it.copy(monitorGain = gain.coerceIn(BeamSettings.GAIN_RANGE))
    }

    fun setSpacing(metres: Double) {
        updateSettings { it.copy(spacingMetres = metres.coerceIn(BeamSettings.SPACING_RANGE)) }
        // Spacing changes the array itself, not just the weights, so the running
        // capture has to be rebuilt around it.
        if (engineState.value.running) {
            stop()
            start()
        } else {
            _state.value = _state.value.copy(
                geometry = geometryFor(_state.value.report, _state.value.settings),
            )
        }
    }

    fun setFollowDirection(follow: Boolean) = updateSettings { it.copy(followDirection = follow) }

    fun setAllowSpeakerMonitor(allow: Boolean) {
        _state.value = _state.value.copy(
            settings = _state.value.settings.copy(allowSpeakerMonitor = allow),
        )
        applyParams()
    }

    private var monitoring = false

    fun toggleMonitoring() {
        val (route, reason) = engine.monitorRoute()
        if (route == com.eroooops.beamformer.audio.MonitorRoute.SPEAKER &&
            !_state.value.settings.allowSpeakerMonitor
        ) {
            _state.value = _state.value.copy(message = reason)
            return
        }
        monitoring = !monitoring
        applyParams()
        // The monitor path is opened when capture starts, so turning it on
        // mid-run needs a restart rather than a flag flip.
        if (engineState.value.running) {
            stop()
            start()
        }
    }

    val monitoringRequested: Boolean get() = monitoring

    private fun updateSettings(transform: (BeamSettings) -> BeamSettings) {
        val next = transform(_state.value.settings)
        _state.value = _state.value.copy(settings = next)
        store.save(next)
        applyParams()
    }

    /**
     * Pushes the current settings into the engine. Weight design happens off the
     * main thread; the audio thread only ever sees a finished set.
     */
    private fun applyParams() {
        val settings = _state.value.settings
        viewModelScope.launch(Dispatchers.Default) {
            engine.updateParams(
                BeamParams(
                    type = settings.beamformerType,
                    steerDeg = settings.steerDeg,
                    minWhiteNoiseGainDb = settings.minWhiteNoiseGainDb,
                    monitoring = monitoring,
                    monitorGain = settings.monitorGain,
                    allowSpeakerMonitor = settings.allowSpeakerMonitor,
                )
            )
        }
    }

    /** Turns the beam to follow the live estimate, when the user asked for that. */
    fun onDirectionEstimate(degrees: Double, confidence: Double) {
        if (!_state.value.settings.followDirection) return
        if (confidence < FOLLOW_CONFIDENCE) return
        val settings = _state.value.settings
        // Ease toward the estimate rather than snapping: a bearing that jitters
        // by a few degrees would otherwise make the monitored audio warble.
        val eased = settings.steerDeg + (degrees - settings.steerDeg) * FOLLOW_RATE
        _state.value = _state.value.copy(settings = settings.copy(steerDeg = eased))
        applyParams()
    }

    // --- Recording -----------------------------------------------------------

    fun toggleRecording() {
        if (engineState.value.recording) {
            val files = engine.stopRecording()
            _state.value = _state.value.copy(
                recordings = recordings.list(),
                message = if (files.isEmpty()) "Nothing was recorded" else null,
            )
        } else {
            if (!engineState.value.running) {
                _state.value = _state.value.copy(message = "Start listening first")
                return
            }
            val started = engine.startRecording(recordings.directory(), recordings.newLabel())
            if (!started) {
                _state.value = _state.value.copy(message = "Could not start recording")
            }
        }
    }

    fun deleteRecording(recording: Recording) {
        recordings.delete(recording)
        _state.value = _state.value.copy(recordings = recordings.list())
    }

    fun shareRecording(recording: Recording) = recordings.shareIntent(recording)

    fun shareReport(): android.content.Intent? {
        val report = _state.value.report ?: return null
        return recordings.shareText(report.asText(), "Beamformer capture probe")
    }

    fun toggleProbeDetail() {
        _state.value = _state.value.copy(showProbeDetail = !_state.value.showProbeDetail)
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    override fun onCleared() {
        engine.stop()
        CaptureService.stop(getApplication())
        super.onCleared()
    }

    private companion object {
        const val FOLLOW_CONFIDENCE = 0.35
        const val FOLLOW_RATE = 0.25
    }
}
