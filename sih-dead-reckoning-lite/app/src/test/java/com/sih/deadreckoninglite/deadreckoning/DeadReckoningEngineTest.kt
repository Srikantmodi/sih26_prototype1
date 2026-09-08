package com.sih.deadreckoninglite.deadreckoning

import com.sih.deadreckoninglite.sensors.FilteredSensorData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeadReckoningEngineTest {

    private lateinit var engine: DeadReckoningEngine

    @Before
    fun setUp() {
        engine = DeadReckoningEngine()
    }

    @Test
    fun straightMovement_projectsForwardWithoutCircles() {
        val startLat = 17.478617
        val startLon = 78.558636
        val headingNorth = 0f
        val speedMps = 10f // ~36 km/h

        engine.setOrigin(startLat, startLon, headingNorth, speedMps)

        // Simulate 5 seconds of straight driving (gz = 0.0)
        var currentPos = Pair(startLat, startLon)
        for (i in 1..5) {
            val sensor = FilteredSensorData(
                timestampNs = i * 1_000_000_000L,
                ax = 0f, ay = 0f, az = 9.8f,
                gx = 0f, gy = 0f, gz = 0f,
                isStationary = false
            )
            currentPos = engine.step(sensor, dtSec = 1.0)
        }

        // Heading north means latitude increases, longitude stays constant
        assertTrue("Latitude must increase when driving North", currentPos.first > startLat)
        assertEquals("Longitude must remain unchanged on straight North heading", startLon, currentPos.second, 0.00001)

        // Estimated uncertainty must stay minimal (not 562 meters!)
        assertTrue("Estimated drift must remain minimal under 15m", engine.estimatedDriftM < 15f)
        assertTrue("Estimated drift must be positive", engine.estimatedDriftM >= 1.5f)
    }

    @Test
    fun stationaryVehicle_freezesPositionAndDrift() {
        val startLat = 17.478617
        val startLon = 78.558636

        engine.setOrigin(startLat, startLon, bearingDeg = 90f, speedMps = 0f)

        val sensorStationary = FilteredSensorData(
            timestampNs = 1_000_000_000L,
            ax = 0f, ay = 0f, az = 9.80665f,
            gx = 0f, gy = 0f, gz = 0f,
            isStationary = true
        )

        val pos = engine.step(sensorStationary, dtSec = 1.0)
        assertEquals(startLat, pos.first, 0.0000001)
        assertEquals(startLon, pos.second, 0.0000001)
        assertEquals("Drift must not explode when static", 1.5f, engine.estimatedDriftM, 0.1f)
    }
}
