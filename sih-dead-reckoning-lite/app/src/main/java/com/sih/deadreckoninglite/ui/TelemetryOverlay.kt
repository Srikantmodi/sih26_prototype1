package com.sih.deadreckoninglite.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure display formatting for the telemetry overlay.
 *
 * This is a **stateless** helper — it has no Android View references,
 * no context dependencies, and no side effects. All methods take raw
 * values and return formatted display strings.
 *
 * ## Thread Safety
 * All methods are pure functions — safe to call from any thread.
 *
 * ## Precision
 * - Accelerometer/Gyroscope: 2 decimal places (readable, not cluttered)
 * - Lat/Lon: 6 decimal places (~0.11 m resolution)
 * - Speed: 1 decimal place
 * - Drift: 1 decimal place (sub-meter precision is meaningless here)
 */
object TelemetryOverlay {

    // ---- Original PRD Contract Methods ----

    fun formatAccel(ax: Float, ay: Float, az: Float): String =
        "Accel  ${formatVector(ax, ay, az)} m/s²"

    fun formatGyro(gx: Float, gy: Float, gz: Float): String =
        "Gyro   ${formatVector(gx, gy, gz)} rad/s"

    fun formatPosition(lat: Double, lon: Double): String =
        String.format(Locale.US, "Position  %.6f, %.6f", lat, lon)

    fun formatDrift(distanceM: Float): String =
        String.format(Locale.US, "Drift  %.1f m", distanceM)

    // ---- New Methods for Enhanced Dashboard ----

    /**
     * Format speed from m/s to km/h for display.
     * Returns just the numeric value (e.g. "48.2") without units —
     * the unit label is a separate TextView in the layout.
     */
    fun formatSpeed(speedMps: Float): String {
        val kmh = speedMps * 3.6f
        return String.format(Locale.US, "%.1f", kmh)
    }

    /**
     * Format latitude with N/S hemisphere suffix.
     * E.g. "12.971602° N"
     */
    fun formatLatitude(lat: Double): String {
        val hemisphere = if (lat >= 0) "N" else "S"
        return String.format(Locale.US, "%.6f° %s", Math.abs(lat), hemisphere)
    }

    /**
     * Format longitude with E/W hemisphere suffix.
     * E.g. "77.594566° E"
     */
    fun formatLongitude(lon: Double): String {
        val hemisphere = if (lon >= 0) "E" else "W"
        return String.format(Locale.US, "%.6f° %s", Math.abs(lon), hemisphere)
    }

    /**
     * Format a drift distance with units.
     * E.g. "0.42 m"
     */
    fun formatDriftValue(distanceM: Float): String =
        String.format(Locale.US, "%.2f m", distanceM)

    /**
     * Format a single axis value for the X/Y/Z grid cells.
     * E.g. "0.04", "9.81", "-0.12"
     */
    fun formatAxisValue(value: Float): String =
        String.format(Locale.US, "%.2f", value)

    /**
     * Format a single axis value for gyroscope (higher precision).
     * E.g. "0.001", "-0.004", "0.015"
     */
    fun formatGyroAxis(value: Float): String =
        String.format(Locale.US, "%.3f", value)

    /**
     * Format current UTC time for the position card.
     * E.g. "UTC 14:22:08.4"
     */
    fun formatUtcTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.S", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return "UTC ${sdf.format(Date())}"
    }

    // ---- Private Helpers ----

    private fun formatVector(x: Float, y: Float, z: Float): String =
        String.format(Locale.US, "(%+.2f, %+.2f, %+.2f)", x, y, z)
}
