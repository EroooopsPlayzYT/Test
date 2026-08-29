package com.eroooops.beamformer.dsp

import kotlin.math.PI
import kotlin.math.sin

/**
 * Windowed analysis/synthesis with overlap-add, at 50% overlap.
 *
 * The window is the *sine* window, `sin(pi (n + 0.5) / N)`, applied on both
 * analysis and synthesis. That choice is not cosmetic: applying a window twice
 * squares it, and at 50% overlap the squared sine window sums to exactly one
 * (`sin^2 + cos^2`), so a frame that is analysed and resynthesised without
 * modification reproduces the input sample for sample. A Hann window — the
 * obvious first choice — does *not* have that property at this hop, and leaves
 * a periodic amplitude ripple that sounds like a faint buzz at the frame rate.
 *
 * [ReconstructionTest] pins this down.
 */
class Stft(val frameSize: Int, val hop: Int = frameSize / 2) {

    init {
        require(frameSize > 1 && frameSize and (frameSize - 1) == 0) {
            "frame size must be a power of two, was $frameSize"
        }
        require(hop > 0 && hop <= frameSize) { "hop out of range: $hop" }
    }

    val window: DoubleArray = DoubleArray(frameSize) { sin(PI * (it + 0.5) / frameSize) }

    /** Number of non-redundant bins for a real input, DC through Nyquist. */
    val bins: Int get() = frameSize / 2 + 1

    private val fft = Fft(frameSize)

    /**
     * Windows `frameSize` samples starting at [offset] and transforms them into
     * [re]/[im], which are overwritten.
     */
    fun analyze(source: DoubleArray, offset: Int, re: DoubleArray, im: DoubleArray) {
        for (n in 0 until frameSize) {
            re[n] = source[offset + n] * window[n]
            im[n] = 0.0
        }
        fft.forward(re, im)
    }

    /**
     * Inverse-transforms [re]/[im], applies the synthesis window and adds the
     * result into [target] at [offset]. [re] and [im] are destroyed.
     */
    fun synthesize(re: DoubleArray, im: DoubleArray, target: DoubleArray, offset: Int) {
        fft.inverse(re, im)
        for (n in 0 until frameSize) {
            target[offset + n] += re[n] * window[n]
        }
    }

    /**
     * Rebuilds the full spectrum from the non-redundant half.
     *
     * Beamforming weights are only computed for bins 0..N/2; the upper half of
     * the spectrum of a real signal is the mirrored conjugate. Filling it in
     * before the inverse transform is what keeps the output real.
     */
    fun mirror(re: DoubleArray, im: DoubleArray) {
        for (k in 1 until frameSize / 2) {
            re[frameSize - k] = re[k]
            im[frameSize - k] = -im[k]
        }
        im[0] = 0.0
        im[frameSize / 2] = 0.0
    }
}
