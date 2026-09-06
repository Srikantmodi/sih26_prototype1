package com.sih.deadreckoninglite.sensors

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Auto-calibrates phone mounting orientation and estimates sensor zero-rate bias.
 *
 * ## Problem Statement Requirement (SIH PS-26168)
 * "Auto-calibration of smartphone sensors to estimate mounting angles and
 * compensate for vehicle dynamics."
 *
 * ## Strategy
 * 1. Collects stationary samples (when vehicle is stopped or at initial app start).
 * 2. Uses the gravity vector [gravX, gravY, gravZ] to compute Pitch and Roll mounting angles.
 * 3. Builds a 3x3 rotation matrix R_vb to transform phone-frame IMU data
 *    into vehicle body-frame (forward, lateral, vertical).
 * 4. Computes stationary gyroscope zero-rate bias (gyroBiasZ) to prevent heading drift.
 */
class OrientationCalibrator {

    companion object {
        private const val REQUIRED_SAMPLES = 50   // ~1 second at 50 Hz
        private const val STILL_VAR_THRESHOLD = 0.15f // m/s^2 max variance for stillness
    }

    private var sampleCount = 0
    private var sumGravX = 0.0
    private var sumGravY = 0.0
    private var sumGravZ = 0.0
    private var sumGz = 0.0

    // Calibration outputs
    var isCalibrated: Boolean = false
        private set

    var pitchRad: Double = 0.0
        private set
    var rollRad: Double = 0.0
        private set
    var gyroBiasZ: Float = 0.0f
        private set

    // 3x3 Rotation matrix (flattened row-major)
    // Transforms [x, y, z]_phone -> [lateral, forward, vertical]_vehicle
    private val rMatrix = DoubleArray(9) { if (it % 4 == 0) 1.0 else 0.0 }

    /**
     * Feed an incoming SensorSample to the calibrator.
     * When enough stationary samples are gathered, computes orientation angles and matrix.
     */
    fun addSample(sample: SensorSample) {
        if (isCalibrated) return

        sumGravX += sample.gravX
        sumGravY += sample.gravY
        sumGravZ += sample.gravZ
        sumGz += sample.gz
        sampleCount++

        if (sampleCount >= REQUIRED_SAMPLES) {
            val avgGx = sumGravX / sampleCount
            val avgGy = sumGravY / sampleCount
            val avgGz = sumGravZ / sampleCount

            // Pitch: rotation around X axis (nose up/down)
            pitchRad = atan2(avgGy, sqrt(avgGx * avgGx + avgGz * avgGz))
            // Roll: rotation around Y axis (tilt left/right)
            rollRad = atan2(-avgGx, avgGz)

            gyroBiasZ = (sumGz / sampleCount).toFloat()

            computeRotationMatrix(pitchRad, rollRad)
            isCalibrated = true
        }
    }

    /**
     * Transform raw phone linear acceleration [ax, ay, az] into vehicle body frame:
     * returns Triple(forward_accel, lateral_accel, vertical_accel).
     */
    fun transformToVehicleFrame(ax: Float, ay: Float, az: Float): Triple<Float, Float, Float> {
        if (!isCalibrated) return Triple(ay, ax, az) // Default forward=Y, lateral=X

        val x = ax.toDouble()
        val y = ay.toDouble()
        val z = az.toDouble()

        val vLateral  = rMatrix[0] * x + rMatrix[1] * y + rMatrix[2] * z
        val vForward  = rMatrix[3] * x + rMatrix[4] * y + rMatrix[5] * z
        val vVertical = rMatrix[6] * x + rMatrix[7] * y + rMatrix[8] * z

        return Triple(vForward.toFloat(), vLateral.toFloat(), vVertical.toFloat())
    }

    private fun computeRotationMatrix(pitch: Double, roll: Double) {
        val cp = cos(pitch)
        val sp = sin(pitch)
        val cr = cos(roll)
        val sr = sin(roll)

        // R = R_pitch * R_roll
        rMatrix[0] = cr
        rMatrix[1] = 0.0
        rMatrix[2] = sr

        rMatrix[3] = sp * sr
        rMatrix[4] = cp
        rMatrix[5] = -sp * cr

        rMatrix[6] = -cp * sr
        rMatrix[7] = sp
        rMatrix[8] = cp * cr
    }

    fun reset() {
        sampleCount = 0
        sumGravX = 0.0
        sumGravY = 0.0
        sumGravZ = 0.0
        sumGz = 0.0
        isCalibrated = false
    }
}
