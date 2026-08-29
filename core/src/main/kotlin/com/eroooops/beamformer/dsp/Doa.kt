package com.eroooops.beamformer.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How far the phase transform is backed off from pure whitening.
 *
 * Textbook PHAT divides every bin by its own magnitude, which weights a bin
 * containing nothing but spectral leakage exactly as heavily as one containing
 * the source. In a quiet room, or with any signal that does not fill the
 * spectrum, the empty bins outnumber the useful ones and the estimate is
 * dominated by noise — a needle that swings confidently around an empty room.
 *
 * Dividing by `|X| + eps * |X|max` instead leaves strong bins effectively
 * whitened, which is the property worth having, while bins far below the
 * frame's peak are attenuated rather than amplified to full weight.
 */
private const val PHAT_REGULARISATION = 0.02

/** A bearing estimate and how much to believe it. */
data class DoaEstimate(
    /** Angle from the array axis, degrees, 0..180. See [MicArray] on why it is a cone. */
    val azimuthDeg: Double,
    /**
     * Peak sharpness of the scan surface, 0..1. Near zero the surface is flat —
     * silence, diffuse noise, or a source the array cannot resolve — and the
     * needle should not be believed. The UI fades it out rather than pretending.
     */
    val confidence: Double,
)

/**
 * Steered-response power with phase transform (SRP-PHAT).
 *
 * For every candidate bearing, this asks "if the sound had come from here, the
 * channels would line up with these delays — how well do they actually line
 * up?". The phase transform throws away magnitude and keeps only phase, which
 * is what makes it work in a reverberant room: a loud reflection contributes no
 * more than a quiet direct path.
 *
 * ## The band limit is not a detail
 *
 * Only frequencies below `c / 2d` are used. Above that the phase difference
 * between the capsules exceeds half a cycle, wraps, and becomes indistinguishable
 * from a different direction entirely — so including those bins does not add
 * detail, it adds confident phantom peaks. On a phone-sized array this throws
 * away everything above roughly 1.2 kHz, which is a real limitation of two
 * microphones 14 cm apart and is reported on screen as such.
 */
class DoaScanner(
    private val array: MicArray,
    private val sampleRate: Int,
    val frameSize: Int = 2048,
    angleStepDeg: Double = 2.5,
    lowHz: Double = 300.0,
    highHz: Double = Double.NaN,
) {
    private val channels = array.channels

    /** Every microphone pair; a two-microphone array has exactly one. */
    private val pairs: List<Pair<Int, Int>> = buildList {
        for (i in 0 until channels) {
            for (j in i + 1 until channels) add(i to j)
        }
    }

    val angles: DoubleArray = run {
        val count = (180.0 / angleStepDeg).toInt() + 1
        DoubleArray(count) { it * angleStepDeg }
    }

    val bandLowHz: Double = lowHz
    val bandHighHz: Double = run {
        val ceiling = if (highHz.isNaN()) array.aliasFrequency else highHz
        min(ceiling, sampleRate / 2.0)
    }

    private val lowBin = max(1, Math.ceil(bandLowHz * frameSize / sampleRate).toInt())
    private val highBin = min(frameSize / 2, Math.floor(bandHighHz * frameSize / sampleRate).toInt())
    private val bandBins = max(0, highBin - lowBin + 1)

    /** True when the geometry leaves no usable band — the scan cannot mean anything. */
    val usable: Boolean get() = bandBins >= 3 && pairs.isNotEmpty()

    // Steering phasors for every (pair, angle, bin). Built once; the scan is
    // then a dot product, which is what lets this run per display frame.
    private val phaseRe = DoubleArray(pairs.size * angles.size * bandBins)
    private val phaseIm = DoubleArray(pairs.size * angles.size * bandBins)

    private val fft = Fft(frameSize)
    private val window = DoubleArray(frameSize) { 0.5 - 0.5 * cos(2.0 * PI * it / frameSize) }
    private val specRe = Array(channels) { DoubleArray(frameSize) }
    private val specIm = Array(channels) { DoubleArray(frameSize) }
    private val crossRe = DoubleArray(pairs.size * bandBins)
    private val crossIm = DoubleArray(pairs.size * bandBins)

    init {
        if (usable) {
            for ((p, pair) in pairs.withIndex()) {
                val offset = array.mics[pair.first] - array.mics[pair.second]
                for (a in angles.indices) {
                    val u = array.direction(angles[a])
                    // Predicted arrival-time difference for this bearing.
                    val tau = -(offset dot u) / array.speedOfSound
                    for (b in 0 until bandBins) {
                        val freq = (lowBin + b).toDouble() * sampleRate / frameSize
                        val phi = 2.0 * PI * freq * tau
                        val index = (p * angles.size + a) * bandBins + b
                        phaseRe[index] = cos(phi)
                        phaseIm[index] = sin(phi)
                    }
                }
            }
        }
    }

    /**
     * Scans [frameSize] samples starting at [offset].
     *
     * [surface] receives the normalised response per bearing (0..1) for the
     * polar display; it must be [angles].size long.
     */
    fun scan(input: Array<DoubleArray>, offset: Int, surface: DoubleArray): DoaEstimate {
        require(surface.size == angles.size) { "surface must be ${angles.size} long" }
        if (!usable) {
            surface.fill(0.0)
            return DoaEstimate(90.0, 0.0)
        }

        for (c in 0 until channels) {
            val re = specRe[c]
            val im = specIm[c]
            for (n in 0 until frameSize) {
                re[n] = input[c][offset + n] * window[n]
                im[n] = 0.0
            }
            fft.forward(re, im)
        }

        // Regularised PHAT cross-spectra: phase-dominated, but bins far below
        // the frame's peak are not amplified up to full weight. See
        // [PHAT_REGULARISATION].
        var coherent = 0.0
        for ((p, pair) in pairs.withIndex()) {
            val (i, j) = pair
            var peakMagnitude = 0.0
            for (b in 0 until bandBins) {
                val bin = lowBin + b
                val cr = specRe[i][bin] * specRe[j][bin] + specIm[i][bin] * specIm[j][bin]
                val ci = specIm[i][bin] * specRe[j][bin] - specRe[i][bin] * specIm[j][bin]
                peakMagnitude = max(peakMagnitude, hypot(cr, ci))
            }
            coherent = max(coherent, peakMagnitude)
            val floor = PHAT_REGULARISATION * peakMagnitude
            for (b in 0 until bandBins) {
                val bin = lowBin + b
                val cr = specRe[i][bin] * specRe[j][bin] + specIm[i][bin] * specIm[j][bin]
                val ci = specIm[i][bin] * specRe[j][bin] - specRe[i][bin] * specIm[j][bin]
                val weight = if (peakMagnitude > 0.0) 1.0 / (hypot(cr, ci) + floor) else 0.0
                crossRe[p * bandBins + b] = cr * weight
                crossIm[p * bandBins + b] = ci * weight
            }
        }
        if (coherent <= 0.0) {
            surface.fill(0.0)
            return DoaEstimate(90.0, 0.0)
        }

        var peak = Double.NEGATIVE_INFINITY
        var peakIndex = 0
        var total = 0.0
        for (a in angles.indices) {
            var accumulator = 0.0
            for (p in pairs.indices) {
                val base = (p * angles.size + a) * bandBins
                val crossBase = p * bandBins
                for (b in 0 until bandBins) {
                    accumulator += crossRe[crossBase + b] * phaseRe[base + b] -
                        crossIm[crossBase + b] * phaseIm[base + b]
                }
            }
            surface[a] = accumulator
            total += accumulator
            if (accumulator > peak) {
                peak = accumulator
                peakIndex = a
            }
        }

        val mean = total / angles.size
        val normalisation = pairs.size * bandBins.toDouble()

        // Peak height above the surface mean, in units where 1.0 is a perfectly
        // coherent single source. Reverberation and noise flatten it.
        val confidence = ((peak - mean) / normalisation).coerceIn(0.0, 1.0)

        var minimum = Double.POSITIVE_INFINITY
        for (a in angles.indices) minimum = min(minimum, surface[a])
        val span = peak - minimum
        if (span > 1e-12) {
            for (a in angles.indices) surface[a] = (surface[a] - minimum) / span
        } else {
            surface.fill(0.0)
        }

        return DoaEstimate(refinePeak(surface, peakIndex), confidence)
    }

    /**
     * Parabolic interpolation across the peak and its neighbours. Without it the
     * bearing can only ever be a multiple of the grid step, and the needle
     * visibly snaps between cells.
     */
    private fun refinePeak(surface: DoubleArray, index: Int): Double {
        if (index <= 0 || index >= surface.size - 1) return angles[index]
        val left = surface[index - 1]
        val centre = surface[index]
        val right = surface[index + 1]
        val denominator = left - 2.0 * centre + right
        if (abs(denominator) < 1e-12) return angles[index]
        val shift = 0.5 * (left - right) / denominator
        val step = angles[1] - angles[0]
        return (angles[index] + shift.coerceIn(-1.0, 1.0) * step).coerceIn(0.0, 180.0)
    }
}

/**
 * Arrival-time difference between one pair of microphones, by GCC-PHAT.
 *
 * Separate from [DoaScanner] because it answers a different question — "how many
 * microseconds apart", not "from which bearing" — and because it is the natural
 * thing to assert against in a test where the true delay is known exactly.
 *
 * The cross-spectrum is zero-padded before the inverse transform, which
 * interpolates the correlation onto a finer lag grid. At 48 kHz one raw sample
 * is 21 microseconds, which on a 14 cm array is around 3 degrees near broadside
 * and far worse near the ends; oversampling by [oversample] and then fitting a
 * parabola to the peak brings that down to a fraction of a degree.
 */
class PairDelayEstimator(
    val frameSize: Int,
    val sampleRate: Int,
    val oversample: Int = 8,
    lowHz: Double = 200.0,
    highHz: Double = 6000.0,
) {
    private val fft = Fft(frameSize)
    private val padded = frameSize * oversample
    private val paddedFft = Fft(padded)
    private val window = DoubleArray(frameSize) { 0.5 - 0.5 * cos(2.0 * PI * it / frameSize) }

    private val lowBin = max(1, Math.ceil(lowHz * frameSize / sampleRate).toInt())
    private val highBin = min(frameSize / 2, Math.floor(highHz * frameSize / sampleRate).toInt())

    private val aRe = DoubleArray(frameSize)
    private val aIm = DoubleArray(frameSize)
    private val bRe = DoubleArray(frameSize)
    private val bIm = DoubleArray(frameSize)
    private val corrRe = DoubleArray(padded)
    private val corrIm = DoubleArray(padded)

    /**
     * Returns `tau_ij` in seconds: positive when channel [first] lags channel
     * [second], i.e. when the source is nearer to [second].
     *
     * [maxDelaySeconds] confines the search to physically reachable delays,
     * which is the cheapest and most effective rejection of correlation peaks
     * caused by reflections.
     */
    fun estimate(
        first: DoubleArray,
        second: DoubleArray,
        offset: Int,
        maxDelaySeconds: Double,
    ): Double {
        for (n in 0 until frameSize) {
            aRe[n] = first[offset + n] * window[n]
            aIm[n] = 0.0
            bRe[n] = second[offset + n] * window[n]
            bIm[n] = 0.0
        }
        fft.forward(aRe, aIm)
        fft.forward(bRe, bIm)

        corrRe.fill(0.0)
        corrIm.fill(0.0)

        var peakMagnitude = 0.0
        for (bin in lowBin..highBin) {
            val cr = aRe[bin] * bRe[bin] + aIm[bin] * bIm[bin]
            val ci = aIm[bin] * bRe[bin] - aRe[bin] * bIm[bin]
            peakMagnitude = max(peakMagnitude, hypot(cr, ci))
        }
        if (peakMagnitude <= 0.0) return 0.0
        val floor = PHAT_REGULARISATION * peakMagnitude

        // Place the non-redundant bins at the bottom of the padded buffer and
        // their conjugates at the top, so the padded inverse transform stays
        // real and simply lands on a finer lag grid.
        for (bin in lowBin..highBin) {
            val ar = aRe[bin]
            val ai = aIm[bin]
            val br = bRe[bin]
            val bi = bIm[bin]
            val cr = ar * br + ai * bi
            val ci = ai * br - ar * bi
            val weight = 1.0 / (hypot(cr, ci) + floor)
            corrRe[bin] = cr * weight
            corrIm[bin] = ci * weight
            corrRe[padded - bin] = cr * weight
            corrIm[padded - bin] = -ci * weight
        }
        paddedFft.inverse(corrRe, corrIm)

        val effectiveRate = sampleRate.toDouble() * oversample
        val maxLag = min((maxDelaySeconds * effectiveRate).toInt() + 2, padded / 2 - 2)

        var bestValue = Double.NEGATIVE_INFINITY
        var bestLag = 0
        for (lag in -maxLag..maxLag) {
            val index = if (lag >= 0) lag else padded + lag
            val value = corrRe[index]
            if (value > bestValue) {
                bestValue = value
                bestLag = lag
            }
        }

        fun at(lag: Int): Double = corrRe[if (lag >= 0) lag else padded + lag]
        val left = at(bestLag - 1)
        val right = at(bestLag + 1)
        val denominator = left - 2.0 * bestValue + right
        val shift = if (abs(denominator) < 1e-15) 0.0 else {
            (0.5 * (left - right) / denominator).coerceIn(-1.0, 1.0)
        }
        return (bestLag + shift) / effectiveRate
    }
}

/**
 * Converts a pair arrival-time difference to an angle from the array axis.
 *
 * Starting from `tau_ij = -((p_i - p_j) . u) / c` and writing `e` for the unit
 * vector pointing from microphone [i] toward microphone [j], this rearranges to
 *
 *     cos(theta) = c * tau_ij / |p_j - p_i|
 *
 * where `theta` is the angle between `e` and the source direction. For the
 * standard [MicArray.linearPair] layout `e` is +x, so the result is the azimuth
 * directly. Delays larger than `|d|/c` cannot occur physically — noise, or a
 * reflection — and are clamped rather than returning NaN from `acos`.
 */
fun bearingFromDelay(array: MicArray, i: Int, j: Int, tauSeconds: Double): Double {
    val axis = array.mics[j] - array.mics[i]
    val axisLength = axis.length
    if (axisLength <= 0.0) return 90.0
    val cosine = (array.speedOfSound * tauSeconds / axisLength).coerceIn(-1.0, 1.0)
    return Math.toDegrees(kotlin.math.acos(cosine)).coerceIn(0.0, 180.0)
}

/** Root-mean-square of a span, used for level meters and silence gating. */
fun rms(samples: DoubleArray, offset: Int = 0, count: Int = samples.size - offset): Double {
    if (count <= 0) return 0.0
    var sum = 0.0
    for (n in offset until offset + count) sum += samples[n] * samples[n]
    return sqrt(sum / count)
}
