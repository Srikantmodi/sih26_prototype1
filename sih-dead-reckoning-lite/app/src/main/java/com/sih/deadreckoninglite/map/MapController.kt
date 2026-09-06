package com.sih.deadreckoninglite.map

import android.graphics.Color
import androidx.core.content.ContextCompat
import com.sih.deadreckoninglite.R
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
        config.osmdroidBasePath = ctx.cacheDir
        config.osmdroidTileCache = File(ctx.cacheDir, "osm_tiles")

        mapView.isTilesScaledToDpi = true
        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)
        mapView.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)

        // Green for GNSS-sourced path
        realPath.outlinePaint.color = Color.rgb(47, 158, 68)
        realPath.outlinePaint.strokeWidth = 8f

        // Amber for dead-reckoned path
        reckonedPath.outlinePaint.color = Color.rgb(232, 137, 22)
        reckonedPath.outlinePaint.strokeWidth = 8f

        // Custom high-contrast vehicle marker puck
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
        realPath.addPoint(GeoPoint(lat, lon))
        mapView.invalidate()
    }

    /**
     * Add a point to the dead-reckoned (amber) path polyline.
     * Called by [MainActivity] when in DEAD_RECKONING mode.
     */
    fun addToReckonedPath(lat: Double, lon: Double) {
        ensureInitialized()
        reckonedPath.addPoint(GeoPoint(lat, lon))
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

    private fun ensureInitialized() {
        if (!initialized) init()
    }

    companion object {
        private const val DEFAULT_ZOOM = 16.5
        // Default center: India (Bangalore area)
        private val DEFAULT_CENTER = GeoPoint(20.5937, 78.9629)
    }
}
