package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.booking.Answers
import com.thomaswcode.calendareventtimers.booking.BookingCandidate
import com.thomaswcode.calendareventtimers.booking.BookingPlanner
import com.thomaswcode.calendareventtimers.booking.BookingRules
import com.thomaswcode.calendareventtimers.booking.Cover
import com.thomaswcode.calendareventtimers.booking.DescriptionText
import com.thomaswcode.calendareventtimers.booking.KnownBooking
import com.thomaswcode.calendareventtimers.booking.ManageController
import com.thomaswcode.calendareventtimers.booking.Recheck
import com.thomaswcode.calendareventtimers.booking.RoomChoice
import com.thomaswcode.calendareventtimers.booking.RoomCover
import com.thomaswcode.calendareventtimers.booking.RoomList
import com.thomaswcode.calendareventtimers.booking.RoomStatus
import com.thomaswcode.calendareventtimers.calendar.Attendee
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.data.BookingEntity
import com.thomaswcode.calendareventtimers.data.BookingState
import com.thomaswcode.calendareventtimers.data.BookingStore
import com.thomaswcode.calendareventtimers.data.RoomReply
import com.thomaswcode.calendareventtimers.outlook.DetailsReader
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases from Codex's second review of PR #1 and another agent's review (2026-10-06). */
class ReplyForTheBookingsOwnRoomTest {
    private val made: Instant = ZonedDateTime.of(2026, 10, 9, 15, 0, 0, 0, ZoneId.of("Europe/London")).toInstant()

    private fun booking(room: String, reply: RoomReply, checkedAt: Instant? = null) = BookingEntity(
        id = 1, occurrenceKey = "orig@1", seriesKey = "s", originalEventId = 1, originalTitle = "Planning",
        eventDate = "2026-10-12", start = "10:00", end = "11:00", bookingTitle = "Room Booking - Planning", room = room,
        notified = "", state = BookingState.SAVED, roomReply = reply, bookingSyncId = "A",
        createdAt = made.toEpochMilli(), checkedAt = checkedAt?.toEpochMilli(),
    )

    private fun at(minutes: Long): Instant = made.plus(Duration.ofMinutes(minutes))

    @Test
    fun theOldRoomsReplyIsNotTheNewRooms() {
        // Changed from KS-121 (declined) to KS-117 a minute ago; the calendar still shows KS-121.
        val changed = booking("KS-117", RoomReply.WAITING, checkedAt = at(600))
        val check = BookingStore.replyFor(changed, true, listOf("KS-121" to RoomReply.DECLINED), at(601))
        assertEquals(BookingStore.ReplyCheck(RoomReply.WAITING), check)
        // The new room arrives, with its reply.
        assertEquals(
            BookingStore.ReplyCheck(RoomReply.RESERVED),
            BookingStore.replyFor(changed, true, listOf("KS-121" to RoomReply.DECLINED, "KS-117" to RoomReply.RESERVED), at(603)),
        )
    }

    @Test
    fun aRoomChangedInOutlookIsFollowedOnceSynced() {
        val old = booking("KS-121", RoomReply.RESERVED, checkedAt = at(2))
        assertEquals(BookingStore.ReplyCheck(RoomReply.TENTATIVE, room = "KS-117"), BookingStore.replyFor(old, true, listOf("KS-117" to RoomReply.TENTATIVE), at(600)))
    }
}

class BookingPairingRound2Test {
    private val day = LocalDate.of(2026, 10, 12)

    private fun booking(id: Long, syncId: String?, start: String = "09:00") = BookingEntity(
        id = id, occurrenceKey = "orig@$id", seriesKey = "s", originalEventId = 1, originalTitle = "Planning",
        eventDate = day.toString(), start = start, end = "09:30", bookingTitle = "Room Booking - Planning", room = "KS-121",
        notified = "", state = BookingState.SAVED, roomReply = RoomReply.RESERVED, bookingSyncId = syncId, createdAt = id, checkedAt = null,
    )

    private fun event(id: Long, syncId: String, from: String = "09:00", to: String = "09:30", cancelled: Boolean = false) =
        calEvent(id, "Room Booking - Planning", day, from, to, syncId = syncId, cancelled = cancelled)

    @Test
    fun aKnownBookingThatVanishedIsNotGivenALookalike() {
        assertTrue(BookingStore.pair(listOf(booking(1, "A")), listOf(event(11, "B"))) { "KS-121" }.isEmpty())
    }

    @Test
    fun aBookingMovedInOutlookNoLongerCountsAtItsTime() {
        assertTrue(BookingStore.pair(listOf(booking(1, "A")), listOf(event(10, "A", "11:00", "11:30"))) { "KS-121" }.isEmpty())
    }

    @Test
    fun aNewBookingIsFoundByItsTitleAndTime() {
        assertEquals(10L, BookingStore.pair(listOf(booking(1, null)), listOf(event(10, "A"))) { "KS-121" }.getValue(1).eventId)
    }

    @Test
    fun aCancelledBookingIsNotPaired() {
        assertTrue(BookingStore.pair(listOf(booking(1, "A")), listOf(event(10, "A", cancelled = true))) { "KS-121" }.isEmpty())
        assertTrue(BookingStore.pair(listOf(booking(1, null)), listOf(event(10, "A", cancelled = true))) { "KS-121" }.isEmpty())
    }

    @Test
    fun anEventAnotherBookingHasSeenIsNotTaken() {
        // Booking 1's event (A) is at another time now; booking 2 is new: A isn't booking 2's.
        val moved = event(10, "A")
        val pairs = BookingStore.pair(listOf(booking(1, "A", start = "08:00"), booking(2, null)), listOf(moved)) { "KS-121" }
        assertTrue(pairs.isEmpty())
    }
}

class CoverRulesRound2Test {
    private val day = LocalDate.of(2026, 10, 12)
    private val rooms = RoomList.DEFAULT
    private val me = setOf("eiderwhi@lshtm.ac.uk")
    private val none = { _: CalEvent -> emptyList<Attendee>() }
    private val meeting = calEvent(1, "Planning", day, "09:00", "10:00")

    @Test
    fun aShorterBookingDoesntHideAMeetingMadeLonger() {
        val short = KnownBooking("KS-121", RoomReply.RESERVED, LocalTime.of(9, 0), LocalTime.of(9, 30))
        assertNull(RoomCover.cover(meeting, emptyList(), short, emptyList(), none, rooms, me))
        val whole = KnownBooking("KS-121", RoomReply.RESERVED, LocalTime.of(9, 0), LocalTime.of(10, 0))
        assertEquals(Cover.AppBooking("KS-121", RoomReply.RESERVED), RoomCover.cover(meeting, emptyList(), whole, emptyList(), none, rooms, me))
        // An end at midnight is the end of the day.
        val late = calEvent(2, "Late", day, "22:00", "00:00")
        assertTrue(KnownBooking("KS-121", RoomReply.RESERVED, LocalTime.of(22, 0), LocalTime.MIDNIGHT).covers(late))
    }

    @Test
    fun aColleaguesBookingWithTheSameNameIsNotMine() {
        val theirs = calEvent(3, "Room Booking - Planning", day, "09:00", "10:00", organizer = "someone.else@lshtm.ac.uk")
        val attendees = mapOf(3L to listOf(room("KS-121")))
        assertNull(RoomCover.cover(meeting, emptyList(), null, listOf(meeting, theirs), { attendees[it.eventId].orEmpty() }, rooms, me))
    }

    @Test
    fun myBookingInTheCalendarCountsOnlyForTheWholeMeeting() {
        val short = calEvent(3, "Room Booking - Planning", day, "09:00", "09:30")
        val whole = calEvent(4, "Room Booking - Planning", day, "09:00", "10:00")
        val attendees = mapOf(3L to listOf(room("KS-121")), 4L to listOf(room("KS-117")))
        val of = { e: CalEvent -> attendees[e.eventId].orEmpty() }
        assertNull(RoomCover.cover(meeting, emptyList(), null, listOf(meeting, short), of, rooms, me))
        assertEquals(Cover.AppBooking("KS-117", RoomReply.RESERVED), RoomCover.cover(meeting, emptyList(), null, listOf(meeting, whole), of, rooms, me))
    }
}

class RecheckAgainstOtherEventsTest {
    private val london = ZoneId.of("Europe/London")
    private val day = LocalDate.of(2026, 10, 12)
    private val now: Instant = ZonedDateTime.of(2026, 10, 9, 15, 0, 0, 0, london).toInstant()
    private val me = setOf("eiderwhi@lshtm.ac.uk")
    private val meeting = calEvent(1, "Planning", day, "10:00", "11:00")
    private val candidate = BookingCandidate(
        event = meeting, label = "Moveable", people = emptyList(), defaults = Answers(bookRoom = true, notify = false),
        remembered = false, previous = null, partial = null, roomDeclined = false,
    )

    @Test
    fun aRoomBookedSinceForTheWholeMeetingCounts() {
        // The user booked KS-121 for "call" over the meeting after answering the wizard.
        val call = calEvent(2, "call", day, "10:00", "11:00")
        val attendees = mapOf(2L to listOf(room("KS-121")))
        val r = BookingPlanner.recheck(candidate, candidate.defaults, listOf(meeting, call), attendees, null, RoomList.DEFAULT, me, now)
        assertTrue("$r", r is Recheck.Skip && r.reason.contains("KS-121"))
    }

    @Test
    fun aBookingMadeEarlierInTheSameRunDoesnt() {
        val booked = calEvent(3, "Room Booking - Overlapping", day, "10:00", "11:00")
        val attendees = mapOf(3L to listOf(room("KS-121")))
        val made = setOf(CalEvent.normaliseTitle(BookingRules.bookingTitle("Overlapping")) to booked.begin)
        val r = BookingPlanner.recheck(candidate, candidate.defaults, listOf(meeting, booked), attendees, null, RoomList.DEFAULT, me, now, made)
        assertTrue("$r", r is Recheck.Go)
    }

    @Test
    fun theUserIsNeverToldAboutTheirOwnBooking() {
        val alias = "richard.white@lshtm.ac.uk"
        val withAlias = candidate.copy(people = listOf(com.thomaswcode.calendareventtimers.booking.Person(alias, "Richard White")))
        val r = BookingPlanner.recheck(
            withAlias, Answers(bookRoom = true, notify = true), listOf(meeting), mapOf(1L to listOf(person("Richard White", alias))), null,
            RoomList.DEFAULT, me + alias, now,
        ) as Recheck.Go
        assertTrue(r.people.isEmpty())
        assertTrue(r.notes.isEmpty())
    }
}

class CalendarOnlyBookingsTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val me = setOf("eiderwhi@lshtm.ac.uk")

    @Test
    fun myBookingEventsWithoutARecordAreListed() {
        val meeting = calEvent(1, "Planning", day, "10:00", "11:00")
        val mine = calEvent(2, "Room Booking - Planning", day, "10:00", "11:00")
        val theirs = calEvent(3, "Room Booking - Seminar", day, "12:00", "13:00", organizer = "someone.else@lshtm.ac.uk")
        val gone = calEvent(4, "Room Booking - Old", day, "14:00", "15:00", cancelled = true)
        val attendees = mapOf(2L to listOf(room("KS-121", Attendee.STATUS_TENTATIVE), person("Ann", "ann@lshtm.ac.uk"), person(null, "eiderwhi@lshtm.ac.uk")))
        val found = ManageController.calendarOnly(listOf(mine, theirs, gone), listOf(meeting, mine, theirs, gone), attendees, RoomList.DEFAULT, me)
        val b = found.single()
        assertTrue(b.id < 0)
        assertEquals("Planning", b.originalTitle)
        assertEquals(meeting.occurrenceKey, b.occurrenceKey)
        assertEquals("KS-121", b.room)
        assertEquals(RoomReply.TENTATIVE, b.roomReply)
        assertEquals("ann@lshtm.ac.uk", b.notified)
        assertEquals("10:00", b.start)
        assertEquals("11:00", b.end)
    }
}

class SmallFixesRound2Test {
    @Test
    fun anInvalidNumericEntityIsLeftAsWritten() {
        val html = "<p>Notes &#x110000; &#55357; &#x1F600; &#163;</p>"
        assertEquals("Notes &#x110000; &#55357; 😀 £", DescriptionText.plain(html))
        assertFalse(DescriptionText.isEmpty(html))
    }

    @Test
    fun onlyAPlainFreeIsFree() {
        assertEquals(RoomStatus.FREE, RoomChoice.status("Free"))
        assertEquals(RoomStatus.FREE, RoomChoice.status(" free "))
        assertEquals(RoomStatus.UNKNOWN, RoomChoice.status("Free until 09:15"))
        assertEquals(RoomStatus.BUSY, RoomChoice.status("Busy"))
        assertEquals(RoomStatus.UNKNOWN, RoomChoice.status("Loading…"))
        assertEquals(RoomStatus.UNKNOWN, RoomChoice.status(null))
    }

    @Test
    fun theDetailsScreenGivesTheEnd() {
        assertEquals(LocalTime.of(18, 0), DetailsReader.read(Fixtures.load("event_details_moveable.xml")).end)
        assertEquals(LocalTime.of(9, 30), DetailsReader.read(Fixtures.load("booking/booking_details_reserved.xml")).end)
        assertNull(DetailsReader.read(Fixtures.load("event_details_multiday.xml")).end)
    }

    @Test
    fun aBookingTitleGivesBackItsEvent() {
        assertEquals("Planning", BookingRules.originalTitle(BookingRules.bookingTitle(" Planning ")))
    }
}
