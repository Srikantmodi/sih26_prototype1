package com.sih.deadreckoninglite.deadreckoning

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.sih.deadreckoninglite.location.GpsSample
import com.sih.deadreckoninglite.util.Constants

/**
 * Simulated-tunnel mode controller (PRD §4.2 item 5).
 *
 * Manages the "Simulate Tunnel" toggle state and, while active, drives
 * periodic constant-velocity projections via [ConstantVelocityReckoner].
 *
 * ## Role in the Architecture
 * This is the decision-point for "which position source is currently
 * authoritative." It does NOT directly call MapController, MainViewModel,
 * or any UI component — it exposes projected positions through a callback
 * that [MainActivity] (the composition root) sets and routes onward.
 *
 * ## Data Flow
 * ```
 * GpsProvider → MainActivity → onRealGpsSample() → stores lastRealFix
 *                                                    (always, even while active)
 *
 * setActive(true) → starts internal ~1 Hz ticker
 *   ticker fires → reckoner.project(lastRealFix, elapsedSeconds)
 *                 → onProjectedPosition callback → MainActivity → MapController
 *
 * setActive(false) → stops ticker, clears elapsed-time anchor
 * ```
 *
 * ## What This Is NOT
 * Not automatic GNSS-quality detection — the toggle is a manual UI switch.
 * Not sensor fusion — the reckoner ignores accelerometer/gyroscope data.
 * Not a Kalman filter — it's a simple boolean mode switch with instant snap.
 */
class TunnelSimulator {

    // ---- Internal components ----

    /** The pure-math position projector (PRD §9.1). */
    private val reckoner = ConstantVelocityReckoner()

    /** Main-thread handler for the ~1 Hz projection ticker. */
    private val handler = Handler(Looper.getMainLooper())

    // ---- State ----

    /**
     * Whether tunnel simulation mode is currently active.
     * When true, MainActivity should NOT route real GPS fixes to the map —
     * instead, projected positions from [onProjectedPosition] are authoritative.
     */
    var isActive: Boolean = false
        private set

    /**
     * The most recent real GPS fix received via [onRealGpsSample].
     * Updated continuously regardless of [isActive] state, so the reckoner
     * always has the freshest possible origin if the toggle is flipped.
     */
    private var lastRealFix: GpsSample? = null

    /**
     * Monotonic timestamp (via [SystemClock.elapsedRealtime]) when the
     * current tunnel simulation was activated. Used to compute elapsed
     * seconds for [ConstantVelocityReckoner.project].
     *
     * Reset on each [setActive]`(true)` so re-toggling starts a fresh
     * elapsed-time interval.
     */
    private var tunnelStartTimeMs: Long = 0L

    /**
     * Guard flag to prevent duplicate tickers from accumulating if
     * [setActive]`(true)` is called multiple times without an intervening
     * [setActive]`(false)`.
     */
    private var tickerRunning: Boolean = false

    // ---- Output callback ----

    /**
     * Callback invoked on each ticker fire with the projected (lat, lon).
     *
     * **Set by MainActivity** (the composition root) before the first
     * [setActive] call. MainActivity routes this output to MapController
     * and MainViewModel as needed.
     *
     * This indirection ensures TunnelSimulator has zero knowledge of
     * MapController, MainViewModel, or any UI component.
     */
    var onProjectedPosition: ((lat: Double, lon: Double) -> Unit)? = null

    // ---- Public API (frozen signatures from PRD §9) ----

    /**
     * Feed a real GPS sample into the simulator.
     *
     * Must be called by MainActivity for every GPS fix, regardless of
     * whether tunnel mode is active. While active, the fix is stored
     * (so the reckoner's origin stays current if re-toggled) but does
     * NOT affect the displayed position — the projected position is
     * authoritative.
     */
    fun onRealGpsSample(sample: GpsSample) {
        lastRealFix = sample
    }

    /**
     * Activate or deactivate simulated-tunnel mode.
     *
     * **setActive(true):**
     * - Records the current time as the tunnel start anchor.
     * - Starts a ~1 Hz ticker that calls [ConstantVelocityReckoner.project]
     *   using [lastRealFix] and the growing elapsed time, then invokes
     *   [onProjectedPosition] with the result.
     * - If no real GPS fix has been received yet, the ticker will fire but
     *   skip projection (no-op) until a fix arrives.
     * - If already active, this is a no-op (no duplicate tickers).
     *
     * **setActive(false):**
     * - Stops the ticker immediately.
     * - Clears the elapsed-time anchor so the next activation starts fresh.
     * - Safe to call multiple times or while already inactive.
     */
    fun setActive(active: Boolean) {
        if (active) {
            if (isActive && tickerRunning) {
                // Already active with a running ticker — no-op to prevent duplicates
                return
            }
            isActive = true
            tunnelStartTimeMs = SystemClock.elapsedRealtime()
            startTicker()
        } else {
            isActive = false
            stopTicker()
        }
    }

    // ---- Internal ticker ----

    /**
     * The ticker Runnable. On each fire (~1 Hz):
     * 1. Checks that the simulator is still active.
     * 2. Checks that a last real GPS fix exists.
     * 3. Computes elapsed seconds since tunnel activation.
     * 4. Calls reckoner.project(lastRealFix, elapsedSeconds).
     * 5. Invokes onProjectedPosition with the result.
     * 6. Reschedules itself for the next tick.
     */
    private val tickRunnable = object : Runnable {
        override fun run() {
            // 1. Bail if deactivated between scheduling and execution
            if (!isActive) {
                tickerRunning = false
                return
            }

            // 2. Project only if we have a real fix to project from
            val fix = lastRealFix
            if (fix != null) {
                // 3. Growing elapsed time from tunnel activation (not from previous tick)
                val elapsedMs = SystemClock.elapsedRealtime() - tunnelStartTimeMs
                val elapsedSeconds = elapsedMs / 1000.0

                // 4. Constant-velocity projection
                val (projLat, projLon) = reckoner.project(fix, elapsedSeconds)

                // 5. Deliver to MainActivity via callback
                onProjectedPosition?.invoke(projLat, projLon)
            }

            // 6. Schedule next tick (only if still active)
            if (isActive) {
                handler.postDelayed(this, Constants.TUNNEL_SIM_TICK_MS)
            } else {
                tickerRunning = false
            }
        }
    }

    /**
     * Starts the ~1 Hz ticker. Safe to call multiple times —
     * will not create duplicate callbacks.
     */
    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        // Fire the first tick immediately so the position updates right away
        handler.post(tickRunnable)
    }

    /**
     * Stops the ticker and removes any pending callbacks.
     * Safe to call multiple times or while already stopped.
     */
    private fun stopTicker() {
        tickerRunning = false
        handler.removeCallbacks(tickRunnable)
    }
}
