package com.eroooops.beamformer.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** What the probe concluded about this device's microphones. */
enum class CaptureVerdict {
    /** Two or more genuinely different channels. Beamforming works here. */
    REAL_MULTICHANNEL,

    /**
     * The device accepted a stereo request and returned the same signal twice.
     * This is the important case to detect: it looks like success from every
     * API the app can ask, and a beamformer fed duplicated mono produces a
     * confident, meaningless bearing.
     */
    DUPLICATED_MONO,

    /** One channel only. Honest, and common. */
    MONO_ONLY,

    /** Nothing opened, or every stream returned digital silence. */
    UNAVAILABLE,

    /** The microphone permission has not been granted. */
    NO_PERMISSION,
}

/** One attempted combination of audio source, channel count and sample rate. */
data class ProbeAttempt(
    val sourceName: String,
    val channels: Int,
    val sampleRate: Int,
    val opened: Boolean,
    val delivered: Boolean,
    /** Correlation between the first two channels; null when only one was captured. */
    val correlation: Double?,
    /** Level difference between the first two channels, dB; null when mono. */
    val levelDifferenceDb: Double?,
    val identicalSamples: Boolean,
    val routedDevice: String?,
    val note: String,
) {
    val usableForBeamforming: Boolean
        get() = opened && delivered && channels >= 2 && !identicalSamples &&
            (correlation ?: 1.0) < IDENTITY_CORRELATION
}

data class MicDescription(
    val description: String,
    val positionKnown: Boolean,
    val x: Double,
    val y: Double,
    val z: Double,
    val directionality: String,
)

data class ProbeReport(
    val verdict: CaptureVerdict,
    val attempts: List<ProbeAttempt>,
    val chosen: ProbeAttempt?,
    /** The device's own claim about unprocessed capture, independent of what we measured. */
    val claimsUnprocessedSupport: Boolean?,
    val microphones: List<MicDescription>,
    val inputDevices: List<String>,
    val deviceLabel: String,
    val androidRelease: String,
) {
    /**
     * The whole report as plain text, for the share button.
     *
     * This is the single most useful thing the app can produce: whether a given
     * phone hands out usable multi-channel audio is not documented anywhere and
     * cannot be looked up, so the measured answer has to come back from the
     * device itself.
     */
    fun asText(): String = buildString {
        appendLine("Beamformer capture probe")
        appendLine("Device: $deviceLabel")
        appendLine("Android: $androidRelease (API ${Build.VERSION.SDK_INT})")
        appendLine("Verdict: $verdict")
        appendLine(
            "Device claims UNPROCESSED support: " +
                when (claimsUnprocessedSupport) {
                    true -> "yes"
                    false -> "no"
                    null -> "did not say"
                }
        )
        appendLine()
        appendLine("Attempts (source / channels / rate):")
        attempts.forEach { attempt ->
            append("  ${attempt.sourceName} ${attempt.channels}ch ${attempt.sampleRate}Hz: ")
            append(if (attempt.opened) "opened" else "failed to open")
            if (attempt.opened) {
                append(if (attempt.delivered) ", audio" else ", SILENT")
                attempt.correlation?.let { append(", correlation %.5f".format(it)) }
                attempt.levelDifferenceDb?.let { append(", level diff %.1f dB".format(it)) }
                if (attempt.identicalSamples) append(", IDENTICAL CHANNELS")
                attempt.routedDevice?.let { append(", routed via $it") }
            }
            if (attempt.note.isNotEmpty()) append(" [${attempt.note}]")
            appendLine()
        }
        appendLine()
        appendLine("Reported microphones:")
        if (microphones.isEmpty()) {
            appendLine("  none reported (needs Android 9+, and many skins report nothing)")
        } else {
            microphones.forEach {
                append("  ${it.description} (${it.directionality}): ")
                appendLine(
                    if (it.positionKnown) {
                        "x=%.3f y=%.3f z=%.3f m".format(it.x, it.y, it.z)
                    } else {
                        "position unknown"
                    }
                )
            }
        }
        appendLine()
        appendLine("Input devices: ${inputDevices.joinToString(", ").ifEmpty { "none" }}")
    }
}

/** Correlation at or above this means the channels carry the same signal. */
private const val IDENTITY_CORRELATION = 0.99999

private val SOURCES = listOf(
    // Unprocessed first: automatic gain control, noise suppression and echo
    // cancellation all rewrite the inter-channel phase, which is the only thing
    // a beamformer has to work with.
    "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
    "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
    "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
    "MIC" to MediaRecorder.AudioSource.MIC,
)

private val RATES = listOf(48000, 44100, 16000)

/**
 * Finds out what this phone will actually give an ordinary app.
 *
 * Three microphones on the spec sheet says nothing about how many channels
 * reach an app: most Android devices mix them down and hand back mono, some
 * accept a stereo request and return the same signal twice, and a few give two
 * genuinely different channels. There is no API that answers this, and no
 * database to look it up in, so the app measures it.
 */
class CaptureProbe(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun run(): ProbeReport {
        val label = "${Build.MANUFACTURER} ${Build.MODEL}"
        val release = Build.VERSION.RELEASE ?: "unknown"

        if (!hasPermission()) {
            return ProbeReport(
                verdict = CaptureVerdict.NO_PERMISSION,
                attempts = emptyList(),
                chosen = null,
                claimsUnprocessedSupport = claimsUnprocessed(),
                microphones = emptyList(),
                inputDevices = inputDeviceNames(),
                deviceLabel = label,
                androidRelease = release,
            )
        }

        val attempts = mutableListOf<ProbeAttempt>()
        var microphones = emptyList<MicDescription>()

        // Stereo first, across every source and rate; then mono, so that a
        // device with no multi-channel capture still reports something useful
        // rather than a list of failures.
        for (channelCount in listOf(2, 1)) {
            for ((sourceName, source) in SOURCES) {
                for (rate in RATES) {
                    val result = attempt(sourceName, source, channelCount, rate)
                    attempts += result
                    if (result.usableForBeamforming && microphones.isEmpty()) {
                        microphones = describeMicrophones(source, channelCount, rate)
                    }
                }
            }
        }

        val chosen = attempts.firstOrNull { it.usableForBeamforming }
            ?: attempts.firstOrNull { it.opened && it.delivered && it.channels == 1 }
            ?: attempts.firstOrNull { it.opened && it.delivered }

        val verdict = when {
            chosen == null -> CaptureVerdict.UNAVAILABLE
            chosen.usableForBeamforming -> CaptureVerdict.REAL_MULTICHANNEL
            attempts.any { it.opened && it.delivered && it.channels >= 2 } ->
                CaptureVerdict.DUPLICATED_MONO
            else -> CaptureVerdict.MONO_ONLY
        }

        if (microphones.isEmpty()) {
            microphones = describeMicrophones(
                MediaRecorder.AudioSource.MIC,
                chosen?.channels ?: 1,
                chosen?.sampleRate ?: 48000,
            )
        }

        return ProbeReport(
            verdict = verdict,
            attempts = attempts,
            chosen = chosen,
            claimsUnprocessedSupport = claimsUnprocessed(),
            microphones = microphones,
            inputDevices = inputDeviceNames(),
            deviceLabel = label,
            androidRelease = release,
        )
    }

    @SuppressLint("MissingPermission")
    private fun attempt(
        sourceName: String,
        source: Int,
        channelCount: Int,
        sampleRate: Int,
    ): ProbeAttempt {
        val mask = if (channelCount == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            return ProbeAttempt(
                sourceName, channelCount, sampleRate,
                opened = false, delivered = false, correlation = null,
                levelDifferenceDb = null, identicalSamples = false, routedDevice = null,
                note = "unsupported combination",
            )
        }

        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                source,
                sampleRate,
                mask,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 4,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                return ProbeAttempt(
                    sourceName, channelCount, sampleRate,
                    opened = false, delivered = false, correlation = null,
                    levelDifferenceDb = null, identicalSamples = false, routedDevice = null,
                    note = "did not initialise",
                )
            }

            // Pin to the built-in microphones. Without this, a connected headset
            // silently becomes the capture device and the array under test is
            // not the array being reported on.
            preferBuiltInMic(record)

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return ProbeAttempt(
                    sourceName, channelCount, sampleRate,
                    opened = true, delivered = false, correlation = null,
                    levelDifferenceDb = null, identicalSamples = false,
                    routedDevice = routedDeviceName(record),
                    note = "start refused, probably held by another app",
                )
            }

            val wanted = (sampleRate * PROBE_SECONDS).toInt() * channelCount
            val buffer = ShortArray(wanted)
            var filled = 0
            val deadline = System.nanoTime() + PROBE_TIMEOUT_NANOS
            while (filled < wanted && System.nanoTime() < deadline) {
                val read = record.read(buffer, filled, wanted - filled)
                if (read <= 0) break
                filled += read
            }
            val routed = routedDeviceName(record)
            record.stop()

            if (filled < channelCount * 64) {
                return ProbeAttempt(
                    sourceName, channelCount, sampleRate,
                    opened = true, delivered = false, correlation = null,
                    levelDifferenceDb = null, identicalSamples = false, routedDevice = routed,
                    note = "read returned almost nothing",
                )
            }

            return analyse(sourceName, channelCount, sampleRate, buffer, filled, routed)
        } catch (error: Throwable) {
            // A refused source throws rather than returning an error code on
            // some devices, and one bad combination must not abort the sweep.
            return ProbeAttempt(
                sourceName, channelCount, sampleRate,
                opened = false, delivered = false, correlation = null,
                levelDifferenceDb = null, identicalSamples = false, routedDevice = null,
                note = error.javaClass.simpleName + (error.message?.let { ": $it" } ?: ""),
            )
        } finally {
            runCatching { record?.release() }
        }
    }

    private fun analyse(
        sourceName: String,
        channelCount: Int,
        sampleRate: Int,
        buffer: ShortArray,
        filled: Int,
        routed: String?,
    ): ProbeAttempt {
        val frames = filled / channelCount
        if (channelCount == 1) {
            val energy = rmsOf(buffer, filled, 1, 0)
            return ProbeAttempt(
                sourceName, channelCount, sampleRate,
                opened = true, delivered = energy > 0.0, correlation = null,
                levelDifferenceDb = null, identicalSamples = false, routedDevice = routed,
                note = if (energy > 0.0) "" else "digital silence",
            )
        }

        val leftRms = rmsOf(buffer, filled, channelCount, 0)
        val rightRms = rmsOf(buffer, filled, channelCount, 1)
        val delivered = leftRms > 0.0 || rightRms > 0.0

        var identical = true
        var dotProduct = 0.0
        var leftEnergy = 0.0
        var rightEnergy = 0.0
        for (n in 0 until frames) {
            val a = buffer[n * channelCount].toDouble()
            val b = buffer[n * channelCount + 1].toDouble()
            if (a != b) identical = false
            dotProduct += a * b
            leftEnergy += a * a
            rightEnergy += b * b
        }
        val correlation = if (leftEnergy > 0.0 && rightEnergy > 0.0) {
            dotProduct / sqrt(leftEnergy * rightEnergy)
        } else {
            null
        }
        val levelDifference = if (leftRms > 0.0 && rightRms > 0.0) {
            20.0 * log10(leftRms / rightRms)
        } else {
            null
        }

        val note = when {
            !delivered -> "digital silence"
            identical -> "channels are bit-identical: this is mono duplicated, not an array"
            correlation != null && correlation >= IDENTITY_CORRELATION ->
                "channels indistinguishable: treat as duplicated mono"
            abs(levelDifference ?: 0.0) > 20.0 ->
                "one channel is far quieter; the second capsule may not be live"
            else -> ""
        }

        return ProbeAttempt(
            sourceName, channelCount, sampleRate,
            opened = true, delivered = delivered, correlation = correlation,
            levelDifferenceDb = levelDifference, identicalSamples = identical && delivered,
            routedDevice = routed, note = note,
        )
    }

    private fun rmsOf(buffer: ShortArray, filled: Int, stride: Int, offset: Int): Double {
        var sum = 0.0
        var count = 0
        var index = offset
        while (index < filled) {
            val value = buffer[index].toDouble()
            sum += value * value
            count++
            index += stride
        }
        return if (count == 0) 0.0 else sqrt(sum / count)
    }

    /**
     * Reads the microphone positions the platform is willing to disclose.
     *
     * Only meaningful while a recording is running, and only from API 28. Plenty
     * of devices — budget skins especially — return an empty list, unknown
     * positions, or throw outright, so every step is guarded: a phone that will
     * not describe its own microphones is a normal outcome here, not an error.
     */
    @SuppressLint("MissingPermission")
    private fun describeMicrophones(
        source: Int,
        channelCount: Int,
        sampleRate: Int,
    ): List<MicDescription> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        if (!hasPermission()) return emptyList()

        val mask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return emptyList()

        var record: AudioRecord? = null
        return try {
            record = AudioRecord(source, sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
            if (record.state != AudioRecord.STATE_INITIALIZED) return emptyList()
            preferBuiltInMic(record)
            record.startRecording()
            val short = ShortArray(minBuffer)
            record.read(short, 0, short.size)
            record.activeMicrophones.map { info ->
                val position = info.position
                // Android's own "not disclosed" sentinel is a fixed large
                // negative float on every axis (MicrophoneInfo.POSITION_UNKNOWN),
                // not zero and not -1 — a naive check against those would treat
                // an undisclosed position as a real one at the origin.
                val known = position != null &&
                    position.x > UNKNOWN_COORDINATE_THRESHOLD &&
                    position.y > UNKNOWN_COORDINATE_THRESHOLD &&
                    position.z > UNKNOWN_COORDINATE_THRESHOLD
                MicDescription(
                    description = info.description ?: "microphone",
                    positionKnown = known,
                    x = position?.x?.toDouble() ?: 0.0,
                    y = position?.y?.toDouble() ?: 0.0,
                    z = position?.z?.toDouble() ?: 0.0,
                    directionality = directionalityName(info.directionality),
                )
            }
        } catch (error: Throwable) {
            emptyList()
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun directionalityName(value: Int): String = when (value) {
        android.media.MicrophoneInfo.DIRECTIONALITY_OMNI -> "omnidirectional"
        android.media.MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> "bidirectional"
        android.media.MicrophoneInfo.DIRECTIONALITY_CARDIOID -> "cardioid"
        android.media.MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID -> "hypercardioid"
        android.media.MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID -> "supercardioid"
        else -> "unknown pattern"
    }

    private fun preferBuiltInMic(record: AudioRecord) {
        runCatching {
            val builtIn = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (builtIn != null) record.preferredDevice = builtIn
        }
    }

    private fun routedDeviceName(record: AudioRecord): String? = runCatching {
        record.routedDevice?.let { deviceName(it) }
    }.getOrNull()

    private fun inputDeviceNames(): List<String> = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { deviceName(it) }
    }.getOrDefault(emptyList())

    private fun deviceName(device: AudioDeviceInfo): String {
        val type = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset (SCO)"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
            else -> "type ${device.type}"
        }
        val product = device.productName?.toString()?.takeIf { it.isNotBlank() }
        return if (product != null) "$type ($product)" else type
    }

    private fun claimsUnprocessed(): Boolean? = runCatching {
        when (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }.getOrNull()

    private companion object {
        const val PROBE_SECONDS = 0.35
        const val PROBE_TIMEOUT_NANOS = 3_000_000_000L

        /**
         * Below this, a reported coordinate is Android's "not disclosed" sentinel
         * (`-Float.MAX_VALUE` on every axis) rather than a real position. Any
         * plausible phone coordinate is within a metre or two of the origin, so
         * this threshold is nowhere near a real value.
         */
        const val UNKNOWN_COORDINATE_THRESHOLD = -1000f
    }
}
