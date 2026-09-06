package com.sih.deadreckoninglite.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.sih.deadreckoninglite.util.Constants

/**
 * Manages accelerometer + gyroscope + gravity sensor registration.
 *
 * ## Design Contract (PRD §4.2 point 2, §9)
 * - ONLY file that touches Android SensorManager.
 * - Emits merged [SensorSample] objects via callback.
 * - Isolation: swap internals without touching any other file.
 *
 * ## What changed vs. prototype
 * Now also captures TYPE_GRAVITY sensor, which provides the gravity vector
 * needed to compute linear (motion) acceleration for the ML model:
 *   linear_a = raw_accelerometer - gravity
 * Without this subtraction, the 9.8 m/s² constant gravity dominates the
 * accelerometer signal and would make the CNN+LSTM speed model inaccurate.
 *
 * ## Merging Strategy
 * Emission trigger: accelerometer tick (~50 Hz at SENSOR_DELAY_GAME).
 * Gyroscope and gravity readings are cached and picked up on the next
 * accelerometer event. Output rate stays at ~50 Hz while each sample
 * includes the most recent gyro and gravity readings.
 *
 * ## Threading
 * Sensor callbacks arrive on a SensorManager-internal thread (NOT the main thread).
 * Snapshot references are replaced atomically — JVM guarantees atomic
 * object-reference writes, eliminating torn-reads without heavyweight locking.
 *
 * @param context Application or Activity context (used only to obtain SensorManager).
 */
class ImuManager(context: Context) {

    companion object {
        private const val TAG = "ImuManager"
    }

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gyroSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val gravitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)   // NEW for ML

    // ------------------------------------------------------------------ //
    //  Immutable axis-triple snapshots (atomic reference replacement)     //
    // ------------------------------------------------------------------ //

    /**
     * Immutable 3-axis reading. JVM guarantees that reference assignment is
     * atomic — replacing latestAccel/latestGyro/latestGravity with a new instance
     * is safe across threads without synchronization.
     */
    private data class AxisSnapshot(val x: Float, val y: Float, val z: Float)

    @Volatile private var latestAccel: AxisSnapshot? = null   // null = no reading yet
    @Volatile private var latestGyro: AxisSnapshot? = null    // null = no reading yet
    @Volatile private var latestGravity: AxisSnapshot? = null // null = no reading yet (NEW)

    // ------------------------------------------------------------------ //
    //  State tracking                                                     //
    // ------------------------------------------------------------------ //

    private var callback: ((SensorSample) -> Unit)? = null

    /** True while listeners are registered. Prevents double-registration. */
    @Volatile private var isRunning: Boolean = false

    /** Whether the device has an accelerometer. */
    val hasAccelerometer: Boolean get() = accelSensor != null

    /** Whether the device has a gyroscope. */
    val hasGyroscope: Boolean get() = gyroSensor != null

    /** Whether the device has a TYPE_GRAVITY sensor (most modern phones do). */
    val hasGravitySensor: Boolean get() = gravitySensor != null

    // ------------------------------------------------------------------ //
    //  Sensor listener                                                    //
    // ------------------------------------------------------------------ //

    private val sensorListener = object : SensorEventListener {

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    // Replace the entire snapshot atomically — no torn reads.
                    latestAccel = AxisSnapshot(event.values[0], event.values[1], event.values[2])
                    // Emit ONLY on accelerometer ticks (primary emission trigger).
                    emitIfReady(event.timestamp)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    // Just cache — picked up on next accelerometer tick.
                    latestGyro = AxisSnapshot(event.values[0], event.values[1], event.values[2])
                }
                Sensor.TYPE_GRAVITY -> {
                    // NEW: cache gravity vector — picked up on next accelerometer tick.
                    latestGravity = AxisSnapshot(event.values[0], event.values[1], event.values[2])
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d(TAG, "Accuracy changed: sensor=${sensor?.name}, accuracy=$accuracy")
        }
    }

    /**
     * Emit a merged [SensorSample] when both accelerometer and gyroscope have reported.
     *
     * Gravity snapshot is included when available. If the device lacks TYPE_GRAVITY,
     * the SensorSample default (gravZ=9.80665f) acts as a reasonable fallback so
     * linearAz will be near-zero rather than near-9.8 at rest.
     */
    private fun emitIfReady(timestampNs: Long) {
        val accel = latestAccel ?: return    // no accel reading yet
        val gyro  = latestGyro  ?: return    // no gyro reading yet — wait for it
        val grav  = latestGravity            // nullable — some devices lack TYPE_GRAVITY

        val sample = SensorSample(
            timestampNs = timestampNs,
            ax = accel.x, ay = accel.y, az = accel.z,
            gx = gyro.x,  gy = gyro.y,  gz = gyro.z,
            // Gravity: use real TYPE_GRAVITY reading if available; standard-gravity fallback if not.
            gravX = grav?.x ?: 0f,
            gravY = grav?.y ?: 0f,
            gravZ = grav?.z ?: 9.80665f
        )

        // Exception-safe callback invocation: a downstream failure (e.g., MlSpeedEstimator
        // TFLite error) must NOT kill the SensorManager delivery thread, which would
        // permanently stop all future sensor events.
        try {
            callback?.invoke(sample)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in sensor callback — swallowed to protect " +
                    "SensorManager delivery thread", e)
        }
    }

    // ------------------------------------------------------------------ //
    //  Public API (PRD §9 contract — signatures unchanged)                //
    // ------------------------------------------------------------------ //

    /**
     * Begin continuous sensor sampling.
     *
     * Registers accelerometer + gyroscope + TYPE_GRAVITY (if available).
     * Double-start guard: if already running, stops cleanly before re-registering
     * (handles Activity config changes where onCreate fires without prior onDestroy).
     *
     * @param callback Receives a merged [SensorSample] on each accelerometer tick
     *                 (~50 Hz at SENSOR_DELAY_GAME). Called on the sensor-delivery
     *                 thread (NOT the main thread).
     *                 MainActivity routes samples to: SensorLogger, MlSpeedEstimator, MainViewModel.
     */
    fun start(callback: (SensorSample) -> Unit) {
        if (isRunning) {
            Log.w(TAG, "start() called while already running — stopping previous session first")
            stop()
        }

        this.callback = callback

        // Reset cached snapshots — prevents emitting stale data from a previous session.
        latestAccel   = null
        latestGyro    = null
        latestGravity = null

        // Register accelerometer (primary emission trigger).
        if (accelSensor != null) {
            sensorManager.registerListener(sensorListener, accelSensor, Constants.IMU_SENSOR_DELAY)
            Log.i(TAG, "Accelerometer registered: ${accelSensor.name}")
        } else {
            Log.w(TAG, "⚠ No accelerometer found — IMU samples will NOT be emitted")
        }

        // Register gyroscope (cached, read on accel ticks).
        if (gyroSensor != null) {
            sensorManager.registerListener(sensorListener, gyroSensor, Constants.IMU_SENSOR_DELAY)
            Log.i(TAG, "Gyroscope registered: ${gyroSensor.name}")
        } else {
            Log.w(TAG, "⚠ No gyroscope found — merged samples will never pass the both-sensors gate")
        }

        // Register gravity sensor (NEW — required for ML linear acceleration computation).
        if (gravitySensor != null) {
            sensorManager.registerListener(sensorListener, gravitySensor, Constants.IMU_SENSOR_DELAY)
            Log.i(TAG, "Gravity sensor registered: ${gravitySensor.name}")
        } else {
            Log.w(TAG, "⚠ No TYPE_GRAVITY sensor — using fallback gravity vector " +
                    "(gravX=0, gravY=0, gravZ=9.80665). linearAz will be approximate.")
        }

        isRunning = true
        Log.i(TAG, "Started — hasAccel=$hasAccelerometer, hasGyro=$hasGyroscope, " +
                "hasGravity=$hasGravitySensor")
    }

    /**
     * Stop all sensor listeners and clear the callback reference.
     * Safe to call multiple times, or before [start] has ever been called.
     */
    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(sensorListener)
        callback = null
        isRunning = false
        Log.i(TAG, "Stopped")
    }
}
