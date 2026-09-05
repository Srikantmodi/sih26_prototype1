package com.sih.deadreckoninglite.util

import android.hardware.SensorManager

/**
 * Shared constants for the Dead Reckoning Lite app.
 *
 * ## Purpose
 * Single source of truth for values used across multiple modules.
 * Prevents magic-number duplication and ensures all members' code stays
 * in sync without coordination overhead.
 *
 * ## Who Uses What
 *
 * | Constant | Used By |
 * |---|---|
 * | [GPS_UPDATE_INTERVAL_MS], [GPS_FASTEST_INTERVAL_MS] | Member 2 (GpsProvider) |
 * | [IMU_SENSOR_DELAY] | Member 2 (ImuManager) |
 * | [IMU_APPROX_RATE_HZ] | Member 4 (SensorLogger — for doc/logging context) |
 * | [EARTH_RADIUS_M] | Member 1 (ConstantVelocityReckoner), Member 2 (GpsSample.distanceTo) |
 * | [LOG_DIR_NAME], [LOG_FILE_PREFIX] | Member 4 (SensorLogger, CsvExporter) |
 * | [TUNNEL_SIM_TICK_MS] | Member 1 (TunnelSimulator projection tick) |
 * | [DR_PATH_COLOR], [GNSS_PATH_COLOR] | Member 5 (MapController polyline colors) |
 * | [MIN_SDK_VERSION] | Member 3 (documentation / manifest cross-check) |
 */
object Constants {

    // ================================================================== //
    //  GPS / Location                                                     //
    // ================================================================== //

    /**
     * Target interval between GPS fixes, in milliseconds.
     * ~1 Hz — matches PRD §10.1 schema and real GNSS receiver rates.
     *
     * Used by: GpsProvider → LocationRequest.Builder interval parameter.
     */
    const val GPS_UPDATE_INTERVAL_MS: Long = 1_000L

    /**
     * Fastest acceptable GPS fix interval, in milliseconds.
     * Allows accepting faster updates if the system offers them (e.g., another
     * app is already requesting high-frequency location updates).
     *
     * Used by: GpsProvider → LocationRequest.Builder.setMinUpdateIntervalMillis.
     */
    const val GPS_FASTEST_INTERVAL_MS: Long = 500L

    // ================================================================== //
    //  IMU / Sensors                                                      //
    // ================================================================== //

    /**
     * Android SensorManager delay constant for sensor registration.
     *
     * SENSOR_DELAY_GAME (~50 Hz on most devices) provides a good balance
     * between data density for future ML training and CPU/battery usage.
     * We intentionally over-sample relative to [IMU_APPROX_RATE_HZ] because
     * down-sampling later is trivial; under-sampling is not recoverable.
     *
     * Used by: ImuManager → sensorManager.registerListener delay parameter.
     */
    const val IMU_SENSOR_DELAY: Int = SensorManager.SENSOR_DELAY_GAME

    /**
     * Approximate effective IMU output rate in Hz, for documentation and
     * logging context. This is NOT a hard cap enforced in code — it reflects
     * the typical rate from [IMU_SENSOR_DELAY] on most Android hardware.
     *
     * The actual emission rate equals the accelerometer's hardware rate
     * (since we emit only on accel ticks, not on every sensor event).
     *
     * Used by: Member 4 (SensorLogger) for log headers / metadata.
     */
    const val IMU_APPROX_RATE_HZ: Int = 50

    /**
     * PRD-specified target rate for IMU data. Referenced in documentation
     * and for any future throttling/downsampling logic.
     */
    const val IMU_TARGET_RATE_HZ: Int = 10

    // ================================================================== //
    //  Earth / Geodesy                                                    //
    // ================================================================== //

    /**
     * Mean Earth radius in meters (WGS-84 approximation).
     *
     * Used for:
     * - Member 1 (ConstantVelocityReckoner): equirectangular projection in
     *   [project()] — converting distance-in-meters to lat/lon deltas
     * - Member 2 (GpsSample): Haversine distance calculation in [distanceTo()]
     *
     * Both modules MUST use the same value to avoid subtle inconsistencies
     * in drift distance calculations.
     */
    const val EARTH_RADIUS_M: Double = 6_371_000.0

    // ================================================================== //
    //  Data Logging                                                       //
    // ================================================================== //

    /**
     * Subdirectory name under `context.getExternalFilesDir(null)` where
     * CSV log files are stored.
     *
     * Full path: `<externalFilesDir>/logs/drive_<timestamp>.csv`
     *
     * Used by: Member 4 (SensorLogger) for file creation,
     *          Member 4 (CsvExporter) for locating the latest log.
     */
    const val LOG_DIR_NAME: String = "logs"

    /**
     * Filename prefix for CSV log files.
     * Full filename: `drive_<System.currentTimeMillis()>.csv`
     *
     * Used by: Member 4 (SensorLogger).
     */
    const val LOG_FILE_PREFIX: String = "drive_"

    // ================================================================== //
    //  Dead Reckoning / Tunnel Simulation                                 //
    // ================================================================== //

    /**
     * Interval in milliseconds between tunnel-simulation projection ticks.
     * At 1000ms (1 Hz), the TunnelSimulator projects a new position once
     * per second — matching the GPS update rate for a smooth handoff.
     *
     * Used by: Member 1 (TunnelSimulator) internal ticker/handler.
     */
    const val TUNNEL_SIM_TICK_MS: Long = 1_000L

    // ================================================================== //
    //  Map / UI Colors (as ARGB int literals)                             //
    // ================================================================== //

    /**
     * Color for the GNSS-sourced path polyline on the map.
     * Green (#FF4CAF50) — matches the PRD's "green = GNSS mode" convention.
     *
     * Used by: Member 5 (MapController.addToRealPath).
     */
    const val GNSS_PATH_COLOR: Int = 0xFF4CAF50.toInt()

    /**
     * Color for the dead-reckoned path polyline on the map.
     * Amber/Orange (#FFFF9800) — matches the PRD's "amber = dead-reckoning mode"
     * convention.
     *
     * Used by: Member 5 (MapController.addToReckonedPath).
     */
    const val DR_PATH_COLOR: Int = 0xFFFF9800.toInt()

    /**
     * Color for the GNSS mode badge.
     * Same green as [GNSS_PATH_COLOR] for visual consistency.
     */
    const val GNSS_BADGE_COLOR: Int = GNSS_PATH_COLOR

    /**
     * Color for the dead-reckoning mode badge.
     * Same amber as [DR_PATH_COLOR] for visual consistency.
     */
    const val DR_BADGE_COLOR: Int = DR_PATH_COLOR

    // ================================================================== //
    //  App-Level                                                          //
    // ================================================================== //

    /**
     * Minimum Android SDK version this app targets.
     * API 26 = Android 8.0 (Oreo). PRD specifies Android 10+ (API 29)
     * as the target, but API 26 as the minimum for broader device coverage.
     */
    const val MIN_SDK_VERSION: Int = 26

    /**
     * Target Android SDK version.
     */
    const val TARGET_SDK_VERSION: Int = 34
}
