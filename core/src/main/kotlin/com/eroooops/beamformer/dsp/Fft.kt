package com.eroooops.beamformer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place radix-2 complex FFT.
 *
 * Real and imaginary parts are kept in separate [DoubleArray]s rather than in a
 * `Complex` class: this runs on every frame of a live audio stream, and a
 * per-bin object allocation there is a per-frame trip to the garbage collector.
 * The twiddle tables and bit-reversal permutation are built once per size.
 *
 * One instance is not safe to share across threads; the capture thread and any
 * analysis thread each hold their own.
 */
class Fft(val size: Int) {

    init {
        require(size > 1 && size and (size - 1) == 0) {
            "FFT size must be a power of two, was $size"
        }
    }

    /** Twiddles for the forward transform (negative exponent). */
    private val cosTable = DoubleArray(size / 2) { cos(-2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(-2.0 * PI * it / size) }

    private val reversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            table[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    /** Forward transform, unscaled. [re] and [im] are overwritten. */
    fun forward(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size) { "buffers must be $size long" }
        permute(re, im)
        transform(re, im)
    }

    /**
     * Inverse transform, scaled by 1/N so `inverse(forward(x)) == x`.
     *
     * Implemented as a conjugated forward transform, which is why there is only
     * one set of twiddle tables.
     */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size) { "buffers must be $size long" }
        for (i in 0 until size) im[i] = -im[i]
        permute(re, im)
        transform(re, im)
        val scale = 1.0 / size
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= -scale
        }
    }

    private fun permute(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
    }

    private fun transform(re: DoubleArray, im: DoubleArray) {
        var span = 2
        while (span <= size) {
            val half = span / 2
            val step = size / span
            var base = 0
            while (base < size) {
                var j = base
                var k = 0
                while (j < base + half) {
                    val l = j + half
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val tre = re[l] * c - im[l] * s
                    val tim = re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    j++
                    k += step
                }
                base += span
            }
            span = span shl 1
        }
    }
}
