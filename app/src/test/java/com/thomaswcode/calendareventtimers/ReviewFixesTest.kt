package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.booking.FormText
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.data.BookingEntity
import com.thomaswcode.calendareventtimers.data.BookingState
import com.thomaswcode.calendareventtimers.data.BookingStore
import com.thomaswcode.calendareventtimers.data.RoomReply
import com.thomaswcode.calendareventtimers.outlook.AlertSheetReader
import com.thomaswcode.calendareventtimers.outlook.Box
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases from the code review of 2026-10-05. */
class TitleComparisonTest {
    @Test
    fun outlooksCleanedTextMatchesWhatWasTyped() {
        // Outlook shows a non-breaking space as a space and drops bidi marks.
        assertTrue(FormText.sameText("Room Booking - Team call", "Room Booking - Team call"))
        assertTrue(FormText.sameText("⁨Room Booking - Team call⁩", "Room Booking -  Team call"))
        assertFalse(FormText.sameText("Room Booking - Team call", "Room Booking - Team Call"))
        assertEquals(CalEvent.normaliseTitle("Team Call "), CalEvent.normaliseTitle("team call"))
    }
}

class AlertSheetDetectionTest {
    private fun text(t: String) = XmlNode("android.widget.TextView", null, t, null, false, false, false, Box(0, 0, 1, 1), emptyList())
    private fun window(vararg kids: XmlNode) = XmlNode(null, null, null, null, false, false, false, Box(0, 0, 1080, 2400), kids.toList())

    @Test
    fun theFormsOwnAlertRowIsNotTheSheet() {
        // A form whose default alert is "At time of event" shows that text in its Alert row.
        val form = window(window(text("Alert"), text("At time of event"), text("Show as"), text("Busy")))
        assertFalse(AlertSheetReader.isOpen(form))
        val sheet = window(window(text("None"), text("At time of event"), text("5 minutes before"), text("10 minutes before")))
        assertTrue(AlertSheetReader.isOpen(sheet))
    }
}

class BookingPairingTest {
    private val day = LocalDate.of(2026, 10, 12)

    private fun booking(id: Long, room: String, syncId: String? = null, createdAt: Long = id) = BookingEntity(
        id = id, occurrenceKey = "orig@1", seriesKey = "s", originalEventId = 1, originalTitle = "Planning",
        eventDate = day.toString(), start = "09:00", end = "09:30", bookingTitle = "Room Booking - Planning", room = room,
        notified = "", state = BookingState.SAVED, roomReply = RoomReply.WAITING, bookingSyncId = syncId, createdAt = createdAt, checkedAt = null,
    )

    @Test
    fun aRebookingAndTheDeclinedBookingAreToldApart() {
        // The first booking's room (KS-121) declined; the event was booked again in KS-117.
        val declined = calEvent(10, "Room Booking - Planning", day, "09:00", "09:30")
        val rebooked = calEvent(11, "Room Booking - Planning", day, "09:00", "09:30")
        val rooms = mapOf(10L to "KS-121", 11L to "KS-117")
        val pairs = BookingStore.pair(listOf(booking(1, "KS-121"), booking(2, "KS-117")), listOf(declined, rebooked)) { rooms[it.eventId] }
        assertEquals(10L, pairs.getValue(1).eventId)
        assertEquals(11L, pairs.getValue(2).eventId)
    }

    @Test
    fun aKnownSyncIdWinsAndNoEventServesTwoBookings() {
        val a = calEvent(10, "Room Booking - Planning", day, "09:00", "09:30", syncId = "A")
        val b = calEvent(11, "Room Booking - Planning", day, "09:00", "09:30", syncId = "B")
        val pairs = BookingStore.pair(listOf(booking(1, "KS-121", syncId = "B"), booking(2, "KS-121")), listOf(a, b)) { "KS-121" }
        assertEquals("B", pairs.getValue(1).syncId)
        assertEquals("A", pairs.getValue(2).syncId)
        // Only one event: the second booking isn't given it too.
        val one = BookingStore.pair(listOf(booking(1, "KS-121"), booking(2, "KS-117")), listOf(a)) { "KS-121" }
        assertEquals(setOf(1L), one.keys)
    }
}
