package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.booking.BookingRules
import com.thomaswcode.calendareventtimers.calendar.Attendee
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.calendar.CalendarRows
import com.thomaswcode.calendareventtimers.calendar.Row
import com.thomaswcode.calendareventtimers.engine.CachedLabels
import com.thomaswcode.calendareventtimers.engine.LabelCachePolicy
import com.thomaswcode.calendareventtimers.engine.LabelTargets
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

/** Provider rows shaped like `adb shell content query` output (PLAN-ROOM-BOOKING.md §2.1). */
object ProviderRows {
    val london: ZoneId = ZoneId.of("Europe/London")

    fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): String =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, london).toInstant().toEpochMilli().toString()

    fun instance(id: Long, title: String, begin: String, end: String, vararg extra: Pair<String, String?>): Row =
        mapOf("event_id" to "$id", "title" to title, "begin" to begin, "end" to end, "allDay" to "0", "selfAttendeeStatus" to "0") + extra

    fun event(id: Long, syncId: String?, changeKey: String?, vararg extra: Pair<String, String?>): Row =
        mapOf("_id" to "$id", "_sync_id" to syncId, "sync_data3" to changeKey, "deleted" to "0") + extra
}

class CalendarRowsTest {
    private val zone = ProviderRows.london

    @Test
    fun picksOutlooksMainCalendarByName() {
        val rows = listOf(
            mapOf("_id" to "29", "name" to "KS-207A", "account_type" to CalendarRows.OUTLOOK_ACCOUNT_TYPE, "calendar_access_level" to "200"),
            mapOf("_id" to "32", "name" to "TB Modelling Group", "account_type" to CalendarRows.OUTLOOK_ACCOUNT_TYPE, "calendar_access_level" to "700"),
            mapOf("_id" to "34", "name" to "Calendar", "account_name" to "eiderwhi@lshtm.ac.uk", "ownerAccount" to "eiderwhi@lshtm.ac.uk",
                "account_type" to CalendarRows.OUTLOOK_ACCOUNT_TYPE, "calendar_access_level" to "700"),
            mapOf("_id" to "15", "name" to "Calendar", "account_type" to "com.google", "calendar_access_level" to "700"),
        )
        val main = CalendarRows.mainCalendar(CalendarRows.calendars(rows))!!
        assertEquals(34L, main.id)
        assertEquals("eiderwhi@lshtm.ac.uk", main.ownerAccount)
        // Room calendars alone (read only) never count.
        assertNull(CalendarRows.mainCalendar(CalendarRows.calendars(rows.take(1))))
    }

    @Test
    fun occurrencesGetSyncColumnsAndLocalTimes() {
        val instances = listOf(
            ProviderRows.instance(87261, "TB Vx modelling 2-weekly call", ProviderRows.millis(2026, 10, 5, 14, 5), ProviderRows.millis(2026, 10, 5, 15, 0),
                "eventLocation" to "https://lshtm.zoom.us/j/995", "rrule" to "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO", "organizer" to "eiderwhi@lshtm.ac.uk"),
            ProviderRows.instance(109617, "Rebecca - interim and annual PDRs ", ProviderRows.millis(2026, 10, 9, 10, 5), ProviderRows.millis(2026, 10, 9, 10, 45)),
            ProviderRows.instance(5, "Gone", ProviderRows.millis(2026, 10, 9, 11, 0), ProviderRows.millis(2026, 10, 9, 12, 0)),
            ProviderRows.instance(6, "No events row", ProviderRows.millis(2026, 10, 9, 11, 0), ProviderRows.millis(2026, 10, 9, 12, 0)),
            ProviderRows.instance(7, "SAGE", ProviderRows.millis(2026, 10, 5, 1, 0), ProviderRows.millis(2026, 10, 9, 1, 0), "allDay" to "1"),
        )
        val events = mapOf(
            87261L to ProviderRows.event(87261, "4172d8ef", "DwAAAhN"),
            109617L to ProviderRows.event(109617, "bde52db4", "DwAAAkL", "original_sync_id" to "801dec05", "original_id" to "87355"),
            5L to ProviderRows.event(5, "dead", "x", "deleted" to "1"),
            7L to ProviderRows.event(7, "sage", "y"),
        )
        val out = CalendarRows.events(instances, events, zone)
        assertEquals(listOf("SAGE", "TB Vx modelling 2-weekly call", "Rebecca - interim and annual PDRs "), out.map { it.title })

        val tb = out[1]
        assertEquals(LocalDate.of(2026, 10, 5), tb.date)
        assertEquals(LocalTime.of(14, 5), tb.start)
        assertEquals(LocalTime.of(15, 0), tb.endTime)
        assertEquals("DwAAAhN", tb.changeKey)
        assertTrue(tb.recurring)
        assertEquals("4172d8ef", tb.labelKey)
        assertEquals("4172d8ef", tb.seriesKey)
        assertEquals("4172d8ef@" + ProviderRows.millis(2026, 10, 5, 14, 5), tb.occurrenceKey)
        assertTrue(tb.sameDay)

        // A changed occurrence of a series: its own labels, the series' memory.
        val pdr = out[2]
        assertEquals("bde52db4", pdr.labelKey)
        assertEquals("801dec05", pdr.seriesKey)
        assertTrue(pdr.recurring)
        assertTrue(out[0].allDay)
    }

    @Test
    fun oneOffEventsAreRememberedByTitle() {
        val e = CalendarRows.events(
            listOf(ProviderRows.instance(1, "  Weekly   Catch-up ", ProviderRows.millis(2026, 10, 6, 9, 0), ProviderRows.millis(2026, 10, 6, 10, 0))),
            mapOf(1L to ProviderRows.event(1, "abc", "k")), zone,
        ).single()
        assertFalse(e.recurring)
        assertEquals("title:weekly catch-up", e.seriesKey)
    }

    @Test
    fun midnightEndIsStillTheSameDay() {
        val late = CalendarRows.events(
            listOf(ProviderRows.instance(1, "Late", ProviderRows.millis(2026, 10, 6, 23, 0), ProviderRows.millis(2026, 10, 7, 0, 0)),
                ProviderRows.instance(2, "Overnight", ProviderRows.millis(2026, 10, 6, 22, 0), ProviderRows.millis(2026, 10, 7, 6, 0))),
            mapOf(1L to ProviderRows.event(1, "a", "k"), 2L to ProviderRows.event(2, "b", "k")), zone,
        )
        assertTrue(late.single { it.title == "Late" }.sameDay)
        assertFalse(late.single { it.title == "Overnight" }.sameDay)
    }

    @Test
    fun cancelledAndDeclined() {
        val out = CalendarRows.events(
            listOf(
                ProviderRows.instance(1, "Cancelled", ProviderRows.millis(2026, 10, 6, 9, 0), ProviderRows.millis(2026, 10, 6, 10, 0)),
                ProviderRows.instance(2, "Declined", ProviderRows.millis(2026, 10, 6, 9, 0), ProviderRows.millis(2026, 10, 6, 10, 0), "selfAttendeeStatus" to "2"),
            ),
            mapOf(1L to ProviderRows.event(1, "a", "k", "eventStatus" to "2"), 2L to ProviderRows.event(2, "b", "k")), zone,
        )
        assertTrue(out.single { it.title == "Cancelled" }.cancelled)
        assertEquals(Attendee.STATUS_DECLINED, out.single { it.title == "Declined" }.selfStatus)
    }

    @Test
    fun attendeesByEvent() {
        val rows = listOf(
            mapOf("event_id" to "118397", "attendeeName" to "KS-121", "attendeeEmail" to "ks-121@lshtm.ac.uk", "attendeeType" to "3", "attendeeStatus" to "1"),
            mapOf("event_id" to "88824", "attendeeName" to "Person D", "attendeeEmail" to "Person.D@LSHTM.ac.uk", "attendeeType" to "1", "attendeeStatus" to "3"),
            mapOf("event_id" to "88824", "attendeeName" to null, "attendeeEmail" to "eiderwhi@lshtm.ac.uk", "attendeeType" to "2", "attendeeStatus" to "1"),
        )
        val byEvent = CalendarRows.attendees(rows)
        assertTrue(byEvent.getValue(118397).single().isResource)
        assertEquals(2, byEvent.getValue(88824).size)
        assertNull(byEvent.getValue(88824)[1].name)
    }
}

class LabelCacheTest {
    private val now = Instant.parse("2026-10-05T20:00:00Z")
    private val cached = CachedLabels("key-1", listOf("Moveable"), now.minus(Duration.ofDays(3)))

    @Test
    fun reusedOnlyForTheSameVersion() {
        assertEquals(listOf("Moveable"), LabelCachePolicy.reuse(cached, "key-1", now))
        assertNull("the event changed", LabelCachePolicy.reuse(cached, "key-2", now))
        assertNull("no change key to compare", LabelCachePolicy.reuse(cached, null, now))
        assertNull(LabelCachePolicy.reuse(null, "key-1", now))
    }

    @Test
    fun expires() {
        assertNull(LabelCachePolicy.reuse(cached.copy(readAt = now.minus(Duration.ofDays(31))), "key-1", now))
        assertNull("read in the future: a clock problem", LabelCachePolicy.reuse(cached.copy(readAt = now.plus(Duration.ofDays(2))), "key-1", now))
    }
}

class LabelTargetsTest {
    private fun event(key: String, day: Int, h: Int, title: String = key) = CalEvent(
        eventId = key.hashCode().toLong(), syncId = key, changeKey = "k", originalSyncId = null, recurring = true, title = title,
        begin = ZonedDateTime.of(2026, 10, day, h, 0, 0, 0, ProviderRows.london).toInstant(),
        end = ZonedDateTime.of(2026, 10, day, h + 1, 0, 0, 0, ProviderRows.london).toInstant(),
        date = LocalDate.of(2026, 10, day), start = LocalTime.of(h, 0), endDate = LocalDate.of(2026, 10, day), endTime = LocalTime.of(h + 1, 0),
        allDay = false, location = null, organizer = null, selfStatus = 0, cancelled = false,
    )

    @Test
    fun oneReadPerSeriesOnAsFewDaysAsPossible() {
        val misses = listOf(
            // A daily series Mon–Fri, a one-off on Wednesday, a series on Tue and Thu.
            event("daily", 12, 8), event("daily", 13, 8), event("daily", 14, 8), event("daily", 15, 8), event("daily", 16, 8),
            event("oneoff", 14, 10),
            event("tuth", 13, 12), event("tuth", 15, 12),
        )
        val targets = LabelTargets.choose(misses)
        assertEquals(3, targets.size)
        assertEquals(setOf("daily", "oneoff", "tuth"), targets.map { it.labelKey }.toSet())
        // The one-off fixes Wednesday, the Tue/Thu series takes Tuesday, and the daily series is read
        // on one of those: two days visited, not five.
        assertEquals(LocalDate.of(2026, 10, 14), targets.single { it.labelKey == "oneoff" }.date)
        assertEquals(LocalDate.of(2026, 10, 13), targets.single { it.labelKey == "tuth" }.date)
        assertEquals(setOf(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14)), targets.map { it.date }.toSet())
        assertEquals(LocalTime.of(8, 0), targets.first().start)
    }

    @Test
    fun nothingToRead() {
        assertTrue(LabelTargets.choose(emptyList()).isEmpty())
    }
}

class BookingRulesTest {
    @Test
    fun bookingTitles() {
        assertEquals("Room Booking - Tomos 121s", BookingRules.bookingTitle("Tomos 121s "))
        assertTrue(BookingRules.isRoomBooking("Room Booking - Tomos 121s"))
        assertTrue(BookingRules.isRoomBooking("room booking - x"))
        // The user's older manual bookings are other events, not the app's.
        assertFalse(BookingRules.isRoomBooking("Room booking for Tomos 121 for 180days"))
    }
}
