package com.sih.deadreckoninglite.sensors

import com.sih.deadreckoninglite.util.Constants
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Filtered and calibrated sensor sample produced by [SensorFilter].
 */
data class FilteredSensorData(
    val timestampNs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val isStationary: Boolean
)

/**
 * Digital signal processing filter and stationary calibration pipeline for IMU data.
 *
 * ## Features
 * 1. **Low-Pass Filter (EMA):** Eliminates MEMS thermal jitter and high-frequency vibration.
 * 2. **Zero-Velocity Update (ZUPT):** Detects when device/vehicle is static via acceleration variance.
 * 3. **Online Gyroscope Bias Learning:** Automatically tracks and removes DC offsets ($gz \approx -0.05$ rad/s).
 * 4. **Stationary Clamping:** Enforces strictly `0.00 rad/s` rotation when stationary.
 * 5. **Yaw Rate Deadband:** Prevents straight-line driving from slowly curling into circles due to sub-threshold bias.
 */
class SensorFilter {

    companion object {
        private const val ALPHA_ACCEL = 0.20f
        private const val ALPHA_GYRO = 0.15f
        private const val GRAVITY = 9.80665f
        private const val YAW_DEADBAND_RAD_S = 0.015f // ~0.86 deg/s
        private const val WINDOW_SIZE = 40 // ~0.8s window at 50 Hz
    }

    private var filtAx = 0f
    private var filtAy = 0f
    private var filtAz = GRAVITY

    private var filtGx = 0f
    private var filtGy = 0f
    private var filtGz = 0f

    // Online estimated gyroscope biases
    var biasGx = 0f; private set
    var biasGy = 0f; private set
    var biasGz = 0f; private set

    // Sliding window buffer for variance estimation
    private val accelHistory = FloatArray(WINDOW_SIZE)
    private var historyIndex = 0
    private var historyCount = 0

    var isStationary: Boolean = true
        private set

    /**
     * Process a raw [SensorSample], returning clean smoothed values.
     */
    fun filter(raw: SensorSample): FilteredSensorData {
        // 1. Single-pole IIR / EMA filter
        filtAx = ALPHA_ACCEL * raw.ax + (1f - ALPHA_ACCEL) * filtAx
        filtAy = ALPHA_ACCEL * raw.ay + (1f - ALPHA_ACCEL) * filtAy
        filtAz = ALPHA_ACCEL * raw.az + (1f - ALPHA_ACCEL) * filtAz

        filtGx = ALPHA_GYRO * raw.gx + (1f - ALPHA_GYRO) * filtGx
        filtGy = ALPHA_GYRO * raw.gy + (1f - ALPHA_GYRO) * filtGy
        filtGz = ALPHA_GYRO * raw.gz + (1f - ALPHA_GYRO) * filtGz

        // 2. Compute magnitude and sliding window variance
        val accelMag = sqrt(filtAx * filtAx + filtAy * filtAy + filtAz * filtAz)
        accelHistory[historyIndex] = accelMag
        historyIndex = (historyIndex + 1) % WINDOW_SIZE
        if (historyCount < WINDOW_SIZE) historyCount++

        var sum = 0f
        for (i in 0 until historyCount) sum += accelHistory[i]
        val mean = sum / historyCount

        var varSum = 0f
        for (i in 0 until historyCount) {
            val d = accelHistory[i] - mean
            varSum += d * d
        }
        val variance = varSum / historyCount

        val gyroMag = sqrt(filtGx * filtGx + filtGy * filtGy + filtGz * filtGz)

        // 3. Stationary Detection (ZUPT)
        val lowVariance = variance < Constants.ZUPT_ACCEL_VAR_THRESHOLD
        val gravityMatch = abs(accelMag - GRAVITY) < 0.35f
        val lowRotation = gyroMag < Constants.ZUPT_GYRO_MAG_THRESHOLD

        isStationary = lowVariance && gravityMatch && lowRotation

        // 4. Gyroscope bias update and stationary output
        if (isStationary) {
            // Slowly accumulate stationary bias
            biasGx = 0.02f * filtGx + 0.98f * biasGx
            biasGy = 0.02f * filtGy + 0.98f * biasGy
            biasGz = 0.02f * filtGz + 0.98f * biasGz

            // Return strictly zeroed rotation and stationary flag
            return FilteredSensorData(
                timestampNs = raw.timestampNs,
                ax = filtAx,
                ay = filtAy,
                az = filtAz,
                gx = 0.00f,
                gy = 0.00f,
                gz = 0.00f,
                isStationary = true
            )
        }

        // 5. Dynamic motion: subtract calibrated bias
        val cleanGx = filtGx - biasGx
        val cleanGy = filtGy - biasGy
        val cleanGz = filtGz - biasGz

        // Apply yaw rate deadband to prevent straight-line driving from curving into circles
        val deadbandGz = if (abs(cleanGz) < YAW_DEADBAND_RAD_S) 0.00f else cleanGz

        return FilteredSensorData(
            timestampNs = raw.timestampNs,
            ax = filtAx,
            ay = filtAy,
            az = filtAz,
            gx = cleanGx,
            gy = cleanGy,
            gz = deadbandGz,
            isStationary = false
        )
    }
}
