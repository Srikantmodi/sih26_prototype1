package com.sih.deadreckoninglite.sensors

/**
 * Merged IMU sample: accelerometer + gyroscope + gravity sensor readings.
 *
 * ## CSV Schema (PRD §10.1 — extended for ML training)
 * `ax,ay,az,gx,gy,gz,grav_x,grav_y,grav_z`
 *
 * ## Linear Acceleration (for ML model input)
 * `linear_ax = ax - gravX` (same for Y, Z)
 * Removes the 9.8 m/s² gravity component that would otherwise saturate
 * the CNN+LSTM model input. Android TYPE_GRAVITY sensor provides gravX/Y/Z.
 *
 * ## Units
 *   - ax, ay, az          — raw accelerometer, m/s² (includes gravity)
 *   - gx, gy, gz          — gyroscope, rad/s
 *   - gravX, gravY, gravZ — gravity vector from TYPE_GRAVITY sensor, m/s²
 *   - timestampNs         — nanoseconds, elapsedRealtimeNanos clock domain
 *
 * ## Thread Safety
 * Immutable data class — safe to share across threads without synchronization.
 *
 * ## Downstream Consumers
 * - SensorLogger: uses toCsvValues() and CSV_HEADER
 * - MlSpeedEstimator: uses linearAx, linearAy, linearAz, gx, gy, gz
 * - MainViewModel/TelemetryOverlay: uses field values for UI display
 * - MainActivity: routes instances to all of the above
 */
data class SensorSample(
    val timestampNs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    // Gravity vector from Android TYPE_GRAVITY sensor.
    // Default: 0f for X/Y, 9.80665f for Z (standard gravity fallback on devices
    // that lack TYPE_GRAVITY, so that linearAz still approximates real linear accel).
    val gravX: Float = 0f,
    val gravY: Float = 0f,
    val gravZ: Float = 9.80665f
) {
    /**
     * Linear (motion) acceleration with gravity removed.
     * This is the ML model's primary input signal — gravity-free motion acceleration.
     * Required by MlSpeedEstimator.addSample().
     */
    val linearAx: Float get() = ax - gravX
    val linearAy: Float get() = ay - gravY
    val linearAz: Float get() = az - gravZ

    /**
     * Human-readable string for Logcat debugging.
     * Shows raw accel, gyro, gravity, and computed linear accel.
     *
     * Example output:
     * `IMU[ts=128394839483] A(0.120, -0.030, 9.810) G(0.001, -0.002, 0.000)
     *  Grav(0.000, 0.000, 9.810) Lin(0.120, -0.030, 0.000)`
     */
    fun toLogString(): String =
        "IMU[ts=$timestampNs] A(${f(ax)}, ${f(ay)}, ${f(az)}) " +
        "G(${f(gx)}, ${f(gy)}, ${f(gz)}) " +
        "Grav(${f(gravX)}, ${f(gravY)}, ${f(gravZ)}) " +
        "Lin(${f(linearAx)}, ${f(linearAy)}, ${f(linearAz)})"

    /**
     * CSV-ready value string matching the IMU columns of the unified schema.
     *
     * Column order: `ax,ay,az,gx,gy,gz,grav_x,grav_y,grav_z`
     * (9 IMU columns total — 3 more than the prototype's 6)
     *
     * Usage by SensorLogger: call this instead of hand-formatting fields,
     * so column ordering has a single source of truth.
     */
    fun toCsvValues(): String =
        "${f6(ax)},${f6(ay)},${f6(az)}," +
        "${f6(gx)},${f6(gy)},${f6(gz)}," +
        "${f6(gravX)},${f6(gravY)},${f6(gravZ)}"

    companion object {
        /**
         * CSV header for the IMU portion of the unified schema.
         * SensorLogger uses this when writing the CSV header row.
         * GpsSample.UNIFIED_CSV_HEADER references this constant.
         */
        const val CSV_HEADER = "ax,ay,az,gx,gy,gz,grav_x,grav_y,grav_z"

        /** 3-decimal-place formatting for log readability. */
        private fun f(v: Float): String  = "%.3f".format(v)

        /** 6-decimal-place formatting for CSV precision. */
        private fun f6(v: Float): String = "%.6f".format(v)
    }
}
