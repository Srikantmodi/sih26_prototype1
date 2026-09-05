package com.sih.deadreckoninglite.map

import android.graphics.Color
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Owns the osmdroid [MapView] — the only file in the codebase that touches
 * osmdroid APIs directly.
 *
 * ## Responsibilities
 * - Configure the map (tile source, zoom, multi-touch)
 * - Move the vehicle marker to a given (lat, lon)
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

    /**
     * Initialize the map: set tile source, enable multi-touch, add overlays.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun init() {
        if (initialized) return

        Configuration.getInstance().userAgentValue = mapView.context.packageName
        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)
        mapView.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)

        // Green for GNSS-sourced path
        realPath.outlinePaint.color = Color.rgb(47, 158, 68)
        realPath.outlinePaint.strokeWidth = 8f

        // Amber for dead-reckoned path
        reckonedPath.outlinePaint.color = Color.rgb(232, 137, 22)
        reckonedPath.outlinePaint.strokeWidth = 8f

        vehicleMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
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
     * Move the vehicle marker to the given coordinates and center the map.
     * Called by [MainActivity] regardless of whether the position is from
     * real GPS or the dead reckoner.
     */
    fun moveVehicleTo(lat: Double, lon: Double) {
        ensureInitialized()
        val point = GeoPoint(lat, lon)
        vehicleMarker.position = point
        mapView.controller.animateTo(point)
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
     * Used by the zoom in/out buttons in the dashboard HUD.
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
     * Re-center the map on the vehicle marker's current position.
     */
    fun recenter() {
        ensureInitialized()
        if (vehicleMarker.position != null) {
            mapView.controller.animateTo(vehicleMarker.position)
        }
    }

    private fun ensureInitialized() {
        if (!initialized) init()
    }

    companion object {
        private const val DEFAULT_ZOOM = 16.0
        // Default center: India (Bangalore area)
        private val DEFAULT_CENTER = GeoPoint(20.5937, 78.9629)
    }
}
