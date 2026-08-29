package com.eroooops.beamformer.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import com.eroooops.beamformer.dsp.ArraySimulator
import com.eroooops.beamformer.dsp.BeamProcessor
import com.eroooops.beamformer.dsp.BeamformerType
import com.eroooops.beamformer.dsp.DoaEstimate
import com.eroooops.beamformer.dsp.DoaScanner
import com.eroooops.beamformer.dsp.SyntheticSource
import com.eroooops.beamformer.dsp.rms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.roundToInt

/** Where the samples come from. */
enum class CaptureSource {
    /** The phone's own microphones — the only source that can be beamformed. */
    PHONE_ARRAY,

    /**
     * A Bluetooth headset's microphone, over the hands-free voice link.
     *
     * The headset may have several capsules, but its own processor combines them
     * and sends one finished mono stream; nothing on Android can reach the
     * individual capsules. So this is a *reference* recording — useful to
     * compare against, impossible to beamform.
     */
    BLUETOOTH_REFERENCE,

    /** No microphone at all: synthetic sources through the real beamformer. */
    SIMULATION,
}

enum class MonitorRoute { NONE, WIRED, USB, BLUETOOTH, SPEAKER }

data class EngineConfig(
    val source: CaptureSource = CaptureSource.PHONE_ARRAY,
    val audioSource: Int = android.media.MediaRecorder.AudioSource.UNPROCESSED,
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val spacingMetres: Double = 0.14,
    val frameSize: Int = 1024,
)

data class BeamParams(
    val type: BeamformerType = BeamformerType.SUPERDIRECTIVE,
    val steerDeg: Double = 90.0,
    val minWhiteNoiseGainDb: Double = -10.0,
    val monitoring: Boolean = false,
    val monitorGain: Double = 1.0,
    val allowSpeakerMonitor: Boolean = false,
)

data class EngineState(
    val running: Boolean = false,
    val source: CaptureSource = CaptureSource.PHONE_ARRAY,
    val sampleRate: Int = 48000,
    val channels: Int = 0,
    val channelLevelsDb: List<Double> = emptyList(),
    val beamLevelDb: Double = SILENCE_DB,
    val omniLevelDb: Double = SILENCE_DB,
    /** Positive means the beam is quieter than an unsteered recording. */
    val beamVsOmniDb: Double = 0.0,
    val doaDeg: Double? = null,
    val doaConfidence: Double = 0.0,
    val surface: List<Float> = emptyList(),
    val angles: List<Float> = emptyList(),
    val directivityIndexDb: Double = 0.0,
    val whiteNoiseGainDb: Double = 0.0,
    val aliasFrequencyHz: Double = 0.0,
    val scanBandHz: Pair<Double, Double>? = null,
    val geometryNote: String = "",
    val geometrySource: GeometrySource = GeometrySource.ASSUMED,
    val directionAvailable: Boolean = false,
    val monitoring: Boolean = false,
    val monitorRoute: MonitorRoute = MonitorRoute.NONE,
    val monitorBlockedReason: String? = null,
    val recording: Boolean = false,
    val recordedSeconds: Double = 0.0,
    val routedDevice: String? = null,
    val error: String? = null,
)

const val SILENCE_DB = -120.0

/**
 * Capture, beamform, monitor and record — all on one dedicated thread.
 *
 * Everything after the microphone runs at a fixed hop with no allocation, on a
 * thread raised to audio priority. It is deliberately not a coroutine: a
 * dispatcher can reschedule this work behind something else, and the cost of
 * being a few milliseconds late is a click in the monitored output.
 */
class AudioEngine(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetooth = BluetoothAudio(appContext)

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    private var params = BeamParams()

    @Volatile
    private var running = false

    private var worker: Thread? = null
    private var processor: BeamProcessor? = null

    @Volatile
    private var recorder: SessionRecorder? = null

    val currentParams: BeamParams get() = params

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Applies new beam settings without interrupting capture.
     *
     * Weight design is a few hundred small linear solves — too slow for the
     * audio thread, fast enough for the caller's. The finished set is swapped in
     * atomically, so a frame is never processed with half of one design.
     */
    fun updateParams(next: BeamParams) {
        val previous = params
        params = next
        val active = processor ?: return
        if (previous.type != next.type ||
            previous.steerDeg != next.steerDeg ||
            previous.minWhiteNoiseGainDb != next.minWhiteNoiseGainDb
        ) {
            active.steer(next.type, next.steerDeg, next.minWhiteNoiseGainDb)
        }
    }

    fun start(config: EngineConfig, geometry: ArrayGeometry) {
        if (running) return
        if (config.source != CaptureSource.SIMULATION && !hasPermission()) {
            _state.value = _state.value.copy(error = "Microphone permission is not granted")
            return
        }
        running = true
        worker = thread(name = "beamformer-audio", priority = Thread.MAX_PRIORITY) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                runLoop(config, geometry)
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    running = false,
                    error = error.message ?: error.javaClass.simpleName,
                )
            } finally {
                running = false
                processor = null
                closeRecorder()
                _state.value = _state.value.copy(running = false, recording = false)
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(1500)
        worker = null
    }

    fun startRecording(directory: File, label: String): Boolean {
        val active = processor ?: return false
        if (recorder != null) return false
        return runCatching {
            recorder = SessionRecorder(directory, label, active.sampleRate, active.channels)
            true
        }.getOrDefault(false)
    }

    fun stopRecording(): List<File> {
        val current = recorder ?: return emptyList()
        recorder = null
        return current.finish()
    }

    private fun closeRecorder() {
        recorder?.finish()
        recorder = null
    }

    @SuppressLint("MissingPermission")
    private fun runLoop(config: EngineConfig, geometry: ArrayGeometry) {
        val channels = if (config.source == CaptureSource.BLUETOOTH_REFERENCE) 1 else config.channels
        val array = geometry.array
        val beamformer = BeamProcessor(array, config.sampleRate, config.frameSize)
        val startParams = params
        beamformer.steer(startParams.type, startParams.steerDeg, startParams.minWhiteNoiseGainDb)
        processor = beamformer

        val hop = beamformer.hop
        // Direction finding gets a longer window than the audio path: a 2048
        // point frame at 48 kHz is 43 ms, which is short enough to track a
        // talker and long enough to resolve the low frequencies this array can
        // actually use.
        val doaFrame = 2048
        val scanner = if (channels >= 2) DoaScanner(array, config.sampleRate, doaFrame) else null
        val directionAvailable = scanner?.usable == true

        val planes = Array(channels) { DoubleArray(hop) }
        val doaHistory = Array(channels) { DoubleArray(doaFrame) }
        val surface = scanner?.let { DoubleArray(it.angles.size) }
        val beam = DoubleArray(hop)
        val omni = DoubleArray(hop)
        val interleaved = ShortArray(hop * channels)
        val monoOut = ShortArray(hop)

        var record: AudioRecord? = null
        var track: AudioTrack? = null
        var simulator: ArraySimulator? = null
        var scoStarted = false

        try {
            when (config.source) {
                CaptureSource.SIMULATION -> {
                    simulator = ArraySimulator(array, config.sampleRate).apply {
                        sources = listOf(
                            SyntheticSource(35.0, 300.0, 2400.0, seed = 1),
                            SyntheticSource(120.0, 400.0, 3000.0, seed = 2, amplitude = 0.7),
                        )
                        noiseAmplitude = 0.05
                        rotationDegreesPerSecond = 9.0
                    }
                }

                CaptureSource.BLUETOOTH_REFERENCE -> {
                    scoStarted = bluetooth.start()
                    if (!scoStarted) {
                        _state.value = _state.value.copy(
                            error = "Could not open the Bluetooth headset microphone",
                        )
                        return
                    }
                    record = openRecord(config, channels, preferBuiltIn = false)
                }

                CaptureSource.PHONE_ARRAY -> {
                    record = openRecord(config, channels, preferBuiltIn = true)
                }
            }

            if (record != null && record.state != AudioRecord.STATE_INITIALIZED) {
                _state.value = _state.value.copy(error = "Could not open the microphone")
                return
            }
            record?.startRecording()

            val routed = runCatching { record?.routedDevice?.type }.getOrNull()
            val routedName = routed?.let { describeInput(it) }

            val (route, blockedReason) = monitorRoute()
            if (params.monitoring && (route != MonitorRoute.SPEAKER || params.allowSpeakerMonitor)) {
                track = openTrack(config.sampleRate)
                track.play()
            }

            _state.value = _state.value.copy(
                running = true,
                source = config.source,
                sampleRate = config.sampleRate,
                channels = channels,
                aliasFrequencyHz = array.aliasFrequency,
                scanBandHz = scanner?.let { it.bandLowHz to it.bandHighHz },
                geometryNote = geometry.note,
                geometrySource = geometry.origin,
                directionAvailable = directionAvailable,
                monitorRoute = route,
                monitorBlockedReason = blockedReason,
                routedDevice = routedName,
                directivityIndexDb = beamformer.directivityIndexDb(),
                whiteNoiseGainDb = beamformer.whiteNoiseGainDb(),
                error = null,
            )

            var hopsSinceScan = 0
            var lastEmit = 0L
            var estimate: DoaEstimate? = null

            while (running) {
                val active = params

                if (simulator != null) {
                    simulator.render(planes, 0, hop)
                } else {
                    val wanted = hop * channels
                    var filled = 0
                    var fatal = false
                    while (filled < wanted && running) {
                        val read = record!!.read(interleaved, filled, wanted - filled)
                        if (read < 0) {
                            // A negative return is one of AudioRecord's ERROR_*
                            // constants — the stream is dead, not just briefly
                            // empty. Retrying in a tight loop here would spin at
                            // full CPU for as long as capture keeps running, so
                            // this is treated as fatal rather than transient.
                            fatal = true
                            break
                        }
                        if (read == 0) break
                        filled += read
                    }
                    if (fatal) {
                        _state.value = _state.value.copy(
                            error = "The microphone stream stopped unexpectedly",
                        )
                        break
                    }
                    if (filled < wanted) continue
                    var index = 0
                    for (n in 0 until hop) {
                        for (c in 0 until channels) {
                            planes[c][n] = interleaved[index++] / 32768.0
                        }
                    }
                }

                beamformer.process(planes, 0, beam, omni, 0)

                if (scanner != null && surface != null) {
                    for (c in 0 until channels) {
                        val history = doaHistory[c]
                        System.arraycopy(history, hop, history, 0, doaFrame - hop)
                        System.arraycopy(planes[c], 0, history, doaFrame - hop, hop)
                    }
                    if (++hopsSinceScan >= SCAN_EVERY_HOPS) {
                        hopsSinceScan = 0
                        estimate = scanner.scan(doaHistory, 0, surface)
                    }
                }

                if (track != null) {
                    val gain = active.monitorGain
                    for (n in 0 until hop) {
                        val value = (beam[n] * gain * 32767.0).coerceIn(-32767.0, 32767.0)
                        monoOut[n] = value.roundToInt().toShort()
                    }
                    track.write(monoOut, 0, hop)
                }

                recorder?.write(planes, beam, hop)

                val now = System.nanoTime()
                if (now - lastEmit > EMIT_INTERVAL_NANOS) {
                    lastEmit = now
                    emit(beamformer, planes, beam, omni, estimate, scanner, surface, hop)
                }
            }
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { track?.stop() }
            runCatching { track?.release() }
            if (scoStarted) bluetooth.stop()
        }
    }

    private fun emit(
        beamformer: BeamProcessor,
        planes: Array<DoubleArray>,
        beam: DoubleArray,
        omni: DoubleArray,
        estimate: DoaEstimate?,
        scanner: DoaScanner?,
        surface: DoubleArray?,
        hop: Int,
    ) {
        val beamRms = rms(beam, 0, hop)
        val omniRms = rms(omni, 0, hop)
        val current = _state.value
        _state.value = current.copy(
            channelLevelsDb = planes.map { toDb(rms(it, 0, hop)) },
            beamLevelDb = toDb(beamRms),
            omniLevelDb = toDb(omniRms),
            // Positive when the beam is quieter than a plain recording of the
            // same instant. Labelled as exactly that in the UI, because it
            // depends on what happened to be making noise and is not the
            // array's directivity.
            beamVsOmniDb = if (beamRms > 0 && omniRms > 0) 20.0 * log10(omniRms / beamRms) else 0.0,
            doaDeg = estimate?.azimuthDeg,
            doaConfidence = estimate?.confidence ?: 0.0,
            surface = surface?.map { it.toFloat() } ?: emptyList(),
            angles = scanner?.angles?.map { it.toFloat() } ?: emptyList(),
            directivityIndexDb = beamformer.directivityIndexDb(),
            whiteNoiseGainDb = beamformer.whiteNoiseGainDb(),
            recording = recorder != null,
            recordedSeconds = recorder?.seconds ?: 0.0,
            monitoring = params.monitoring,
        )
    }

    private fun toDb(value: Double): Double =
        if (value <= 1e-7) SILENCE_DB else (20.0 * log10(value)).coerceAtLeast(SILENCE_DB)

    @SuppressLint("MissingPermission")
    private fun openRecord(
        config: EngineConfig,
        channels: Int,
        preferBuiltIn: Boolean,
    ): AudioRecord {
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            config.audioSource,
            config.sampleRate,
            mask,
            AudioFormat.ENCODING_PCM_16BIT,
            // Four periods of headroom: enough that a scheduling hiccup does not
            // drop samples, short enough that latency stays usable.
            maxOf(minBuffer * 4, config.frameSize * channels * 8),
        )
        if (preferBuiltIn) {
            runCatching {
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    ?.let { record.preferredDevice = it }
            }
        }
        return record
    }

    private fun openTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build()
    }

    /**
     * Decides whether monitoring is safe.
     *
     * An open microphone fed to the phone's own loudspeaker at beamformer gain
     * howls within a second, and on a superdirective setting it howls loudly.
     * So the speaker is refused unless the user has explicitly accepted it.
     */
    fun monitorRoute(): Pair<MonitorRoute, String?> {
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrDefault(emptyArray())

        val types = outputs.map { it.type }.toSet()
        return when {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES in types ||
                AudioDeviceInfo.TYPE_WIRED_HEADSET in types -> MonitorRoute.WIRED to null

            AudioDeviceInfo.TYPE_USB_HEADSET in types ||
                AudioDeviceInfo.TYPE_USB_DEVICE in types -> MonitorRoute.USB to null

            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types ||
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types ->
                MonitorRoute.BLUETOOTH to
                    "Bluetooth adds 150-300 ms, so the beam will sound behind the room. " +
                    "Wired headphones are much better for live listening."

            else -> MonitorRoute.SPEAKER to
                "No headphones connected. Monitoring through the loudspeaker feeds the " +
                "microphones back into themselves and will howl."
        }
    }

    fun headsetName(): String? = bluetooth.headsetName()

    fun hasBluetoothMicrophone(): Boolean = bluetooth.available()

    private fun describeInput(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in microphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        else -> "input type $type"
    }

    private companion object {
        /** Roughly 23 direction scans a second, which is faster than the eye needs. */
        const val SCAN_EVERY_HOPS = 4
        const val EMIT_INTERVAL_NANOS = 50_000_000L
    }
}
