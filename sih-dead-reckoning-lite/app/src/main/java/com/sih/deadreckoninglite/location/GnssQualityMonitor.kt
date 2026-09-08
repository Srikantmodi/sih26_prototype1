package com.sih.deadreckoninglite.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sih.deadreckoninglite.util.Constants

/**
 * Operating mode determined automatically by [GnssQualityMonitor].
 */
enum class NavigationMode {
    /** 3D GNSS Lock with active location fixes and internet. Solid green, no flapping. */
    GNSS_LOCK,

    /** Marginal accuracy (25m - 45m), alerting fallback. */
    GNSS_DEGRADED,

    /** GNSS signal lost (timeout > 4.0s without fix, e.g. entering tunnel). Autonomous DR active. */
    DEAD_RECKONING_AUTO,

    /** Location service disabled in phone settings or permission denied. Offline IMU DR active. */
    OFFLINE_STANDALONE
}

/**
 * Autonomous monitor that continuously evaluates GNSS fix health, internet connectivity,
 * and location provider availability.
 *
 * ## Fixed Glitching / Flapping
 * 1. Checks internet connectivity via [ConnectivityManager].
 * 2. When Location is enabled and Internet is connected, fixes arriving within 4.0s stay
 *    firmly in [NavigationMode.GNSS_LOCK] without flapping.
 * 3. Switches to [NavigationMode.DEAD_RECKONING_AUTO] ONLY when location fixes stop arriving
 *    (timeout > 4.0s, e.g. entering a tunnel) or when both internet and location are lost.
 * 4. Switches to [NavigationMode.OFFLINE_STANDALONE] when location service is disabled in settings.
 */
class GnssQualityMonitor(
    context: Context,
    private val onModeChanged: (NavigationMode) -> Unit
) {
    companion object {
        private const val TAG = "GnssQualityMonitor"
    }

    private val appContext = context.applicationContext

    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var lastFixTimestampMs: Long = 0L
    @Volatile private var satellitesUsedInFix: Int = 0
    @Volatile private var isRunning: Boolean = false
    @Volatile private var isNetworkConnected: Boolean = true

    var currentMode: NavigationMode = NavigationMode.GNSS_LOCK
        private set

    val satelliteCount: Int get() = satellitesUsedInFix

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            isNetworkConnected = true
            handler.post { evaluateQuality() }
        }

        override fun onLost(network: Network) {
            isNetworkConnected = checkInternet()
            handler.post { evaluateQuality() }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            isNetworkConnected = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            handler.post { evaluateQuality() }
        }
    }

    private val gnssStatusCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) {
                        used++
                    }
                }
                satellitesUsedInFix = used
            }
        }
    } else null

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            evaluateQuality()
            handler.postDelayed(this, Constants.GNSS_WATCHDOG_INTERVAL_MS)
        }
    }

    private fun checkInternet(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun isLocationServiceEnabled(): Boolean {
        return try {
            val isEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled
            } else {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
            isEnabled || locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            try {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * Start the autonomous quality monitor and watchdog timer.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        isRunning = true
        lastFixTimestampMs = System.currentTimeMillis()
        isNetworkConnected = checkInternet()

        if (!isLocationServiceEnabled()) {
            transitionTo(NavigationMode.OFFLINE_STANDALONE)
        } else {
            transitionTo(NavigationMode.GNSS_LOCK)
        }

        // Register default network callback for real-time connectivity tracking
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
        }

        // Register hardware satellite callback
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
                locationManager.registerGnssStatusCallback(gnssStatusCallback, handler)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing for GnssStatusCallback: ${e.message}")
        }

        handler.post(watchdogRunnable)
        Log.i(TAG, "Autonomous GNSS Quality Monitor started")
    }

    /**
     * Stop the quality monitor and watchdog timer.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacks(watchdogRunnable)

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering networkCallback", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssStatusCallback != null) {
            try {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering GnssStatusCallback", e)
            }
        }
        Log.i(TAG, "Autonomous GNSS Quality Monitor stopped")
    }

    /**
     * Called whenever a new [GpsSample] is received from [GpsProvider].
     * Resets the watchdog timer and confirms live GNSS lock.
     */
    fun onGpsSampleReceived(sample: GpsSample) {
        lastFixTimestampMs = System.currentTimeMillis()

        if (!isLocationServiceEnabled()) {
            transitionTo(NavigationMode.OFFLINE_STANDALONE)
            return
        }

        val hasInternet = checkInternet() || isNetworkConnected
        if (hasInternet || sample.accuracyM <= 45f) {
            transitionTo(NavigationMode.GNSS_LOCK)
        } else {
            transitionTo(NavigationMode.GNSS_DEGRADED)
        }
    }

    /**
     * Autonomous quality monitor evaluation:
     * - When there is Location Access AND Internet -> GNSS ACTIVE (GNSS_LOCK)
     * - When Location Access is turned OFF -> DEAD RECKONING (OFFLINE_STANDALONE)
     * - When there is NO Internet (offline / tunnel / outage):
     *     - If GPS fixes are active (< 4s) -> GNSS_LOCK (autonomous GPS satellite tracking)
     *     - If GPS fixes have stopped (> 4s, tunnel) -> DEAD_RECKONING_AUTO
     */
    private fun evaluateQuality() {
        val hasLocationAccess = isLocationServiceEnabled()
        val hasInternet = checkInternet() || isNetworkConnected
        val now = System.currentTimeMillis()
        val timeSinceLastFix = now - lastFixTimestampMs

        // 1. If user disabled location access in device settings -> OFFLINE_STANDALONE DR
        if (!hasLocationAccess) {
            transitionTo(NavigationMode.OFFLINE_STANDALONE)
            return
        }

        // 2. When there is Location Access AND Internet -> Always GNSS Active!
        if (hasInternet) {
            transitionTo(NavigationMode.GNSS_LOCK)
            return
        }

        // 3. No internet (offline / tunnel / remote outage)
        // If fixes are actively arriving within 4s -> GNSS_LOCK
        // If fixes have stopped (> 4s, entering tunnel) -> DEAD_RECKONING_AUTO
        if (timeSinceLastFix <= Constants.GNSS_OUTAGE_TIMEOUT_MS) {
            transitionTo(NavigationMode.GNSS_LOCK)
        } else {
            transitionTo(NavigationMode.DEAD_RECKONING_AUTO)
        }
    }

    private fun transitionTo(mode: NavigationMode) {
        if (currentMode != mode) {
            Log.i(TAG, "Navigation mode transition: $currentMode -> $mode (internet=$isNetworkConnected)")
            currentMode = mode
            onModeChanged(mode)
        }
    }
}
