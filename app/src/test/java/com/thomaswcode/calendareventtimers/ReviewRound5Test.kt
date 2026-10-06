package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.booking.ManageController
import com.thomaswcode.calendareventtimers.booking.RoomList
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases from Codex's fifth review of PR #1 (2026-10-06). */
class ReviewRound5Test {
    private val day = LocalDate.of(2026, 10, 12)
    private val me = setOf("eiderwhi@lshtm.ac.uk")

    @Test
    fun aCalendarBookingForOneOfTwoLookalikeMeetingsIsLinkedToNeither() {
        val one = calEvent(1, "Planning", day, "10:00", "11:00")
        val two = calEvent(2, "Planning", day, "10:00", "10:30")
        val booking = calEvent(3, "Room Booking - Planning", day, "10:00", "11:00")
        val attendees = mapOf(1L to listOf(person("Ann", "ann@lshtm.ac.uk")), 3L to listOf(room("KS-121")))
        val b = ManageController.calendarOnly(listOf(booking), listOf(one, two, booking), attendees, RoomList.DEFAULT, me).single()
        assertEquals(-1L, b.originalEventId)
        assertTrue(b.occurrenceKey.startsWith("calendar:"))
        val warnings = ManageController.warnings(b, null, listOf(one, two, booking), emptyMap(), canCheck = true, unclear = true)
        assertTrue(warnings.single().contains("More than one “Planning”"))
        // With only one, it is linked.
        val linked = ManageController.calendarOnly(listOf(booking), listOf(one, booking), attendees, RoomList.DEFAULT, me).single()
        assertEquals(1L, linked.originalEventId)
    }
}
