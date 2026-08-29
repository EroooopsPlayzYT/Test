package com.eroooops.beamformer.audio

import com.eroooops.beamformer.dsp.MicArray
import com.eroooops.beamformer.dsp.Vec3
import kotlin.math.abs

/**
 * Where the microphone positions used for beamforming came from.
 *
 * Worth showing on screen: a beam computed from real capsule coordinates and one
 * computed from a guessed 14 cm spacing behave differently, and if the bearings
 * look wrong the first question is which of the two is in play.
 */
enum class GeometrySource {
    /** Read from `AudioRecord.getActiveMicrophones()`. */
    REPORTED_BY_DEVICE,

    /** The device reported nothing usable; spacing is the user's setting. */
    ASSUMED,

    /** Demo mode's idealised array. */
    SIMULATED,
}

data class ArrayGeometry(
    val array: MicArray,
    val origin: GeometrySource,
    val spacingMetres: Double,
    val note: String,
)

/**
 * Turns whatever the platform disclosed into an array geometry, falling back to
 * an assumed spacing.
 *
 * Android reports microphone positions in metres from the bottom-left-back
 * corner of the device, with x across the width, y along the length and z
 * through the depth. The beamformer wants its principal axis on x, and for a
 * phone the useful baseline is almost always the long axis — bottom capsule by
 * the charging port, top capsule by the earpiece — so the two are swapped here.
 */
object MicGeometry {

    /** Smallest believable spacing; anything under this is a misreport, not an array. */
    private const val MIN_PLAUSIBLE_SPACING = 0.01

    /** Longer than any phone. */
    private const val MAX_PLAUSIBLE_SPACING = 0.40

    fun fromReport(
        microphones: List<MicDescription>,
        channels: Int,
        assumedSpacingMetres: Double,
    ): ArrayGeometry {
        val positioned = microphones.filter { it.positionKnown }
        if (positioned.size >= channels && channels >= 2) {
            val positions = positioned.take(channels).map {
                // Android's y (along the phone's length) becomes our x.
                Vec3(x = it.y, y = it.x, z = it.z)
            }
            val candidate = MicArray.centred(positions, source = "device")
            val spacing = candidate.maxSpacing
            if (spacing in MIN_PLAUSIBLE_SPACING..MAX_PLAUSIBLE_SPACING) {
                return ArrayGeometry(
                    array = candidate,
                    origin = GeometrySource.REPORTED_BY_DEVICE,
                    spacingMetres = spacing,
                    note = "positions reported by the device, %.0f mm apart".format(spacing * 1000),
                )
            }
            return ArrayGeometry(
                array = MicArray.linearPair(assumedSpacingMetres, source = "assumed"),
                origin = GeometrySource.ASSUMED,
                spacingMetres = assumedSpacingMetres,
                note = "device reported an implausible %.0f mm spacing; using the setting instead"
                    .format(spacing * 1000),
            )
        }

        if (channels < 2) {
            return single("one channel only, so there is no array and no direction to find")
        }

        return ArrayGeometry(
            array = MicArray.linearPair(assumedSpacingMetres, source = "assumed"),
            origin = GeometrySource.ASSUMED,
            spacingMetres = assumedSpacingMetres,
            note = "device did not report microphone positions; using the assumed spacing",
        )
    }

    /**
     * A degenerate one-microphone geometry. [BeamProcessor] on a single channel
     * is well-defined — both modes reduce to passing the channel through
     * unchanged (proved by `a one microphone array has no directivity` in the
     * core test suite) — so this is what keeps a mono source, notably the
     * Bluetooth reference recording, from being run through weights sized for
     * an array it does not have.
     */
    fun single(note: String): ArrayGeometry = ArrayGeometry(
        array = MicArray(listOf(Vec3(0.0, 0.0, 0.0)), source = "single"),
        origin = GeometrySource.ASSUMED,
        spacingMetres = 0.0,
        note = note,
    )

    fun simulated(spacingMetres: Double): ArrayGeometry = ArrayGeometry(
        array = MicArray.linearPair(spacingMetres, source = "simulated"),
        origin = GeometrySource.SIMULATED,
        spacingMetres = spacingMetres,
        note = "idealised array; no microphone is involved",
    )

    /** True when the two spacings would produce visibly different bearings. */
    fun differsMaterially(a: Double, b: Double): Boolean = abs(a - b) > 0.005
}
