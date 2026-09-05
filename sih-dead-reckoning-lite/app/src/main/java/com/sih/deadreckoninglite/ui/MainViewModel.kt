package com.sih.deadreckoninglite.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.sih.deadreckoninglite.location.GpsSample
import com.sih.deadreckoninglite.sensors.SensorSample

/**
 * Holds the live UI state for the main dashboard.
 *
 * ## Architecture Rule
 * [MainActivity] is the **only** writer — it pushes state into this ViewModel
 * via the `set*` / `publish*` methods. The UI layer (TextViews, badges, etc.)
 * observes the exposed [LiveData] fields.
 *
 * ## Fields
 * - [currentMode] — GNSS or DEAD_RECKONING, drives badge color + text
 * - [latestSample] — most recent merged IMU sample (accel + gyro)
 * - [latestGps] — most recent GPS fix (lat, lon, speed, bearing, accuracy)
 * - [driftEstimateM] — distance between last real GPS fix and current
 *   reckoned position, in meters (only meaningful during DR mode)
 * - [csvLoggingActive] — whether CSV logging is currently running
 */
class MainViewModel : ViewModel() {

    enum class Mode { GNSS, DEAD_RECKONING }

    // ---- Mutable (private) ----
    private val _mode = MutableLiveData(Mode.GNSS)
    private val _sample = MutableLiveData<SensorSample>()
    private val _gpsSample = MutableLiveData<GpsSample>()
    private val _drift = MutableLiveData(0f)
    private val _csvActive = MutableLiveData(false)

    // ---- Observable (public) ----
    val currentMode: LiveData<Mode> = _mode
    val latestSample: LiveData<SensorSample> = _sample
    val latestGps: LiveData<GpsSample> = _gpsSample
    val driftEstimateM: LiveData<Float> = _drift
    val csvLoggingActive: LiveData<Boolean> = _csvActive

    // ---- Setters for MainActivity ----

    fun setMode(value: Mode) {
        _mode.value = value
    }

    fun publishSample(value: SensorSample) {
        _sample.value = value
    }

    fun publishGps(value: GpsSample) {
        _gpsSample.value = value
    }

    fun setDriftEstimateM(value: Float) {
        _drift.value = value.coerceAtLeast(0f)
    }

    fun setCsvLoggingActive(active: Boolean) {
        _csvActive.value = active
    }
}
