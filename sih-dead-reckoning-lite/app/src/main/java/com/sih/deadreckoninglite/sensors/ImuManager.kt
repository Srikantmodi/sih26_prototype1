package com.sih.deadreckoninglite.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Manages accelerometer + gyroscope registration via Android [SensorManager].
 *
 * ## Design Contract (PRD §4.2 point 2, §9)
 * - This is the **ONLY** file that touches [android.hardware.SensorManager].
 * - It knows nothing about logging, the map, or GPS.
 * - It emits merged [SensorSample] objects via a callback.
 * - Isolation: if the sampling strategy changes later, only this file changes.
 *
 * ## Merging Strategy
 * Accelerometer and gyroscope events arrive on **separate** callbacks at
 * potentially different rates (~50 Hz each at SENSOR_DELAY_GAME).
 *
 * We cache the latest 3-axis reading from each sensor as an **immutable snapshot**
 * (fixing the torn-reads concurrency bug from v1). A merged [SensorSample] is
 * emitted only when the **accelerometer** fires (it's the primary sensor for
 * dead reckoning), using the most recent gyroscope snapshot alongside it.
 * This keeps the output rate at ~50 Hz instead of ~100 Hz, and guarantees
 * that every emitted sample has a fresh accelerometer reading.
 *
 * ## Guards & Safety
 * - **Both-sensors gate:** No sample is emitted until at least one reading has
 *   arrived from BOTH sensors, preventing the "stale zeros" bug.
 * - **Double-start guard:** Calling [start] twice stops the first session cleanly.
 * - **Callback exception safety:** Exceptions in the downstream callback are
 *   caught and logged, preventing them from killing the SensorManager delivery thread.
 * - **Sensor availability:** Missing sensors are logged as warnings; the class
 *   still functions with whatever hardware is present.
 *
 * ## Threading
 * Sensor callbacks arrive on a SensorManager-internal thread (NOT the main thread).
 * Snapshot references are replaced atomically (JVM object-reference writes are atomic),
 * eliminating the torn-reads bug without heavyweight locking.
 *
 * @param context Application or Activity context (used only to obtain [SensorManager]).
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

    // ------------------------------------------------------------------ //
    //  Immutable axis-triple snapshots (atomic reference replacement)     //
    // ------------------------------------------------------------------ //

    /**
     * Immutable 3-axis reading. JVM guarantees that reference assignment is
     * atomic — so replacing [latestAccel] or [latestGyro] with a new instance
     * is safe across threads without synchronization. A reader always sees
     * either the old snapshot or the new one, never a half-written mix.
     */
    private data class AxisSnapshot(val x: Float, val y: Float, val z: Float)

    @Volatile private var latestAccel: AxisSnapshot? = null  // null = no reading yet
    @Volatile private var latestGyro: AxisSnapshot? = null   // null = no reading yet

    // ------------------------------------------------------------------ //
    //  State tracking                                                     //
    // ------------------------------------------------------------------ //

    private var callback: ((SensorSample) -> Unit)? = null

    /** True while listeners are registered. Prevents double-registration. */
    @Volatile private var isRunning: Boolean = false

    /**
     * Whether the device has an accelerometer.
     * Useful for Member 3 (MainActivity) to show a user-facing warning if `false`.
     */
    val hasAccelerometer: Boolean get() = accelSensor != null

    /**
     * Whether the device has a gyroscope.
     * Many budget Android phones lack one — this lets Member 3 surface a warning.
     */
    val hasGyroscope: Boolean get() = gyroSensor != null

    // ------------------------------------------------------------------ //
    //  Sensor listener                                                    //
    // ------------------------------------------------------------------ //

    private val sensorListener = object : SensorEventListener {

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    // Replace the entire snapshot atomically — no torn reads.
                    latestAccel = AxisSnapshot(
                        x = event.values[0],
                        y = event.values[1],
                        z = event.values[2]
                    )

                    // Emit ONLY on accelerometer ticks (primary sensor).
                    // This keeps the output rate at ~50 Hz instead of ~100 Hz
                    // and guarantees every emitted sample has a fresh accel reading.
                    emitIfReady(event.timestamp)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    // Just cache — don't emit. Gyro data is picked up on the
                    // next accelerometer tick.
                    latestGyro = AxisSnapshot(
                        x = event.values[0],
                        y = event.values[1],
                        z = event.values[2]
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Log accuracy changes for debugging — a sudden drop to
            // SENSOR_STATUS_UNRELIABLE during a demo is worth knowing about.
            Log.d(TAG, "Accuracy changed: sensor=${sensor?.name}, accuracy=$accuracy")
        }
    }

    /**
     * Emit a merged [SensorSample] if and only if both sensors have reported
     * at least one reading. This prevents the "stale zeros" bug where the
     * first few samples have `gx=gy=gz=0.0` that's indistinguishable from
     * a real zero-rotation reading.
     */
    private fun emitIfReady(timestampNs: Long) {
        val accel = latestAccel ?: return    // no accel reading yet — shouldn't happen here, but guard anyway
        val gyro = latestGyro ?: return      // no gyro reading yet — wait for it

        val sample = SensorSample(
            timestampNs = timestampNs,
            ax = accel.x, ay = accel.y, az = accel.z,
            gx = gyro.x,  gy = gyro.y,  gz = gyro.z
        )

        // Wrap callback invocation — a downstream exception (e.g., Member 4's
        // logger hitting a file I/O error) must NOT kill the SensorManager
        // delivery thread, which would permanently stop all future sensor events.
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
     * If already running, the previous session is stopped cleanly before
     * re-registering (double-start guard). This handles Activity config
     * changes where `onCreate` may fire again without a prior `onDestroy`.
     *
     * @param callback Receives a merged [SensorSample] on each **accelerometer** tick
     *                 (~50 Hz at SENSOR_DELAY_GAME). Called on the sensor-delivery
     *                 thread (NOT the main thread). The caller (MainActivity) is
     *                 responsible for routing the sample onward to SensorLogger /
     *                 MainViewModel.
     */
    fun start(callback: (SensorSample) -> Unit) {
        // Double-start guard: stop any existing session cleanly.
        if (isRunning) {
            Log.w(TAG, "start() called while already running — stopping previous session first")
            stop()
        }

        this.callback = callback

        // Reset cached snapshots so we go through the both-sensors-received
        // gate again (prevents emitting stale data from a previous session).
        latestAccel = null
        latestGyro = null

        // Register accelerometer (primary emission trigger).
        if (accelSensor != null) {
            sensorManager.registerListener(
                sensorListener,
                accelSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            Log.i(TAG, "Accelerometer registered: ${accelSensor.name}, " +
                    "maxRange=${accelSensor.maximumRange}, " +
                    "resolution=${accelSensor.resolution}")
        } else {
            Log.w(TAG, "⚠ No accelerometer found on this device — " +
                    "IMU samples will NOT be emitted")
        }

        // Register gyroscope (cached, read on accel ticks).
        if (gyroSensor != null) {
            sensorManager.registerListener(
                sensorListener,
                gyroSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            Log.i(TAG, "Gyroscope registered: ${gyroSensor.name}, " +
                    "maxRange=${gyroSensor.maximumRange}, " +
                    "resolution=${gyroSensor.resolution}")
        } else {
            Log.w(TAG, "⚠ No gyroscope found on this device — " +
                    "merged samples will never pass the both-sensors gate. " +
                    "This device cannot produce valid IMU data.")
        }

        isRunning = true
        Log.i(TAG, "Started — hasAccel=$hasAccelerometer, hasGyro=$hasGyroscope")
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
