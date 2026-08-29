package com.eroooops.beamformer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins down the sign convention documented on [MicArray].
 *
 * Every other result in this package is built on it, and a flipped sign here
 * produces an app that is confidently mirror-imaged — pointing the beam at 30
 * degrees when the sound is at 150 — which no amount of listening on a phone
 * would reliably catch.
 */
class SteeringTest {

    private val spacing = 0.14
    private val array = MicArray.linearPair(spacing)

    @Test
    fun `the microphone nearer the source hears it first`() {
        // Source at 0 degrees is off toward +x, where microphone 1 sits.
        val delays = array.delays(0.0)
        assertTrue(
            "microphone 1 is nearer the source so should have the smaller delay",
            delays[1] < delays[0],
        )
        assertEquals(spacing / SPEED_OF_SOUND, delays[0] - delays[1], 1e-12)
    }

    @Test
    fun `broadside gives equal delays`() {
        val delays = array.delays(90.0)
        assertEquals(delays[0], delays[1], 1e-15)
    }

    @Test
    fun `delay difference follows the cosine of the bearing`() {
        for (azimuth in listOf(0.0, 30.0, 60.0, 90.0, 120.0, 180.0)) {
            val delays = array.delays(azimuth)
            val expected = spacing * Math.cos(Math.toRadians(azimuth)) / SPEED_OF_SOUND
            assertEquals("azimuth $azimuth", expected, delays[0] - delays[1], 1e-12)
        }
    }

    @Test
    fun `bearing from delay inverts the delay calculation`() {
        for (azimuth in listOf(0.0, 15.0, 45.0, 90.0, 135.0, 180.0)) {
            val delays = array.delays(azimuth)
            val recovered = bearingFromDelay(array, 0, 1, delays[0] - delays[1])
            assertEquals("azimuth $azimuth", azimuth, recovered, 1e-9)
        }
    }

    @Test
    fun `alias frequency follows half wavelength spacing`() {
        assertEquals(SPEED_OF_SOUND / (2 * spacing), array.aliasFrequency, 1e-9)
        // A wider array resolves direction over a narrower band.
        assertTrue(MicArray.linearPair(0.30).aliasFrequency < array.aliasFrequency)
    }

    @Test
    fun `a bearing past the axis folds back`() {
        assertEquals(160.0, wrapBearing(200.0), 1e-12)
        assertEquals(20.0, wrapBearing(-20.0), 1e-12)
        assertEquals(45.0, wrapBearing(405.0), 1e-12)
    }

    @Test
    fun `centring positions does not change relative delays`() {
        val offCentre = MicArray(listOf(Vec3(1.0, 0.0), Vec3(1.14, 0.0)))
        val centred = MicArray.centred(offCentre.mics)
        for (azimuth in listOf(0.0, 37.0, 90.0, 143.0)) {
            val a = offCentre.delays(azimuth)
            val b = centred.delays(azimuth)
            assertEquals("azimuth $azimuth", a[0] - a[1], b[0] - b[1], 1e-15)
        }
    }
}
