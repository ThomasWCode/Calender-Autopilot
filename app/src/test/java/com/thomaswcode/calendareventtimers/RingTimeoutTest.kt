package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.domain.RingTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingTimeoutTest {
    @Test
    fun defaultIsOneOfTheOptions() {
        assertTrue(RingTimeout.DEFAULT_MIN in RingTimeout.OPTIONS_MIN)
        assertEquals(10, RingTimeout.DEFAULT_MIN)
    }

    @Test
    fun optionsAreAscendingAndShortEnoughToMiss() {
        assertEquals(RingTimeout.OPTIONS_MIN.sorted(), RingTimeout.OPTIONS_MIN)
        assertEquals(RingTimeout.OPTIONS_MIN.distinct(), RingTimeout.OPTIONS_MIN)
        assertTrue(RingTimeout.OPTIONS_MIN.all { it > 0 })
    }

    @Test
    fun chosenValueIsKept() {
        for (m in RingTimeout.OPTIONS_MIN) assertEquals(m, RingTimeout.normalise(m))
    }

    @Test
    fun anythingElseFallsBackToTheDefault() {
        for (m in listOf(Int.MIN_VALUE, -1, 0, 3, 11, 60, 24 * 60, Int.MAX_VALUE)) {
            assertEquals("$m", RingTimeout.DEFAULT_MIN, RingTimeout.normalise(m))
        }
    }

    @Test
    fun labels() {
        assertEquals("1 minute", RingTimeout.label(1))
        assertEquals("2 minutes", RingTimeout.label(2))
        assertEquals("10 minutes", RingTimeout.label(10))
    }
}
