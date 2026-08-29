package com.eroooops.beamformer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoaTest {

    private val sampleRate = 48000
    private val spacing = 0.14
    private val array = MicArray.linearPair(spacing)

    private fun simulate(
        azimuthDeg: Double,
        samples: Int,
        noiseAmplitude: Double = 0.0,
        lowHz: Double = 300.0,
        highHz: Double = 3000.0,
        seed: Int = 5,
    ): Array<DoubleArray> {
        val simulator = ArraySimulator(array, sampleRate, seed = seed)
        simulator.sources = listOf(
            SyntheticSource(azimuthDeg, lowHz = lowHz, highHz = highHz, seed = seed),
        )
        simulator.noiseAmplitude = noiseAmplitude
        val out = Array(array.channels) { DoubleArray(samples) }
        simulator.render(out, 0, samples)
        return out
    }

    @Test
    fun `gcc phat recovers the arrival time difference`() {
        val frameSize = 4096
        val signals = simulate(30.0, frameSize)
        val estimator = PairDelayEstimator(frameSize, sampleRate)

        val expected = spacing * Math.cos(Math.toRadians(30.0)) / SPEED_OF_SOUND
        val measured = estimator.estimate(signals[0], signals[1], 0, spacing / SPEED_OF_SOUND)

        // One raw sample at 48 kHz is 21 microseconds; the oversampled peak fit
        // should land well inside that.
        assertEquals(expected, measured, 5e-6)
    }

    @Test
    fun `gcc phat recovers the bearing within three degrees`() {
        val frameSize = 4096
        val estimator = PairDelayEstimator(frameSize, sampleRate)
        for (truth in listOf(30.0, 60.0, 90.0, 120.0, 150.0)) {
            val signals = simulate(truth, frameSize)
            val tau = estimator.estimate(signals[0], signals[1], 0, spacing / SPEED_OF_SOUND)
            val bearing = bearingFromDelay(array, 0, 1, tau)
            assertEquals("bearing $truth", truth, bearing, 3.0)
        }
    }

    @Test
    fun `gcc phat still works at ten decibels signal to noise`() {
        val frameSize = 4096
        val estimator = PairDelayEstimator(frameSize, sampleRate)
        // The source is normalised to unit RMS, so an amplitude of 0.316 on
        // independent per-channel noise is exactly 10 dB SNR.
        val noise = Math.pow(10.0, -10.0 / 20.0)
        for (truth in listOf(45.0, 90.0, 135.0)) {
            val signals = simulate(truth, frameSize, noiseAmplitude = noise, seed = 21)
            val tau = estimator.estimate(signals[0], signals[1], 0, spacing / SPEED_OF_SOUND)
            val bearing = bearingFromDelay(array, 0, 1, tau)
            assertEquals("bearing $truth at 10 dB SNR", truth, bearing, 5.0)
        }
    }

    @Test
    fun `srp phat peaks at the true bearing`() {
        val scanner = DoaScanner(array, sampleRate)
        val surface = DoubleArray(scanner.angles.size)
        for (truth in listOf(30.0, 60.0, 90.0, 120.0, 150.0)) {
            val signals = simulate(truth, scanner.frameSize)
            val estimate = scanner.scan(signals, 0, surface)
            assertEquals("bearing $truth", truth, estimate.azimuthDeg, 3.0)
            assertTrue(
                "a clean single source should be confident, was ${estimate.confidence}",
                estimate.confidence > 0.5,
            )
        }
    }

    @Test
    fun `srp phat is unconfident when there is nothing to hear`() {
        val scanner = DoaScanner(array, sampleRate)
        val surface = DoubleArray(scanner.angles.size)
        val silence = Array(array.channels) { DoubleArray(scanner.frameSize) }
        val estimate = scanner.scan(silence, 0, surface)
        assertTrue("silence should not be confident", estimate.confidence < 0.2)
    }

    /**
     * Independent noise on each channel has no consistent arrival-time
     * difference, so no bearing should stand out. This is the case that would
     * otherwise produce a needle wandering confidently around an empty room.
     */
    @Test
    fun `uncorrelated noise produces no confident bearing`() {
        val scanner = DoaScanner(array, sampleRate)
        val surface = DoubleArray(scanner.angles.size)
        val simulator = ArraySimulator(array, sampleRate, seed = 3)
        simulator.noiseAmplitude = 1.0
        val signals = Array(array.channels) { DoubleArray(scanner.frameSize) }
        simulator.render(signals, 0, scanner.frameSize)

        val estimate = scanner.scan(signals, 0, surface)
        assertTrue(
            "diffuse noise should not look like a source, confidence ${estimate.confidence}",
            estimate.confidence < 0.35,
        )
    }

    /**
     * The scan band must stop at the aliasing frequency. Above it, a bearing and
     * its alias are indistinguishable, so including those bins does not sharpen
     * the estimate — it invents peaks.
     */
    @Test
    fun `the scan band stops at the aliasing frequency`() {
        val scanner = DoaScanner(array, sampleRate)
        assertEquals(array.aliasFrequency, scanner.bandHighHz, 1e-9)
        assertTrue("band should be usable for a phone-sized array", scanner.usable)

        val wide = DoaScanner(MicArray.linearPair(0.60), sampleRate)
        assertTrue(
            "a wider array must scan a narrower band",
            wide.bandHighHz < scanner.bandHighHz,
        )
    }
}
