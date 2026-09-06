package com.sih.deadreckoninglite.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.sih.deadreckoninglite.location.GpsSample
import com.sih.deadreckoninglite.sensors.SensorSample

/**
 * Holds live UI state for the main dashboard.
 *
 * ## Architecture Rule
 * MainActivity is the ONLY writer via the set* / publish* methods.
 *
 * ## Fields (new in ML version)
 * - mlSpeedMps: current ML-predicted speed (null = model not ready yet)
 * - mlReady: whether MlSpeedEstimator has accumulated enough samples
 */
class MainViewModel : ViewModel() {

    enum class Mode { GNSS, DEAD_RECKONING }

    private val _mode        = MutableLiveData(Mode.GNSS)
    private val _sample      = MutableLiveData<SensorSample>()
    private val _gpsSample   = MutableLiveData<GpsSample>()
    private val _drift       = MutableLiveData(0f)
    private val _csvActive   = MutableLiveData(false)
    private val _mlSpeedMps  = MutableLiveData<Float?>(null)   // NEW
    private val _mlReady     = MutableLiveData(false)           // NEW

    val currentMode:      LiveData<Mode>         = _mode
    val latestSample:     LiveData<SensorSample> = _sample
    val latestGps:        LiveData<GpsSample>    = _gpsSample
    val driftEstimateM:   LiveData<Float>        = _drift
    val csvLoggingActive: LiveData<Boolean>      = _csvActive
    val mlSpeedMps:       LiveData<Float?>       = _mlSpeedMps   // NEW
    val mlReady:          LiveData<Boolean>      = _mlReady      // NEW

    fun setMode(value: Mode)               { _mode.value        = value  }
    fun publishSample(value: SensorSample) { _sample.value      = value  }
    fun publishGps(value: GpsSample)       { _gpsSample.value   = value  }
    fun setDriftEstimateM(value: Float)    { _drift.value       = value.coerceAtLeast(0f) }
    fun setCsvLoggingActive(active: Boolean){ _csvActive.value  = active }
    fun setMlSpeedMps(value: Float?)       { _mlSpeedMps.value  = value  }   // NEW
    fun setMlReady(ready: Boolean)         { _mlReady.value     = ready  }   // NEW
}
