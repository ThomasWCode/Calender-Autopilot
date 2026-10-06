package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.booking.ManageController
import com.thomaswcode.calendareventtimers.booking.RoomList
import com.thomaswcode.calendareventtimers.calendar.Attendee
import com.thomaswcode.calendareventtimers.calendar.CalendarRows
import com.thomaswcode.calendareventtimers.data.BookingEntity
import com.thomaswcode.calendareventtimers.data.BookingState
import com.thomaswcode.calendareventtimers.data.BookingStore
import com.thomaswcode.calendareventtimers.data.RoomReply
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases from Codex's sixth review of PR #1 (2026-10-06). */
class ReviewRound6Test {
    private fun calendar(id: Int, account: String) = mapOf(
        "_id" to "$id", "name" to "Calendar", "account_name" to account, "ownerAccount" to account,
        "account_type" to CalendarRows.OUTLOOK_ACCOUNT_TYPE, "calendar_access_level" to "700",
    )

    @Test
    fun theMainCalendarIsTheLshtmAccountsOnly() {
        val personal = calendar(12, "someone@outlook.com")
        val work = calendar(34, "eiderwhi@lshtm.ac.uk")
        assertEquals(34L, CalendarRows.mainCalendar(CalendarRows.calendars(listOf(personal, work)))!!.id)
        assertEquals(34L, CalendarRows.mainCalendar(CalendarRows.calendars(listOf(work, personal)))!!.id)
        assertNull("only a personal account", CalendarRows.mainCalendar(CalendarRows.calendars(listOf(personal))))
        assertNull("two LSHTM accounts: which?", CalendarRows.mainCalendar(CalendarRows.calendars(listOf(work, calendar(40, "other@lshtm.ac.uk")))))
    }

    private val day = LocalDate.of(2026, 10, 12)

    private fun booking() = BookingEntity(
        id = 1, occurrenceKey = "orig@1", seriesKey = "s", originalEventId = 1, originalTitle = "Planning",
        eventDate = day.toString(), start = "09:00", end = "09:30", bookingTitle = "Room Booking - Planning", room = "KS-121",
        notified = "", state = BookingState.SAVED, roomReply = RoomReply.WAITING, bookingSyncId = null, createdAt = 1, checkedAt = null,
    )

    private fun event(id: Long) = calEvent(id, "Room Booking - Planning", day, "09:00", "09:30")

    @Test
    fun anOldDeclinedEventIsNotANewBookingsOwn() {
        val old = event(10)
        val declined = { e: com.thomaswcode.calendareventtimers.calendar.CalEvent -> e.eventId == 10L }
        // Only the old one, whose room declined: not taken while the new booking may still be syncing.
        assertTrue(BookingStore.pair(listOf(booking()), listOf(old), declinedOf = declined) { "KS-121" }.isEmpty())
        // The new one arrives: that one.
        assertEquals(11L, BookingStore.pair(listOf(booking()), listOf(old, event(11)), declinedOf = declined) { "KS-121" }.getValue(1).eventId)
        // Synced, and still only the declined one: it is the booking's, its room declined.
        assertEquals(10L, BookingStore.pair(listOf(booking()), listOf(old), setOf(1L), declined) { "KS-121" }.getValue(1).eventId)
    }

    @Test
    fun theDeclinedTestLooksAtEveryRoom() {
        val rooms = RoomList.DEFAULT
        assertTrue(BookingStore.declined(listOf(room("KS-121", Attendee.STATUS_DECLINED)), rooms))
        assertTrue(!BookingStore.declined(listOf(room("KS-121", Attendee.STATUS_DECLINED), room("KS-117")), rooms))
        assertTrue(!BookingStore.declined(emptyList(), rooms))
    }

    @Test
    fun thePeopleToldAreTheBookingEventsPeople() {
        val attendees = listOf(
            room("KS-121"), person("Ann", "Ann@lshtm.ac.uk"), person(null, "eiderwhi@lshtm.ac.uk"), person("KS-117", "ks-117@lshtm.ac.uk"),
        )
        assertEquals(listOf("ann@lshtm.ac.uk"), ManageController.toldOn(attendees, RoomList.DEFAULT, setOf("eiderwhi@lshtm.ac.uk")))
    }
}
