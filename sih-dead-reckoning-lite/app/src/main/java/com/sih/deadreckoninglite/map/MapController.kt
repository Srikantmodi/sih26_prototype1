package com.sih.deadreckoninglite.map

import android.graphics.Color
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Owns the map overlays used to compare GNSS and dead-reckoned positions. */
class MapController(private val mapView: MapView) {
	private val vehicleMarker = Marker(mapView)
	private val realPath = Polyline(mapView)
	private val reckonedPath = Polyline(mapView)
	private var initialized = false

	fun init() {
		if (initialized) return

		Configuration.getInstance().userAgentValue = mapView.context.packageName
		mapView.setMultiTouchControls(true)
		mapView.setBuiltInZoomControls(false)
		mapView.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)

		realPath.color = Color.rgb(47, 158, 68)
		realPath.width = 8f
		reckonedPath.color = Color.rgb(232, 137, 22)
		reckonedPath.width = 8f

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

	fun moveVehicleTo(lat: Double, lon: Double) {
		ensureInitialized()
		val point = GeoPoint(lat, lon)
		vehicleMarker.position = point
		mapView.controller.animateTo(point)
		mapView.invalidate()
	}

	fun addToRealPath(lat: Double, lon: Double) {
		ensureInitialized()
		realPath.addPoint(GeoPoint(lat, lon))
		mapView.invalidate()
	}

	fun addToReckonedPath(lat: Double, lon: Double) {
		ensureInitialized()
		reckonedPath.addPoint(GeoPoint(lat, lon))
		mapView.invalidate()
	}

	private fun ensureInitialized() {
		if (!initialized) init()
	}

	companion object {
		private const val DEFAULT_ZOOM = 16.0
		private val DEFAULT_CENTER = GeoPoint(20.5937, 78.9629)
	}
}
