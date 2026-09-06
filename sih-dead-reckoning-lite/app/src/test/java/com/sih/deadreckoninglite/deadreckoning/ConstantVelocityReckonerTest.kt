package com.sih.deadreckoninglite.deadreckoning

import com.sih.deadreckoninglite.location.GpsSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstantVelocityReckonerTest {

    private val reckoner = ConstantVelocityReckoner()

    @Test
    fun testZeroElapsedTime_staysAtSamePosition() {
        val initialFix = GpsSample(
            timestampNs = 1_000_000_000L,
            latDeg = 12.971598,
            lonDeg = 77.594562,
            speedMps = 15.0f,
            bearingDeg = 45.0f,
            accuracyM = 3.0f
        )

        val (projLat, projLon) = reckoner.project(initialFix, 0.0)
        assertEquals(initialFix.latDeg, projLat, 1e-9)
        assertEquals(initialFix.lonDeg, projLon, 1e-9)
    }

    @Test
    fun testZeroSpeed_staysAtSamePosition() {
        val initialFix = GpsSample(
            timestampNs = 1_000_000_000L,
            latDeg = 12.971598,
            lonDeg = 77.594562,
            speedMps = 0.0f,
            bearingDeg = 90.0f,
            accuracyM = 2.0f
        )

        val (projLat, projLon) = reckoner.project(initialFix, 10.0)
        assertEquals(initialFix.latDeg, projLat, 1e-9)
        assertEquals(initialFix.lonDeg, projLon, 1e-9)
    }

    @Test
    fun testSubThresholdSpeed_treatedAsStationary() {
        val initialFix = GpsSample(
            timestampNs = 1_000_000_000L,
            latDeg = 12.971598,
            lonDeg = 77.594562,
            speedMps = 0.25f, // Below 0.35 m/s threshold
            bearingDeg = 90.0f,
            accuracyM = 2.0f
        )

        val (projLat, projLon) = reckoner.project(initialFix, 15.0)
        assertEquals(initialFix.latDeg, projLat, 1e-9)
        assertEquals(initialFix.lonDeg, projLon, 1e-9)
    }

    @Test
    fun testHeadingNorth_increasesLatitudeOnly() {
        val initialFix = GpsSample(
            timestampNs = 1_000_000_000L,
            latDeg = 12.0,
            lonDeg = 77.0,
            speedMps = 20.0f,
            bearingDeg = 0.0f, // True North
            accuracyM = 1.0f
        )

        val (projLat, projLon) = reckoner.project(initialFix, 10.0) // 200m North
        assertTrue("Latitude should increase heading North", projLat > initialFix.latDeg)
        assertEquals(initialFix.lonDeg, projLon, 1e-7)
    }

    @Test
    fun testHeadingEast_increasesLongitudeOnly() {
        val initialFix = GpsSample(
            timestampNs = 1_000_000_000L,
            latDeg = 12.0,
            lonDeg = 77.0,
            speedMps = 20.0f,
            bearingDeg = 90.0f, // True East
            accuracyM = 1.0f
        )

        val (projLat, projLon) = reckoner.project(initialFix, 10.0) // 200m East
        assertEquals(initialFix.latDeg, projLat, 1e-7)
        assertTrue("Longitude should increase heading East", projLon > initialFix.lonDeg)
    }
}
