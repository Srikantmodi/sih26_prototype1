package com.sih.deadreckoninglite.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.sih.deadreckoninglite.util.Constants

/**
 * Wraps [FusedLocationProviderClient] to produce a continuous ~1 Hz stream
 * of [GpsSample] objects via a callback.
 *
 * ## Design Contract (PRD §4.2 point 3, §9)
 * - This is the **ONLY** file that touches [FusedLocationProviderClient].
 * - It knows nothing about what happens to the samples afterward.
 * - Same isolation principle as [com.sih.deadreckoninglite.sensors.ImuManager].
 * - Caller (MainActivity) is responsible for routing samples to
 *   SensorLogger, TunnelSimulator, and MapController.
 *
 * ## Permission Requirement
 * [android.Manifest.permission.ACCESS_FINE_LOCATION] must already be granted
 * before calling [start]. Permission handling is owned by MainActivity
 * (Member 3) — this class does not request permissions itself.
 *
 * ## Timestamp Clock Domain
 * Uses [android.location.Location.getElapsedRealtimeNanos] which is on the
 * [android.os.SystemClock.elapsedRealtimeNanos] clock — the **same clock domain**
 * as [android.hardware.SensorEvent.timestamp] used by [ImuManager].
 * This means timestamps from both streams are directly comparable for
 * time-alignment in Member 4's CSV and Member 1's dead reckoning math.
 *
 * ## Guards & Safety
 * - **Double-start guard:** Calling [start] twice stops the first session cleanly.
 * - **Play Services check:** Verifies Google Play Services availability before starting.
 * - **SecurityException safety:** Catches runtime permission revocation gracefully.
 * - **Null/missing-field handling:** Gracefully defaults speed, bearing, accuracy
 *   when the platform doesn't provide them (common on first fix or indoors).
 *
 * @param context Application or Activity context.
 */
class GpsProvider(context: Context) {

    companion object {
        private const val TAG = "GpsProvider"
    }

    private val appContext: Context = context.applicationContext

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)

    private var onSample: ((GpsSample) -> Unit)? = null

    /** True while location updates are registered. Prevents double-registration. */
    @Volatile private var isRunning: Boolean = false

    /** Count of fixes received in the current session — useful for debugging. */
    @Volatile private var fixCount: Long = 0L

    /**
     * Whether Google Play Services is available and up-to-date on this device.
     * Check this before calling [start] — if `false`, [FusedLocationProviderClient]
     * will fail with an opaque error.
     *
     * Exposed so Member 3 (MainActivity) can show a user-facing error message
     * instead of crashing.
     */
    val isPlayServicesAvailable: Boolean
        get() = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

    /**
     * Location request configured for ~1 Hz updates at high accuracy.
     *
     * - Interval = 1 000 ms  → matches PRD §10 schema's GPS rate
     * - Fastest  =   500 ms  → accept faster updates if the system offers them
     *                           (e.g., from another app sharing location access)
     * - Priority = HIGH_ACCURACY → uses GPS hardware, not just network/WiFi
     */
    private val locationRequest: LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, Constants.GPS_UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(Constants.GPS_FASTEST_INTERVAL_MS)
            .build()

    /**
     * Internal location callback — converts platform [android.location.Location]
     * into our [GpsSample] data class with:
     * - Consistent timestamping (elapsedRealtimeNanos — same clock as IMU)
     * - Safe field extraction with has*() guards
     * - Exception-safe callback invocation
     */
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation
            if (location == null) {
                Log.w(TAG, "LocationResult.lastLocation was null — skipping")
                return
            }

            val sample = GpsSample(
                // Use the Location's own elapsedRealtimeNanos — same clock domain
                // as SensorEvent.timestamp, making the two streams time-aligned.
                timestampNs = location.elapsedRealtimeNanos,
                latDeg      = location.latitude,
                lonDeg      = location.longitude,
                // Safely extract optional fields with sensible defaults.
                // First fix often lacks speed/bearing. Using 0f (not NaN or -1)
                // because downstream math (ConstantVelocityReckoner.project)
                // does multiplication on these — 0f produces "stationary" which
                // is safe, while NaN would silently poison all derived values.
                speedMps    = if (location.hasSpeed()) location.speed else 0f,
                bearingDeg  = if (location.hasBearing()) location.bearing else 0f,
                // Float.MAX_VALUE for unknown accuracy signals "completely unreliable"
                // to any downstream consumer that checks accuracy thresholds.
                accuracyM   = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
            )

            fixCount++

            // Log periodically for debugging without flooding Logcat.
            if (fixCount == 1L || fixCount % 10 == 0L) {
                Log.d(TAG, "Fix #$fixCount: ${sample.toLogString()}")
            }

            // Exception-safe callback invocation — a downstream crash must NOT
            // kill the FusedLocationProvider's callback delivery mechanism.
            try {
                onSample?.invoke(sample)
            } catch (e: Exception) {
                Log.e(TAG, "Exception in GPS callback — swallowed to protect " +
                        "location delivery", e)
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Public API (PRD §9 contract — signatures unchanged)                //
    // ------------------------------------------------------------------ //

    /**
     * Begin continuous ~1 Hz location updates.
     *
     * If already running, the previous session is stopped cleanly before
     * re-registering (double-start guard). This handles Activity config
     * changes where `onCreate` may fire again without a prior `onDestroy`.
     *
     * @param onSample Receives a [GpsSample] on each fix.
     *                 Called on the **main looper** so the callback can
     *                 safely update LiveData / UI state in MainActivity.
     *
     * @return `true` if location updates were successfully requested,
     *         `false` if they couldn't start (missing Play Services or
     *         missing permission). The caller should check this and
     *         show an appropriate user-facing message.
     *
     * **Note:** The return type is additive — callers who don't check it
     * still compile and work (they just won't know about failures).
     */
    @SuppressLint("MissingPermission") // Permission is checked by MainActivity before calling start()
    fun start(onSample: (GpsSample) -> Unit): Boolean {
        // Double-start guard.
        if (isRunning) {
            Log.w(TAG, "start() called while already running — stopping previous session first")
            stop()
        }

        // Pre-flight: verify Play Services is available.
        if (!isPlayServicesAvailable) {
            Log.e(TAG, "Google Play Services is NOT available or outdated — " +
                    "FusedLocationProviderClient will not work. " +
                    "Cannot start GPS updates.")
            return false
        }

        this.onSample = onSample
        fixCount = 0

        // Wrap the actual registration in try-catch to handle:
        // - SecurityException: permission revoked between the check and here
        // - Any other platform exception from FusedLocationProviderClient
        return try {
            fusedClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            isRunning = true
            Log.i(TAG, "Started — requesting ~1 Hz high-accuracy updates")
            true
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException — ACCESS_FINE_LOCATION permission " +
                    "was revoked between the permission check and " +
                    "requestLocationUpdates(). Cannot start.", se)
            this.onSample = null
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception while starting location updates", e)
            this.onSample = null
            false
        }
    }

    /**
     * Stop location updates and clear the callback reference.
     * Safe to call multiple times, or before [start] has ever been called.
     */
    fun stop() {
        if (!isRunning) return

        fusedClient.removeLocationUpdates(locationCallback)
        onSample = null
        isRunning = false

        Log.i(TAG, "Stopped after $fixCount fixes in this session")
    }
}
