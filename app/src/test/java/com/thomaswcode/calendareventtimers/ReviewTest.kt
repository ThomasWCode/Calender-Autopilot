package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.domain.ExistingAlarm
import com.thomaswcode.calendareventtimers.domain.Review
import com.thomaswcode.calendareventtimers.domain.ScannedEvent
import com.thomaswcode.calendareventtimers.domain.TriggerTime
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

class TriggerTimeTest {
    private val london = ZoneId.of("Europe/London")

    @Test
    fun atStartAndFiveMinutesBefore() {
        val day = LocalDate.of(2026, 10, 5)
        assertEquals(Instant.parse("2026-10-05T16:35:00Z"), TriggerTime.trigger(day, LocalTime.of(17, 35), 0, london))
        assertEquals(Instant.parse("2026-10-05T16:30:00Z"), TriggerTime.trigger(day, LocalTime.of(17, 35), 5, london))
    }

    @Test
    fun justAfterMidnightRingsTheEveningBefore() {
        val trigger = TriggerTime.trigger(LocalDate.of(2026, 10, 6), LocalTime.of(0, 2), 5, london)
        assertEquals(ZonedDateTime.of(2026, 10, 5, 23, 57, 0, 0, london).toInstant(), trigger)
    }

    @Test
    fun bstToGmtChangeover() {
        // Clocks go back at 02:00 BST on Sunday 25 October 2026.
        assertEquals(Instant.parse("2026-10-24T08:00:00Z"), TriggerTime.trigger(LocalDate.of(2026, 10, 24), LocalTime.of(9, 0), 0, london))
        assertEquals(Instant.parse("2026-10-25T09:00:00Z"), TriggerTime.trigger(LocalDate.of(2026, 10, 25), LocalTime.of(9, 0), 0, london))
        // 5 minutes before 02:02 GMT is 01:57 GMT (the second 01:57 that night), not 01:57 BST.
        assertEquals(Instant.parse("2026-10-25T01:57:00Z"), TriggerTime.trigger(LocalDate.of(2026, 10, 25), LocalTime.of(2, 2), 5, london))
        // An ambiguous 01:30 resolves to the earlier (BST) occurrence.
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), TriggerTime.trigger(LocalDate.of(2026, 10, 25), LocalTime.of(1, 30), 0, london))
    }

    @Test
    fun gmtToBstChangeover() {
        // Clocks go forward at 01:00 GMT on Sunday 28 March 2027; 01:30 does not exist and becomes 02:30 BST.
        assertEquals(Instant.parse("2027-03-28T01:30:00Z"), TriggerTime.trigger(LocalDate.of(2027, 3, 28), LocalTime.of(1, 30), 0, london))
        assertEquals(Instant.parse("2027-03-28T08:55:00Z"), TriggerTime.trigger(LocalDate.of(2027, 3, 28), LocalTime.of(10, 0), 5, london))
    }
}

class ReviewTest {
    private val london = ZoneId.of("Europe/London")
    private val sun4 = LocalDate.of(2026, 10, 4)
    private val now = ZonedDateTime.of(2026, 10, 4, 15, 0, 0, 0, london).toInstant()

    private fun event(title: String, h: Int, m: Int, vararg categories: String, location: String? = null) =
        ScannedEvent(title, sun4, LocalTime.of(h, m), location, categories.toList())

    @Test
    fun keepsLabelledFutureEventsInTimeOrder() {
        val items = Review.build(
            listOf(
                event("Late", 18, 0, "Immoveable"),
                event("Zumba", 14, 0, "Immoveable"), // already started
                event("Phone cat", 16, 0, "Urgent"), // not a target label
                event("Soon", 15, 3, "Urgent", "moveable", location = "https://lshtm.zoom.us/j/1"),
                event("Now", 15, 0, "Moveable"), // starts this minute: counts as started
                event("Late", 18, 0, "Immoveable"), // duplicate block
            ),
            now, london,
        ) { null }
        assertEquals(listOf("Soon", "Late"), items.map { it.event.title })
        assertEquals("Moveable", items[0].label)
        assertEquals("Soon (Moveable) @ Zoom", items[0].alarmText)
        assertFalse("14:58 has passed", items[0].earlyAllowed)
        assertTrue(items[1].earlyAllowed)
        assertNull(items[1].existing)
    }

    @Test
    fun marksEventsThatAlreadyHaveAnAlarm() {
        val existing = ExistingAlarm(7, Instant.parse("2026-10-04T16:55:00Z"), 5)
        val items = Review.build(listOf(event("Late", 18, 0, "Immoveable")), now, london) { e ->
            existing.takeIf { e.title == "Late" }
        }
        assertEquals(existing, items.single().existing)
    }
}
