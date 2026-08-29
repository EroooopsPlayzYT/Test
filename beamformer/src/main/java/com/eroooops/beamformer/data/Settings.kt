package com.eroooops.beamformer.data

import android.content.Context
import com.eroooops.beamformer.dsp.BeamformerType

data class BeamSettings(
    /**
     * Assumed distance between the two capsules, used when the device will not
     * disclose its real microphone positions. The default is roughly the long
     * axis of a large phone.
     */
    val spacingMetres: Double = 0.14,
    val beamformerType: BeamformerType = BeamformerType.SUPERDIRECTIVE,
    val steerDeg: Double = 90.0,
    /**
     * How much microphone hiss the superdirective weights may amplify, in dB.
     * More negative buys sharper low-frequency directivity at the cost of noise.
     */
    val minWhiteNoiseGainDb: Double = -10.0,
    val monitorGain: Double = 1.0,
    val allowSpeakerMonitor: Boolean = false,
    val followDirection: Boolean = false,
) {
    companion object {
        val SPACING_RANGE = 0.02..0.30
        val WNG_RANGE = -20.0..0.0
        val GAIN_RANGE = 0.0..4.0
    }
}

private const val PREFS = "beamformer"

/** Persists the beam settings. Small enough that shared preferences is the right tool. */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): BeamSettings = BeamSettings(
        spacingMetres = prefs.getFloat("spacing", 0.14f).toDouble()
            .coerceIn(BeamSettings.SPACING_RANGE),
        beamformerType = runCatching {
            BeamformerType.valueOf(
                prefs.getString("type", BeamformerType.SUPERDIRECTIVE.name)!!
            )
        }.getOrDefault(BeamformerType.SUPERDIRECTIVE),
        steerDeg = prefs.getFloat("steer", 90f).toDouble().coerceIn(0.0, 180.0),
        minWhiteNoiseGainDb = prefs.getFloat("wng", -10f).toDouble()
            .coerceIn(BeamSettings.WNG_RANGE),
        monitorGain = prefs.getFloat("gain", 1f).toDouble().coerceIn(BeamSettings.GAIN_RANGE),
        // Deliberately not persisted as enabled by default: acknowledging the
        // feedback risk once should not silently re-arm the loudspeaker on every
        // future launch.
        allowSpeakerMonitor = false,
        followDirection = prefs.getBoolean("follow", false),
    )

    fun save(settings: BeamSettings) {
        prefs.edit()
            .putFloat("spacing", settings.spacingMetres.toFloat())
            .putString("type", settings.beamformerType.name)
            .putFloat("steer", settings.steerDeg.toFloat())
            .putFloat("wng", settings.minWhiteNoiseGainDb.toFloat())
            .putFloat("gain", settings.monitorGain.toFloat())
            .putBoolean("follow", settings.followDirection)
            .apply()
    }
}
