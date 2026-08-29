package com.eroooops.beamformer.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

enum class BeamformerType {
    /**
     * Align the channels on the look direction and average. Robust, and on a
     * phone-sized array almost useless below about a kilohertz: two capsules
     * 14 cm apart see nearly the same pressure at 300 Hz whatever direction the
     * sound came from, so the sum barely discriminates. Kept because it is the
     * honest baseline the other mode is measured against.
     */
    DELAY_SUM,

    /**
     * Minimum-variance weights against a diffuse noise field. This is what makes
     * a small array actually directional at low frequency: instead of averaging,
     * it subtracts a scaled version of one channel from the other, which is a
     * high-pass-shaped operation with real directivity — and correspondingly
     * amplifies uncorrelated microphone self-noise. That trade is what
     * [minWhiteNoiseGainDb] governs.
     */
    SUPERDIRECTIVE,
}

/** Per-bin complex weights, plus the figures of merit that came out of the design. */
class BeamWeights(val bins: Int, val channels: Int) {
    val re = DoubleArray(bins * channels)
    val im = DoubleArray(bins * channels)

    /**
     * Gain applied to microphone self-noise, per bin, in dB. Negative means the
     * beamformer is amplifying hiss; -10 dB means ten times the noise power of a
     * single capsule. This is the cost side of superdirectivity.
     */
    val whiteNoiseGainDb = DoubleArray(bins)

    /**
     * Directivity index per bin, in dB: how much diffuse ambient sound is
     * suppressed relative to the wanted direction. This is the benefit side, and
     * unlike the on-screen beam-vs-omni meter it is a property of the weights
     * alone, not of whatever happened to be making noise at the time.
     */
    val directivityIndexDb = DoubleArray(bins)

    fun setBin(bin: Int, channel: Int, real: Double, imag: Double) {
        re[bin * channels + channel] = real
        im[bin * channels + channel] = imag
    }
}

/**
 * Turns an array geometry and a look direction into per-bin weights.
 *
 * Design runs once when the steering angle or mode changes, not per audio
 * frame: a few hundred tiny linear solves is nothing once, and far too much
 * every 10 ms.
 */
class BeamDesigner(
    private val array: MicArray,
    private val frameSize: Int,
    private val sampleRate: Int,
) {
    private val bins = frameSize / 2 + 1
    private val channels = array.channels

    fun design(
        type: BeamformerType,
        azimuthDeg: Double,
        minWhiteNoiseGainDb: Double = -10.0,
    ): BeamWeights {
        val weights = BeamWeights(bins, channels)
        val delays = array.delays(azimuthDeg)

        val aRe = DoubleArray(channels)
        val aIm = DoubleArray(channels)
        val coherence = DoubleArray(channels * channels)

        for (bin in 0 until bins) {
            val freq = bin.toDouble() * sampleRate / frameSize

            // Steering vector: a_m = exp(-j 2 pi f tau_m), so that a plane wave
            // from the look direction has X_m = S * a_m.
            for (m in 0 until channels) {
                val phase = -2.0 * PI * freq * delays[m]
                aRe[m] = cos(phase)
                aIm[m] = sin(phase)
            }

            when (type) {
                BeamformerType.DELAY_SUM -> setDelaySum(weights, bin, aRe, aIm)
                BeamformerType.SUPERDIRECTIVE -> {
                    diffuseCoherence(freq, coherence)
                    setSuperdirective(weights, bin, aRe, aIm, coherence, minWhiteNoiseGainDb)
                }
            }

            recordMetrics(weights, bin, aRe, aIm, freq, coherence, type)
        }
        return weights
    }

    private fun setDelaySum(weights: BeamWeights, bin: Int, aRe: DoubleArray, aIm: DoubleArray) {
        val scale = 1.0 / channels
        for (m in 0 until channels) {
            weights.setBin(bin, m, aRe[m] * scale, aIm[m] * scale)
        }
    }

    /**
     * Coherence of a diffuse (spherically isotropic) sound field between each
     * pair of capsules: `sin(kd) / kd`. At low frequency it tends to one — the
     * two channels see the same ambient noise — which is exactly the structure
     * the minimum-variance solution exploits to cancel it.
     */
    private fun diffuseCoherence(freq: Double, out: DoubleArray) {
        val k = 2.0 * PI * freq / array.speedOfSound
        for (i in 0 until channels) {
            out[i * channels + i] = 1.0
            for (j in i + 1 until channels) {
                val kd = k * array.spacing(i, j)
                val value = if (abs(kd) < 1e-9) 1.0 else sin(kd) / kd
                out[i * channels + j] = value
                out[j * channels + i] = value
            }
        }
    }

    /**
     * `w = G^-1 a / (a^H G^-1 a)` with `G` the diffuse coherence plus diagonal
     * loading.
     *
     * The loading is not a fixed fudge factor. Left alone, the solution near DC
     * has enormous weights and turns microphone hiss into a roar, and picking a
     * constant that avoids that at 100 Hz throws away all the directivity at
     * 800 Hz. Instead the loading is searched per bin for the smallest value
     * meeting the requested white-noise-gain floor, which is the quantity the
     * user actually cares about and the one shown on screen.
     */
    private fun setSuperdirective(
        weights: BeamWeights,
        bin: Int,
        aRe: DoubleArray,
        aIm: DoubleArray,
        coherence: DoubleArray,
        minWhiteNoiseGainDb: Double,
    ) {
        var low = 1e-9
        var high = 1e4
        var best: Pair<DoubleArray, DoubleArray>? = null

        // As loading grows the solution converges on delay-and-sum, whose
        // white-noise gain is 10*log10(M) — so a floor below that always has a
        // solution and the search cannot run away.
        repeat(48) {
            val mid = Math.sqrt(low * high)
            val solved = solveWeights(aRe, aIm, coherence, mid)
            if (solved == null) {
                low = mid
                return@repeat
            }
            val wng = whiteNoiseGainDb(solved.first, solved.second)
            if (wng >= minWhiteNoiseGainDb) {
                // Meets the floor: try less loading for more directivity.
                best = solved
                high = mid
            } else {
                low = mid
            }
        }

        val chosen = best
            ?: solveWeights(aRe, aIm, coherence, 1e4)
            ?: return setDelaySum(weights, bin, aRe, aIm)

        for (m in 0 until channels) {
            weights.setBin(bin, m, chosen.first[m], chosen.second[m])
        }
    }

    /**
     * Solves `(G + load*I) v = a` and normalises so `w^H a = 1`.
     *
     * `G` is real, so the complex system splits into two real solves sharing one
     * factorisation, rather than needing complex arithmetic throughout.
     */
    private fun solveWeights(
        aRe: DoubleArray,
        aIm: DoubleArray,
        coherence: DoubleArray,
        load: Double,
    ): Pair<DoubleArray, DoubleArray>? {
        val matrix = DoubleArray(channels * channels)
        for (i in 0 until channels * channels) matrix[i] = coherence[i]
        for (i in 0 until channels) matrix[i * channels + i] += load

        val vRe = aRe.copyOf()
        val vIm = aIm.copyOf()
        if (!solveInPlace(matrix, vRe, vIm, channels)) return null

        // a^H v is real and positive for a positive-definite G; take the real
        // part and reject the degenerate case rather than dividing by noise.
        var denom = 0.0
        for (m in 0 until channels) {
            denom += aRe[m] * vRe[m] + aIm[m] * vIm[m]
        }
        if (denom <= 1e-12 || !denom.isFinite()) return null

        for (m in 0 until channels) {
            vRe[m] /= denom
            vIm[m] /= denom
        }
        return vRe to vIm
    }

    private fun whiteNoiseGainDb(wRe: DoubleArray, wIm: DoubleArray): Double {
        var power = 0.0
        for (m in wRe.indices) power += wRe[m] * wRe[m] + wIm[m] * wIm[m]
        if (power <= 0.0 || !power.isFinite()) return Double.NEGATIVE_INFINITY
        return -10.0 * log10(power)
    }

    private fun recordMetrics(
        weights: BeamWeights,
        bin: Int,
        aRe: DoubleArray,
        aIm: DoubleArray,
        freq: Double,
        coherenceScratch: DoubleArray,
        type: BeamformerType,
    ) {
        val wRe = DoubleArray(channels) { weights.re[bin * channels + it] }
        val wIm = DoubleArray(channels) { weights.im[bin * channels + it] }
        weights.whiteNoiseGainDb[bin] = whiteNoiseGainDb(wRe, wIm)

        if (type == BeamformerType.DELAY_SUM) diffuseCoherence(freq, coherenceScratch)

        // w^H G w, the diffuse-noise power at the output for unit look-direction
        // gain. Its reciprocal in dB is the directivity index.
        var diffuse = 0.0
        for (i in 0 until channels) {
            for (j in 0 until channels) {
                val g = coherenceScratch[i * channels + j]
                diffuse += g * (wRe[i] * wRe[j] + wIm[i] * wIm[j])
            }
        }
        weights.directivityIndexDb[bin] =
            if (diffuse > 0.0 && diffuse.isFinite()) -10.0 * log10(diffuse) else 0.0
    }
}

/**
 * Gaussian elimination with partial pivoting on a real matrix with two
 * right-hand sides. Returns false if the matrix is singular to working
 * precision, which the caller treats as "this loading is too small".
 *
 * Sizes here are 2x2 to 4x4, so the naive algorithm is the right one.
 */
internal fun solveInPlace(
    matrix: DoubleArray,
    rhsRe: DoubleArray,
    rhsIm: DoubleArray,
    n: Int,
): Boolean {
    for (col in 0 until n) {
        var pivot = col
        var best = abs(matrix[col * n + col])
        for (row in col + 1 until n) {
            val candidate = abs(matrix[row * n + col])
            if (candidate > best) {
                best = candidate
                pivot = row
            }
        }
        if (best < 1e-14) return false

        if (pivot != col) {
            for (k in 0 until n) {
                val t = matrix[col * n + k]
                matrix[col * n + k] = matrix[pivot * n + k]
                matrix[pivot * n + k] = t
            }
            var t = rhsRe[col]; rhsRe[col] = rhsRe[pivot]; rhsRe[pivot] = t
            t = rhsIm[col]; rhsIm[col] = rhsIm[pivot]; rhsIm[pivot] = t
        }

        val diag = matrix[col * n + col]
        for (row in col + 1 until n) {
            val factor = matrix[row * n + col] / diag
            if (factor == 0.0) continue
            for (k in col until n) {
                matrix[row * n + k] -= factor * matrix[col * n + k]
            }
            rhsRe[row] -= factor * rhsRe[col]
            rhsIm[row] -= factor * rhsIm[col]
        }
    }

    for (row in n - 1 downTo 0) {
        var sumRe = rhsRe[row]
        var sumIm = rhsIm[row]
        for (k in row + 1 until n) {
            sumRe -= matrix[row * n + k] * rhsRe[k]
            sumIm -= matrix[row * n + k] * rhsIm[k]
        }
        val diag = matrix[row * n + row]
        rhsRe[row] = sumRe / diag
        rhsIm[row] = sumIm / diag
    }
    return true
}
