package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.booking.KnownBooking
import com.thomaswcode.calendareventtimers.booking.ManageController
import com.thomaswcode.calendareventtimers.booking.PartialCover
import com.thomaswcode.calendareventtimers.booking.RoomCover
import com.thomaswcode.calendareventtimers.data.BookingEntity
import com.thomaswcode.calendareventtimers.data.BookingState
import com.thomaswcode.calendareventtimers.data.BookingStore
import com.thomaswcode.calendareventtimers.data.RoomReply
import com.thomaswcode.calendareventtimers.domain.EventParser
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases from Codex's fourth review of PR #1 (2026-10-06). */
class StoppedRunCleanupTest {
    private class FormStillOpen : Exception("form still open")

    /** What a stopped run ends with, after a cleanup that hops to [other] and back (as OutlookSession's does). */
    private fun endOf(cleanup: suspend (other: kotlinx.coroutines.CoroutineDispatcher) -> Unit): Throwable? = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { other ->
            var ended: Throwable? = null
            launch(Dispatchers.Default) {
                try {
                    try {
                        coroutineContext.job.cancel() // STOP
                        throw FormStillOpen() // what the run ends with
                    } finally {
                        cleanup(other)
                    }
                } catch (t: Throwable) {
                    ended = t
                }
            }.join()
            ended
        }
    }

    @Test
    fun aRunStoppedWithAFormOpenStillSaysSo() {
        // One hop back into the stopped run throws "cancelled", replacing what the run ended with.
        assertTrue(endOf { other -> withContext(NonCancellable + other) {} } is CancellationException)
        // Two (as OutlookSession now does): the failure survives the cleanup.
        assertTrue(endOf { other -> withContext(NonCancellable) { withContext(other) {} } } is FormStillOpen)
    }
}

class AmbiguousPairingTest {
    private val day = LocalDate.of(2026, 10, 12)

    private fun booking(id: Long, room: String, end: String) = BookingEntity(
        id = id, occurrenceKey = "orig@$id", seriesKey = "s", originalEventId = id, originalTitle = "Planning",
        eventDate = day.toString(), start = "09:00", end = end, bookingTitle = "Room Booking - Planning", room = room,
        notified = "", state = BookingState.SAVED, roomReply = RoomReply.WAITING, bookingSyncId = null, createdAt = id, checkedAt = null,
    )

    private fun event(id: Long, to: String) = calEvent(id, "Room Booking - Planning", day, "09:00", to)

    @Test
    fun lookalikeBookingsAreNotTiedByListOrder() {
        val bookings = listOf(booking(1, "KS-121", "09:30"), booking(2, "KS-121", "09:30"))
        val events = listOf(event(10, "09:30"), event(11, "09:30"))
        val pairs = BookingStore.pair(bookings, events) { "KS-121" }
        assertTrue(pairs.isEmpty())
        assertEquals(setOf(1L, 2L), BookingStore.unsure(bookings, events, pairs))
    }

    @Test
    fun aDifferentEndTellsThemApart() {
        val bookings = listOf(booking(1, "KS-121", "09:30"), booking(2, "KS-121", "10:00"))
        val events = listOf(event(10, "10:00"), event(11, "09:30"))
        val pairs = BookingStore.pair(bookings, events) { "KS-121" }
        assertEquals(11L, pairs.getValue(1).eventId)
        assertEquals(10L, pairs.getValue(2).eventId)
        assertTrue(BookingStore.unsure(bookings, events, pairs).isEmpty())
    }

    @Test
    fun theOnlyBookingAndTheOnlyEventStillPair() {
        // Its room was changed in Outlook before the app first saw it: still the one.
        val pairs = BookingStore.pair(listOf(booking(1, "KS-121", "09:30")), listOf(event(10, "09:30"))) { "KS-117" }
        assertEquals(10L, pairs.getValue(1).eventId)
    }
}

class TitlesInTheDayViewTest {
    @Test
    fun invisibleMarksAndCaseDontHideAnEvent() {
        val desc = "Monday 5 October, 10:00 to 11:00, Team call, at location KS-121"
        assertTrue(EventParser.descMentions(desc, "Team call"))
        assertTrue(EventParser.descMentions(desc, "⁨Team call⁩"))
        assertTrue(EventParser.descMentions(desc, "team CALL"))
    }
}

class OwnPartialBookingTest {
    private val day = LocalDate.of(2026, 10, 12)

    @Test
    fun theMeetingsOwnShortBookingIsANote() {
        val meeting = calEvent(1, "Planning", day, "09:00", "10:00")
        val short = KnownBooking("KS-121", RoomReply.WAITING, LocalTime.of(9, 0), LocalTime.of(9, 30))
        assertEquals(PartialCover("Room Booking - Planning", "KS-121", LocalTime.of(9, 0), LocalTime.of(9, 30)), RoomCover.partialFromKnown(short, meeting))
        // Whole, declined or unknown times: no note.
        assertNull(RoomCover.partialFromKnown(short.copy(end = LocalTime.of(10, 0)), meeting))
        assertNull(RoomCover.partialFromKnown(short.copy(reply = RoomReply.DECLINED), meeting))
        assertNull(RoomCover.partialFromKnown(KnownBooking("KS-121", RoomReply.WAITING), meeting))
    }
}

class OriginalAfterSyncIdTest {
    @Test
    fun theMeetingIsFoundAgainByItsRowAndTime() {
        val day = LocalDate.of(2026, 10, 12)
        val before = calEvent(5, "Planning", day, "10:00", "11:00", syncId = "")
        val now = before.copy(syncId = "AAMk…")
        val booking = BookingEntity(
            id = 1, occurrenceKey = before.occurrenceKey, seriesKey = before.seriesKey, originalEventId = 5, originalTitle = "Planning",
            eventDate = day.toString(), start = "10:00", end = "11:00", bookingTitle = "Room Booking - Planning", room = "KS-121",
            notified = "", state = BookingState.SAVED, roomReply = RoomReply.RESERVED, bookingSyncId = null, createdAt = 0, checkedAt = null,
        )
        assertEquals(now, ManageController.originalOf(booking, listOf(calEvent(6, "Room Booking - Planning", day, "10:00", "11:00"), now)))
        // Another time: not it.
        assertNull(ManageController.originalOf(booking, listOf(now.copy(start = LocalTime.of(12, 0)))))
    }
}
