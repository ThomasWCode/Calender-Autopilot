package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.domain.AlarmText
import com.thomaswcode.calendareventtimers.domain.EventParser
import com.thomaswcode.calendareventtimers.outlook.Box
import com.thomaswcode.calendareventtimers.outlook.CalendarReader
import com.thomaswcode.calendareventtimers.outlook.DetailsReader
import com.thomaswcode.calendareventtimers.outlook.UiNode
import com.thomaswcode.calendareventtimers.outlook.calendarOnlyDump
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen readers against the uiautomator dumps captured from the real Outlook app. */
class CalendarReaderTest {
    private val today = Fixtures.load("day_view.xml")
    private val tomorrow = Fixtures.load("day_view_tomorrow.xml")
    private val sun4 = LocalDate.of(2026, 10, 4)
    private val mon5 = LocalDate.of(2026, 10, 5)

    @Test
    fun recognisesTheDayView() {
        assertTrue(CalendarReader.isCalendar(today))
        assertTrue(CalendarReader.isDayView(today))
        assertTrue(CalendarReader.isDayView(tomorrow))
    }

    @Test
    fun detailsScreenIsNotTheCalendar() {
        val details = Fixtures.load("event_details_immoveable.xml")
        assertFalse(CalendarReader.isCalendar(details))
        assertTrue(DetailsReader.isDetails(details))
        assertFalse(DetailsReader.isDetails(today))
    }

    @Test
    fun weekStripOfToday() {
        val days = CalendarReader.stripDays(today)
        assertEquals(
            listOf(
                "Monday 28 September", "Tuesday 29 September", "Wednesday 30 September", "Thursday 1 October",
                "Friday 2 October", "Saturday 3 October", "Sunday 4 October",
            ),
            days.map { it.label },
        )
        val selected = CalendarReader.selectedDay(today)!!
        assertEquals(EventParser.dayLabel(sun4), selected.label)
        assertTrue(selected.isToday)
        assertEquals(1, days.count { it.isToday })
    }

    @Test
    fun weekStripAfterGoingToTomorrow() {
        val selected = CalendarReader.selectedDay(tomorrow)!!
        assertEquals(EventParser.dayLabel(mon5), selected.label)
        assertFalse(selected.isToday)
        assertTrue(CalendarReader.stripDays(tomorrow).none { it.isToday })
    }

    @Test
    fun stripGridAndCalendarTab() {
        assertEquals(Box(0, 394, 1080, 549), CalendarReader.stripGrid(today)!!.bounds)
        assertEquals(Box(360, 2171, 720, 2337), CalendarReader.calendarTab(today)!!.bounds)
        assertNull(CalendarReader.closeButton(today))
    }

    @Test
    fun eventBlocksOfToday() {
        val blocks = CalendarReader.eventBlocks(today)
        // 1 in the all-day strip + 11 in the hourly grid; the week strip's buttons are not events.
        assertEquals(12, blocks.size)
        assertTrue(blocks.none { it.desc.contains("Selected") })
        val starts = blocks.mapNotNull { EventParser.startTimeIfStartsOn(it.desc, sun4) }
        // Excludes "T no comp" (started Saturday) and the long-running "Rui ZHANG" event.
        assertEquals(
            listOf("07:00", "11:05", "11:35", "12:05", "13:05", "14:00", "14:05", "15:35", "16:00", "16:35"),
            starts.map { it.toString() }.sorted(),
        )
    }

    @Test
    fun eventBlocksOfTomorrow() {
        val blocks = CalendarReader.eventBlocks(tomorrow)
        assertTrue(blocks.any { it.desc.contains("Work location") })
        val timed = blocks.filter { EventParser.startTimeIfStartsOn(it.desc, mon5) != null }
        assertEquals(17, timed.size)
        assertTrue(timed.none { it.desc.contains("Work location") || it.desc.contains("Rui ZHANG") })
        val kristian = timed.single { it.desc.contains("Kristian pdr") }
        assertEquals(LocalTime.of(17, 35), EventParser.startTimeIfStartsOn(kristian.desc, mon5))
        // Nothing from tomorrow's view counts as today's.
        assertTrue(blocks.none { EventParser.startTimeIfStartsOn(it.desc, sun4) != null })
    }

    @Test
    fun dumpsKeepToTheCalendar() {
        assertTrue(today.calendarOnlyDump().contains("t='Zumba'"))
        assertTrue(Fixtures.load("event_details_immoveable.xml").calendarOnlyDump().contains("t='Immoveable'"))
        // Any other screen (say, a mail list) keeps its structure but loses its words.
        val mail = XmlNode(null, null, null, null, false, false, false, Box(0, 0, 1080, 2400), listOf(
            XmlNode("android.widget.TextView", "com.microsoft.office.outlook:id/subject", "Secret subject", "From Bob", false, false, false, Box(0, 0, 10, 10), emptyList()),
        ))
        val dump = mail.calendarOnlyDump()
        assertFalse(dump.contains("Secret") || dump.contains("Bob"))
        assertTrue(dump.contains("#subject t='<14 chars>' d='<8 chars>'"))

        // On the calendar, an off-screen node outside its containers (a covered mail screen) is
        // blanked too, while off-screen events of the day are kept.
        fun node(id: String?, text: String?, visible: Boolean, vararg kids: UiNode) =
            XmlNode(null, id?.let { "com.microsoft.office.outlook:id/$it" }, text, null, false, false, false, Box(0, 0, 1, 1), kids.toList(), visible)
        val mixed = node(null, null, true,
            node("menu_calendar_views", null, true),
            node("multiday_view", null, true, node(null, "Late event", false)),
            node("message_list", null, false, node(null, "Secret subject", false)),
        )
        val mixedDump = mixed.calendarOnlyDump()
        assertTrue(mixedDump.contains("t='Late event'"))
        assertFalse(mixedDump.contains("Secret"))
    }

    @Test
    fun screensCountOnlyWhenOnScreen() {
        val covered = XmlNode(null, null, null, null, false, false, false, Box(0, 0, 1, 1), listOf(
            XmlNode("android.widget.Button", "com.microsoft.office.outlook:id/menu_calendar_views", null, null, true, false, false, Box(0, 0, 1, 1), emptyList(), visible = false),
        ))
        assertFalse(CalendarReader.isCalendar(covered))
        assertTrue(CalendarReader.isCalendar(today))
    }

    @Test
    fun dayGridScrollablesSkipTheAllDayStrip() {
        val scrollables = CalendarReader.dayGridScrollables(today)
        assertEquals(listOf("android.widget.ScrollView", "androidx.recyclerview.widget.RecyclerView"), scrollables.map { it.className })
        assertEquals(Box(0, 739, 1080, 2171), scrollables.first().bounds)
    }
}

class DetailsReaderTest {
    @Test
    fun immoveableEvent() {
        val root = Fixtures.load("event_details_immoveable.xml")
        val d = DetailsReader.read(root)
        assertEquals("Zumba", d.title)
        assertEquals(LocalDate.of(2026, 10, 4), d.date)
        assertEquals(LocalTime.of(14, 0), d.start)
        assertEquals("Pancras Square Leisure, 5 Pancras Square, London, Camden, N1C 4AG", d.location)
        assertTrue(d.categoryRowFound)
        assertEquals(listOf("Immoveable"), d.categories)
        assertEquals("Immoveable", EventParser.matchLabel(d.categories))
        assertEquals(Box(0, 121, 147, 268), CalendarReader.closeButton(root)!!.bounds)
    }

    @Test
    fun moveableEventWithZoomLocation() {
        val d = DetailsReader.read(Fixtures.load("event_details_moveable.xml"))
        assertEquals("Kristian pdr", d.title)
        assertEquals(LocalDate.of(2026, 10, 5), d.date)
        assertEquals(LocalTime.of(17, 35), d.start)
        assertEquals("https://lshtm.zoom.us/j/85988210188?pwd=fcXj2stOK1mtxeavOtAix3a9h2uRGb.1&from=addon", d.location)
        // The category row is at the very bottom edge of the screen here, but still in the tree.
        assertEquals(listOf("Moveable"), d.categories)
        assertEquals("Moveable", EventParser.matchLabel(d.categories))
    }

    @Test
    fun eventWithoutCategory() {
        val d = DetailsReader.read(Fixtures.load("event_details_no_category.xml"))
        assertEquals("think bra modelling and my presentation", d.title)
        assertEquals(LocalTime.of(14, 5), d.start)
        assertNull(d.location)
        assertTrue(d.categoryRowFound)
        assertEquals(emptyList<String>(), d.categories)
        assertNull(EventParser.matchLabel(d.categories))
    }

    @Test
    fun everyLocationRowIsRead() {
        val zoomAndRoom = DetailsReader.read(Fixtures.load("event_details_zoom_and_room.xml"))
        assertEquals("Chat about funding/IDM links", zoomAndRoom.title)
        assertEquals("https://lshtm.zoom.us/j/86875902372?pwd=UT5l9BOwiyLXl2kHSe8wehiTDfAubL.1&from=addon; KS-121", zoomAndRoom.location)
        assertEquals("Zoom; KS-121", AlarmText.shortLocation(zoomAndRoom.location))
        assertEquals(2, zoomAndRoom.locationRows)
        assertEquals(1, DetailsReader.read(Fixtures.load("event_details_immoveable.xml")).locationRows)
        assertEquals(0, DetailsReader.read(Fixtures.load("event_details_no_category.xml")).locationRows)

        val addressAndMeet = DetailsReader.read(Fixtures.load("event_details_address_and_meet.xml"))
        assertEquals(LocalTime.of(12, 20), addressAndMeet.start)
        assertEquals(
            "Lower Ground Floor X-Ray Dept, University College Hospital, 235 Euston Road London NW1 2BU; Meet",
            AlarmText.shortLocation(addressAndMeet.location),
        )
    }

    @Test
    fun multiDayEventUsesTheSeparateStartTime() {
        val d = DetailsReader.read(Fixtures.load("event_details_multiday.xml"))
        assertEquals("T no comp", d.title)
        assertEquals(LocalDate.of(2026, 10, 3), d.date)
        assertEquals(LocalTime.of(22, 0), d.start)
        assertEquals(emptyList<String>(), d.categories)
        assertNotNull(d.dateText)
    }
}
