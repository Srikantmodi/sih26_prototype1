package com.sih.deadreckoninglite.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SensorFilterTest {

    private lateinit var filter: SensorFilter

    @Before
    fun setUp() {
        filter = SensorFilter()
    }

    @Test
    fun stationarySamples_triggerZuptAndZeroGyro() {
        // Feed 50 stationary samples with small sensor noise around gravity (9.81 m/s^2)
        var lastFiltered: FilteredSensorData? = null
        for (i in 0 until 50) {
            val sample = SensorSample(
                timestampNs = (i * 20_000_000L), // 50 Hz
                ax = 0.02f * (i % 3 - 1),
                ay = 0.01f * (i % 2),
                az = 9.80665f + 0.02f * (i % 3 - 1),
                gx = 0.03f, // Hardware bias
                gy = -0.02f,
                gz = -0.05f  // Problematic Z bias from user's phone
            )
            lastFiltered = filter.filter(sample)
        }

        assertTrue("Filter should detect stationary device", filter.isStationary)
        assertEquals("Stationary gyro X must be clamped to 0.0", 0.00f, lastFiltered!!.gx, 0.0001f)
        assertEquals("Stationary gyro Y must be clamped to 0.0", 0.00f, lastFiltered.gy, 0.0001f)
        assertEquals("Stationary gyro Z must be clamped to 0.0", 0.00f, lastFiltered.gz, 0.0001f)
    }

    @Test
    fun dynamicMotion_detectsMovementAndSubtractsBias() {
        // First calibrate in stationary state
        for (i in 0 until 50) {
            filter.filter(
                SensorSample(
                    timestampNs = i * 20_000_000L,
                    ax = 0f, ay = 0f, az = 9.80665f,
                    gx = 0f, gy = 0f, gz = -0.05f // Learned bias
                )
            )
        }

        // Now vehicle turns at 0.5 rad/s + bias for 15 samples (~0.3s)
        var filtered: FilteredSensorData? = null
        for (i in 1..15) {
            val turningSample = SensorSample(
                timestampNs = (50 + i) * 20_000_000L,
                ax = 2.5f, // Dynamic lateral acceleration
                ay = 1.0f,
                az = 9.8f,
                gx = 0f,
                gy = 0f,
                gz = 0.5f - 0.05f // Raw with bias
            )
            filtered = filter.filter(turningSample)
        }

        assertFalse("Vehicle turning should not be marked stationary", filtered!!.isStationary)
        // With bias subtracted, gz should reflect true turn rate
        assertTrue("Gyro Z should reflect true turn rate (was: ${filtered.gz})", filtered.gz > 0.3f)
    }
}
