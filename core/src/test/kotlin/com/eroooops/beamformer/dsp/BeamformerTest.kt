package com.eroooops.beamformer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class BeamformerTest {

    private val sampleRate = 48000
    private val spacing = 0.14
    private val array = MicArray.linearPair(spacing)
    private val frameSize = 1024

    private fun binFor(hz: Double) = (hz * frameSize / sampleRate).toInt()

    /** Runs the streaming beamformer over a simulated scene and returns output levels. */
    private fun render(
        sourceAzimuths: List<Double>,
        steerAzimuth: Double,
        type: BeamformerType,
        minWhiteNoiseGainDb: Double = -10.0,
        lowHz: Double = 300.0,
        highHz: Double = 1200.0,
        seconds: Double = 0.5,
    ): Levels {
        val processor = BeamProcessor(array, sampleRate, frameSize)
        processor.steer(type, steerAzimuth, minWhiteNoiseGainDb)

        val simulator = ArraySimulator(array, sampleRate, seed = 13)
        simulator.sources = sourceAzimuths.mapIndexed { index, azimuth ->
            SyntheticSource(azimuth, lowHz = lowHz, highHz = highHz, seed = 13 + index)
        }

        val hop = processor.hop
        val frames = (seconds * sampleRate / hop).toInt()
        val input = Array(array.channels) { DoubleArray(frames * hop) }
        simulator.render(input, 0, frames * hop)

        val beam = DoubleArray(frames * hop)
        val omni = DoubleArray(frames * hop)
        for (frame in 0 until frames) {
            processor.process(input, frame * hop, beam, omni, frame * hop)
        }

        // The overlap-add buffer starts empty, so the opening frames are a
        // fade-in rather than a measurement.
        val skip = 8 * hop
        return Levels(rms(beam, skip, beam.size - skip), rms(omni, skip, omni.size - skip))
    }

    private data class Levels(val beam: Double, val omni: Double) {
        /** How much quieter the beam is than an unsteered recording, in dB. */
        val rejectionDb: Double get() = 20.0 * log10(omni / beam)
    }

    /**
     * The defining constraint: whatever else the weights do, a source in the
     * look direction must come through at its original level. Both modes solve
     * `w^H a = 1`, so both must satisfy this to the last decimal.
     */
    @Test
    fun `a source in the look direction passes through at unit gain`() {
        for (type in BeamformerType.entries) {
            for (azimuth in listOf(0.0, 45.0, 90.0, 135.0)) {
                val levels = render(listOf(azimuth), azimuth, type)
                assertEquals(
                    "$type steered at $azimuth should pass the source unchanged",
                    1.0,
                    levels.beam,
                    0.05,
                )
            }
        }
    }

    /**
     * The theoretical ceiling for two elements is a hypercardioid, 6.02 dB of
     * directivity index. Reaching it at low frequency — where the wavelength
     * dwarfs the 14 cm spacing and the array is working as a differential pair —
     * is the strongest single check that the minimum-variance solution is
     * actually being solved rather than approximated.
     */
    @Test
    fun `superdirective reaches the two element theoretical limit`() {
        val designer = BeamDesigner(array, frameSize, sampleRate)
        // A permissive noise floor, since the limit is only reachable when the
        // weights are allowed to grow.
        val weights = designer.design(BeamformerType.SUPERDIRECTIVE, 0.0, -25.0)
        assertEquals(
            "hypercardioid limit at low frequency",
            6.02,
            weights.directivityIndexDb[binFor(80.0)],
            0.1,
        )
    }

    /**
     * Delay-and-sum on two microphones cannot beat 3 dB, and at low frequency it
     * approaches nothing at all: two capsules 14 cm apart see almost the same
     * pressure at 100 Hz, so averaging them discriminates almost not at all.
     * This is the concrete reason the superdirective mode exists.
     */
    @Test
    fun `delay and sum is nearly useless at low frequency`() {
        val designer = BeamDesigner(array, frameSize, sampleRate)
        val delaySum = designer.design(BeamformerType.DELAY_SUM, 0.0)
        val superdirective = designer.design(BeamformerType.SUPERDIRECTIVE, 0.0, -12.0)

        assertTrue(
            "delay-and-sum at 150 Hz should be under 1 dB, got " +
                delaySum.directivityIndexDb[binFor(150.0)],
            delaySum.directivityIndexDb[binFor(150.0)] < 1.0,
        )
        assertTrue(
            "superdirective at 150 Hz should be over 5 dB, got " +
                superdirective.directivityIndexDb[binFor(150.0)],
            superdirective.directivityIndexDb[binFor(150.0)] > 5.0,
        )
        // Delay-and-sum does become genuinely directional as the spacing
        // approaches half a wavelength — around 1.2 kHz on this array — so there
        // is no fixed ceiling to assert against. The invariant that does hold
        // everywhere is optimality: the superdirective weights minimise diffuse
        // noise for unit look-direction gain, so no other weighting, delay-and-sum
        // included, can beat their directivity index at any frequency.
        for (hz in listOf(100.0, 150.0, 300.0, 500.0, 1000.0, 2000.0, 4000.0)) {
            val bin = binFor(hz)
            assertTrue(
                "superdirective ${superdirective.directivityIndexDb[bin]} must be at least " +
                    "delay-and-sum ${delaySum.directivityIndexDb[bin]} at $hz Hz",
                superdirective.directivityIndexDb[bin] >= delaySum.directivityIndexDb[bin] - 0.01,
            )
            assertTrue(
                "first-order directivity cannot exceed 6 dB, got " +
                    "${superdirective.directivityIndexDb[bin]} at $hz Hz",
                superdirective.directivityIndexDb[bin] <= 6.1,
            )
        }
    }

    /**
     * The white-noise-gain floor is the user-facing safety control: it bounds how
     * much microphone hiss the weights may amplify. If the designer ignored it,
     * the sharp end of the slider would look fine in simulation and produce an
     * unusable roar on a real phone.
     */
    @Test
    fun `the white noise gain floor is respected`() {
        val designer = BeamDesigner(array, frameSize, sampleRate)
        for (floor in listOf(-3.0, -6.0, -12.0)) {
            val weights = designer.design(BeamformerType.SUPERDIRECTIVE, 0.0, floor)
            for (bin in binFor(100.0)..binFor(8000.0)) {
                assertTrue(
                    "bin $bin white noise gain ${weights.whiteNoiseGainDb[bin]} " +
                        "below requested floor $floor",
                    weights.whiteNoiseGainDb[bin] >= floor - 0.5,
                )
            }
        }
    }

    /**
     * Loosening the noise floor must buy directivity, otherwise the control is
     * decorative. The trade only bites below about 500 Hz — above that the
     * unconstrained solution already has positive white-noise gain and the floor
     * is inactive — so it is checked where it actually applies.
     */
    @Test
    fun `allowing more noise buys more directivity where the floor binds`() {
        val designer = BeamDesigner(array, frameSize, sampleRate)
        val cautious = designer.design(BeamformerType.SUPERDIRECTIVE, 0.0, -1.0)
        val aggressive = designer.design(BeamformerType.SUPERDIRECTIVE, 0.0, -15.0)

        val bin = binFor(100.0)
        assertTrue(
            "aggressive ${aggressive.directivityIndexDb[bin]} dB should clearly exceed " +
                "cautious ${cautious.directivityIndexDb[bin]} dB at 100 Hz",
            aggressive.directivityIndexDb[bin] > cautious.directivityIndexDb[bin] + 2.0,
        )

        // ...and above the crossover the two must agree, because the constraint
        // is no longer doing anything.
        val high = binFor(1000.0)
        assertEquals(
            "the floor should be inactive at 1 kHz",
            cautious.directivityIndexDb[high],
            aggressive.directivityIndexDb[high],
            0.01,
        )
    }

    /**
     * End-to-end through the streaming path: a source behind the array, with the
     * beam pointed the other way. At low frequency the hypercardioid response at
     * 180 degrees is -6 dB, and that is what should come out.
     */
    @Test
    fun `superdirective rejects a source behind the beam`() {
        val levels = render(
            sourceAzimuths = listOf(180.0),
            steerAzimuth = 0.0,
            type = BeamformerType.SUPERDIRECTIVE,
            lowHz = 100.0,
            highHz = 400.0,
        )
        assertTrue(
            "expected at least 5 dB of rejection, got ${"%.1f".format(levels.rejectionDb)} dB",
            levels.rejectionDb >= 5.0,
        )
    }

    @Test
    fun `steering away from a source rejects far more than steering at it`() {
        val onTarget = render(listOf(180.0), 180.0, BeamformerType.SUPERDIRECTIVE, lowHz = 100.0, highHz = 400.0)
        val away = render(listOf(180.0), 0.0, BeamformerType.SUPERDIRECTIVE, lowHz = 100.0, highHz = 400.0)
        assertTrue(
            "steering away (${"%.1f".format(away.rejectionDb)} dB) should beat " +
                "steering at it (${"%.1f".format(onTarget.rejectionDb)} dB)",
            away.rejectionDb > onTarget.rejectionDb + 5.0,
        )
    }

    /**
     * The realistic case, and the one the directivity index actually predicts:
     * one wanted talker plus interference spread all around the array. Measuring
     * the wanted and unwanted parts separately through the identical path gives
     * a clean signal-to-interference figure.
     */
    @Test
    fun `beamforming improves signal to interference against spread out sources`() {
        val interferers = (1..11).map { it * 15.0 }

        fun improvementFor(type: BeamformerType): Double {
            val target = render(listOf(0.0), 0.0, type)
            val noise = render(interferers, 0.0, type)
            val beamRatio = 20.0 * log10(target.beam / noise.beam)
            val omniRatio = 20.0 * log10(target.omni / noise.omni)
            return beamRatio - omniRatio
        }

        val superdirective = improvementFor(BeamformerType.SUPERDIRECTIVE)
        val delaySum = improvementFor(BeamformerType.DELAY_SUM)

        assertTrue(
            "superdirective should gain at least 4 dB, got ${"%.2f".format(superdirective)} dB",
            superdirective >= 4.0,
        )
        assertTrue(
            "superdirective ${"%.2f".format(superdirective)} dB should beat " +
                "delay-and-sum ${"%.2f".format(delaySum)} dB",
            superdirective > delaySum + 1.0,
        )
    }

    /** A single microphone cannot be directional, and must not pretend to be. */
    @Test
    fun `a one microphone array has no directivity`() {
        val mono = MicArray(listOf(Vec3(0.0, 0.0, 0.0)))
        val designer = BeamDesigner(mono, frameSize, sampleRate)
        for (type in BeamformerType.entries) {
            val weights = designer.design(type, 45.0)
            for (hz in listOf(200.0, 1000.0, 4000.0)) {
                assertEquals(
                    "$type at $hz Hz",
                    0.0,
                    weights.directivityIndexDb[binFor(hz)],
                    0.01,
                )
            }
        }
    }
}
