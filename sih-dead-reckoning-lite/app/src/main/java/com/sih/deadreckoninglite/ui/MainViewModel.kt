package com.sih.deadreckoninglite.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.sih.deadreckoninglite.sensors.SensorSample

class MainViewModel : ViewModel() {
	enum class Mode { GNSS, DEAD_RECKONING }

	private val mode = MutableLiveData(Mode.GNSS)
	private val sample = MutableLiveData<SensorSample>()
	private val drift = MutableLiveData(0f)

	val currentMode: LiveData<Mode> = mode
	val latestSample: LiveData<SensorSample> = sample
	val driftEstimateM: LiveData<Float> = drift

	fun setMode(value: Mode) {
		mode.value = value
	}

	fun publishSample(value: SensorSample) {
		sample.value = value
	}

	fun setDriftEstimateM(value: Float) {
		drift.value = value.coerceAtLeast(0f)
	}
}
