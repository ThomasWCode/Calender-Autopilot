package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.booking.FormText
import com.thomaswcode.calendareventtimers.booking.RoomCover
import com.thomaswcode.calendareventtimers.booking.RoomList
import com.thomaswcode.calendareventtimers.booking.RowOutcome
import com.thomaswcode.calendareventtimers.calendar.Attendee
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.calendar.CalendarRows
import com.thomaswcode.calendareventtimers.calendar.TimeZones
import com.thomaswcode.calendareventtimers.data.BookingEntity
import com.thomaswcode.calendareventtimers.data.BookingState
import com.thomaswcode.calendareventtimers.data.RoomReply
import com.thomaswcode.calendareventtimers.engine.CachedLabels
import com.thomaswcode.calendareventtimers.engine.LabelCachePolicy
import com.thomaswcode.calendareventtimers.engine.LabelTargets
import com.thomaswcode.calendareventtimers.outlook.Box
import com.thomaswcode.calendareventtimers.outlook.DescriptionReader
import com.thomaswcode.calendareventtimers.outlook.EventFormReader
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors
import com.thomaswcode.calendareventtimers.outlook.PeopleReader
import com.thomaswcode.calendareventtimers.outlook.UiNode
import com.thomaswcode.calendareventtimers.ui.AppNav
import com.thomaswcode.calendareventtimers.ui.Page
import com.thomaswcode.calendareventtimers.ui.ReplyText
import com.thomaswcode.calendareventtimers.ui.bookingSummary
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the phone checks of 2026-10-07 found, and the fixes for it. */
class TimeZonesTest {
    private val la = ZoneId.of("America/Los_Angeles")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, zone: ZoneId) = ZonedDateTime.of(y, mo, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun utc(y: Int, mo: Int, d: Int, h: Int) = ms(y, mo, d, h, ZoneId.of("UTC"))

    @Test
    fun aSeriesInAZoneAndroidDoesntKnowIsMovedBackToItsClockTime() {
        // 09:00 Pacific, first in summer (16:00 UTC). The provider repeats it in GMT: 16:00 UTC in
        // December too, where 09:00 Pacific is 17:00 UTC.
        val dtstart = ms(2026, 7, 6, 9, la)
        val unknown = { _: String -> false }
        val winter = TimeZones.correct(utc(2026, 12, 7, 16), utc(2026, 12, 7, 17), dtstart, "US/Pacific-New", unknown)
        assertEquals(ms(2026, 12, 7, 9, la) to ms(2026, 12, 7, 10, la), winter)
        // Same offset as the first: nothing to correct.
        assertNull(TimeZones.correct(utc(2026, 7, 13, 16), utc(2026, 7, 13, 17), dtstart, "US/Pacific-New", unknown))
        // First in winter, an occurrence in summer: an hour earlier.
        val janStart = ms(2026, 1, 5, 9, la)
        assertEquals(ms(2026, 7, 6, 9, la) to ms(2026, 7, 6, 10, la), TimeZones.correct(utc(2026, 7, 6, 17), utc(2026, 7, 6, 18), janStart, "US/Pacific-New", unknown))
    }

    @Test
    fun onlyZonesAndroidDoesntKnowAreCorrected() {
        val dtstart = ms(2026, 7, 6, 9, la)
        assertNull(TimeZones.correct(utc(2026, 12, 7, 16), utc(2026, 12, 7, 17), dtstart, "America/Los_Angeles") { true })
        assertNull(TimeZones.correct(utc(2026, 12, 7, 16), utc(2026, 12, 7, 17), dtstart, "") { false })
        // Unknown to java.time as well: left as the provider has it.
        assertNull(TimeZones.correct(utc(2026, 12, 7, 16), utc(2026, 12, 7, 17), dtstart, "Mars/Olympus") { false })
        assertEquals(la, TimeZones.intended("US/Pacific-New"))
        assertEquals(ZoneId.of("America/Regina"), TimeZones.intended("Canada/East-Saskatchewan"))
    }

    @Test
    fun calendarRowsCorrectRepeatingEventsOnly() {
        val london = ProviderRows.london
        val dtstart = ms(2026, 7, 6, 9, la).toString()
        val bad = utc(2026, 12, 7, 16).toString()
        val badEnd = utc(2026, 12, 7, 17).toString()
        val rows = listOf(
            ProviderRows.instance(1, "Series", bad, badEnd, "rrule" to "FREQ=WEEKLY;BYDAY=MO", "eventTimezone" to "US/Pacific-New", "dtstart" to dtstart),
            ProviderRows.instance(2, "One-off", bad, badEnd, "eventTimezone" to "US/Pacific-New", "dtstart" to bad),
        )
        val events = mapOf(1L to ProviderRows.event(1, "a", "k"), 2L to ProviderRows.event(2, "b", "k"))
        val out = CalendarRows.events(rows, events, london) { it != "US/Pacific-New" }
        // 09:00 Pacific in December is 17:00 in London.
        assertEquals(LocalTime.of(17, 0), out.single { it.title == "Series" }.start)
        assertEquals(LocalTime.of(18, 0), out.single { it.title == "Series" }.endTime)
        assertEquals(LocalTime.of(16, 0), out.single { it.title == "One-off" }.start)
    }
}

class FormDateWordsTest {
    @Test
    fun theDateWheelsWordsForNearbyDays() {
        val today = LocalDate.of(2026, 10, 7)
        assertEquals(LocalDate.of(2026, 10, 8), FormText.parseDate("Tomorrow", today, today))
        assertEquals(today, FormText.parseDate("Today", today, today))
        assertEquals(LocalDate.of(2026, 10, 6), FormText.parseDate(" yesterday ", today, today))
        assertEquals(LocalDate.of(2026, 10, 8), FormText.parseDate("Thu 8 Oct", today, today))
        assertNull(FormText.parseDate("Someday", today, today))
    }
}

class AbsentLabelsTest {
    private val now = Instant.parse("2026-10-07T20:00:00Z")

    @Test
    fun anEventNotInOutlookIsRememberedForAWeekOnly() {
        val absent = listOf(LabelCachePolicy.ABSENT)
        assertEquals(absent, LabelCachePolicy.reuse(CachedLabels("k", absent, now.minus(Duration.ofDays(6))), "k", now))
        assertNull(LabelCachePolicy.reuse(CachedLabels("k", absent, now.minus(Duration.ofDays(8))), "k", now))
        // Labels read in Outlook last longer.
        assertEquals(listOf("Moveable"), LabelCachePolicy.reuse(CachedLabels("k", listOf("Moveable"), now.minus(Duration.ofDays(8))), "k", now))
        assertNull(LabelCachePolicy.reuse(CachedLabels("k", absent, now), "changed", now))
        assertFalse(LabelCachePolicy.isAbsent(listOf("Moveable")))
        assertFalse(LabelCachePolicy.isAbsent(null))
    }
}

class PhoneReadersTest {
    private fun node(
        className: String?, id: String? = null, text: String? = null, desc: String? = null, clickable: Boolean = false,
        children: List<UiNode> = emptyList(),
    ): UiNode = XmlNode(className, id, text, desc, clickable, false, false, Box(0, 0, 100, 100), children)

    @Test
    fun chipsAndTheAddressField() {
        val chip = Fixtures.load("booking/add_people_chip.xml")
        val chips = PeopleReader.chips(chip)
        assertEquals(listOf("<nobody@example.com>"), chips.map { it.label })
        assertEquals(listOf("nobody@example.com"), chips.map { it.address })
        // The placeholder isn't typed text.
        assertEquals("", PeopleReader.inputText(chip))
        assertEquals("nobody@example.com", PeopleReader.inputText(Fixtures.load("booking/add_people_typed.xml")))
    }

    @Test
    fun aChipWithoutAnAddressAndAClickableLayoutAroundIt() {
        val nameOnly = node(
            "android.widget.LinearLayout", desc = "Probe Person", clickable = true,
            children = listOf(node("android.widget.LinearLayout", OutlookSelectors.CONTACT_CHIP, children = listOf(node("android.widget.TextView", OutlookSelectors.CONTACT_CHIP_TEXT, text = "Probe Person")))),
        )
        val root = node(null, children = listOf(node("android.widget.FrameLayout", OutlookSelectors.PEOPLE_ROOT, children = listOf(node("android.view.ViewGroup", clickable = true, children = listOf(nameOnly))))))
        val chips = PeopleReader.chips(root)
        assertEquals(1, chips.size)
        assertEquals("Probe Person", chips.single().label)
        assertNull(chips.single().address)
        assertEquals(emptyList<String>(), PeopleReader.chipAddresses(root))
    }

    @Test
    fun theOnlineMeetingSwitch() {
        val switch = EventFormReader.onlineMeetingSwitch(Fixtures.load("booking/new_event_form.xml"))
        assertNotNull(switch)
        assertFalse(switch!!.checked)
        assertEquals(Box(934, 1296, 1060, 1422), switch.bounds)
    }

    @Test
    fun theDescriptionEditorsWebViewWithoutItsId() {
        val inner = node("android.webkit.WebView")
        val root = node(null, children = listOf(node("android.widget.LinearLayout", OutlookSelectors.DESCRIPTION_FIELD, desc = "Event description", children = listOf(node("android.webkit.WebView", children = listOf(inner))))))
        val webView = DescriptionReader.webView(root)
        assertNotNull(webView)
        assertEquals(1, webView!!.children.size)
        assertNotNull(DescriptionReader.editor(root))
    }
}

class LauncherOnOpenTest {
    @Test
    fun theLauncherWhenTheUserOpensTheApp() {
        assertEquals(Page.LAUNCHER, AppNav.pageOnResume(wasStopped = true, returnedTo = null, awayOnPurpose = false))
        // Brought back by a run, or back from a settings screen the app opened: left where it is.
        assertNull(AppNav.pageOnResume(wasStopped = true, returnedTo = Page.BOOKING, awayOnPurpose = false))
        assertNull(AppNav.pageOnResume(wasStopped = true, returnedTo = null, awayOnPurpose = true))
        // Only paused (a dialog over it): nothing changes.
        assertNull(AppNav.pageOnResume(wasStopped = false, returnedTo = null, awayOnPurpose = false))
    }
}

/** Codex's first review of the phone-check fixes. */
class PhoneChecksReviewTest {
    private fun event(key: String, day: Int, h: Int) = CalEvent(
        eventId = key.hashCode().toLong(), syncId = key, changeKey = "k", originalSyncId = null, recurring = true, title = key,
        begin = ZonedDateTime.of(2026, 10, day, h, 0, 0, 0, ProviderRows.london).toInstant(),
        end = ZonedDateTime.of(2026, 10, day, h + 1, 0, 0, 0, ProviderRows.london).toInstant(),
        date = LocalDate.of(2026, 10, day), start = LocalTime.of(h, 0), endDate = LocalDate.of(2026, 10, day), endTime = LocalTime.of(h + 1, 0),
        allDay = false, location = null, organizer = null, selfStatus = 0, cancelled = false,
    )

    @Test
    fun anAbsentOccurrenceLeavesItsSeriesToBeReadElsewhere() {
        val events = listOf(event("series", 12, 9), event("series", 13, 9), event("series", 14, 9), event("other", 12, 11))
        val first = LabelTargets.choose(events)
        val phantom = first.single { it.labelKey == "series" }
        assertEquals(events[0].occurrenceKey, phantom.occurrenceKey)
        // The chosen occurrence wasn't in Outlook: the series is read at another occurrence.
        val next = LabelTargets.retry(events, emptySet(), first, listOf(phantom))
        assertEquals(listOf(events[1].occurrenceKey), next.map { it.occurrenceKey })
        // Nor at one known to be absent already.
        assertEquals(listOf(events[2].occurrenceKey), LabelTargets.retry(events, setOf(events[1].occurrenceKey), first, listOf(phantom)).map { it.occurrenceKey })
        // Read (or a problem reported) at another occurrence: done.
        assertTrue(LabelTargets.retry(events, emptySet(), first + next, listOf(phantom)).isEmpty())
        assertTrue(LabelTargets.retry(events, emptySet(), first, emptyList()).isEmpty())
        // Absence is kept under the occurrence, never the series' label key.
        assertEquals("absent:" + events[0].occurrenceKey, LabelCachePolicy.absentKey(phantom.occurrenceKey))
    }

    @Test
    fun occurrencesMovedAcrossMidnightAreKeptOrDropped() {
        val la = ZoneId.of("America/Los_Angeles")
        val london = ProviderRows.london
        // 15:30 Pacific, first in winter: 23:30 UTC. In July the provider (repeating it in GMT) still
        // says 23:30 UTC, 00:30 on the 7th in London; corrected, it is 23:30 on the 6th.
        val dtstart = ZonedDateTime.of(2026, 1, 5, 15, 30, 0, 0, la).toInstant().toEpochMilli()
        val bad = ZonedDateTime.of(2026, 7, 7, 0, 30, 0, 0, london).toInstant()
        val rows = listOf(
            ProviderRows.instance(1, "Late series", bad.toEpochMilli().toString(), bad.plusSeconds(1800).toEpochMilli().toString(),
                "rrule" to "FREQ=WEEKLY", "eventTimezone" to "US/Pacific-New", "dtstart" to dtstart.toString()),
        )
        val out = CalendarRows.events(rows, mapOf(1L to ProviderRows.event(1, "a", "k")), london) { it != "US/Pacific-New" }
        assertEquals(LocalDate.of(2026, 7, 6), out.single().date)
        assertEquals(LocalTime.of(23, 30), out.single().start)
        // Found only by asking the provider beyond the day, then kept for the 6th, not the 7th.
        val jul6 = LocalDate.of(2026, 7, 6).atStartOfDay(london).toInstant()
        val jul7 = LocalDate.of(2026, 7, 7).atStartOfDay(london).toInstant()
        assertEquals(1, CalendarRows.within(out, jul6, jul7).size)
        assertTrue(CalendarRows.within(out, jul7.plusSeconds(3_600), jul7.plusSeconds(86_400)).isEmpty())
    }

    @Test
    fun aNewChipLookingLikeAnOldOneIsStillAStray() {
        fun chip(label: String, address: String?) = PeopleReader.Chip(XmlNode(null, null, label, null, true, false, false, Box(0, 0, 1, 1), emptyList()), label, address)
        val old = chip("Sam Jones", null)
        val newSam = chip("Sam Jones", null)
        val asked = chip("<a@lshtm.ac.uk>", "a@lshtm.ac.uk")
        val before = mapOf("Sam Jones" to 1)
        assertEquals(listOf(newSam), PeopleReader.strays(listOf(old, asked, newSam), before, listOf("a@lshtm.ac.uk")))
        assertTrue(PeopleReader.strays(listOf(old, asked), before, listOf("a@lshtm.ac.uk")).isEmpty())
    }

    @Test
    fun aRetryNeverOffersAnOccurrenceAlreadyTried() {
        // Three phantom occurrences, then a real one: each round offers the next, then none.
        val events = (12..15).map { event("series", it, 9) }
        var tried = LabelTargets.choose(events)
        val absent = ArrayList(tried)
        repeat(3) {
            val next = LabelTargets.retry(events, emptySet(), tried, absent)
            assertEquals(1, next.size)
            assertTrue(next.single() !in tried)
            tried = tried + next
            if (it < 2) absent += next
        }
        assertTrue(LabelTargets.retry(events, emptySet(), tried, absent).isEmpty())
    }
}

class ResultsSummaryTest {
    @Test
    fun aDeclinedBookingIsNotCountedAsBooked() {
        // Seen on 2026-10-08: a 06:00 booking declined by its room was shown as "1 booked".
        val declined = RowOutcome.Booked("KS-103D", saved = true, notes = emptyList(), bookingId = 1)
        val waiting = RowOutcome.Booked("KS-117", saved = true, notes = emptyList(), bookingId = 2)
        val reserved = RowOutcome.Booked("KS-121", saved = true, notes = emptyList(), bookingId = 3)
        val replies = mapOf(1L to RoomReply.DECLINED, 2L to RoomReply.WAITING, 3L to RoomReply.RESERVED)
        assertEquals("2 booked · 1 declined", ReplyText.resultsSummary(listOf(declined, waiting, reserved), replies, dryRun = false))
        // Before any reply is read, a saved booking counts.
        assertEquals("1 booked", ReplyText.resultsSummary(listOf(declined), emptyMap(), dryRun = false))
        val dry = RowOutcome.Booked("KS-103D", saved = false, notes = emptyList(), bookingId = null)
        assertEquals("1 would be booked · 1 not done", ReplyText.resultsSummary(listOf(dry, RowOutcome.NotDone("Stopped.")), emptyMap(), dryRun = true))
    }
}

class StaleRoomRowsTest {
    private fun event(id: Long, location: String?) = CalEvent(
        eventId = id, syncId = "s$id", changeKey = "k", originalSyncId = null, recurring = false, title = "Room Booking - CA test A",
        begin = ZonedDateTime.of(2026, 10, 8, 6, 0, 0, 0, ProviderRows.london).toInstant(),
        end = ZonedDateTime.of(2026, 10, 8, 6, 30, 0, 0, ProviderRows.london).toInstant(),
        date = LocalDate.of(2026, 10, 8), start = LocalTime.of(6, 0), endDate = LocalDate.of(2026, 10, 8), endTime = LocalTime.of(6, 30),
        allDay = false, location = location, organizer = "eiderwhi@lshtm.ac.uk", selfStatus = 0, cancelled = false,
    )

    private fun room(name: String, status: Int) = Attendee(name, "${name.lowercase()}@lshtm.onmicrosoft.com", Attendee.TYPE_RESOURCE, status)

    @Test
    fun aRoomTheLocationNoLongerNamesIsDropped() {
        val rooms = RoomList.DEFAULT
        val person = Attendee("Sam", "sam@lshtm.ac.uk", Attendee.TYPE_REQUIRED, Attendee.STATUS_ACCEPTED)
        val events = listOf(
            event(1, ""), // the room taken off in Outlook: its row stays, accepted
            event(2, "KS-117; KS-117"), // as Outlook names a room picked from Recent on an Edit form
            event(3, "https://lshtm.zoom.us/j/876; KS-119a"), // a colleague's meeting
            event(4, "KS-103D"), // a room that declined stays in the location
            event(6, ""),
        )
        val attendees = mapOf(
            1L to listOf(room("KS-117", Attendee.STATUS_ACCEPTED), person),
            2L to listOf(room("KS-117", Attendee.STATUS_ACCEPTED)),
            3L to listOf(room("KS-119a", Attendee.STATUS_DECLINED)),
            4L to listOf(room("KS-103D", Attendee.STATUS_DECLINED)),
            5L to listOf(room("KS-121", Attendee.STATUS_ACCEPTED)), // an event not looked at: kept
            // A room invited as a person: no location, Outlook shows it as an attendee (`calls`).
            6L to listOf(Attendee("KS-184", "KS-184@lshtm.ac.uk", Attendee.TYPE_REQUIRED, Attendee.STATUS_ACCEPTED)),
        )
        val out = RoomCover.withoutStaleRooms(events, attendees, rooms)
        assertEquals(listOf(person), out[1L])
        assertEquals(attendees[2L], out[2L])
        assertEquals(attendees[3L], out[3L])
        assertEquals(attendees[4L], out[4L])
        assertEquals(attendees[5L], out[5L])
        assertEquals(attendees[6L], out[6L])
        assertNull(RoomCover.roomReply(out[1L]!!, rooms))
        assertEquals("KS-184", RoomCover.roomReply(out[6L]!!, rooms)?.first)
    }
}

class LauncherBookingLineTest {
    private fun booking(date: String, reply: RoomReply) = BookingEntity(
        occurrenceKey = "k$date$reply", seriesKey = "s", originalEventId = 1, originalTitle = "T", eventDate = date, start = "06:00", end = "06:30",
        bookingTitle = "Room Booking - T", room = "KS-117", notified = "", state = BookingState.SAVED, roomReply = reply,
        bookingSyncId = null, createdAt = 0, checkedAt = null,
    )

    @Test
    fun whatNeedsLookingAtComesFirst() {
        // Seen on 2026-10-08: "This week: 0 booked · Next week: not booked yet · 1 withou…" cut off.
        val monday = LocalDate.of(2026, 10, 5)
        assertEquals(
            "1 without a room · This week: 0 booked · Next week: not booked yet",
            bookingSummary(listOf(booking("2026-10-08", RoomReply.NO_ROOM)), monday),
        )
        assertEquals(
            "This week: 1 booked · Next week: 1 booked",
            bookingSummary(listOf(booking("2026-10-08", RoomReply.RESERVED), booking("2026-10-13", RoomReply.WAITING)), monday),
        )
    }
}
