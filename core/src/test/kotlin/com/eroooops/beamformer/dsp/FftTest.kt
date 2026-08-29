package com.eroooops.beamformer.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FftTest {

    @Test
    fun `matches a direct discrete Fourier transform`() {
        val n = 64
        val random = Random(4)
        val signal = DoubleArray(n) { random.nextDouble() * 2 - 1 }

        val re = signal.copyOf()
        val im = DoubleArray(n)
        Fft(n).forward(re, im)

        for (k in 0 until n) {
            var expectedRe = 0.0
            var expectedIm = 0.0
            for (t in 0 until n) {
                val angle = -2.0 * PI * k * t / n
                expectedRe += signal[t] * cos(angle)
                expectedIm += signal[t] * sin(angle)
            }
            assertEquals("bin $k real", expectedRe, re[k], 1e-9)
            assertEquals("bin $k imaginary", expectedIm, im[k], 1e-9)
        }
    }

    @Test
    fun `inverse undoes forward`() {
        val n = 1024
        val random = Random(9)
        val signal = DoubleArray(n) { random.nextDouble() * 2 - 1 }

        val re = signal.copyOf()
        val im = DoubleArray(n)
        val fft = Fft(n)
        fft.forward(re, im)
        fft.inverse(re, im)

        for (t in 0 until n) {
            assertEquals(signal[t], re[t], 1e-12)
            assertEquals(0.0, im[t], 1e-12)
        }
    }

    @Test
    fun `a pure tone lands in exactly one bin`() {
        val n = 256
        val binIndex = 17
        val re = DoubleArray(n) { cos(2.0 * PI * binIndex * it / n) }
        val im = DoubleArray(n)
        Fft(n).forward(re, im)

        for (k in 0..n / 2) {
            val magnitude = kotlin.math.hypot(re[k], im[k])
            if (k == binIndex) {
                assertEquals("expected energy at bin $k", n / 2.0, magnitude, 1e-9)
            } else {
                assertEquals("expected silence at bin $k", 0.0, magnitude, 1e-9)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non power of two size`() {
        Fft(100)
    }
}
