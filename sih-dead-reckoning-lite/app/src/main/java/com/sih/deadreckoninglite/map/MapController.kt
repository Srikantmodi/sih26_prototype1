package com.sih.deadreckoninglite.map

import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.ContextCompat
import com.sih.deadreckoninglite.R
import com.sih.deadreckoninglite.util.Constants
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Owns the osmdroid [MapView] — the only file in the codebase that touches
 * osmdroid APIs directly.
 *
 * ## Responsibilities
 * - Configure the map (tile source, zoom, multi-touch, User-Agent, cache)
 * - Move the vehicle marker to a given (lat, lon) smoothly
 * - Draw two separate colored [Polyline] overlays:
 *   - **Real path** (green) — positions sourced from live GPS
 *   - **Reckoned path** (amber/orange) — positions projected by dead reckoning
 *
 * ## Architecture Rule
 * This class does NOT know whether a position came from real GPS or the
 * reckoner — [MainActivity] decides which `addTo*Path` method to call.
 * [MapController] just draws what it's told.
 *
 * ## Thread Safety
 * All methods must be called from the main/UI thread (osmdroid requirement).
 */
class MapController(private val mapView: MapView) {

    private val vehicleMarker = Marker(mapView)
    private val realPath = Polyline(mapView)
    private val reckonedPath = Polyline(mapView)
    private var initialized = false
    private var hasFirstPosition = false

    /** Whether the map camera should automatically stay centered on vehicle updates. */
    var autoFollow: Boolean = true

    /**
     * Initialize the map: set tile source, enable multi-touch, add overlays.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun init() {
        if (initialized) return

        val ctx = mapView.context
        val config = Configuration.getInstance()
        // OpenStreetMap requires a descriptive custom User-Agent to prevent 403 throttling
        config.userAgentValue = "DeadReckoningLite/1.0 (com.sih.deadreckoninglite; SIH-PS-26168)"
        
        // Persistent internal storage for osmdroid (never cleared by Android OS low-memory)
        val basePath = File(ctx.filesDir, "osmdroid")
        basePath.mkdirs()
        config.osmdroidBasePath = basePath
        config.osmdroidTileCache = File(basePath, "tiles")
        
        // Offline tile cache policy: retain tiles in cache and expand capacity
        config.cacheMapTileCount = 1200
        config.cacheMapTileOvershoot = 500
        config.expirationExtendedDuration = 1000L * 60 * 60 * 24 * 60L // 60 days retention

        mapView.isTilesScaledToDpi = true
        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)
        mapView.minZoomLevel = 11.0
        mapView.maxZoomLevel = 19.0
        mapView.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)

        updateDataConnectionMode()

        // Green for GNSS-sourced path
        realPath.outlinePaint.color = Color.rgb(47, 158, 68)
        realPath.outlinePaint.strokeWidth = 8f

        // Amber for dead-reckoned path
        reckonedPath.outlinePaint.color = Color.rgb(232, 137, 22)
        reckonedPath.outlinePaint.strokeWidth = 8f

        // Custom high-contrast circular vehicle marker puck
        val customIcon = ContextCompat.getDrawable(ctx, R.drawable.ic_vehicle_marker)
        if (customIcon != null) {
            vehicleMarker.icon = customIcon
        }
        vehicleMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        vehicleMarker.title = "Vehicle position"

        mapView.overlays.add(realPath)
        mapView.overlays.add(reckonedPath)
        mapView.overlays.add(vehicleMarker)
        mapView.controller.setZoom(DEFAULT_ZOOM)
        mapView.controller.setCenter(DEFAULT_CENTER)

        initialized = true
        mapView.invalidate()
    }

    /**
     * Seed the initial position immediately from cache before any GPS fix arrives.
     * Guarantees that in offline mode, the map centers on the vehicle and places the puck.
     */
    fun seedInitialPosition(lat: Double, lon: Double) {
        ensureInitialized()
        val point = GeoPoint(lat, lon)
        vehicleMarker.position = point
        mapView.controller.setCenter(point)
        hasFirstPosition = true
        mapView.invalidate()
    }

    /**
     * Move the vehicle marker to the given coordinates.
     * Updates marker position directly and smoothly updates center without
     * disruptive 1-second animations that freeze touch interaction.
     */
    fun moveVehicleTo(lat: Double, lon: Double) {
        ensureInitialized()
        val point = GeoPoint(lat, lon)
        vehicleMarker.position = point

        if (!hasFirstPosition) {
            mapView.controller.setCenter(point)
            hasFirstPosition = true
        } else if (autoFollow) {
            mapView.controller.setCenter(point)
        }

        mapView.invalidate()
    }

    /**
     * Add a point to the GNSS-sourced (green) path polyline.
     * Called by [MainActivity] when in GNSS mode.
     */
    fun addToRealPath(lat: Double, lon: Double) {
        ensureInitialized()
        val newPoint = GeoPoint(lat, lon)
        val points = realPath.actualPoints
        if (points.isNotEmpty()) {
            val lastPoint = points.last()
            val dist = lastPoint.distanceToAsDouble(newPoint)
            if (dist < 1.5) return // Ignore stationary micro-jitter
            if (dist > 150.0) {
                // Teleportation guard: don't connect discontinuous distant points
                points.clear()
            }
        }
        realPath.addPoint(newPoint)
        mapView.invalidate()
    }

    /**
     * Add a point to the dead-reckoned (amber) path polyline.
     * Called by [MainActivity] when in DEAD_RECKONING mode.
     */
    fun addToReckonedPath(lat: Double, lon: Double) {
        ensureInitialized()
        val newPoint = GeoPoint(lat, lon)
        val points = reckonedPath.actualPoints
        if (points.isNotEmpty()) {
            val lastPoint = points.last()
            val dist = lastPoint.distanceToAsDouble(newPoint)
            if (dist < 1.5) return // Ignore stationary micro-jitter
            if (dist > 150.0) {
                // Teleportation guard: clear stale points to prevent drawing lines across the map
                points.clear()
            }
        }
        reckonedPath.addPoint(newPoint)
        mapView.invalidate()
    }

    /**
     * Clear only the dead-reckoned path overlay when re-locking GNSS or resetting DR.
     */
    fun clearReckonedPath() {
        ensureInitialized()
        reckonedPath.actualPoints.clear()
        mapView.invalidate()
    }

    /**
     * Clear both path overlays. Useful when starting a new recording
     * session or resetting the map state.
     */
    fun clearPaths() {
        realPath.actualPoints.clear()
        reckonedPath.actualPoints.clear()
        mapView.invalidate()
    }

    /**
     * Set the map zoom level programmatically.
     */
    fun zoomIn() {
        ensureInitialized()
        mapView.controller.zoomIn()
    }

    /**
     * Decrease the map zoom level.
     */
    fun zoomOut() {
        ensureInitialized()
        mapView.controller.zoomOut()
    }

    /**
     * Re-center the map on the vehicle marker's current position and re-enable autoFollow.
     */
    fun recenter() {
        ensureInitialized()
        autoFollow = true
        val pos = vehicleMarker.position
        if (pos != null) {
            mapView.controller.animateTo(pos)
        }
    }

    /**
     * Updates osmdroid's data connection setting based on active internet connectivity.
     * When offline, setting useDataConnection=false avoids failing HTTP requests and
     * ensures immediate rendering from local offline tile archives.
     */
    fun updateDataConnectionMode() {
        val isOnline = checkInternet(mapView.context)
        mapView.setUseDataConnection(isOnline)
        Log.i(TAG, "Map data connection set to: isOnline=$isOnline")
    }

    /**
     * Force a full map reload: clears tile memory cache, updates data connection mode,
     * and forces an immediate invalidate redraw.
     */
    fun reloadMap() {
        ensureInitialized()
        updateDataConnectionMode()
        mapView.tileProvider.clearTileCache()
        mapView.invalidate()
        Log.i(TAG, "Map reload triggered successfully")
    }

    private fun checkInternet(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun ensureInitialized() {
        if (!initialized) init()
    }

    companion object {
        private const val TAG = "MapController"
        private const val DEFAULT_ZOOM = 16.5
        // Default center: Hyderabad region (SIH demonstration area)
        private val DEFAULT_CENTER = GeoPoint(Constants.DEFAULT_SEED_LAT, Constants.DEFAULT_SEED_LON)

        /**
         * Copies the bundled offline tile archive from assets to internal storage
         * so osmdroid's MapTileFileArchiveProvider can load street tiles completely offline.
         */
        fun copyOfflineAssetsIfNeeded(context: Context) {
            try {
                val basePath = File(context.filesDir, "osmdroid")
                if (!basePath.exists()) {
                    basePath.mkdirs()
                }
                val destZip = File(basePath, "hyderabad_offline.zip")
                if (!destZip.exists() || destZip.length() < 1_000_000L) {
                    context.assets.open("hyderabad_offline.zip").use { input ->
                        destZip.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Offline tile archive extracted: ${destZip.length()} bytes to ${destZip.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not extract offline tile archive from assets", e)
            }
        }
    }
}
