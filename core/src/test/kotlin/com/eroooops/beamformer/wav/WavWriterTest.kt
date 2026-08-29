package com.eroooops.beamformer.wav

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {

    private fun ascii(bytes: ByteArray, from: Int, length: Int) =
        String(bytes, from, length, Charsets.US_ASCII)

    private fun int32(bytes: ByteArray, at: Int) =
        ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun int16(bytes: ByteArray, at: Int) =
        ByteBuffer.wrap(bytes, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

    @Test
    fun `header is byte exact for a stereo file`() {
        val header = wavHeader(sampleRate = 48000, channels = 2, dataBytes = 4000)
        assertEquals(44, header.size)
        assertEquals("RIFF", ascii(header, 0, 4))
        assertEquals(36 + 4000, int32(header, 4))
        assertEquals("WAVE", ascii(header, 8, 4))
        assertEquals("fmt ", ascii(header, 12, 4))
        assertEquals(16, int32(header, 16))
        assertEquals(1, int16(header, 20))          // uncompressed PCM
        assertEquals(2, int16(header, 22))          // channels
        assertEquals(48000, int32(header, 24))
        assertEquals(48000 * 2 * 2, int32(header, 28))  // byte rate
        assertEquals(4, int16(header, 32))          // block align
        assertEquals(16, int16(header, 34))         // bits per sample
        assertEquals("data", ascii(header, 36, 4))
        assertEquals(4000, int32(header, 40))
    }

    @Test
    fun `header is byte exact for a mono file`() {
        val header = wavHeader(sampleRate = 16000, channels = 1, dataBytes = 32)
        assertEquals(1, int16(header, 22))
        assertEquals(16000, int32(header, 24))
        assertEquals(16000 * 2, int32(header, 28))
        assertEquals(2, int16(header, 32))
        assertEquals(32, int32(header, 40))
    }

    @Test
    fun `written file has a patched length and round trips its samples`() {
        val file = File.createTempFile("beamformer", ".wav")
        file.deleteOnExit()

        val samples = doubleArrayOf(0.0, 0.5, -0.5, 1.0, -1.0, 0.25)
        WavWriter(file, sampleRate = 48000, channels = 1).use { it.write(samples) }

        val bytes = file.readBytes()
        assertEquals(44 + samples.size * 2, bytes.size)
        assertEquals("length field must be patched on close", samples.size * 2, int32(bytes, 40))
        assertEquals(36 + samples.size * 2, int32(bytes, 4))

        val expected = intArrayOf(0, 16384, -16384, 32767, -32767, 8192)
        val actual = IntArray(samples.size) { int16(bytes, 44 + it * 2) }
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `samples beyond full scale are clipped rather than wrapped`() {
        val file = File.createTempFile("beamformer-clip", ".wav")
        file.deleteOnExit()

        // A beamformer can overshoot; wrapping would turn a loud moment into
        // full-scale noise, which sounds like a catastrophic bug rather than
        // like clipping.
        WavWriter(file, 48000, 1).use { it.write(doubleArrayOf(4.0, -4.0)) }

        val bytes = file.readBytes()
        assertEquals(32767, int16(bytes, 44))
        assertEquals(-32767, int16(bytes, 46))
    }

    @Test
    fun `planes are interleaved in channel order`() {
        val file = File.createTempFile("beamformer-planes", ".wav")
        file.deleteOnExit()

        val left = doubleArrayOf(0.25, 0.5)
        val right = doubleArrayOf(-0.25, -0.5)
        WavWriter(file, 48000, 2).use { it.writePlanes(arrayOf(left, right), 0, 2) }

        val bytes = file.readBytes()
        assertEquals(8192, int16(bytes, 44))
        assertEquals(-8192, int16(bytes, 46))
        assertEquals(16384, int16(bytes, 48))
        assertEquals(-16384, int16(bytes, 50))
    }
}
