package com.sih.deadreckoninglite.deadreckoning

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.sih.deadreckoninglite.location.GpsSample
import com.sih.deadreckoninglite.ml.MlSpeedEstimator
import com.sih.deadreckoninglite.util.Constants

/**
 * Simulated-tunnel mode controller — ML-upgraded version.
 *
 * ## What changed vs. prototype
 * Speed source priority:
 *   1. MlSpeedEstimator (primary) — when mlEstimator.isReady() == true
 *   2. ConstantVelocityReckoner (fallback) — during ML warm-up (~2 s after activation)
 *
 * Heading is always from last real GPS fix (bearingDeg). Gyro-based heading is future work.
 *
 * Position integration changed: instead of projecting from time-zero (which assumed
 * constant speed from the last GPS fix), we now integrate step-by-step:
 *   new_pos = prev_pos + speed x delta_t_in_direction_of_bearing
 * This allows ML speed to vary naturally over time.
 *
 * ## Architecture Rule (unchanged)
 * TunnelSimulator has NO knowledge of MapController, MainViewModel, or any UI component.
 * All output goes through the onProjectedPosition callback -> MainActivity routes it.
 *
 * ## Dependency Injection
 * mlEstimator is SET by MainActivity before setActive(true) is called.
 * TunnelSimulator never creates or destroys MlSpeedEstimator.
 */
class TunnelSimulator {

    companion object {
        private const val TAG = "TunnelSimulator"
    }

    private val reckoner = ConstantVelocityReckoner()   // kept as CV fallback
    private val handler  = Handler(Looper.getMainLooper())

    // ── State ──────────────────────────────────────────────────────────────

    var isActive: Boolean = false
        private set

    private var lastRealFix: GpsSample? = null
    private var tickerRunning: Boolean  = false

    // ML mode: running integrated position and dynamic heading
    private var reckonedLat: Double  = 0.0
    private var reckonedLon: Double  = 0.0
    private var reckonedHeadingDeg: Double = 0.0
    private var lastTickTimeMs: Long = 0L

    // Gyroscope tracking for curved tunnels (Gap 1 recovery)
    @Volatile private var currentYawRateRads: Float = 0f
    var gyroBiasZ: Float = 0f

    // ── External dependencies (set by MainActivity before setActive) ────────

    /**
     * ML speed estimator. Set by MainActivity (composition root).
     * If null, falls back to constant-velocity reckoning only.
     */
    var mlEstimator: MlSpeedEstimator? = null

    /**
     * Output callback — invoked each tick with projected (lat, lon).
     * Set by MainActivity; routes to MapController.addToReckonedPath() and MainViewModel.
     */
    var onProjectedPosition: ((lat: Double, lon: Double) -> Unit)? = null

    // ── Public API ──────────────────────────────────────────────────────────

    /** Feed a real GPS sample. Always called by MainActivity, regardless of isActive. */
    fun onRealGpsSample(sample: GpsSample) {
        lastRealFix = sample
    }

    /** Feed live gyroscope Z-axis reading (~50 Hz) for heading integration. */
    fun onGyroSample(gzRads: Float) {
        currentYawRateRads = gzRads
    }

    /**
     * Activate or deactivate dead-reckoning mode.
     * setActive(true): starts 10-Hz ticker, resets ML window, anchors reckoned position and heading.
     * setActive(false): stops ticker immediately.
     */
    fun setActive(active: Boolean) {
        if (active) {
            if (isActive && tickerRunning) return
            isActive = true
            lastTickTimeMs = SystemClock.elapsedRealtime()

            // Anchor reckoned position and heading to last real GPS fix
            lastRealFix?.let {
                reckonedLat = it.latDeg
                reckonedLon = it.lonDeg
                reckonedHeadingDeg = it.bearingDeg.toDouble()
            }

            mlEstimator?.reset()
            startTicker()
            Log.i(TAG, "Activated DR mode — Initial heading: $reckonedHeadingDeg deg, ML ready=${mlEstimator?.isReady()}")
        } else {
            isActive = false
            stopTicker()
            Log.i(TAG, "Deactivated DR mode")
        }
    }

    // ── Internal ticker ─────────────────────────────────────────────────────

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isActive) { tickerRunning = false; return }

            val fix = lastRealFix
            if (fix != null) {
                val nowMs     = SystemClock.elapsedRealtime()
                val dtSeconds = (nowMs - lastTickTimeMs) / 1000.0
                lastTickTimeMs = nowMs

                // ── Dynamic Heading Update (Gyroscope Yaw Integration) ─────
                val unbiasedYawRate = (currentYawRateRads - gyroBiasZ).toDouble()
                // Deadband on minute sensor noise below 0.005 rad/s (~0.28 deg/s)
                if (Math.abs(unbiasedYawRate) > 0.005 && dtSeconds > 0) {
                    val deltaHeadingDeg = Math.toDegrees(unbiasedYawRate * dtSeconds)
                    reckonedHeadingDeg = (reckonedHeadingDeg + deltaHeadingDeg + 360.0) % 360.0
                }

                // ── Select speed source ──────────────────────────────────
                val ml = mlEstimator
                val speedMps: Double = if (ml != null && ml.isReady()) {
                    val s = ml.predictSpeedMps()?.toDouble() ?: fix.speedMps.toDouble()
                    s
                } else {
                    fix.speedMps.toDouble()
                }

                // ── Integrate one step with dynamic heading ──────────────
                val (newLat, newLon) = stepReckoning(
                    lat        = reckonedLat,
                    lon        = reckonedLon,
                    speedMps   = speedMps,
                    bearingDeg = reckonedHeadingDeg,
                    dtSeconds  = dtSeconds
                )
                reckonedLat = newLat
                reckonedLon = newLon

                onProjectedPosition?.invoke(reckonedLat, reckonedLon)
            }

            if (isActive) handler.postDelayed(this, Constants.TUNNEL_SIM_TICK_MS)
            else tickerRunning = false
        }
    }

    /**
     * Single equirectangular dead-reckoning step.
     * Stationary deadband: speedMps < 0.35 m/s is treated as zero (no position change).
     */
    private fun stepReckoning(
        lat: Double, lon: Double,
        speedMps: Double, bearingDeg: Double,
        dtSeconds: Double
    ): Pair<Double, Double> {
        val effectiveSpeed = if (speedMps < 0.35) 0.0 else speedMps
        if (effectiveSpeed == 0.0 || dtSeconds <= 0.0) return Pair(lat, lon)

        val distM      = effectiveSpeed * dtSeconds
        val bearingRad = Math.toRadians(bearingDeg)
        val latRad     = Math.toRadians(lat)
        val R          = Constants.EARTH_RADIUS_M

        val deltaLat = (distM * Math.cos(bearingRad)) / R
        val deltaLon = (distM * Math.sin(bearingRad)) / (R * Math.cos(latRad))

        return Pair(
            lat + Math.toDegrees(deltaLat),
            lon + Math.toDegrees(deltaLon)
        )
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        handler.post(tickRunnable)
    }

    private fun stopTicker() {
        tickerRunning = false
        handler.removeCallbacks(tickRunnable)
    }
}
