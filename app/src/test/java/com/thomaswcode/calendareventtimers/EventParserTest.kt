package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.domain.EventParser
import com.thomaswcode.calendareventtimers.domain.cleanUiText
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventParserTest {
    private val FSI = Char(0x2068) // first strong isolate, as Outlook puts before "None"
    private val PDI = Char(0x2069)
    private val NBSP = Char(0x00A0)

    private val sun4 = LocalDate.of(2026, 10, 4)
    private val mon5 = LocalDate.of(2026, 10, 5)

    @Test
    fun dayLabelsMatchOutlook() {
        assertEquals("Sunday 4 October", EventParser.dayLabel(sun4))
        assertEquals("Monday 5 October", EventParser.dayLabel(mon5))
        assertEquals("Thursday 1 October", EventParser.dayLabel(LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun startTimeOnlyForTimedEventsStartingThatDay() {
        assertEquals(
            LocalTime.of(14, 0),
            EventParser.startTimeIfStartsOn("Sunday 4 October, 14:00 to 14:55, Zumba, at location Pancras Square Leisure", sun4),
        )
        // Spans midnight but starts on the day: included.
        assertEquals(
            LocalTime.of(22, 0),
            EventParser.startTimeIfStartsOn("Sunday 4 October, 22:00 to Monday 5 October, 06:00, Night shift", sun4),
        )
        // Started the day before.
        assertNull(EventParser.startTimeIfStartsOn("Saturday 3 October, 22:00 to Sunday 4 October, 06:00, T no comp", sun4))
        // All-day, work-location chip, long-running, other day.
        assertNull(EventParser.startTimeIfStartsOn("Sunday 4 October to Monday 5 October, All Day, Holiday", sun4))
        assertNull(EventParser.startTimeIfStartsOn("Monday 5 October, Work location: Set work location", mon5))
        assertNull(EventParser.startTimeIfStartsOn("Tuesday 4 August, 12:00 to Friday 30 October, 12:30, Rui ZHANG-PhD in lshtm", sun4))
        assertNull(EventParser.startTimeIfStartsOn("Monday 5 October, 17:35 to 18:00, Kristian pdr", sun4))
        // "Sunday 11 October" must not pass for "Sunday 1 …".
        assertNull(EventParser.startTimeIfStartsOn("Sunday 11 October, 09:00 to 10:00, X", LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun parsesTimes() {
        assertEquals(LocalTime.of(6, 0), EventParser.parseTime("6:00"))
        assertEquals(LocalTime.of(14, 0), EventParser.parseTime("14:00 ▸ 14:55 (55 minutes)"))
        assertEquals(LocalTime.of(14, 0), EventParser.parseTime("14:00 to 14:55, duration: 55 minutes"))
        assertEquals(LocalTime.of(14, 5), EventParser.parseTime("2:05 PM"))
        assertEquals(LocalTime.of(0, 30), EventParser.parseTime("12:30 am"))
        assertNull(EventParser.parseTime("Sunday, 4 October 2026"))
        assertNull(EventParser.parseTime("25:00"))
        assertNull(EventParser.parseTime(null))
    }

    @Test
    fun parsesDetailsDates() {
        assertEquals(sun4, EventParser.parseDetailsDate("Sunday, 4 October 2026"))
        assertEquals(mon5, EventParser.parseDetailsDate("${FSI}Monday, 5 October 2026${PDI}"))
        assertEquals(LocalDate.of(2027, 1, 1), EventParser.parseDetailsDate("Friday, 1 January 2027"))
        assertNull(EventParser.parseDetailsDate("14:00 ▸ 14:55"))
    }

    @Test
    fun categories() {
        assertEquals(emptyList<String>(), EventParser.categoriesFrom(listOf("Categorise", "None")))
        assertEquals(emptyList<String>(), EventParser.categoriesFrom(listOf("Categorise", "${FSI}None")))
        assertEquals(listOf("Immoveable"), EventParser.categoriesFrom(listOf("Immoveable")))
        assertEquals(listOf("Urgent", "Moveable"), EventParser.categoriesFrom(listOf(" Urgent ", "", null, "Moveable")))
        assertEquals(emptyList<String>(), EventParser.categoriesFrom(emptyList()))
    }

    @Test
    fun labelsMatchExactlyIgnoringCase() {
        assertEquals("Immoveable", EventParser.matchLabel(listOf("Urgent", "immoveable")))
        assertEquals("Moveable", EventParser.matchLabel(listOf("MOVEABLE")))
        assertEquals("Moveable", EventParser.matchLabel(listOf("Moveable", "Immoveable")))
        assertNull(EventParser.matchLabel(listOf("Movable")))
        assertNull(EventParser.matchLabel(listOf("Moveables", "Not Immoveable")))
        assertNull(EventParser.matchLabel(listOf("BCC BCC BCC", "Urgent")))
        assertNull(EventParser.matchLabel(emptyList()))
    }

    @Test
    fun titleFromDescIsBestEffort() {
        assertEquals("Zumba", EventParser.titleFromDesc("Sunday 4 October, 14:00 to 14:55, Zumba, at location Pancras Square Leisure, 5 Pancras Square"))
        assertEquals("Re: Sorry to ask at the weekend", EventParser.titleFromDesc("Sunday 4 October, 12:05 to 12:30, Re: Sorry to ask at the weekend, in 27 mins"))
        assertEquals("Thomas", EventParser.titleFromDesc("Sunday 4 October, 07:00 to 10:00, Thomas, private event"))
        assertEquals("Night", EventParser.titleFromDesc("Sunday 4 October, 22:00 to Monday 5 October, 06:00, Night"))
        assertNull(EventParser.titleFromDesc("Monday 5 October, Work location: Set work location"))
    }

    @Test
    fun stableDescDropsTheCountdown() {
        val base = "Sunday 4 October, 13:05 to 13:30, Re: Sorry to ask at the weekend"
        assertEquals(base, EventParser.stableDesc("$base, in 2 mins"))
        assertEquals(base, EventParser.stableDesc("$base, in 1 min"))
        assertEquals(base, EventParser.stableDesc("$base, in 1 hour"))
        assertEquals(base, EventParser.stableDesc(base))
        // Only a trailing countdown goes: titles keep their words.
        assertEquals("Monday 5 October, 09:00 to 10:00, Drop in 5 mins early", EventParser.stableDesc("Monday 5 October, 09:00 to 10:00, Drop in 5 mins early"))
    }

    @Test
    fun countsTheLocationsADescriptionLists() {
        assertEquals(2, EventParser.locationCountInDesc("Monday 5 October, 10:30 to 11:30, Chat about funding/IDM links, at location https://lshtm.zoom.us/j/86875902372?pwd=UT5l9BOwiyLXl2kHSe8wehiTDfAubL.1&from=addon; KS-121 with KS-121, Richard White, Tom Sumner"))
        assertEquals(2, EventParser.locationCountInDesc("Monday 5 October, 12:20 to 13:20, DO NOT EAT 6 HOURS BEFORE. DRINK ONLY WATER. Arrive 12:00. UCLH US abdoman, at location Lower Ground Floor X-Ray Dept, University College Hospital, 235 Euston Road \r\nLondon NW1 2BU; https://meet.google.com/evt-oviw-qoi with Richard White, Sally Oldfield"))
        assertEquals(1, EventParser.locationCountInDesc("Monday 5 October, 17:35 to 18:00, Kristian pdr, at location https://lshtm.zoom.us/j/85988210188?pwd=fcXj2stOK1mtxeavOtAix3a9h2uRGb.1&from=addon with Kristian Godfrey"))
        assertEquals(1, EventParser.locationCountInDesc("Monday 5 October, 18:00 to 21:00, LCV, at location Mo:  West end (W1D 6AF) Coach & Horses in Romilly St //  W: City (EC3R 8EE), private event"))
        assertEquals(1, EventParser.locationCountInDesc("Sunday 4 October, 14:00 to 14:55, Zumba, at location Pancras Square Leisure, 5 Pancras Square, London, Camden, N1C 4AG, in 5 mins"))
        assertEquals(0, EventParser.locationCountInDesc("Sunday 4 October, 16:00 to 16:25, Phone cat"))
        assertEquals(0, EventParser.locationCountInDesc("Saturday 3 October, 22:00 to Sunday 4 October, 06:00, T no comp with Sally Oldfield (Not work), Thomas White"))
    }

    @Test
    fun locationRowsFollowTheDescriptionsOrder() {
        val desc = "Monday 5 October, 10:30 to 11:30, Chat about funding/IDM links, at location https://lshtm.zoom.us/j/868?pwd=x&from=addon; KS-121 with KS-121, Richard White"
        assertEquals(listOf("https://lshtm.zoom.us/j/868?pwd=x&from=addon", "KS-121"), EventParser.locationsInDesc(desc))
        // Seen on the Pixel: the same event's rows came back in either order on different scans.
        assertEquals(
            listOf("https://lshtm.zoom.us/j/868?pwd=x&from=addon", "KS-121"),
            EventParser.orderLike(EventParser.locationsInDesc(desc), listOf("KS-121", "https://lshtm.zoom.us/j/868?pwd=x&from=addon")),
        )
        // Whitespace differences (the address has a line break in one place) don't matter; unknown rows go last.
        val addressDesc = "Monday 5 October, 12:20 to 13:20, X, at location 235 Euston Road \r\nLondon NW1 2BU; https://meet.google.com/evt with Richard White"
        assertEquals(
            listOf("235 Euston Road London NW1 2BU", "https://meet.google.com/evt", "Room 9"),
            EventParser.orderLike(EventParser.locationsInDesc(addressDesc), listOf("Room 9", "https://meet.google.com/evt", "235 Euston Road London NW1 2BU")),
        )
    }

    @Test
    fun descriptionsMentionTheirTitles() {
        val desc = "Monday 5 October, 15:35 to 16:00, finn & rpts 121s , at location https://lshtm.zoom.us/j/9 with Finn Mcquaid"
        assertTrue(EventParser.descMentions(desc, "finn & rpts 121s"))
        assertTrue(EventParser.descMentions("Monday 5 October, 09:00 to 10:00, Two  spaces", "Two spaces"))
        assertFalse(EventParser.descMentions(desc, "Kristian pdr"))
    }

    @Test
    fun resolvesYearlessLabels() {
        assertEquals(LocalDate.of(2026, 9, 28), EventParser.resolveLabel("Monday 28 September", sun4))
        assertEquals(LocalDate.of(2027, 1, 1), EventParser.resolveLabel("Friday 1 January", LocalDate.of(2026, 12, 30)))
        assertEquals(LocalDate.of(2026, 12, 28), EventParser.resolveLabel("Monday 28 December, Selected", LocalDate.of(2027, 1, 2)))
    }

    @Test
    fun cleansInvisibleMarks() {
        assertEquals("None", cleanUiText("${FSI}None${PDI}"))
        assertEquals("a b", cleanUiText(" a${NBSP}b "))
    }
}
