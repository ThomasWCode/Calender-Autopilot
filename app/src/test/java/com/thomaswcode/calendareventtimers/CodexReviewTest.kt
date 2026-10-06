package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.booking.Answers
import com.thomaswcode.calendareventtimers.booking.BookingCandidate
import com.thomaswcode.calendareventtimers.booking.BookingPlanner
import com.thomaswcode.calendareventtimers.booking.Cover
import com.thomaswcode.calendareventtimers.booking.KnownBooking
import com.thomaswcode.calendareventtimers.booking.ManageController
import com.thomaswcode.calendareventtimers.booking.People
import com.thomaswcode.calendareventtimers.booking.Recheck
import com.thomaswcode.calendareventtimers.booking.RoomChoice
import com.thomaswcode.calendareventtimers.booking.RoomCover
import com.thomaswcode.calendareventtimers.booking.RoomList
import com.thomaswcode.calendareventtimers.calendar.Attendee
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.data.BookingEntity
import com.thomaswcode.calendareventtimers.data.BookingState
import com.thomaswcode.calendareventtimers.data.BookingStore
import com.thomaswcode.calendareventtimers.data.RoomReply
import com.thomaswcode.calendareventtimers.outlook.LabelRead
import com.thomaswcode.calendareventtimers.outlook.LabelSlots
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

/** Cases from Codex's review of PR #1 (2026-10-06). */
class LabelSlotsTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val nine = LocalTime.of(9, 0)
    private val title = CalEvent.normaliseTitle("Team call")
    private fun read(vararg labels: String) = LabelRead.Read("Team call", labels.toList())

    @Test
    fun twinsAreTheEventsTheDayViewShowsAlike() {
        val events = listOf(
            calEvent(1, "Team call", day, "09:00", "09:30"),
            calEvent(2, "team call ", day, "09:00", "10:00"),
            calEvent(3, "Team call", day, "09:00", "09:30", cancelled = true),
            calEvent(4, "Team call", day, "09:00", "09:30", selfStatus = Attendee.STATUS_DECLINED),
            calEvent(5, "Team call", day, "10:00", "10:30"),
            calEvent(6, "Team call with Bob", day, "09:00", "09:30"),
        )
        assertEquals(2, LabelSlots.twins(events, day, nine, title))
        assertNull(LabelSlots.twins(null, day, nine, title))
    }

    @Test
    fun oneEventIsReadAsBefore() {
        assertEquals(read("Moveable"), LabelSlots.decide("Team call", "09:00", 1, listOf(read("Moveable")), emptyList(), null))
        // Another block mentioning the title couldn't be read: one event in the calendar, so it is this one.
        assertEquals(read("Moveable"), LabelSlots.decide("Team call", "09:00", 1, listOf(read("Moveable")), listOf("didn't open"), null))
    }

    @Test
    fun twinsWithDifferentLabelsAreLeftUnread() {
        // Only one twin needed reading, but the Day view can't say which block is that one.
        val r = LabelSlots.decide("Team call", "09:00", 2, listOf(read("Moveable"), read("Immoveable")), emptyList(), null)
        assertTrue(r is LabelRead.Problem && r.message.contains("different labels"))
        // Two copies of one event (another calendar shown in Outlook) that disagree: the same.
        assertTrue(LabelSlots.decide("Team call", "09:00", 1, listOf(read("Moveable"), read()), emptyList(), null) is LabelRead.Problem)
    }

    @Test
    fun twinsThatAgreeShareTheirLabels() {
        assertEquals(read("Moveable"), LabelSlots.decide("Team call", "09:00", 2, listOf(read("Moveable"), read("moveable")), emptyList(), null))
    }

    @Test
    fun aTwinThatCouldntBeReadLeavesAllUnread() {
        val missing = LabelSlots.decide("Team call", "09:00", 2, listOf(read("Moveable")), emptyList(), null)
        assertTrue(missing is LabelRead.Problem)
        val unreadable = LabelSlots.decide("Team call", "09:00", 2, listOf(read("Moveable"), read("Moveable")), listOf("didn't open"), null)
        assertTrue(unreadable is LabelRead.Problem)
    }

    @Test
    fun nothingExactSaysWhy() {
        assertEquals(LabelRead.Problem("opened 'Team call 2'"), LabelSlots.decide("Team call", "09:00", 1, emptyList(), emptyList(), "opened 'Team call 2'"))
        assertEquals(LabelRead.Problem("didn't open"), LabelSlots.decide("Team call", "09:00", 1, emptyList(), listOf("didn't open"), "other"))
    }
}

class RoomNamesTest {
    @Test
    fun aRoomIsNeverAnotherRoomsPrefix() {
        assertFalse(RoomChoice.sameRoom("KS-103", "KS-103D"))
        assertFalse(RoomChoice.sameRoom("KS-103D", "KS-103"))
        assertTrue(RoomChoice.sameRoom("KS-103D", "ks-103d"))
        assertTrue(RoomChoice.sameRoom("KS103D", "KS-103D"))
        assertTrue(RoomChoice.sameRoom("KS-106", "KS-106 (EPH staff only)"))
        assertTrue(RoomChoice.sameRoom("KS-106 (EPH staff only)", "KS-106"))
        assertFalse(RoomChoice.sameRoom(null, "KS-106"))
    }

    @Test
    fun aLocationNamesARoomOnlyAsAWholeRow() {
        assertTrue(RoomChoice.namesRoom("https://lshtm.zoom.us/j/1; KS-121", "KS-121"))
        assertFalse(RoomChoice.namesRoom("KS-1210", "KS-121"))
        assertFalse(RoomChoice.namesRoom("KS-103D", "KS-103"))
        assertFalse(RoomChoice.namesRoom(null, "KS-103"))
    }
}

class DeclinedRoomInLocationTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val rooms = RoomList.DEFAULT
    private val none = { _: CalEvent -> emptyList<Attendee>() }
    private val me = setOf("eiderwhi@lshtm.ac.uk")
    private val meeting = calEvent(1, "Planning", day, "10:00", "11:00", location = "KS-121")

    @Test
    fun aRoomThatDeclinedStaysInTheLocationButDoesntCount() {
        assertNull(RoomCover.cover(meeting, listOf(room("KS-121", Attendee.STATUS_DECLINED)), null, emptyList(), none, rooms, me))
    }

    @Test
    fun aRoomOnlyInTheLocationStillCounts() {
        assertEquals(Cover.LocationNamesRoom("KS-121"), RoomCover.cover(meeting, emptyList(), null, emptyList(), none, rooms, me))
        // Another room declined; the location's room was never invited.
        assertEquals(
            Cover.LocationNamesRoom("KS-121"),
            RoomCover.cover(meeting, listOf(room("KS-117", Attendee.STATUS_DECLINED)), null, emptyList(), none, rooms, me),
        )
    }
}

class BookingWithoutARoomTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val made: Instant = ZonedDateTime.of(2026, 10, 9, 15, 0, 0, 0, ZoneId.of("Europe/London")).toInstant()

    private fun booking(reply: RoomReply = RoomReply.WAITING, checkedAt: Instant? = null) = BookingEntity(
        id = 1, occurrenceKey = "orig@1", seriesKey = "s", originalEventId = 1, originalTitle = "Planning",
        eventDate = day.toString(), start = "10:00", end = "11:00", bookingTitle = "Room Booking - Planning", room = "KS-121",
        notified = "", state = BookingState.SAVED, roomReply = reply, bookingSyncId = null,
        createdAt = made.toEpochMilli(), checkedAt = checkedAt?.toEpochMilli(),
    )

    private fun after(minutes: Long) = made.plus(Duration.ofMinutes(minutes))

    @Test
    fun aRoomTakenOffTheBookingStopsHoldingItOnceSynced() {
        // Just saved: Outlook may not have synced the room yet.
        assertEquals(RoomReply.WAITING, BookingStore.replyFor(booking(), found = true, room = null, now = after(5)))
        assertEquals(RoomReply.NO_ROOM, BookingStore.replyFor(booking(), found = true, room = null, now = after(20)))
        // Reserved for a day, then the room was taken off in Outlook.
        assertEquals(RoomReply.NO_ROOM, BookingStore.replyFor(booking(RoomReply.RESERVED, after(2)), found = true, room = null, now = after(24 * 60)))
        assertEquals(RoomReply.RESERVED, BookingStore.replyFor(booking(RoomReply.WAITING), found = true, room = RoomReply.RESERVED, now = after(1)))
    }

    @Test
    fun aRoomJustChangedInManageBookingsGetsTimeToSync() {
        val changed = booking(RoomReply.WAITING, checkedAt = after(60))
        assertEquals(RoomReply.WAITING, BookingStore.replyFor(changed, found = true, room = null, now = after(65)))
        assertEquals(RoomReply.NO_ROOM, BookingStore.replyFor(changed, found = true, room = null, now = after(80)))
    }

    @Test
    fun aBookingNotInTheCalendarIsStillNotFound() {
        assertEquals(RoomReply.WAITING, BookingStore.replyFor(booking(), found = false, room = null, now = after(5)))
        assertEquals(RoomReply.NOT_FOUND, BookingStore.replyFor(booking(), found = false, room = null, now = after(20)))
    }

    @Test
    fun itsEventIsOfferedAgainWithAWarning() {
        val meeting = calEvent(1, "Planning", day, "10:00", "11:00")
        val none = { _: CalEvent -> emptyList<Attendee>() }
        assertNull(RoomCover.cover(meeting, emptyList(), KnownBooking("KS-121", RoomReply.NO_ROOM), emptyList(), none, RoomList.DEFAULT, setOf("me@lshtm.ac.uk")))
        val warnings = ManageController.warnings(booking(RoomReply.NO_ROOM), meeting, listOf(meeting), emptyMap(), canCheck = false)
        assertTrue(warnings.single().contains("no room"))
    }
}

class RecheckBeforeBookingTest {
    private val london = ZoneId.of("Europe/London")
    private val day = LocalDate.of(2026, 10, 12)
    private val now: Instant = ZonedDateTime.of(2026, 10, 9, 15, 0, 0, 0, london).toInstant()
    private val me = setOf("eiderwhi@lshtm.ac.uk")
    private val rooms = RoomList.DEFAULT
    private val event = calEvent(1, "Planning", day, "10:00", "11:00")
    private val ann = person("Ann", "ann@lshtm.ac.uk")
    private val bob = person("Bob", "bob@lshtm.ac.uk")
    private val tellBoth = Answers(bookRoom = true, notify = true)

    private val candidate = BookingCandidate(
        event = event, label = "Moveable", people = People.toNotify(listOf(ann, bob), event.organizer, me, rooms),
        defaults = Answers(bookRoom = true, notify = false), remembered = false, previous = null, partial = null, roomDeclined = false,
    )

    private fun check(events: List<CalEvent>, attendees: List<Attendee> = listOf(ann, bob), at: Instant = now, known: KnownBooking? = null) =
        BookingPlanner.recheck(candidate, tellBoth, events, events.associate { it.eventId to attendees }, known, rooms, me, at)

    private fun skipped(r: Recheck, why: String) = assertTrue("$r", r is Recheck.Skip && r.reason.contains(why))

    @Test
    fun anUnchangedEventIsBookedAsAnswered() {
        assertEquals(Recheck.Go(event, listOf("ann@lshtm.ac.uk", "bob@lshtm.ac.uk"), emptyList()), check(listOf(event)))
        // Its sync id came after the wizard: still the same occurrence.
        val synced = event.copy(syncId = "new")
        assertEquals(synced, (check(listOf(synced)) as Recheck.Go).event)
    }

    @Test
    fun aChangedEventIsntBooked() {
        skipped(check(listOf(calEvent(1, "Planning", day, "11:00", "12:00"))), "moved")
        skipped(check(emptyList()), "deleted")
        skipped(check(listOf(event.copy(cancelled = true))), "cancelled")
        skipped(check(listOf(event.copy(selfStatus = Attendee.STATUS_DECLINED))), "declined")
        skipped(check(listOf(event.copy(end = event.end.plus(Duration.ofMinutes(30))))), "time")
        skipped(check(listOf(event.copy(title = "Planning (moved online)"))), "renamed")
        skipped(check(listOf(event), at = event.begin.plusSeconds(60)), "started")
    }

    @Test
    fun anEventGivenARoomSinceIsntBooked() {
        skipped(check(listOf(event), attendees = listOf(ann, bob, room("KS-117"))), "has a room now (KS-117)")
        skipped(check(listOf(event), known = KnownBooking("KS-121", RoomReply.WAITING)), "has a room now")
    }

    @Test
    fun peopleNoLongerInvitedArentTold() {
        val r = check(listOf(event), attendees = listOf(ann, person("Bob", "bob@lshtm.ac.uk", status = Attendee.STATUS_DECLINED))) as Recheck.Go
        assertEquals(listOf("ann@lshtm.ac.uk"), r.people)
        assertTrue(r.notes.single().contains("bob@lshtm.ac.uk"))
        // Newly invited people aren't added: nobody chose to tell them.
        val more = check(listOf(event), attendees = listOf(ann, bob, person("Cat", "cat@lshtm.ac.uk"))) as Recheck.Go
        assertEquals(listOf("ann@lshtm.ac.uk", "bob@lshtm.ac.uk"), more.people)
    }
}
