package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.booking.ManageController
import com.thomaswcode.calendareventtimers.data.BookingEntity
import com.thomaswcode.calendareventtimers.data.BookingState
import com.thomaswcode.calendareventtimers.data.RoomReply
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageWarningsTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val original = calEvent(1, "Planning meeting", day, "09:00", "09:30")

    private fun booking(reply: RoomReply = RoomReply.RESERVED, start: String = "09:00", end: String = "09:30") = BookingEntity(
        id = 7, occurrenceKey = original.occurrenceKey, seriesKey = original.seriesKey, originalEventId = 1,
        originalTitle = "Planning meeting", eventDate = day.toString(), start = start, end = end,
        bookingTitle = "Room Booking - Planning meeting", room = "KS-103D", notified = "", state = BookingState.SAVED,
        roomReply = reply, bookingSyncId = null, createdAt = 0, checkedAt = null,
    )

    @Test
    fun nothingToSay() {
        val labels = mapOf(original.labelKey to listOf("Moveable"))
        assertTrue(ManageController.warnings(booking(), original, listOf(original), labels, canCheck = true).isEmpty())
    }

    @Test
    fun declinedOrGoneFromTheCalendar() {
        assertEquals(1, ManageController.warnings(booking(RoomReply.DECLINED), original, listOf(original), emptyMap(), true).size)
        assertTrue(ManageController.warnings(booking(RoomReply.NOT_FOUND), original, listOf(original), emptyMap(), true).single().contains("isn't in your calendar"))
    }

    @Test
    fun theOriginalMovedOrWent() {
        val moved = calEvent(2, "Planning meeting", day, "10:00", "10:30")
        val w = ManageController.warnings(booking(), null, listOf(moved), emptyMap(), true).single()
        assertTrue(w, w.contains("now starts at 10:00"))
        assertTrue(ManageController.warnings(booking(), null, emptyList(), emptyMap(), true).single().contains("is no longer on"))
        // Without calendar access nothing can be said about the original.
        assertTrue(ManageController.warnings(booking(), null, emptyList(), emptyMap(), canCheck = false).isEmpty())
    }

    @Test
    fun relabelledOrLonger() {
        val labels = mapOf(original.labelKey to listOf("Urgent"))
        assertTrue(ManageController.warnings(booking(), original, listOf(original), labels, true).single().contains("isn't labelled"))
        val longer = ManageController.warnings(booking(end = "09:15"), original, listOf(original), emptyMap(), true).single()
        assertTrue(longer, longer.contains("now ends at 09:30"))
    }
}
