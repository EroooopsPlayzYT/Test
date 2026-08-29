package com.eroooops.beamformer.wav

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

private const val HEADER_BYTES = 44
private const val BITS_PER_SAMPLE = 16

/**
 * Builds a 44-byte canonical WAV header for 16-bit PCM.
 *
 * Split out from the writer so it can be checked byte by byte in a test. A
 * header that is subtly wrong produces a file that some players open and others
 * reject, which is a miserable thing to debug from a phone.
 */
fun wavHeader(sampleRate: Int, channels: Int, dataBytes: Int): ByteArray {
    val blockAlign = channels * BITS_PER_SAMPLE / 8
    val byteRate = sampleRate * blockAlign
    val header = ByteArray(HEADER_BYTES)
    var p = 0

    fun ascii(text: String) {
        for (ch in text) header[p++] = ch.code.toByte()
    }

    fun int32(value: Int) {
        header[p++] = (value and 0xFF).toByte()
        header[p++] = ((value ushr 8) and 0xFF).toByte()
        header[p++] = ((value ushr 16) and 0xFF).toByte()
        header[p++] = ((value ushr 24) and 0xFF).toByte()
    }

    fun int16(value: Int) {
        header[p++] = (value and 0xFF).toByte()
        header[p++] = ((value ushr 8) and 0xFF).toByte()
    }

    ascii("RIFF")
    int32(36 + dataBytes)
    ascii("WAVE")
    ascii("fmt ")
    int32(16)          // PCM subchunk size
    int16(1)           // format: uncompressed PCM
    int16(channels)
    int32(sampleRate)
    int32(byteRate)
    int16(blockAlign)
    int16(BITS_PER_SAMPLE)
    ascii("data")
    int32(dataBytes)
    return header
}

/**
 * Streams 16-bit PCM to a WAV file, patching the length fields on close.
 *
 * Recordings are written as they are captured rather than buffered in memory:
 * a raw two-channel 48 kHz capture is 11 MB a minute, and a long run that dies
 * on an out-of-memory error would take the evidence with it.
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
) : Closeable {

    private val stream = BufferedOutputStream(FileOutputStream(file))
    private var dataBytes = 0
    private var closed = false

    init {
        stream.write(wavHeader(sampleRate, channels, 0))
    }

    /** Writes interleaved samples in -1..1; values outside are clipped, not wrapped. */
    fun write(interleaved: DoubleArray, offset: Int = 0, count: Int = interleaved.size - offset) {
        val bytes = ByteArray(count * 2)
        var p = 0
        for (n in offset until offset + count) {
            val clipped = interleaved[n].coerceIn(-1.0, 1.0)
            // Round half away from zero, so a waveform and its inverse quantise
            // to exact mirror images. Math.round alone rounds .5 toward positive
            // infinity, which biases negative samples by one code.
            val scaled = clipped * 32767.0
            val value = if (scaled >= 0) {
                Math.round(scaled).toInt()
            } else {
                -Math.round(-scaled).toInt()
            }
            bytes[p++] = (value and 0xFF).toByte()
            bytes[p++] = ((value shr 8) and 0xFF).toByte()
        }
        stream.write(bytes)
        dataBytes += bytes.size
    }

    /** Writes already-quantised interleaved samples straight through. */
    fun write(interleaved: ShortArray, offset: Int = 0, count: Int = interleaved.size - offset) {
        val bytes = ByteArray(count * 2)
        var p = 0
        for (n in offset until offset + count) {
            val value = interleaved[n].toInt()
            bytes[p++] = (value and 0xFF).toByte()
            bytes[p++] = ((value shr 8) and 0xFF).toByte()
        }
        stream.write(bytes)
        dataBytes += bytes.size
    }

    /** Interleaves and writes [count] samples from each of [planes]. */
    fun writePlanes(planes: Array<DoubleArray>, offset: Int, count: Int) {
        require(planes.size == channels) { "expected $channels planes" }
        val interleaved = DoubleArray(count * channels)
        var p = 0
        for (n in 0 until count) {
            for (c in 0 until channels) interleaved[p++] = planes[c][offset + n]
        }
        write(interleaved)
    }

    val bytesWritten: Int get() = dataBytes

    val durationSeconds: Double
        get() = dataBytes.toDouble() / (sampleRate * channels * (BITS_PER_SAMPLE / 8))

    override fun close() {
        if (closed) return
        closed = true
        stream.flush()
        stream.close()
        // Patch RIFF and data sizes now that the total is known.
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(wavHeader(sampleRate, channels, dataBytes))
        }
    }
}
