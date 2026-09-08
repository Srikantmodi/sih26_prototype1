package com.sih.deadreckoninglite.location

import android.content.Context
import android.content.SharedPreferences
import com.sih.deadreckoninglite.util.Constants

/**
 * Manages persistent storage of the vehicle's last known location and heading.
 *
 * Ensures the application can start up and display an immediate map position
 * even when offline or when location permissions/GPS providers are unavailable.
 */
class LocationCache(context: Context) {

    companion object {
        private const val PREFS_NAME = "dr_location_cache"
        private const val KEY_LAT = "cached_lat"
        private const val KEY_LON = "cached_lon"
        private const val KEY_BEARING = "cached_bearing"
        private const val KEY_SPEED = "cached_speed"
        private const val KEY_HAS_FIX = "has_valid_fix"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Save the latest valid GPS fix into persistent cache.
     */
    fun saveLastKnownPosition(lat: Double, lon: Double, bearing: Float, speedMps: Float) {
        prefs.edit()
            .putString(KEY_LAT, lat.toString())
            .putString(KEY_LON, lon.toString())
            .putFloat(KEY_BEARING, bearing)
            .putFloat(KEY_SPEED, speedMps)
            .putBoolean(KEY_HAS_FIX, true)
            .apply()
    }

    /**
     * Retrieve the last known location.
     * If no prior fix was recorded, defaults to the project demonstration region (Hyderabad).
     *
     * @return Triple(lat, lon, bearing)
     */
    fun getLastKnownPosition(): Triple<Double, Double, Float> {
        val latStr = prefs.getString(KEY_LAT, null)
        val lonStr = prefs.getString(KEY_LON, null)
        val bearing = prefs.getFloat(KEY_BEARING, 0f)

        val lat = latStr?.toDoubleOrNull() ?: Constants.DEFAULT_SEED_LAT
        val lon = lonStr?.toDoubleOrNull() ?: Constants.DEFAULT_SEED_LON

        return Triple(lat, lon, bearing)
    }

    /**
     * Returns whether at least one real GPS fix was saved previously.
     */
    fun hasValidFix(): Boolean = prefs.getBoolean(KEY_HAS_FIX, false)
}
