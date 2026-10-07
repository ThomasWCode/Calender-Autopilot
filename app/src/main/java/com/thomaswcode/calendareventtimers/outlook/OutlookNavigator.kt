package com.thomaswcode.calendareventtimers.outlook

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import com.thomaswcode.calendareventtimers.calendar.Attendee
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.domain.EventParser
import com.thomaswcode.calendareventtimers.domain.ScannedEvent
import com.thomaswcode.calendareventtimers.domain.TriggerTime
import com.thomaswcode.calendareventtimers.domain.cleanUiText
import com.thomaswcode.calendareventtimers.engine.LabelTarget
import com.thomaswcode.calendareventtimers.outlook.CalendarReader.EventBlock
import com.thomaswcode.calendareventtimers.outlook.CalendarReader.StripDay
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.delay

/** A scan step that cannot go on; the message is shown to the user. */
class ScanFailure(message: String) : Exception(message)

/** What reading one event's labels in Outlook gave. */
sealed interface LabelRead {
    data class Read(val title: String, val categories: List<String>) : LabelRead

    data class Problem(val message: String) : LabelRead

    /**
     * The Day view, loaded, shows no event of that title at that time: the phone's calendar has an
     * event Outlook doesn't (seen on 2026-10-07: a series moved to another day left behind in the
     * provider). Remembered for a while, so it isn't looked for again on every run.
     */
    data class Absent(val message: String) : LabelRead
}

/**
 * Drives Outlook's calendar (PLAN.md §4.3): launch → Calendar tab → Day view → target date →
 * open events, read their details, close them. Navigation is always explicit, because Outlook
 * remembers the last day and view shown.
 *
 * Two ways to read: [scan] opens every timed event of a day (the original timers scan, now the
 * fallback when the calendar provider can't be read); [readLabels] opens only the events whose
 * labels aren't known yet (PLAN-ROOM-BOOKING.md §3.3).
 *
 * [scan]'s results accumulate in [events] and [problems] as it goes, so a scan that fails part-way
 * can still offer what it read.
 */
class OutlookNavigator(
    private val service: AccessibilityService,
    internal val driver: UiDriver,
    internal val onProgress: (String) -> Unit,
) {
    val events = mutableListOf<ScannedEvent>()
    val problems = mutableListOf<String>()

    /** Today only: events whose start had passed, which were not opened. */
    var startedSkipped = 0
        private set

    suspend fun scan(target: LocalDate, hasStarted: (LocalTime) -> Boolean) {
        openDay(target)
        readDay(target, hasStarted)
        ScanLog.i("Scan of ${EventParser.dayLabel(target)} done: ${events.size} read, $startedSkipped already started, ${problems.size} problem(s)")
    }

    /**
     * Opens each target event and reads its labels, visiting the days in date order. [expected]
     * are the provider's events per day, compared with the Day view on every day visited anyway
     * (only logged: it shows calendars or sync gaps the provider misses). Results go into [out]
     * as they are read, so a run that stops part-way keeps what it read.
     */
    suspend fun readLabels(
        targets: List<LabelTarget>,
        expected: Map<LocalDate, List<CalEvent>> = emptyMap(),
        out: MutableMap<LabelTarget, LabelRead> = LinkedHashMap(),
    ): Map<LabelTarget, LabelRead> {
        if (targets.isEmpty()) return out
        launchOutlook()
        openCalendar()
        ensureDayView()
        for ((date, dayTargets) in targets.groupBy { it.date }.toSortedMap()) {
            goToDate(date)
            waitForDay(date)
            expected[date]?.let { crossCheck(date, it) }
            // Events sharing a start and a title look the same in the Day view, so they are read together.
            val slots = dayTargets.groupBy { it.start to CalEvent.normaliseTitle(it.title) }
            for (slot in slots.keys.sortedWith(compareBy({ it.first }, { it.second }))) {
                val group = slots.getValue(slot)
                onProgress("Reading labels ${out.size + 1} of ${targets.size}: ${group.first().title}")
                val twins = maxOf(LabelSlots.twins(expected[date], date, slot.first, slot.second) ?: 0, group.size)
                val read = readSlot(group.first(), twins)
                group.forEach { out[it] = read }
            }
        }
        val read = out.values.count { it is LabelRead.Read }
        ScanLog.i("Labels read in Outlook: $read of ${targets.size}")
        return out
    }

    // ---- Navigation, also used by BookingNavigator ----

    /** Outlook in front, on its calendar, in Day view, showing [date] with its events loaded. */
    internal suspend fun openDay(date: LocalDate) {
        launchOutlook()
        openCalendar()
        ensureDayView()
        goToDate(date)
        waitForDay(date)
    }

    /** Back on [date]'s Day view if Outlook has left it (another screen, or another day). */
    internal suspend fun ensureOnDay(date: LocalDate) {
        val root = driver.snapshot()
        val onDay = CalendarReader.isCalendar(root) && !DetailsReader.isDetails(root) &&
            CalendarReader.selectedDay(root)?.label == EventParser.dayLabel(date)
        if (onDay) return
        ScanLog.i("Not on ${EventParser.dayLabel(date)}; going there")
        if (!driver.outlookInFront()) launchOutlook()
        if (!CalendarReader.isCalendar(root) || DetailsReader.isDetails(root)) openCalendar()
        ensureDayView()
        goToDate(date)
        waitForDay(date)
    }

    private suspend fun launchOutlook() {
        onProgress("Opening Outlook…")
        val intent = service.packageManager.getLaunchIntentForPackage(OutlookSelectors.PACKAGE)
            ?: throw ScanFailure("Outlook isn't installed.")
        service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (!driver.waitUntil(10_000) { driver.outlookInFront() }) fail("Outlook didn't open.")
        delay(700)
    }

    internal suspend fun openCalendar() {
        onProgress("Opening the calendar…")
        repeat(6) {
            val root = driver.snapshot()
            if (CalendarReader.isCalendar(root) && !DetailsReader.isDetails(root)) return
            val tab = CalendarReader.calendarTab(root)
            when {
                tab != null -> {
                    driver.click(tab, "Calendar tab")
                    if (driver.waitUntil(4_000) { driver.hasId(OutlookSelectors.VIEW_SWITCHER) }) return
                }
                root.children.isEmpty() || !driver.outlookInFront() -> launchOutlook()
                else -> {
                    // Some other Outlook screen (an event, a message, search…): back out of it.
                    val close = CalendarReader.closeButton(root)
                    if (close != null) driver.click(close, "Close") else driver.back()
                    delay(600)
                }
            }
        }
        fail("Couldn't find Outlook's Calendar tab. Outlook may have changed.")
    }

    /** The chosen view does not reliably persist, so check the switcher's description every run. */
    private suspend fun ensureDayView() {
        repeat(3) {
            val root = driver.snapshot()
            val switcher = CalendarReader.viewSwitcher(root)
                ?: fail("Couldn't find the calendar's view switcher. Outlook may have changed.")
            if (CalendarReader.isDayView(root)) return
            onProgress("Switching to Day view…")
            ScanLog.i("View switcher says '${switcher.desc}'")
            driver.click(switcher, "view switcher")
            val day = driver.waitFor(3_000) { r ->
                r.find { it.viewId == OutlookSelectors.VIEW_MENU_ITEM_TITLE && cleanUiText(it.text).equals(OutlookSelectors.VIEW_MENU_DAY, ignoreCase = true) }
            }
            if (day != null) {
                driver.click(day, "Day view")
                if (driver.waitFor(3_000) { if (CalendarReader.isDayView(it)) true else null } != null) return
            }
        }
        fail("Couldn't switch Outlook to Day view. Outlook may have changed.")
    }

    /** PLAN.md §2.3: find the day in the week strip (swiping weeks if needed) and select it. */
    private suspend fun goToDate(target: LocalDate) {
        val label = EventParser.dayLabel(target)
        onProgress("Going to $label…")
        repeat(5) {
            val root = driver.snapshot()
            val days = CalendarReader.stripDays(root)
            if (days.isEmpty()) fail("Couldn't find the calendar's week strip. Outlook may have changed.")
            val day = days.firstOrNull { it.label == label }
            if (day != null) {
                if (day.isSelected) return
                driver.click(day.node, label)
                if (driver.waitFor(3_000) { r -> CalendarReader.selectedDay(r)?.takeIf { it.label == label } } != null) return
            } else {
                val shown = days.mapNotNull { EventParser.resolveLabel(it.label, target) }
                swipeStrip(root, days, forward = shown.isEmpty() || shown.max() < target)
            }
        }
        if (flingFromToday(target, label)) return
        fail("Couldn't open $label in Outlook's calendar.")
    }

    private suspend fun swipeStrip(root: UiNode, days: List<StripDay>, forward: Boolean) {
        val b = CalendarReader.stripGrid(root)?.bounds
            ?: days.first().node.bounds.let { Box(0, it.top, driver.screenWidth, it.bottom) }
        val y = b.centerY.toFloat()
        val right = b.left + b.width * 0.88f
        val left = b.left + b.width * 0.14f
        ScanLog.i("Swiping the week strip to the ${if (forward) "next" else "previous"} week")
        if (forward) driver.swipe(right, y, left, y, 300) else driver.swipe(left, y, right, y, 300)
        delay(800)
    }

    /** Fallback: select today, then fling the Day grid one day at a time (a slow swipe does nothing). */
    private suspend fun flingFromToday(target: LocalDate, label: String): Boolean {
        val steps = ChronoUnit.DAYS.between(LocalDate.now(), target)
        if (steps !in -7..7) return false
        ScanLog.w("Week strip didn't work; flinging from today instead")
        val today = CalendarReader.stripDays(driver.snapshot()).firstOrNull { it.isToday } ?: return false
        if (!today.isSelected) driver.click(today.node, "today")
        delay(800)
        repeat(abs(steps).toInt()) {
            val grid = CalendarReader.dayGridScrollables(driver.snapshot()).firstOrNull()?.bounds ?: return false
            val y = grid.top + grid.height * 0.4f
            val right = grid.left + grid.width * 0.88f
            val left = grid.left + grid.width * 0.09f
            if (steps > 0) driver.swipe(right, y, left, y, 120) else driver.swipe(left, y, right, y, 120)
            delay(900)
        }
        return driver.waitFor(2_000) { r -> CalendarReader.selectedDay(r)?.takeIf { it.label == label } } != null
    }

    /** Outlook may still be syncing after launch: wait until the day's blocks stop changing. */
    private suspend fun waitForDay(target: LocalDate) {
        val label = EventParser.dayLabel(target)
        onProgress("Waiting for $label to load…")
        val start = SystemClock.uptimeMillis()
        var last: List<String> = emptyList()
        var stableSince = start
        while (true) {
            val now = SystemClock.uptimeMillis()
            val blocks = CalendarReader.eventBlocks(driver.snapshot()).map { it.desc }.sorted()
            if (blocks != last) {
                last = blocks
                stableSince = now
            }
            val stable = now - stableSince
            val elapsed = now - start
            val hasDay = blocks.any { it.startsWith(label) }
            if (elapsed >= 1_000 && ((hasDay && stable >= 800) || stable >= 2_500)) break
            if (elapsed >= 8_000) break
            delay(250)
        }
        ScanLog.i("$label shows ${last.size} event block(s)")
    }

    // ---- The whole-day scan ----

    private suspend fun readDay(target: LocalDate, hasStarted: (LocalTime) -> Boolean) {
        val label = EventParser.dayLabel(target)
        val seen = HashSet<String>()
        var renavigations = 0
        var fruitlessScrolls = 0
        scrollToTop()
        var guard = 0
        while (guard++ < 400) {
            val root = driver.snapshot()
            val onDay = CalendarReader.isCalendar(root) && !DetailsReader.isDetails(root) &&
                CalendarReader.selectedDay(root)?.label == label
            if (!onDay) {
                if (++renavigations > 3) fail("Outlook kept leaving $label.")
                ScanLog.w("No longer on $label; going back to it")
                if (!CalendarReader.isCalendar(root) || DetailsReader.isDetails(root)) openCalendar()
                ensureDayView()
                goToDate(target)
                waitForDay(target)
                scrollToTop()
                continue
            }
            val fresh = CalendarReader.eventBlocks(root)
                .filter { EventParser.stableDesc(it.desc) !in seen }
                .sortedWith(compareBy({ EventParser.startTimeIfStartsOn(it.desc, target) ?: LocalTime.MIN }, { it.node.bounds.top }))
            if (fresh.isEmpty()) {
                if (fruitlessScrolls >= 4 || !scrollGrid(down = true)) break
                fruitlessScrolls++
                continue
            }
            fruitlessScrolls = 0
            for (block in fresh) {
                seen += EventParser.stableDesc(block.desc)
                // Skips all-day events, work-location chips and events that began on an earlier day.
                val start = EventParser.startTimeIfStartsOn(block.desc, target) ?: continue
                if (hasStarted(start)) {
                    startedSkipped++
                    continue
                }
                readEvent(block, target, start, label)
                break // the screen changed; look again
            }
        }
    }

    private suspend fun readEvent(block: EventBlock, target: LocalDate, listedStart: LocalTime, label: String) {
        val name = EventParser.titleFromDesc(block.desc) ?: block.desc.take(40)
        onProgress("Reading event ${events.size + problems.size + 1}: $name")
        if (!openEvent(block, name)) {
            problems += "$name: the event didn't open"
            returnToCalendar()
            return
        }
        val read = readOpenedDetails(name, EventParser.locationCountInDesc(block.desc))
        closeDetails()

        // The screen that opened must be this block's event, not one left open or opened by a stray tap.
        val title = read.title ?: EventParser.titleFromDesc(block.desc)
        when {
            title == null -> problems += "$name: couldn't read its title"
            read.date != null && read.date != target -> problems += "$title: its details say ${read.dateText}, not $label"
            read.start != null && read.start != listedStart ->
                problems += "$name: the event that opened starts at ${read.start}, not $listedStart; skipped"
            !EventParser.descMentions(block.desc, title) -> problems += "$name: the event that opened was '$title'; skipped"
            else -> {
                if (!read.categoryRowFound) problems += "$title: couldn't find its categories"
                val start = read.start ?: listedStart
                // Outlook adds location rows in varying order; the Day view's order is stable.
                val location = EventParser.orderLike(EventParser.locationsInDesc(block.desc), read.locations)
                    .joinToString("; ").ifEmpty { null }
                events += ScannedEvent(title, target, start, location, read.categories)
                ScanLog.i("Read '$title' at ${TriggerTime.formatHhMm(start)}, categories ${read.categories}, location $location")
            }
        }
    }

    // ---- Reading the labels of one slot: a day, a start and a title ----

    /**
     * Opens every block of [t]'s slot that mentions its title (normally one) and keeps those that
     * open as exactly this event. When the calendar has [twins] such events, nothing on the Day
     * view says which block is which, so [LabelSlots.decide] only gives labels all of them share.
     */
    private suspend fun readSlot(t: LabelTarget, twins: Int): LabelRead {
        val time = TriggerTime.formatHhMm(t.start)
        ensureOnDay(t.date)
        val count = min(findBlocks(t.date, t.start, t.title).size, MAX_SLOT_BLOCKS)
        if (count == 0) return LabelRead.Absent("${t.title} ($time): in the phone's calendar but not in Outlook")
        val exact = ArrayList<LabelRead.Read>()
        val unreadable = ArrayList<String>()
        var other: String? = null
        for (i in 0 until count) {
            if (i > 0) ensureOnDay(t.date)
            val block = findBlocks(t.date, t.start, t.title).getOrNull(i) ?: break
            when (val r = readBlock(t, block)) {
                is BlockRead.Exact -> exact += r.read
                is BlockRead.Other -> if (other == null) other = r.message
                is BlockRead.Unreadable -> unreadable += r.message
            }
        }
        return LabelSlots.decide(t.title, time, twins, exact, unreadable, other)
    }

    private sealed interface BlockRead {
        data class Exact(val read: LabelRead.Read) : BlockRead

        /** Another event: its description mentions the title too. */
        data class Other(val message: String) : BlockRead

        /** Couldn't be read, so it may or may not be the event. */
        data class Unreadable(val message: String) : BlockRead
    }

    private suspend fun readBlock(t: LabelTarget, block: EventBlock): BlockRead {
        val time = TriggerTime.formatHhMm(t.start)
        if (!openEvent(block, t.title)) {
            returnToCalendar()
            return BlockRead.Unreadable("${t.title} ($time): the event didn't open")
        }
        val read = readOpenedDetails(t.title, EventParser.locationCountInDesc(block.desc))
        closeDetails()
        val title = read.title
        return when {
            title == null -> BlockRead.Unreadable("${t.title} ($time): couldn't read its title")
            read.date != null && read.date != t.date -> BlockRead.Other("${t.title}: its details say ${read.dateText}")
            read.start != null && read.start != t.start -> BlockRead.Other("${t.title}: the event that opened starts at ${read.start}, not $time")
            CalEvent.normaliseTitle(title) != CalEvent.normaliseTitle(t.title) ->
                BlockRead.Other("${t.title} ($time): the event that opened was '$title'")
            !read.categoryRowFound -> BlockRead.Unreadable("${t.title} ($time): couldn't find its categories")
            else -> BlockRead.Exact(LabelRead.Read(title, read.categories)).also {
                ScanLog.i("Labels of '$title' ($time): ${read.categories}")
            }
        }
    }

    /**
     * Every block on [date] starting at [start] whose description mentions [title], left to right.
     * "Mentions" is a substring test (titles can contain commas), so callers must check the details
     * that open. The service normally sees the whole day; scrolling through the grid is the fallback.
     */
    internal suspend fun findBlocks(date: LocalDate, start: LocalTime, title: String): List<EventBlock> {
        fun matching(root: UiNode) = CalendarReader.eventBlocks(root)
            .filter { EventParser.startTimeIfStartsOn(it.desc, date) == start && EventParser.descMentions(it.desc, title) }
            .sortedBy { it.node.bounds.left }
        matching(driver.snapshot()).let { if (it.isNotEmpty()) return it }
        scrollToTop()
        repeat(12) {
            matching(driver.snapshot()).let { if (it.isNotEmpty()) return it }
            if (!scrollGrid(down = true)) return emptyList()
        }
        return emptyList()
    }

    /** Logs differences between the Day view's timed events and the provider's for [date]. */
    private fun crossCheck(date: LocalDate, expected: List<CalEvent>) {
        val blocks = CalendarReader.eventBlocks(driver.snapshot())
            .mapNotNull { b -> EventParser.startTimeIfStartsOn(b.desc, date)?.let { it to b.desc } }
        val timed = expected.filter { !it.allDay && it.date == date }
        val notInOutlook = timed.filter { e -> blocks.none { (s, d) -> s == e.start && EventParser.descMentions(d, e.title) } }
        val notInProvider = blocks.filter { (s, d) -> timed.none { e -> s == e.start && EventParser.descMentions(d, e.title) } }
        if (notInOutlook.isEmpty() && notInProvider.isEmpty()) {
            ScanLog.i("${EventParser.dayLabel(date)}: the Day view and the phone's calendar agree (${timed.size} events)")
        } else {
            ScanLog.w(
                "${EventParser.dayLabel(date)}: not in the Day view: ${notInOutlook.joinToString { "${it.start} ${it.title}" }.ifEmpty { "-" }}; " +
                    "only in the Day view: ${notInProvider.joinToString { (s, d) -> "$s ${EventParser.titleFromDesc(d) ?: d.take(30)}" }.ifEmpty { "-" }}",
            )
        }
    }

    // ---- Opening and reading an event's details ----

    /** Opens [block]'s details; false if they didn't open (the screen may be anywhere then). */
    internal suspend fun openEvent(block: EventBlock, name: String): Boolean {
        driver.click(block.node, "event '$name'")
        var opened = driver.waitUntil(5_000) { driver.hasId(OutlookSelectors.DETAILS_TITLE) }
        if (!opened && driver.outlookInFront() && driver.hasId(OutlookSelectors.VIEW_SWITCHER) && !driver.hasId(OutlookSelectors.DETAILS_TITLE)) {
            // Still on the calendar: the click didn't take, so tap the block's centre instead, but
            // only where the block really is on screen (a tap elsewhere could hit Join or a link).
            val again = CalendarReader.eventBlocks(driver.snapshot())
                .firstOrNull { EventParser.stableDesc(it.desc) == EventParser.stableDesc(block.desc) }
            if (again != null && again.node.visible && !again.node.bounds.isEmpty) {
                driver.tap(again.node.bounds)
                opened = driver.waitUntil(4_000) { driver.hasId(OutlookSelectors.DETAILS_TITLE) }
            }
        }
        if (!opened) ScanLog.dump("Event didn't open: $name", driver.snapshot().calendarOnlyDump())
        return opened
    }

    /** Reads the open details screen once it has settled, scrolling down for the category row. */
    internal suspend fun readOpenedDetails(name: String, expectedLocations: Int): DetailsRead {
        var (screen, read) = readSettledDetails(name, expectedLocations)
        // The category row is at the bottom; scroll down if it isn't in the tree.
        var scrolls = 0
        while (!read.categoryRowFound && scrolls++ < 4) {
            val scrollView = screen.byId(OutlookSelectors.DETAILS_SCROLLVIEW) ?: break
            if (!driver.scrollVertically(scrollView, down = true)) break
            delay(400)
            screen = driver.snapshot()
            read = read.merge(DetailsReader.read(screen))
        }
        if (!read.categoryRowFound) ScanLog.dump("No category row: $name", screen.calendarOnlyDump())
        return read
    }

    /**
     * Outlook fills the details screen in stages after its title appears: a second location row
     * comes 1–1.3 s later. So read until nothing has changed for a while and there are as many
     * location rows as the Day view's description listed ([expectedLocations]).
     */
    private suspend fun readSettledDetails(name: String, expectedLocations: Int): Pair<UiNode, DetailsRead> {
        val opened = SystemClock.uptimeMillis()
        var screen: UiNode = driver.snapshot()
        var read = DetailsReader.read(screen)
        var lastChange = opened
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - opened >= SETTLE_MAX_MS) {
                ScanLog.w("'$name' details: ${read.locationRows} of $expectedLocations location(s) after ${SETTLE_MAX_MS} ms; using what is there")
                break
            }
            val complete = read.locationRows >= expectedLocations
            if (complete && now - opened >= SETTLE_MIN_MS && now - lastChange >= SETTLE_QUIET_MS) break
            delay(150)
            val again = driver.snapshot()
            if (!DetailsReader.isDetails(again)) break
            val reread = DetailsReader.read(again)
            if (reread != read) {
                val what = listOfNotNull(
                    "title".takeIf { reread.title != read.title },
                    "time".takeIf { reread.start != read.start || reread.date != read.date },
                    "location".takeIf { reread.location != read.location },
                    "categories".takeIf { reread.categories != read.categories || reread.categoryRowFound != read.categoryRowFound },
                )
                ScanLog.i("'$name' details changed (${what.joinToString()}) ${SystemClock.uptimeMillis() - opened} ms after opening")
                screen = again
                read = reread
                lastChange = SystemClock.uptimeMillis()
            }
        }
        return screen to read
    }

    internal suspend fun closeDetails() {
        val close = CalendarReader.closeButton(driver.snapshot())
        if (close != null) driver.click(close, "Close") else driver.back()
        val back = driver.waitUntil(4_000) {
            driver.hasId(OutlookSelectors.VIEW_SWITCHER) && !driver.hasId(OutlookSelectors.DETAILS_TITLE)
        }
        if (!back) returnToCalendar()
    }

    internal suspend fun returnToCalendar() {
        repeat(3) {
            val root = driver.snapshot()
            if (CalendarReader.isCalendar(root) && !DetailsReader.isDetails(root)) return
            if (root.children.isEmpty() || !driver.outlookInFront()) {
                launchOutlook()
            } else {
                val close = CalendarReader.closeButton(root)
                if (close != null) driver.click(close, "Close") else driver.back()
                delay(800)
            }
        }
        if (!CalendarReader.isCalendar(driver.snapshot())) fail("Couldn't get back to Outlook's calendar.")
    }

    private suspend fun scrollToTop() {
        repeat(8) { if (!scrollGrid(down = false)) return }
    }

    /** Scrolls the hourly grid by about a screen; false once it can't go further. */
    private suspend fun scrollGrid(down: Boolean): Boolean {
        val root = driver.snapshot()
        val scrollables = CalendarReader.dayGridScrollables(root)
        for (node in scrollables) {
            if (driver.scrollVertically(node, down)) {
                delay(500)
                return true
            }
        }
        // No accessibility scroll on offer: drag in the hour-label gutter, where no event can be grabbed.
        val grid = scrollables.firstOrNull()?.bounds ?: return false
        val before = signature(root)
        val x = (grid.left + min(70, grid.width / 12)).toFloat()
        val upper = grid.top + grid.height * 0.25f
        val lower = grid.top + grid.height * 0.85f
        if (down) driver.swipe(x, lower, x, upper, 450) else driver.swipe(x, upper, x, lower, 450)
        delay(600)
        return signature(driver.snapshot()) != before
    }

    private fun signature(root: UiNode) = CalendarReader.eventBlocks(root).map { "${it.desc}@${it.node.bounds}" }.sorted()

    internal fun fail(message: String): Nothing {
        runCatching { ScanLog.dump(message, driver.snapshot().calendarOnlyDump()) }
        throw ScanFailure(message)
    }

    private companion object {
        /**
         * Details are read no sooner than this after opening, once unchanged for [SETTLE_QUIET_MS]
         * and with all the locations the description promised (late rows measured at 0.9–1.3 s).
         */
        const val SETTLE_MIN_MS = 700L
        const val SETTLE_QUIET_MS = 350L
        const val SETTLE_MAX_MS = 4_000L

        /** Blocks opened at most for one slot (a title that is part of other titles at the same time). */
        const val MAX_SLOT_BLOCKS = 6
    }
}

/**
 * Events that share a day, a start and a title ("twins") look the same in Outlook's Day view: its
 * blocks give no way to tell which is which. So their labels are read from every block that may
 * be one of them, and count only if those all agree; otherwise none is remembered rather than
 * one event getting another's labels.
 */
internal object LabelSlots {
    /** How many events the Day view may show for this slot: timed, live and not declined; null: unknown. */
    fun twins(expected: List<CalEvent>?, date: LocalDate, start: LocalTime, normalisedTitle: String): Int? =
        expected?.count {
            it.date == date && !it.allDay && it.start == start && !it.cancelled &&
                it.selfStatus != Attendee.STATUS_DECLINED && CalEvent.normaliseTitle(it.title) == normalisedTitle
        }

    /**
     * The slot's labels from the blocks that opened as exactly [title] ([exact]), with the messages
     * of blocks that couldn't be read ([unreadable]) and of one that was another event ([other]).
     */
    fun decide(title: String, time: String, twins: Int, exact: List<LabelRead.Read>, unreadable: List<String>, other: String?): LabelRead {
        if (exact.isEmpty()) return LabelRead.Problem(unreadable.firstOrNull() ?: other ?: "$title ($time): not found in Outlook's Day view")
        val sets = exact.map { r -> r.categories.map { it.trim().lowercase() }.toSet() }.distinct()
        return when {
            sets.size > 1 -> LabelRead.Problem(
                "$title ($time): ${exact.size} events have this title and time but different labels, and Outlook doesn't show which is which",
            )
            twins > 1 && exact.size < twins -> LabelRead.Problem(
                "$title ($time): $twins events have this title and time, and only ${exact.size} could be read in Outlook",
            )
            // A block that couldn't be read may be this very event (the one read being another
            // calendar's copy, say), so the labels read can't be said to be its.
            unreadable.isNotEmpty() -> LabelRead.Problem(
                "$title ($time): another event at this time couldn't be read in Outlook (${unreadable.first()}), so whose labels these are isn't certain",
            )
            else -> exact.first()
        }
    }
}
