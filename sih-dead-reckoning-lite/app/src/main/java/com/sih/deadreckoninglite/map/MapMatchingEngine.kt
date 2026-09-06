package com.sih.deadreckoninglite.map

import com.sih.deadreckoninglite.util.Constants
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometric road-network / tunnel map matching engine.
 *
 * ## Problem Statement Requirement (SIH PS-26168)
 * "Map-matching constraints (e.g. snapping to road/tunnel networks)."
 *
 * ## Logic
 * Projects dead-reckoned (lat, lon) coordinates orthogonally onto road or tunnel
 * centerline segments. If the lateral deviation is within [MAX_SNAP_DISTANCE_M],
 * snaps the position to the road centerline while maintaining longitudinal forward progress.
 */
class MapMatchingEngine {

    companion object {
        private const val MAX_SNAP_DISTANCE_M = 25.0 // Max perpendicular distance to snap
    }

    data class RoadSegment(
        val startLat: Double, val startLon: Double,
        val endLat: Double, val endLon: Double
    )

    // Active tunnel/road corridor segments
    private val corridor = mutableListOf<RoadSegment>()

    /**
     * Add a road or tunnel corridor segment to match against.
     */
    fun addCorridorSegment(startLat: Double, startLon: Double, endLat: Double, endLon: Double) {
        corridor.add(RoadSegment(startLat, startLon, endLat, endLon))
    }

    /**
     * Snap a candidate dead-reckoned position (lat, lon) to the nearest corridor segment.
     * If outside the snap radius or corridor is empty, returns raw (lat, lon).
     */
    fun snapToRoad(lat: Double, lon: Double): Pair<Double, Double> {
        if (corridor.isEmpty()) return Pair(lat, lon)

        var closestPoint = Pair(lat, lon)
        var minDistanceM = Double.MAX_VALUE

        for (segment in corridor) {
            val projected = projectPointOnSegment(lat, lon, segment)
            val distM = distanceMeters(lat, lon, projected.first, projected.second)
            if (distM < minDistanceM) {
                minDistanceM = distM
                closestPoint = projected
            }
        }

        return if (minDistanceM <= MAX_SNAP_DISTANCE_M) closestPoint else Pair(lat, lon)
    }

    private fun projectPointOnSegment(lat: Double, lon: Double, seg: RoadSegment): Pair<Double, Double> {
        val cosLat = cos(Math.toRadians(lat))
        val R = Constants.EARTH_RADIUS_M

        // Convert to local metric coordinates (x=East, y=North) relative to seg.start
        val dxSeg = Math.toRadians(seg.endLon - seg.startLon) * cosLat * R
        val dySeg = Math.toRadians(seg.endLat - seg.startLat) * R

        val dxPt = Math.toRadians(lon - seg.startLon) * cosLat * R
        val dyPt = Math.toRadians(lat - seg.startLat) * R

        val segLenSq = dxSeg * dxSeg + dySeg * dySeg
        if (segLenSq == 0.0) return Pair(seg.startLat, seg.startLon)

        // Projection scalar t clamped to [0, 1]
        val t = ((dxPt * dxSeg + dyPt * dySeg) / segLenSq).coerceIn(0.0, 1.0)

        val snappedLat = seg.startLat + t * (seg.endLat - seg.startLat)
        val snappedLon = seg.startLon + t * (seg.endLon - seg.startLon)
        return Pair(snappedLat, snappedLon)
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = Constants.EARTH_RADIUS_M
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    fun clearCorridor() {
        corridor.clear()
    }
}
