package com.sih.deadreckoninglite.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsSampleTest {

    @Test
    fun testDistanceToSamePoint_isZero() {
        val sample = GpsSample(
            timestampNs = 1_000_000_000L,
            latDeg = 12.971598,
            lonDeg = 77.594562,
            speedMps = 10f,
            bearingDeg = 0f,
            accuracyM = 2f
        )

        val distance = sample.distanceTo(sample)
        assertEquals(0.0, distance, 1e-4)
    }

    @Test
    fun testDistanceToKnownOffset_isAccurate() {
        val fix1 = GpsSample(
            timestampNs = 1_000_000_000L,
            latDeg = 12.9716,
            lonDeg = 77.5946,
            speedMps = 10f,
            bearingDeg = 0f,
            accuracyM = 2f
        )
        // 0.01 degree latitude difference is approx 1111 meters
        val fix2 = GpsSample(
            timestampNs = 2_000_000_000L,
            latDeg = 12.9816,
            lonDeg = 77.5946,
            speedMps = 10f,
            bearingDeg = 0f,
            accuracyM = 2f
        )

        val distance = fix1.distanceTo(fix2)
        assertTrue("Distance should be around 1111 meters", distance in 1100.0..1125.0)
    }

    @Test
    fun testCsvFormatting() {
        val sample = GpsSample(
            timestampNs = 1_000_000_000L,
            latDeg = 12.97159812,
            lonDeg = 77.59456234,
            speedMps = 12.3456f,
            bearingDeg = 90.0f,
            accuracyM = 3.5f
        )

        val csv = sample.toCsvValues()
        val parts = csv.split(",")
        assertEquals(4, parts.size)
        assertEquals("12.97159812", parts[0])
        assertEquals("77.59456234", parts[1])
        assertEquals("12.3456", parts[2])
        assertEquals("3.5000", parts[3])
    }
}
