package com.sih.deadreckoninglite.sensors

/**
 * Merged IMU sample containing one accelerometer + one gyroscope reading,
 * timestamped to the sensor event's own hardware clock.
 *
 * ## Units (match the unified CSV schema in PRD §10.1)
 *   - ax, ay, az  — accelerometer, m/s²
 *   - gx, gy, gz  — gyroscope, rad/s
 *   - timestampNs  — nanoseconds, using [android.os.SystemClock.elapsedRealtimeNanos]
 *                     clock domain (same as [android.hardware.SensorEvent.timestamp]
 *                     on API 17+). This is the same clock domain used by
 *                     [com.sih.deadreckoninglite.location.GpsSample.timestampNs]
 *                     so the two streams are directly comparable.
 *
 * ## Thread Safety
 * This class is immutable — safe to share across threads without synchronization.
 *
 * ## Downstream Consumers
 * - Member 4 (SensorLogger): uses [toCsvValues] and [CSV_HEADER]
 * - Member 5 (MainViewModel/TelemetryOverlay): uses field values for UI display
 * - Member 3 (MainActivity): routes instances to the above consumers
 */
data class SensorSample(
    val timestampNs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float
) {

    /**
     * Human-readable string for Logcat debugging.
     * Rounds to 3 decimal places so values are scannable at a glance.
     *
     * Example output:
     * `IMU[ts=128394839483] A(0.120, -0.030, 9.810) G(0.001, -0.002, 0.000)`
     */
    fun toLogString(): String =
        "IMU[ts=$timestampNs] A(${f(ax)}, ${f(ay)}, ${f(az)}) G(${f(gx)}, ${f(gy)}, ${f(gz)})"

    /**
     * CSV-ready value string matching the IMU columns of the unified schema (PRD §10.1).
     *
     * Column order: `ax,ay,az,gx,gy,gz`
     *
     * Precision: 6 decimal places — enough for raw sensor resolution without
     * introducing rounding artifacts that would degrade future ML training data.
     *
     * **Usage by Member 4 (SensorLogger):** call this instead of hand-formatting
     * the fields, so column ordering has a single source of truth.
     */
    fun toCsvValues(): String =
        "${f6(ax)},${f6(ay)},${f6(az)},${f6(gx)},${f6(gy)},${f6(gz)}"

    companion object {
        /**
         * CSV header for the IMU portion of the unified schema.
         * Member 4 (SensorLogger) uses this when writing the CSV header row.
         */
        const val CSV_HEADER = "ax,ay,az,gx,gy,gz"

        /** 3-decimal-place formatting for log readability. */
        private fun f(v: Float): String = "%.3f".format(v)

        /** 6-decimal-place formatting for CSV precision. */
        private fun f6(v: Float): String = "%.6f".format(v)
    }
}
