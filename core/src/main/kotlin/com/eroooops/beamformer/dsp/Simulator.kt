package com.eroooops.beamformer.dsp

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A synthetic source, defined as a function of continuous time.
 *
 * Building sources as a sum of sinusoids rather than as a sampled buffer is a
 * deliberate choice: a plane wave reaching two microphones needs the *same*
 * signal evaluated at two instants a fraction of a sample apart, and a closed
 * form gives that exactly. Resampling a buffer would introduce interpolation
 * error into the very quantity — sub-sample arrival-time difference — that the
 * tests are trying to measure, so a passing test would no longer mean the
 * beamformer was right.
 */
class SyntheticSource(
    val azimuthDeg: Double,
    lowHz: Double = 300.0,
    highHz: Double = 3000.0,
    // Enough partials to fill the spectrum rather than leave most bins empty.
    // Real sound is broadband; a handful of tones is not a fair stand-in, and a
    // direction finder tuned against a sparse signal would be tuned against the
    // wrong problem.
    components: Int = 256,
    seed: Int = 1,
    val amplitude: Double = 1.0,
) {
    private val random = Random(seed)
    private val frequencies = DoubleArray(components) {
        lowHz * (highHz / lowHz).pow(random.nextDouble())
    }
    private val phases = DoubleArray(components) { random.nextDouble() * 2.0 * PI }
    private val gains = DoubleArray(components) { 1.0 }

    init {
        // Normalise so the source has unit RMS regardless of how many partials
        // it was built from; otherwise "10 dB SNR" would mean different things
        // in different tests.
        val power = gains.sumOf { it * it } / 2.0
        val norm = 1.0 / kotlin.math.sqrt(power)
        for (i in gains.indices) gains[i] *= norm
    }

    fun at(timeSeconds: Double): Double {
        var sum = 0.0
        for (i in frequencies.indices) {
            sum += gains[i] * sin(2.0 * PI * frequencies[i] * timeSeconds + phases[i])
        }
        return amplitude * sum
    }
}

/**
 * Renders what a microphone array would have picked up.
 *
 * Used for two things: the unit tests, where the true bearing is known and the
 * estimate can be checked against it; and the app's demo mode on devices that
 * refuse to hand out more than one real channel. Both go through the same code,
 * so the demo exercises the actual beamformer rather than a mock of it.
 */
class ArraySimulator(
    private val array: MicArray,
    private val sampleRate: Int,
    private val seed: Int = 7,
) {
    var sources: List<SyntheticSource> = emptyList()

    /**
     * Independent noise added per channel, as an amplitude relative to a unit
     * source. This models microphone self-noise — uncorrelated between capsules
     * — which is the noise superdirective weights amplify and therefore the
     * right thing to test robustness against.
     */
    var noiseAmplitude: Double = 0.0

    private val random = Random(seed)
    private var sampleIndex = 0L

    fun reset() {
        sampleIndex = 0L
    }

    /** Advances all sources by moving them [degreesPerSecond] around the array. */
    var rotationDegreesPerSecond: Double = 0.0

    fun render(out: Array<DoubleArray>, offset: Int, count: Int) {
        require(out.size == array.channels) { "expected ${array.channels} channels" }
        for (n in 0 until count) {
            val t = (sampleIndex + n).toDouble() / sampleRate
            for (c in 0 until array.channels) out[c][offset + n] = 0.0

            for (source in sources) {
                val azimuth = wrapBearing(source.azimuthDeg + rotationDegreesPerSecond * t)
                val delays = array.delays(azimuth)
                for (c in 0 until array.channels) {
                    out[c][offset + n] += source.at(t - delays[c])
                }
            }

            if (noiseAmplitude > 0.0) {
                for (c in 0 until array.channels) {
                    out[c][offset + n] += noiseAmplitude * gaussian()
                }
            }
        }
        sampleIndex += count
    }

    private fun gaussian(): Double {
        // Box-Muller; one of the pair is discarded, which costs nothing here.
        val u1 = (random.nextDouble() + 1e-12).coerceAtMost(1.0)
        val u2 = random.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * PI * u2)
    }
}

/**
 * Folds a bearing into 0..180.
 *
 * A two-microphone array genuinely cannot tell 200 degrees from 160, so a
 * simulated source that rotates past the axis should reappear travelling back
 * the way it came — which is also exactly what the real display does.
 */
fun wrapBearing(degrees: Double): Double {
    var value = degrees % 360.0
    if (value < 0) value += 360.0
    return if (value <= 180.0) value else 360.0 - value
}
