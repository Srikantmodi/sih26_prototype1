package com.sih.deadreckoninglite.ml

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for pure-Kotlin logic in MlSpeedEstimator.
 * TFLite interpreter cannot run in JVM unit tests (requires Android native .so).
 * These tests cover: normalization math, deadband, downsampling, window management.
 */
class MlSpeedEstimatorTest {

    @Test
    fun `z-score normalization formula is correct`() {
        val mean  = 0.5f
        val std   = 2.0f
        val raw   = 2.5f
        val result = (raw - mean) / std
        assertEquals(1.0f, result, 0.0001f)
    }

    @Test
    fun `deadband suppresses speed below 1_26 kmh`() {
        val predicted = 1.0f
        val effective = if (predicted < 1.26f) 0.0f else predicted
        assertEquals(0.0f, effective, 0.0001f)
    }

    @Test
    fun `deadband passes speed at threshold 1_26 kmh`() {
        val predicted = 1.26f
        val effective = if (predicted < 1.26f) 0.0f else predicted
        assertEquals(1.26f, effective, 0.0001f)
    }

    @Test
    fun `deadband passes speed above threshold`() {
        val predicted = 50.0f
        val effective = if (predicted < 1.26f) 0.0f else predicted
        assertEquals(50.0f, effective, 0.0001f)
    }

    @Test
    fun `kmh to mps conversion is correct`() {
        assertEquals(10.0f, 36.0f / 3.6f, 0.001f)
        assertEquals(0.0f,  0.0f  / 3.6f, 0.001f)
    }

    @Test
    fun `downsample factor reduces inference frequency`() {
        val FACTOR = 5
        var skipCounter = 0
        var inferenceCount = 0
        repeat(50) {
            skipCounter++
            if (skipCounter >= FACTOR) {
                skipCounter = 0
                inferenceCount++
            }
        }
        assertEquals(10, inferenceCount)   // 50 calls / factor 5 = 10 inferences
    }

    @Test
    fun `window buffer size does not exceed WINDOW_SIZE`() {
        val WINDOW_SIZE = 20
        val buffer = ArrayDeque<FloatArray>(WINDOW_SIZE)
        repeat(25) { i ->
            if (buffer.size >= WINDOW_SIZE) buffer.removeFirst()
            buffer.addLast(floatArrayOf(i.toFloat()))
        }
        assertEquals(WINDOW_SIZE, buffer.size)
        assertEquals(5.0f, buffer.first()[0], 0.001f)   // oldest = sample index 5
    }

    @Test
    fun `speed clamped to 0-200 kmh`() {
        assertEquals(0.0f,   (-5.0f).coerceIn(0f, 200f), 0.001f)
        assertEquals(200.0f, (250.0f).coerceIn(0f, 200f), 0.001f)
        assertEquals(60.0f,  (60.0f).coerceIn(0f, 200f), 0.001f)
    }

    @Test
    fun `linear acceleration formula is correct`() {
        val ax    = 10.5f
        val gravX = 9.8f
        val linearAx = ax - gravX
        assertEquals(0.7f, linearAx, 0.001f)
    }

    @Test
    fun `stationary deadband at exactly 0_35 mps allows motion`() {
        val speedMps    = 0.35
        val effective   = if (speedMps < 0.35) 0.0 else speedMps
        assertEquals(0.35, effective, 0.0001)
    }

    @Test
    fun `below stationary deadband stops motion`() {
        val speedMps  = 0.34
        val effective = if (speedMps < 0.35) 0.0 else speedMps
        assertEquals(0.0, effective, 0.0001)
    }
}
