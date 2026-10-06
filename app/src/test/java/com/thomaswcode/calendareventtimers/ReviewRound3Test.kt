package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.data.BookingEntity
import com.thomaswcode.calendareventtimers.data.BookingState
import com.thomaswcode.calendareventtimers.data.BookingStore
import com.thomaswcode.calendareventtimers.data.RoomReply
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cases from Codex's third review of PR #1 (2026-10-06). */
class ReviewRound3Test {
    private val made: Instant = ZonedDateTime.of(2026, 10, 9, 15, 0, 0, 0, ZoneId.of("Europe/London")).toInstant()

    private fun at(minutes: Long): Instant = made.plus(Duration.ofMinutes(minutes))

    private fun booking(reply: RoomReply, checkedAt: Instant?) = BookingEntity(
        id = 1, occurrenceKey = "orig@1", seriesKey = "s", originalEventId = 1, originalTitle = "Planning",
        eventDate = "2026-10-12", start = "10:00", end = "11:00", bookingTitle = "Room Booking - Planning", room = "KS-117",
        notified = "", state = BookingState.SAVED, roomReply = reply, bookingSyncId = "A",
        createdAt = made.toEpochMilli(), checkedAt = checkedAt?.toEpochMilli(),
    )

    @Test
    fun aBookingJustChangedGetsTimeToSyncBeforeItCountsAsMissing() {
        // Made long ago; its room was changed a minute ago and Outlook hasn't synced the event back yet.
        val changed = booking(RoomReply.WAITING, checkedAt = at(24 * 60))
        assertEquals(RoomReply.WAITING, BookingStore.replyFor(changed, false, emptyList(), at(24 * 60 + 1)).reply)
        assertEquals(RoomReply.NOT_FOUND, BookingStore.replyFor(changed, false, emptyList(), at(24 * 60 + 16)).reply)
        // Reserved, then gone: kept as it was until synced, never shown as waiting again.
        assertEquals(RoomReply.RESERVED, BookingStore.replyFor(booking(RoomReply.RESERVED, at(30)), false, emptyList(), at(35)).reply)
    }

    @Test
    fun onlyTheUsersOwnBookingEventsAreTheAppsBookings() {
        val day = LocalDate.of(2026, 10, 12)
        val mine = calEvent(1, "Room Booking - Planning", day, "10:00", "11:00")
        val theirs = calEvent(2, "Room Booking - Planning", day, "10:00", "11:00", organizer = "Someone.Else@lshtm.ac.uk")
        val meeting = calEvent(3, "Planning", day, "10:00", "11:00")
        assertEquals(listOf(mine), BookingStore.ownBookingEvents(listOf(mine, theirs, meeting), setOf("eiderwhi@lshtm.ac.uk")))
    }
}
