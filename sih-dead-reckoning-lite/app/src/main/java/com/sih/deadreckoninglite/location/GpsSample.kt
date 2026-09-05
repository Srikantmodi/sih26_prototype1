package com.sih.deadreckoninglite.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A single GPS fix from [com.google.android.gms.location.FusedLocationProviderClient].
 *
 * ## CSV Schema Mapping (PRD §10.1)
 * Fields map to the unified CSV columns:
 *   `gnss_lat` ← [latDeg], `gnss_lon` ← [lonDeg],
 *   `gnss_speed` ← [speedMps], `gnss_accuracy` ← [accuracyM]
 *
 * ## Units
 *   - latDeg, lonDeg   — decimal degrees (WGS-84)
 *   - speedMps         — meters per second (0f if unavailable)
 *   - bearingDeg       — degrees from true north, clockwise [0, 360)
 *                         (0f if unavailable — e.g. first fix or stationary)
 *   - accuracyM        — estimated horizontal accuracy radius in meters
 *                         ([Float.MAX_VALUE] if unavailable — signals "unreliable")
 *   - timestampNs      — nanoseconds, using [android.os.SystemClock.elapsedRealtimeNanos]
 *                         clock domain. Sourced from [android.location.Location.getElapsedRealtimeNanos].
 *                         This is the **same clock domain** used by
 *                         [com.sih.deadreckoninglite.sensors.SensorSample.timestampNs]
 *                         so the two streams are directly comparable for time-alignment.
 *
 * ## Thread Safety
 * This class is immutable — safe to share across threads without synchronization.
 *
 * ## Downstream Consumers
 * - Member 1 (TunnelSimulator/ConstantVelocityReckoner): needs [speedMps] + [bearingDeg]
 * - Member 4 (SensorLogger): uses [toCsvValues] and [CSV_HEADER]
 * - Member 5 (MapController): needs [latDeg] + [lonDeg] for marker positioning
 * - Member 5 (MainViewModel): uses [distanceTo] for live drift estimation
 * - Member 3 (MainActivity): routes instances to all of the above
 */
data class GpsSample(
    val timestampNs: Long,
    val latDeg: Double,
    val lonDeg: Double,
    val speedMps: Float,
    val bearingDeg: Float,
    val accuracyM: Float
) {

    /**
     * Human-readable string for Logcat debugging.
     * Lat/lon to 6 decimal places (~0.11 m resolution), speed to 1dp.
     *
     * Example output:
     * `GPS[ts=12345678] (28.613940, 77.209021) spd=12.3m/s brg=45.0° acc=3.2m`
     */
    fun toLogString(): String =
        "GPS[ts=$timestampNs] (${f6(latDeg)}, ${f6(lonDeg)}) " +
                "spd=${f1(speedMps)}m/s brg=${f1(bearingDeg)}° acc=${f1(accuracyM)}m"

    /**
     * CSV-ready value string matching the GNSS columns of the unified schema (PRD §10.1).
     *
     * Column order: `gnss_lat,gnss_lon,gnss_speed,gnss_accuracy`
     *
     * Lat/lon precision: 8 decimal places (~1.1 mm at the equator) — preserves
     * the full resolution from `Location.getLatitude()/getLongitude()` which
     * return `double`. Speed/accuracy at 4dp (sub-millimeter, more than enough).
     *
     * **Usage by Member 4 (SensorLogger):** call this instead of hand-formatting
     * the fields, so column ordering has a single source of truth.
     */
    fun toCsvValues(): String =
        "${f8(latDeg)},${f8(lonDeg)},${f4(speedMps)},${f4(accuracyM)}"

    /**
     * Haversine distance in meters between this fix and [other].
     *
     * Used by Member 5 (MainViewModel) to compute the live drift estimate:
     * distance between the last real GPS fix and the current reckoned position
     * during DEAD_RECKONING mode.
     *
     * Also useful for Member 1 (TunnelSimulator) to sanity-check how far
     * the reckoner has drifted from reality after toggle-off.
     *
     * Accuracy: exact for any distance on Earth (uses spherical Haversine,
     * not the equirectangular approximation in ConstantVelocityReckoner).
     */
    fun distanceTo(other: GpsSample): Double = haversineMeters(
        lat1 = this.latDeg, lon1 = this.lonDeg,
        lat2 = other.latDeg, lon2 = other.lonDeg
    )

    /**
     * Haversine distance in meters to a raw (lat, lon) coordinate pair.
     *
     * Convenience overload for when you have projected coordinates from
     * [com.sih.deadreckoninglite.deadreckoning.ConstantVelocityReckoner.project]
     * which returns `Pair<Double, Double>` rather than a [GpsSample].
     */
    fun distanceTo(latDeg: Double, lonDeg: Double): Double = haversineMeters(
        lat1 = this.latDeg, lon1 = this.lonDeg,
        lat2 = latDeg, lon2 = lonDeg
    )

    companion object {
        /**
         * CSV header for the GNSS portion of the unified schema.
         * Member 4 (SensorLogger) uses this when writing the CSV header row.
         */
        const val CSV_HEADER = "gnss_lat,gnss_lon,gnss_speed,gnss_accuracy"

        /**
         * Full unified CSV header combining both sensor streams (PRD §10.1).
         * Convenience for Member 4 so the complete header is in one place.
         *
         * Schema: `timestamp_ns,ax,ay,az,gx,gy,gz,gnss_lat,gnss_lon,gnss_speed,gnss_accuracy`
         *
         * Note: This is a `val` (not `const val`) because it references
         * [SensorSample.CSV_HEADER] from another class at runtime.
         */
        val UNIFIED_CSV_HEADER: String =
            "timestamp_ns," + com.sih.deadreckoninglite.sensors.SensorSample.CSV_HEADER + "," + CSV_HEADER

        // ---- private formatting helpers ----

        private fun f1(v: Float): String = "%.1f".format(v)
        private fun f4(v: Float): String = "%.4f".format(v)
        private fun f6(v: Double): String = "%.6f".format(v)
        private fun f8(v: Double): String = "%.8f".format(v)

        private const val EARTH_RADIUS_M = 6_371_000.0

        /**
         * Standard Haversine formula — accurate for any distance on a sphere.
         */
        private fun haversineMeters(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double
        ): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * asin(sqrt(a))
            return EARTH_RADIUS_M * c
        }
    }
}
