package com.sih.deadreckoninglite.ml

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * CNN+LSTM speed estimator running on TFLite.
 *
 * ## Isolation Contract (Architecture Rule)
 * This class has ZERO knowledge of:
 * - Android UI / Views / ViewModel
 * - MapController, TunnelSimulator, SensorLogger
 * - GPS / SensorManager
 * It is a pure function: IMU window -> speed in m/s.
 * MainActivity is the ONLY class that creates and holds a reference to this.
 *
 * ## Input
 * Sliding window of WINDOW_SIZE=20 IMU frames x NUM_FEATURES=6 channels:
 *   [linear_ax, linear_ay, linear_az, gx, gy, gz]
 * Each channel is Z-score normalized using stats from norm_stats.json.
 * SensorSample.linearAx/Y/Z provides the gravity-compensated acceleration.
 *
 * ## Output
 * Predicted vehicle speed in m/s (predicted as km/h internally, then converted).
 *
 * ## Usage by MainActivity
 * 1. Create: mlEstimator = MlSpeedEstimator(this)
 * 2. Wire:   tunnelSimulator.mlEstimator = mlEstimator
 * 3. Feed:   mlEstimator.addSample(...) on every ImuManager callback
 * 4. Destroy: mlEstimator.close() in onDestroy()
 *
 * ## Assets required (in app/src/main/assets/)
 * - speed_estimator.tflite  (trained CNN+LSTM model)
 * - norm_stats.json         (mean/std from Python training script)
 */
class MlSpeedEstimator(context: Context) {

    companion object {
        private const val TAG            = "MlSpeedEstimator"
        private const val WINDOW_SIZE    = 20       // must match Python WINDOW_SIZE
        private const val NUM_FEATURES   = 6        // linear_ax/ay/az + gx/gy/gz
        private const val MODEL_FILE     = "speed_estimator.tflite"
        private const val NORM_FILE      = "norm_stats.json"
        // Stationary deadband: predictions below 1.26 km/h (0.35 m/s) treated as zero
        private const val SPEED_DEADBAND_KMH = 1.26f
        // Downsample: ImuManager emits at ~50 Hz, model trained at 10 Hz
        private const val DOWNSAMPLE_FACTOR = 5   // feed every 5th sample to model
    }

    private var interpreter: Interpreter? = null
    private val mean: FloatArray
    private val std:  FloatArray

    // Rolling window of WINDOW_SIZE normalized IMU frames
    private val window = ArrayDeque<FloatArray>(WINDOW_SIZE)

    // Skip counter for downsampling
    private var skipCounter = 0

    // Latest prediction (cached between model calls)
    @Volatile private var lastPrediction: Float? = null

    init {
        try {
            val modelBuffer = loadModelFile(context)
            val options = Interpreter.Options().apply { numThreads = 2 }
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "TFLite interpreter created from $MODEL_FILE")
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize TFLite model from $MODEL_FILE (${e.message}). Falling back to CV mode until model is provided.")
            interpreter = null
        }

        val (loadedMean, loadedStd) = try {
            val normJson = context.assets.open(NORM_FILE).bufferedReader().readText()
            val json = JSONObject(normJson)
            val meanArr = json.getJSONArray("mean")
            val stdArr  = json.getJSONArray("std")
            val m = FloatArray(NUM_FEATURES) { meanArr.getDouble(it).toFloat() }
            val s = FloatArray(NUM_FEATURES) { stdArr.getDouble(it).toFloat() }
            Log.i(TAG, "Normalization stats loaded from $NORM_FILE")
            Pair(m, s)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load $NORM_FILE (${e.message}). Using default normalization stats.")
            Pair(FloatArray(NUM_FEATURES) { 0f }, FloatArray(NUM_FEATURES) { 1f })
        }
        mean = loadedMean
        std  = loadedStd

        Log.d(TAG, "  means: ${mean.toList()}")
        Log.d(TAG, "  stds:  ${std.toList()}")
    }

    /**
     * Feed one IMU sample into the rolling window.
     *
     * Call this on EVERY sensor tick from ImuManager (~50 Hz).
     * Internal downsampling reduces to ~10 Hz matching the training rate.
     *
     * @param linearAx  SensorSample.linearAx  (ax - gravX), m/s²
     * @param linearAy  SensorSample.linearAy  (ay - gravY), m/s²
     * @param linearAz  SensorSample.linearAz  (az - gravZ), m/s²
     * @param gx        SensorSample.gx, rad/s
     * @param gy        SensorSample.gy, rad/s
     * @param gz        SensorSample.gz, rad/s
     */
    fun addSample(
        linearAx: Float, linearAy: Float, linearAz: Float,
        gx: Float, gy: Float, gz: Float
    ) {
        skipCounter++
        if (skipCounter < DOWNSAMPLE_FACTOR) return
        skipCounter = 0

        // Z-score normalize each feature channel
        val raw = floatArrayOf(linearAx, linearAy, linearAz, gx, gy, gz)
        val normalized = FloatArray(NUM_FEATURES) { i -> (raw[i] - mean[i]) / std[i] }

        // Maintain fixed-size sliding window
        if (window.size >= WINDOW_SIZE) window.removeFirst()
        window.addLast(normalized)

        // Run inference once window is full and interpreter is available
        if (window.size == WINDOW_SIZE && interpreter != null) {
            lastPrediction = runInference()
        }
    }

    /**
     * Returns the latest ML-predicted speed in m/s.
     * Returns null if not enough samples have been fed yet
     * (less than WINDOW_SIZE x DOWNSAMPLE_FACTOR = 100 IMU ticks = ~2 seconds).
     */
    fun predictSpeedMps(): Float? {
        val pred = lastPrediction ?: return null
        val effectiveKmh = if (pred < SPEED_DEADBAND_KMH) 0.0f else pred
        return effectiveKmh / 3.6f   // km/h -> m/s
    }

    /**
     * Whether the model has received enough samples to produce a prediction.
     * Call this in TunnelSimulator to decide ML vs. constant-velocity fallback.
     */
    fun isReady(): Boolean = interpreter != null && lastPrediction != null

    /**
     * Reset the sliding window. Call when starting a new drive session or
     * when activating tunnel mode after a long pause.
     */
    fun reset() {
        window.clear()
        lastPrediction = null
        skipCounter = 0
    }

    /**
     * Release TFLite resources. Call from Activity.onDestroy().
     */
    fun close() {
        interpreter?.close()
        interpreter = null
        Log.i(TAG, "Closed")
    }

    // ── Private ────────────────────────────────────────────────────────────

    private fun runInference(): Float {
        val interp = interpreter ?: return 0f
        // Input shape: [1, WINDOW_SIZE, NUM_FEATURES]
        val input  = Array(1) { Array(WINDOW_SIZE) { idx -> window[idx].copyOf() } }
        val output = Array(1) { FloatArray(1) }
        return try {
            interp.run(input, output)
            // Clamp to physically reasonable range: 0–200 km/h
            output[0][0].coerceIn(0f, 200f)
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference failed", e)
            0f
        }
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val assetFd = context.assets.openFd(MODEL_FILE)
        val stream  = FileInputStream(assetFd.fileDescriptor)
        return stream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFd.startOffset,
            assetFd.declaredLength
        )
    }
}
