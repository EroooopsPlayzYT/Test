package com.eroooops.beamformer.dsp

/**
 * The streaming beamformer: multi-channel samples in, one channel out.
 *
 * Fixed hop size, no allocation after construction, and no coroutines — this
 * runs on the audio thread, where a garbage collection pause is an audible
 * click.
 *
 * Alongside the beamformed output it produces the **unsteered channel average**,
 * which is what a plain recording of the same moment would have sounded like.
 * Having both from the identical signal path is the only way to answer "is this
 * actually doing anything" with a number instead of an impression.
 *
 * End-to-end delay is [frameSize] samples — 21 ms at 48 kHz. Bluetooth output
 * adds an order of magnitude more than that, which is why wired monitoring is
 * recommended in the UI.
 */
class BeamProcessor(
    val array: MicArray,
    val sampleRate: Int,
    val frameSize: Int = 1024,
) {
    val hop: Int = frameSize / 2
    val channels: Int = array.channels

    private val stft = Stft(frameSize, hop)
    private val designer = BeamDesigner(array, frameSize, sampleRate)
    private val bins = frameSize / 2 + 1

    private val history = Array(channels) { DoubleArray(frameSize) }
    private val specRe = Array(channels) { DoubleArray(frameSize) }
    private val specIm = Array(channels) { DoubleArray(frameSize) }
    private val outRe = DoubleArray(frameSize)
    private val outIm = DoubleArray(frameSize)
    private val overlap = DoubleArray(frameSize + hop)
    private val omniOverlap = DoubleArray(frameSize + hop)

    @Volatile
    private var weights: BeamWeights = designer.design(BeamformerType.DELAY_SUM, 90.0)

    var type: BeamformerType = BeamformerType.DELAY_SUM
        private set

    var azimuthDeg: Double = 90.0
        private set

    var minWhiteNoiseGainDb: Double = -10.0
        private set

    /**
     * Redesigns the weights. Called from the UI thread; the audio thread only
     * ever reads the reference, and a whole [BeamWeights] is swapped in at once
     * so a frame can never be processed with half of one design and half of
     * another.
     */
    fun steer(
        type: BeamformerType = this.type,
        azimuthDeg: Double = this.azimuthDeg,
        minWhiteNoiseGainDb: Double = this.minWhiteNoiseGainDb,
    ) {
        this.type = type
        this.azimuthDeg = azimuthDeg
        this.minWhiteNoiseGainDb = minWhiteNoiseGainDb
        weights = designer.design(type, azimuthDeg, minWhiteNoiseGainDb)
    }

    /** Mean directivity index across the usable band, in dB — the honest headline figure. */
    fun directivityIndexDb(): Double {
        val current = weights
        val top = minOf(bins - 1, (8000.0 * frameSize / sampleRate).toInt())
        val low = maxOf(1, (200.0 * frameSize / sampleRate).toInt())
        if (top <= low) return 0.0
        var sum = 0.0
        for (bin in low..top) sum += current.directivityIndexDb[bin]
        return sum / (top - low + 1)
    }

    /** Worst-case noise amplification across the same band, in dB. */
    fun whiteNoiseGainDb(): Double {
        val current = weights
        val top = minOf(bins - 1, (8000.0 * frameSize / sampleRate).toInt())
        val low = maxOf(1, (200.0 * frameSize / sampleRate).toInt())
        if (top <= low) return 0.0
        var worst = Double.POSITIVE_INFINITY
        for (bin in low..top) worst = minOf(worst, current.whiteNoiseGainDb[bin])
        return if (worst.isFinite()) worst else 0.0
    }

    /**
     * Consumes exactly [hop] samples per channel from [input] at [inputOffset],
     * and writes [hop] samples to [beam] and to [omni] at [outputOffset].
     */
    fun process(
        input: Array<DoubleArray>,
        inputOffset: Int,
        beam: DoubleArray,
        omni: DoubleArray,
        outputOffset: Int,
    ) {
        val current = weights

        for (c in 0 until channels) {
            val buffer = history[c]
            System.arraycopy(buffer, hop, buffer, 0, frameSize - hop)
            System.arraycopy(input[c], inputOffset, buffer, frameSize - hop, hop)
            stft.analyze(buffer, 0, specRe[c], specIm[c])
        }

        // Beamformed output first: Y = w^H X.
        for (bin in 0 until bins) {
            var accRe = 0.0
            var accIm = 0.0
            for (m in 0 until channels) {
                val wr = current.re[bin * channels + m]
                val wi = current.im[bin * channels + m]
                val xr = specRe[m][bin]
                val xi = specIm[m][bin]
                accRe += wr * xr + wi * xi
                accIm += wr * xi - wi * xr
            }
            outRe[bin] = accRe
            outIm[bin] = accIm
        }
        stft.mirror(outRe, outIm)
        stft.synthesize(outRe, outIm, overlap, 0)

        // Unsteered average, through the identical path so the two are comparable.
        val scale = 1.0 / channels
        for (bin in 0 until bins) {
            var accRe = 0.0
            var accIm = 0.0
            for (m in 0 until channels) {
                accRe += specRe[m][bin]
                accIm += specIm[m][bin]
            }
            outRe[bin] = accRe * scale
            outIm[bin] = accIm * scale
        }
        stft.mirror(outRe, outIm)
        stft.synthesize(outRe, outIm, omniOverlap, 0)

        System.arraycopy(overlap, 0, beam, outputOffset, hop)
        System.arraycopy(omniOverlap, 0, omni, outputOffset, hop)

        slide(overlap)
        slide(omniOverlap)
    }

    private fun slide(buffer: DoubleArray) {
        System.arraycopy(buffer, hop, buffer, 0, buffer.size - hop)
        java.util.Arrays.fill(buffer, buffer.size - hop, buffer.size, 0.0)
    }

    /** Clears all overlap state, for when capture restarts. */
    fun reset() {
        history.forEach { it.fill(0.0) }
        overlap.fill(0.0)
        omniOverlap.fill(0.0)
    }
}
