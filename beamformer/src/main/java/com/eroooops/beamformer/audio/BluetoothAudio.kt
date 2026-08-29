package com.eroooops.beamformer.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Brings up the hands-free voice link so the headset's microphone can be
 * recorded as a reference.
 *
 * Worth being clear about what this can and cannot do. A headset like the
 * Soundcore Space One has three microphones, but they are wired to its own
 * processor: it runs its noise reduction and beam selection internally and
 * sends the phone a single finished mono stream, at 16 kHz on a good link and
 * 8 kHz on a poor one. No Android API reaches the individual capsules, and
 * there is no way to combine that stream with the phone's own microphones into
 * one array either — two capture streams on unrelated clocks, with Bluetooth's
 * tens of milliseconds of jitter, cannot be aligned to the fractions of a
 * millisecond that beamforming measures.
 *
 * So the headset is useful here as a comparison, not as an array: its own
 * processed output, recorded on the same scene, against ours.
 *
 * Opening this link also forces the headset out of high-quality playback into
 * the voice profile, so monitoring through the same headset while recording
 * from it sounds far worse. The UI says so rather than letting it surprise
 * anyone.
 */
class BluetoothAudio(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var startedLegacySco = false
    private var claimedCommunicationDevice = false

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    private fun scoInput(): AudioDeviceInfo? = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }.getOrNull()

    fun available(): Boolean = scoInput() != null

    /** The headset's name, when the platform will disclose it. */
    fun headsetName(): String? {
        if (!hasConnectPermission()) return null
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                ?.productName?.toString()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Returns true once the voice link is actually up. */
    fun start(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: return false
            claimedCommunicationDevice = runCatching {
                audioManager.setCommunicationDevice(device)
            }.getOrDefault(false)
            return claimedCommunicationDevice
        }

        @Suppress("DEPRECATION")
        run {
            if (!audioManager.isBluetoothScoAvailableOffCall) return false
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            startedLegacySco = true
        }

        // The link takes a moment to come up and there is no callback worth
        // wiring for a one-off; poll briefly rather than start recording into a
        // route that is not ready and capture a second of silence.
        val deadline = SystemClock.elapsedRealtime() + SCO_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothScoOn) return true
            SystemClock.sleep(50)
        }
        stop()
        return false
    }

    fun stop() {
        if (claimedCommunicationDevice) {
            runCatching { audioManager.clearCommunicationDevice() }
            claimedCommunicationDevice = false
        }
        if (startedLegacySco) {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
            startedLegacySco = false
        }
    }

    private companion object {
        const val SCO_TIMEOUT_MILLIS = 3000L
    }
}
