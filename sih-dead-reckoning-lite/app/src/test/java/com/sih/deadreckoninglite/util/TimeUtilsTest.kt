package com.sih.deadreckoninglite.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeUtilsTest {

    @Test
    fun testNanosToMillis() {
        val nanos = 2_500_000_000L
        val millis = TimeUtils.nanosToMillis(nanos)
        assertEquals(2500L, millis)
    }

    @Test
    fun testNanosToSeconds() {
        val nanos = 1_500_000_000L
        val seconds = TimeUtils.nanosToSeconds(nanos)
        assertEquals(1.5, seconds, 1e-9)
    }

    @Test
    fun testMillisToNanos() {
        val millis = 1000L
        val nanos = TimeUtils.millisToNanos(millis)
        assertEquals(1_000_000_000L, nanos)
    }
}
