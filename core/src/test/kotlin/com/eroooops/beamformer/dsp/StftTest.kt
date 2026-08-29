package com.eroooops.beamformer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StftTest {

    @Test
    fun `squared window sums to one at fifty percent overlap`() {
        val stft = Stft(frameSize = 512)
        val hop = stft.hop
        for (n in 0 until hop) {
            val a = stft.window[n]
            val b = stft.window[n + hop]
            assertEquals("COLA at $n", 1.0, a * a + b * b, 1e-12)
        }
    }

    /**
     * The property the whole streaming path rests on: analyse, do nothing,
     * resynthesise, and the samples come back unchanged. If this drifts, every
     * beamformer output carries a periodic amplitude ripple at the frame rate
     * that is easy to mistake for a bug in the weights.
     */
    @Test
    fun `analysis and synthesis reconstruct the input exactly`() {
        val frameSize = 256
        val stft = Stft(frameSize)
        val hop = stft.hop
        val frames = 12
        val length = frames * hop + frameSize

        val random = Random(3)
        val input = DoubleArray(length) { random.nextDouble() * 2 - 1 }
        val output = DoubleArray(length)

        val re = DoubleArray(frameSize)
        val im = DoubleArray(frameSize)
        for (frame in 0 until frames) {
            val offset = frame * hop
            stft.analyze(input, offset, re, im)
            stft.synthesize(re, im, output, offset)
        }

        // The first and last frame only ever receive one contribution, so
        // reconstruction is exact strictly inside the overlapped region.
        for (n in hop until (frames - 1) * hop) {
            assertEquals("sample $n", input[n], output[n], 1e-10)
        }
    }

    @Test
    fun `mirroring keeps the inverse transform real`() {
        val frameSize = 128
        val stft = Stft(frameSize)
        val random = Random(11)

        val re = DoubleArray(frameSize)
        val im = DoubleArray(frameSize)
        for (bin in 0..frameSize / 2) {
            re[bin] = random.nextDouble() * 2 - 1
            im[bin] = random.nextDouble() * 2 - 1
        }
        stft.mirror(re, im)
        Fft(frameSize).inverse(re, im)

        for (n in 0 until frameSize) {
            assertTrue("sample $n should be real, imaginary part ${im[n]}", kotlin.math.abs(im[n]) < 1e-12)
        }
    }
}
