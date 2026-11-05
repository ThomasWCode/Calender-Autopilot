package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.domain.RingTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingTimeoutTest {
    @Test
    fun defaultIsOneOfTheOptions() {
        assertTrue(RingTimeout.DEFAULT_SEC in RingTimeout.OPTIONS_SEC)
        assertEquals(600, RingTimeout.DEFAULT_SEC)
    }

    @Test
    fun optionsAreTheAgreedOnes() {
        assertEquals(listOf(20, 60, 120, 300, 600), RingTimeout.OPTIONS_SEC)
    }

    @Test
    fun chosenValueIsKept() {
        for (s in RingTimeout.OPTIONS_SEC) assertEquals(s, RingTimeout.normalise(s))
    }

    @Test
    fun anythingElseFallsBackToTheDefault() {
        // 15 and 30 minutes were offered briefly; 1, 2, 5 and 10 are old-style minute values.
        for (s in listOf(Int.MIN_VALUE, -1, 0, 1, 10, 30, 900, 1800, 86_400, Int.MAX_VALUE)) {
            assertEquals("$s", RingTimeout.DEFAULT_SEC, RingTimeout.normalise(s))
        }
    }

    @Test
    fun labels() {
        assertEquals("20 seconds", RingTimeout.label(20))
        assertEquals("1 minute", RingTimeout.label(60))
        assertEquals("2 minutes", RingTimeout.label(120))
        assertEquals("5 minutes", RingTimeout.label(300))
        assertEquals("10 minutes", RingTimeout.label(600))
    }
}
