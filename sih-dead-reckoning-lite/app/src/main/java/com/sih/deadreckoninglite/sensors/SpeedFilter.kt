package com.sih.deadreckoninglite.sensors

import com.sih.deadreckoninglite.util.Constants

/**
 * Filter and estimator for vehicle speed.
 *
 * ## Features
 * 1. **Zero-Velocity Clamping:** Enforces strict 0.0 km/h when stationary.
 * 2. **GPS Speed Smoothing:** Applies EMA to remove multipath speed jitter.
 * 3. **DR Outage Roll-out:** Smoothly maintains and coasts down speed during GNSS outages
 *    without letting speed accelerate due to accelerometer pitch tilt.
 */
class SpeedFilter {

    companion object {
        private const val BETA_GPS = 0.35f
        private const val DR_DECEL_RATE_MPS2 = 0.08f // Gentle coast-down drag
    }

    private var smoothedSpeedMps: Float = 0f
    private var lastSpeedTimestampNs: Long = 0L

    /** Current vehicle speed in meters per second. */
    val speedMps: Float get() = smoothedSpeedMps

    /** Current vehicle speed in kilometers per hour. */
    val speedKmh: Float get() = smoothedSpeedMps * 3.6f

    /**
     * Update speed from a live GPS fix.
     */
    fun onGpsSpeed(rawSpeedMps: Float, isStationary: Boolean) {
        if (isStationary || rawSpeedMps < Constants.SPEED_DEADBAND_MPS) {
            smoothedSpeedMps = 0f
            return
        }

        // Apply EMA filter
        smoothedSpeedMps = if (smoothedSpeedMps == 0f) {
            rawSpeedMps
        } else {
            BETA_GPS * rawSpeedMps + (1f - BETA_GPS) * smoothedSpeedMps
        }
    }

    /**
     * Step the speed forward during Dead Reckoning using elapsed time and stationary state.
     */
    fun stepDrSpeed(dtSec: Double, isStationary: Boolean): Float {
        if (isStationary || dtSec <= 0.0) {
            smoothedSpeedMps = 0f
            return 0f
        }

        if (smoothedSpeedMps > Constants.SPEED_DEADBAND_MPS) {
            // Apply slight natural deceleration
            smoothedSpeedMps = (smoothedSpeedMps - (DR_DECEL_RATE_MPS2 * dtSec).toFloat())
                .coerceAtLeast(0f)
        } else {
            smoothedSpeedMps = 0f
        }

        return smoothedSpeedMps
    }

    /**
     * Seed speed explicitly (e.g. on entering DR mode).
     */
    fun seedSpeed(initialSpeedMps: Float) {
        smoothedSpeedMps = if (initialSpeedMps < Constants.SPEED_DEADBAND_MPS) 0f else initialSpeedMps
    }
}
