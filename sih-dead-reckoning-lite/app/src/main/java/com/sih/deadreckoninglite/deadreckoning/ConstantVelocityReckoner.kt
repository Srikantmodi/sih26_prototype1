package com.sih.deadreckoninglite.deadreckoning

import com.sih.deadreckoninglite.location.GpsSample
import com.sih.deadreckoninglite.util.Constants
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rule-based constant-velocity position projector (PRD §9.1).
 *
 * Given the last known GPS fix (speed + heading) and elapsed time since
 * GNSS was lost, projects a new (lat, lon) assuming the vehicle continues
 * moving in a straight line at the same speed and heading.
 *
 * ## What this is
 * Plain trigonometry — equirectangular approximation. Accurate enough for
 * the short distances (tens to hundreds of meters) encountered during a
 * simulated tunnel demo.
 *
 * ## What this is NOT
 * Not AI, not a Kalman filter, not sensor fusion, not map-matching.
 * The IMU data (accelerometer/gyroscope) is logged alongside GPS by other
 * modules for future ML training — it is NOT consumed here.
 *
 * ## Isolation
 * This class is a pure function-holder. It has no knowledge of:
 * - Android UI / Views / ViewModel
 * - MapController
 * - TunnelSimulator's toggle state
 * - SensorManager / GPS provider
 * - Logging / CSV
 *
 * This isolation is deliberate: swap this class's internals for the real
 * UKF+AI system later, without touching any other file.
 */
class ConstantVelocityReckoner {

    /**
     * Projects a new position from [lastFix] after [elapsedSeconds] have passed.
     *
     * @param lastFix       The last real GPS sample before GNSS was lost.
     *                      Uses [GpsSample.speedMps] and [GpsSample.bearingDeg].
     * @param elapsedSeconds Total seconds since GNSS outage began (grows each tick).
     *                       Must NOT be the delta since the *previous* projection —
     *                       it is always measured from the tunnel activation moment.
     *
     * @return Pair(newLatDeg, newLonDeg) — the projected position in decimal degrees.
     */
    fun project(lastFix: GpsSample, elapsedSeconds: Double): Pair<Double, Double> {
        val earthRadiusM = Constants.EARTH_RADIUS_M

        // 1. Distance traveled: speed × time (stationary deadband below 0.35 m/s)
        val effectiveSpeed = if (lastFix.speedMps < 0.35f) 0.0 else lastFix.speedMps.toDouble()
        if (effectiveSpeed == 0.0 || elapsedSeconds <= 0.0) {
            return Pair(lastFix.latDeg, lastFix.lonDeg)
        }
        val distanceM = effectiveSpeed * elapsedSeconds

        // 2. Convert heading from degrees to radians
        val bearingRad = Math.toRadians(lastFix.bearingDeg.toDouble())

        // 3. Convert current latitude to radians (needed for longitude scaling)
        val latRad = Math.toRadians(lastFix.latDeg)

        // 4. North/south component of the displacement
        val deltaLat = (distanceM * cos(bearingRad)) / earthRadiusM

        // 5. East/west component, adjusted for latitude convergence
        val deltaLon = (distanceM * sin(bearingRad)) /
                (earthRadiusM * cos(latRad))

        // 6. Apply offsets to the last known position
        val newLat = lastFix.latDeg + Math.toDegrees(deltaLat)
        val newLon = lastFix.lonDeg + Math.toDegrees(deltaLon)

        // 7. Projected position
        return Pair(newLat, newLon)
    }
}
