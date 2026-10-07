package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.booking.FormText
import com.thomaswcode.calendareventtimers.calendar.CalendarRows
import com.thomaswcode.calendareventtimers.calendar.TimeZones
import com.thomaswcode.calendareventtimers.engine.CachedLabels
import com.thomaswcode.calendareventtimers.engine.LabelCachePolicy
import com.thomaswcode.calendareventtimers.outlook.Box
import com.thomaswcode.calendareventtimers.outlook.DescriptionReader
import com.thomaswcode.calendareventtimers.outlook.EventFormReader
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors
import com.thomaswcode.calendareventtimers.outlook.PeopleReader
import com.thomaswcode.calendareventtimers.outlook.UiNode
import com.thomaswcode.calendareventtimers.ui.AppNav
import com.thomaswcode.calendareventtimers.ui.Page
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
