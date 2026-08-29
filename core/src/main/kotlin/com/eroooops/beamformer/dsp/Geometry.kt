package com.eroooops.beamformer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Speed of sound in air at roughly 20 degrees C, in metres per second. */
const val SPEED_OF_SOUND = 343.0

data class Vec3(val x: Double, val y: Double, val z: Double = 0.0) {
    infix fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z
    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun div(k: Double): Vec3 = Vec3(x / k, y / k, z / k)
    val length: Double get() = sqrt(this dot this)
}

/**
 * Microphone positions, in metres, in a frame fixed to the phone:
 * +x toward the top edge, +y toward the right edge, +z out of the screen.
 *
 * ## Sign convention
 *
 * A bearing [azimuthDeg] is the direction the sound comes *from*, measured in
 * the xy plane anticlockwise from +x. The unit vector `u` points from the array
 * toward the source, so a microphone displaced along `u` is nearer the source
 * and hears the wavefront *earlier*:
 *
 *     x_m(t) = s(t - tau_m),  tau_m = -(p_m . u) / c
 *
 * Every other file depends on that one line, so it is stated here once and
 * tested in `SteeringTest`.
 *
 * ## What a two-microphone array can and cannot tell you
 *
 * Two microphones measure exactly one number about direction: the arrival-time
 * difference along the line joining them. Every direction making the same angle
 * with that line produces the same number, so the answer is a *cone* around the
 * array axis, not a point — and a source in front of the phone is
 * indistinguishable from the mirror-image source behind it. [azimuthDeg] is
 * therefore reported over 0..180 only, and read as "angle from the array axis".
 */
data class MicArray(
    val mics: List<Vec3>,
    val speedOfSound: Double = SPEED_OF_SOUND,
    /** Where the positions came from, for display. */
    val source: String = "manual",
) {
    init {
        require(mics.size >= 1) { "an array needs at least one microphone" }
    }

    val channels: Int get() = mics.size

    /** Largest distance between any two microphones, in metres. */
    val maxSpacing: Double = run {
        var largest = 0.0
        for (i in mics.indices) {
            for (j in i + 1 until mics.size) {
                largest = maxOf(largest, (mics[i] - mics[j]).length)
            }
        }
        largest
    }

    /**
     * Above `c / 2d` the phase difference between the outermost pair exceeds
     * half a cycle and wraps, so two different directions produce identical
     * measurements. Direction finding is restricted to below this; the number is
     * shown on screen because it explains the array's whole character.
     */
    val aliasFrequency: Double
        get() = if (maxSpacing > 0.0) speedOfSound / (2.0 * maxSpacing) else Double.POSITIVE_INFINITY

    /** Unit vector toward a source at [azimuthDeg], in the xy plane. */
    fun direction(azimuthDeg: Double): Vec3 {
        val rad = azimuthDeg * PI / 180.0
        return Vec3(cos(rad), sin(rad), 0.0)
    }

    /** Per-microphone arrival delays in seconds for a source at [azimuthDeg]. */
    fun delays(azimuthDeg: Double): DoubleArray = delaysToward(direction(azimuthDeg))

    fun delaysToward(u: Vec3): DoubleArray =
        DoubleArray(mics.size) { -(mics[it] dot u) / speedOfSound }

    /** Distance between two microphones, in metres. */
    fun spacing(i: Int, j: Int): Double = (mics[i] - mics[j]).length

    companion object {
        /**
         * Two microphones [spacingMetres] apart, centred on the origin and lying
         * along the phone's long axis — the layout of essentially every phone
         * that exposes two channels, with one capsule by the charging port and
         * one at the top edge.
         */
        fun linearPair(spacingMetres: Double, source: String = "manual"): MicArray {
            val half = spacingMetres / 2.0
            return MicArray(
                mics = listOf(Vec3(-half, 0.0, 0.0), Vec3(half, 0.0, 0.0)),
                source = source,
            )
        }

        /** Recentres arbitrary positions on their centroid. */
        fun centred(positions: List<Vec3>, source: String = "manual"): MicArray {
            var sum = Vec3(0.0, 0.0, 0.0)
            positions.forEach { sum += it }
            val centre = sum / positions.size.toDouble()
            return MicArray(positions.map { it - centre }, source = source)
        }
    }
}

/** Distance in the xy plane, used when reducing reported 3D positions to a bearing. */
internal fun Vec3.planarLength(): Double = hypot(x, y)

/** Angle of the xy component, in degrees, in 0..360. */
internal fun Vec3.planarAngleDeg(): Double {
    val deg = Math.toDegrees(kotlin.math.atan2(y, x))
    return if (deg < 0) deg + 360.0 else deg
}

/** Small helper used by the simulator and the tests. */
internal fun unitVector(azimuthDeg: Double): Vec3 {
    val rad = azimuthDeg * PI / 180.0
    return Vec3(cos(rad), sin(rad), 0.0)
}
