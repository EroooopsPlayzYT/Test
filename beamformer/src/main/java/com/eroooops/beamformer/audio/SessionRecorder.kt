package com.eroooops.beamformer.audio

import com.eroooops.beamformer.wav.WavWriter
import java.io.File

/**
 * Writes a run to disk: the beamformed output, and the raw microphone channels
 * beside it.
 *
 * Saving the raw channels is the point. The beamformed track alone is a result
 * you cannot argue with — if a bearing looked wrong there is no way back to why.
 * With the raw capture kept, the same scene can be pushed through a changed
 * beamformer as many times as needed, off the phone, which is the only practical
 * way to work on this.
 */
class SessionRecorder(
    directory: File,
    label: String,
    private val sampleRate: Int,
    private val channels: Int,
) {
    private val beamFile = File(directory, "$label-beam.wav")
    private val rawFile = File(directory, "$label-raw.wav")

    private val beamWriter: WavWriter
    private val rawWriter: WavWriter?

    init {
        directory.mkdirs()
        beamWriter = WavWriter(beamFile, sampleRate, 1)
        rawWriter = if (channels >= 2) WavWriter(rawFile, sampleRate, channels) else null
    }

    @Volatile
    var seconds: Double = 0.0
        private set

    private var finished = false

    fun write(planes: Array<DoubleArray>, beam: DoubleArray, count: Int) {
        if (finished) return
        runCatching {
            beamWriter.write(beam, 0, count)
            rawWriter?.writePlanes(planes, 0, count)
            seconds += count.toDouble() / sampleRate
        }
    }

    /** Closes both files and returns the ones that ended up with audio in them. */
    fun finish(): List<File> {
        if (finished) return emptyList()
        finished = true
        runCatching { beamWriter.close() }
        runCatching { rawWriter?.close() }
        return listOfNotNull(
            beamFile.takeIf { it.length() > HEADER_ONLY },
            rawFile.takeIf { rawWriter != null && it.length() > HEADER_ONLY },
        )
    }

    private companion object {
        /** A file with only a header holds no audio and is not worth listing. */
        const val HEADER_ONLY = 44L
    }
}
