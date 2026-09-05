package com.sih.deadreckoninglite.util

import android.os.SystemClock

/**
 * Time-related utility functions for the Dead Reckoning Lite app.
 *
 * ## Clock Domain
 * All functions in this file operate on the [SystemClock.elapsedRealtimeNanos]
 * clock domain — the same clock used by:
 * - [android.hardware.SensorEvent.timestamp] (ImuManager)
 * - [android.location.Location.getElapsedRealtimeNanos] (GpsProvider)
 *
 * This ensures all timestamps across the app are directly comparable
 * without clock-domain conversion.
 *
 * ## Who Uses This
 * - Member 1 (TunnelSimulator): [elapsedSecondsSince] to compute how long
 *   the tunnel simulation has been active since the last real GPS fix
 * - Member 3 (MainActivity): [nowNanos] for general timing
 * - Member 4 (SensorLogger): [nanosToMillis] for human-readable log timestamps
 * - Member 5 (TelemetryOverlay): [formatDurationSince] for on-screen timer display
 */
object TimeUtils {

    // ================================================================== //
    //  Core timestamp access                                              //
    // ================================================================== //

    /**
     * Current monotonic time in nanoseconds.
     *
     * Uses [SystemClock.elapsedRealtimeNanos] — the same clock that
     * [android.hardware.SensorEvent.timestamp] and
     * [android.location.Location.getElapsedRealtimeNanos] use.
     *
     * Prefer this over [System.nanoTime] or [System.currentTimeMillis]
     * to stay in the same clock domain as sensor and GPS timestamps.
     *
     * This clock is:
     * - Monotonic (never goes backward)
     * - Includes time spent in deep sleep (unlike uptimeMillis)
     * - Independent of wall-clock changes (user changing time, NTP sync)
     */
    fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()

    // ================================================================== //
    //  Unit conversions                                                   //
    // ================================================================== //

    /** Nanoseconds per millisecond. */
    const val NANOS_PER_MILLI: Long = 1_000_000L

    /** Nanoseconds per second. */
    const val NANOS_PER_SECOND: Long = 1_000_000_000L

    /**
     * Convert nanoseconds to milliseconds.
     *
     * Useful for Member 4 (SensorLogger) to produce human-readable timestamps
     * in log metadata, and for Member 1 (TunnelSimulator) when computing
     * intervals against [Constants.TUNNEL_SIM_TICK_MS].
     */
    fun nanosToMillis(nanos: Long): Long = nanos / NANOS_PER_MILLI

    /**
     * Convert nanoseconds to seconds as a [Double].
     *
     * Primary use case: Member 1 (TunnelSimulator) needs to pass
     * `elapsedSeconds: Double` to [ConstantVelocityReckoner.project].
     *
     * Example:
     * ```kotlin
     * val elapsed = TimeUtils.nanosToSeconds(nowNanos() - lastFixTimestampNs)
     * val (lat, lon) = reckoner.project(lastFix, elapsed)
     * ```
     */
    fun nanosToSeconds(nanos: Long): Double = nanos.toDouble() / NANOS_PER_SECOND

    /**
     * Convert milliseconds to nanoseconds.
     *
     * Useful when comparing a millisecond-based constant (like
     * [Constants.GPS_UPDATE_INTERVAL_MS]) against nanosecond timestamps.
     */
    fun millisToNanos(millis: Long): Long = millis * NANOS_PER_MILLI

    // ================================================================== //
    //  Elapsed time helpers                                               //
    // ================================================================== //

    /**
     * Compute elapsed time in seconds since [startNanos].
     *
     * This is the primary helper for Member 1 (TunnelSimulator):
     * ```kotlin
     * val elapsedSec = TimeUtils.elapsedSecondsSince(lastRealFix.timestampNs)
     * val (lat, lon) = reckoner.project(lastRealFix, elapsedSec)
     * ```
     *
     * @param startNanos A timestamp in the [SystemClock.elapsedRealtimeNanos] domain.
     * @return Elapsed time in seconds as a [Double]. Always ≥ 0 (clamped).
     */
    fun elapsedSecondsSince(startNanos: Long): Double {
        val delta = nowNanos() - startNanos
        // Clamp to zero — negative delta can happen if startNanos is from a
        // slightly future sensor event (rare but theoretically possible on
        // some hardware due to sensor timestamp jitter).
        return if (delta < 0) 0.0 else nanosToSeconds(delta)
    }

    /**
     * Compute elapsed time in milliseconds since [startNanos].
     *
     * @param startNanos A timestamp in the [SystemClock.elapsedRealtimeNanos] domain.
     * @return Elapsed time in milliseconds. Always ≥ 0 (clamped).
     */
    fun elapsedMillisSince(startNanos: Long): Long {
        val delta = nowNanos() - startNanos
        return if (delta < 0) 0L else nanosToMillis(delta)
    }

    // ================================================================== //
    //  Formatting helpers (for UI / logging)                              //
    // ================================================================== //

    /**
     * Format elapsed time since [startNanos] as a human-readable string.
     *
     * Examples:
     * - `"0.3s"` (sub-second)
     * - `"5.2s"` (seconds)
     * - `"1m 23s"` (minutes + seconds)
     * - `"1h 05m"` (hours + minutes)
     *
     * Used by Member 5 (TelemetryOverlay) for on-screen display of how long
     * the tunnel simulation has been active.
     *
     * @param startNanos A timestamp in the [SystemClock.elapsedRealtimeNanos] domain.
     */
    fun formatDurationSince(startNanos: Long): String {
        val totalSeconds = elapsedSecondsSince(startNanos)
        return formatSeconds(totalSeconds)
    }

    /**
     * Format a duration given in seconds as a human-readable string.
     *
     * @param seconds Duration in seconds (can be fractional).
     */
    fun formatSeconds(seconds: Double): String {
        return when {
            seconds < 1.0 -> "%.1fs".format(seconds)
            seconds < 60.0 -> "%.1fs".format(seconds)
            seconds < 3600.0 -> {
                val m = (seconds / 60).toInt()
                val s = (seconds % 60).toInt()
                "${m}m ${s}s"
            }
            else -> {
                val h = (seconds / 3600).toInt()
                val m = ((seconds % 3600) / 60).toInt()
                "${h}h %02dm".format(m)
            }
        }
    }

    /**
     * Format a nanosecond timestamp as a relative offset from a reference point,
     * useful for CSV metadata headers or debug logs.
     *
     * Example: `"+003.245s"` (3.245 seconds after reference)
     *
     * @param timestampNs The timestamp to format.
     * @param referenceNs The reference point (typically session start time).
     */
    fun formatRelativeTimestamp(timestampNs: Long, referenceNs: Long): String {
        val deltaSec = nanosToSeconds(timestampNs - referenceNs)
        return "%+010.3fs".format(deltaSec)
    }
}
