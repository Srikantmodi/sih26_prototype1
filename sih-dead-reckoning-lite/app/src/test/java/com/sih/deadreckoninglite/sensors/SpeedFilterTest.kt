package com.sih.deadreckoninglite.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpeedFilterTest {

    private lateinit var speedFilter: SpeedFilter

    @Before
    fun setUp() {
        speedFilter = SpeedFilter()
    }

    @Test
    fun stationaryState_forcesZeroSpeed() {
        speedFilter.onGpsSpeed(rawSpeedMps = 2.5f, isStationary = true)
        assertEquals("Stationary state must clamp speed to 0.0 m/s", 0.0f, speedFilter.speedMps, 0.001f)
        assertEquals("Stationary state must clamp speed to 0.0 km/h", 0.0f, speedFilter.speedKmh, 0.001f)
    }

    @Test
    fun subDeadbandSpeed_clampsToZero() {
        speedFilter.onGpsSpeed(rawSpeedMps = 0.30f, isStationary = false)
        assertEquals("Speeds below deadband clamp to 0.0", 0.0f, speedFilter.speedMps, 0.001f)
    }

    @Test
    fun validGpsSpeed_smoothesCorrectly() {
        speedFilter.onGpsSpeed(rawSpeedMps = 10.0f, isStationary = false) // ~36 km/h
        assertEquals(10.0f, speedFilter.speedMps, 0.01f)

        speedFilter.onGpsSpeed(rawSpeedMps = 12.0f, isStationary = false)
        // EMA: 0.35 * 12 + 0.65 * 10 = 4.2 + 6.5 = 10.7
        assertEquals(10.7f, speedFilter.speedMps, 0.05f)
        assertTrue(speedFilter.speedKmh > 36.0f)
    }

    @Test
    fun drSpeedStep_appliesCoastDownDeceleration() {
        speedFilter.seedSpeed(10.0f)
        val decayed = speedFilter.stepDrSpeed(dtSec = 1.0, isStationary = false)
        assertTrue("DR speed must coast down gradually without accelerating", decayed < 10.0f)
        assertTrue("DR speed must remain positive during short outage", decayed > 9.5f)
    }
}
