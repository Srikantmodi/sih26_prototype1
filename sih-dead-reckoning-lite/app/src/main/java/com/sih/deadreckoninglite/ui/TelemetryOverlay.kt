package com.sih.deadreckoninglite.ui

import java.util.Locale

/** Pure display formatting for the telemetry overlay. */
object TelemetryOverlay {
	fun formatAccel(ax: Float, ay: Float, az: Float): String =
		"Accel  ${formatVector(ax, ay, az)} m/s²"

	fun formatGyro(gx: Float, gy: Float, gz: Float): String =
		"Gyro   ${formatVector(gx, gy, gz)} rad/s"

	fun formatPosition(lat: Double, lon: Double): String =
		String.format(Locale.US, "Position  %.6f, %.6f", lat, lon)

	fun formatDrift(distanceM: Float): String =
		String.format(Locale.US, "Drift  %.1f m", distanceM)

	private fun formatVector(x: Float, y: Float, z: Float): String =
		String.format(Locale.US, "(%+.2f, %+.2f, %+.2f)", x, y, z)
}
