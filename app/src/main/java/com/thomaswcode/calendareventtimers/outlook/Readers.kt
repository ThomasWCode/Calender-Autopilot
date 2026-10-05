package com.thomaswcode.calendareventtimers.outlook

import com.thomaswcode.calendareventtimers.domain.EventParser
import com.thomaswcode.calendareventtimers.domain.cleanUiText
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.ALL_DAY_CONTAINER
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.CALENDAR_VIEWS_CONTAINER
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.CATEGORY_ROW
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DAY_VIEW_CONTAINER
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DAY_VIEW_MARKER
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DESC_CALENDAR_TAB
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DESC_CLOSE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DETAILS_END_DATE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DETAILS_LOCATION_CAPTION
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DETAILS_LOCATION_NAME
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DETAILS_ROOT
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DETAILS_START_DATE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DETAILS_START_TIME
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DETAILS_TITLE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.NAV_LABEL
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.STRIP_FLAG_SELECTED
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.STRIP_FLAG_TODAY
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.VIEW_SWITCHER
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.WEEK_STRIP_CONTAINER
import java.time.LocalDate
import java.time.LocalTime
import java.util.Collections
import java.util.IdentityHashMap

/** Reads Outlook's calendar screens (tab bar, header, week strip, Day view). Pure: no Android calls. */
object CalendarReader {
    /** A week-strip day button; [label] is e.g. "Monday 5 October". */
    class StripDay(val node: UiNode, val label: String, val isToday: Boolean, val isSelected: Boolean)

    /** An event block in the Day view, with its full content description. */
    class EventBlock(val node: UiNode, val desc: String)

    private val stripFlagsOnly = Regex("""^(?:, (?:today|Selected))*$""")

    /** The calendar is on screen (Outlook keeps covered screens in the tree, so visibility counts). */
    fun isCalendar(root: UiNode): Boolean = root.find { it.visible && it.viewId == VIEW_SWITCHER } != null

    fun viewSwitcher(root: UiNode): UiNode? = root.byId(VIEW_SWITCHER)

    fun isDayView(root: UiNode): Boolean =
        viewSwitcher(root)?.desc?.contains(DAY_VIEW_MARKER, ignoreCase = true) == true

    /**
     * The bottom-navigation Calendar tab (not clickable while it is already selected). Like
     * [closeButton], only on-screen nodes count: Outlook keeps screens it has covered in the tree.
     */
    fun calendarTab(root: UiNode): UiNode? =
        root.find { n -> n.visible && n.desc == DESC_CALENDAR_TAB && n.children.any { it.viewId == NAV_LABEL } }
            ?: root.find { it.visible && it.desc == DESC_CALENDAR_TAB && it.clickable }

    /** A screen's "Close" (back arrow) button, as on the event details screen. */
    fun closeButton(root: UiNode): UiNode? = root.find { it.visible && it.desc == DESC_CLOSE && it.clickable }

    fun stripDays(root: UiNode): List<StripDay> {
        val container = root.byId(WEEK_STRIP_CONTAINER)
        return (container ?: root).findAll { it.isButton && it.desc != null }.mapNotNull { node ->
            val desc = node.desc!!
            val match = EventParser.dateLabelAtStart.find(desc) ?: return@mapNotNull null
            val rest = desc.substring(match.range.last + 1)
            // Without the container to go by, only buttons with nothing but flags after the date count.
            if (rest.contains(" to ") || (container == null && !stripFlagsOnly.matches(rest))) return@mapNotNull null
            StripDay(node, match.groupValues[1], rest.contains(STRIP_FLAG_TODAY), rest.contains(STRIP_FLAG_SELECTED))
        }
    }

    fun selectedDay(root: UiNode): StripDay? = stripDays(root).firstOrNull { it.isSelected }

    /** The scrollable week strip itself, for swiping between weeks. */
    fun stripGrid(root: UiNode): UiNode? = root.byId(WEEK_STRIP_CONTAINER)?.find { it.scrollable }

    /**
     * Every event block of the Day view, including all-day ones and work-location chips (filter
     * with [EventParser.startTimeIfStartsOn]). Only blocks in the tree are returned, so on the
     * phone the grid may need scrolling to see the whole day.
     */
    fun eventBlocks(root: UiNode): List<EventBlock> {
        val container = root.byId(DAY_VIEW_CONTAINER) ?: root.byId(CALENDAR_VIEWS_CONTAINER)
        val stripNodes = identitySet(stripDays(root).map { it.node })
        return (container ?: root)
            .findAll { n -> n.isButton && n.desc?.let { EventParser.dateLabelAtStart.containsMatchIn(it) } == true }
            .filter { it !in stripNodes }
            .map { EventBlock(it, it.desc!!) }
    }

    /** Scrollable nodes of the timed (hourly) part of the Day view, outermost first. */
    fun dayGridScrollables(root: UiNode): List<UiNode> {
        val container = root.byId(DAY_VIEW_CONTAINER) ?: return emptyList()
        val allDay = identitySet(container.byId(ALL_DAY_CONTAINER)?.walk()?.toList().orEmpty())
        return container.walk().filter { it.scrollable && it !in allDay }.toList()
    }

    private fun identitySet(nodes: List<UiNode>): Set<UiNode> =
        Collections.newSetFromMap(IdentityHashMap<UiNode, Boolean>()).apply { addAll(nodes) }
}

private val CALENDAR_AREAS = setOf(
    WEEK_STRIP_CONTAINER, CALENDAR_VIEWS_CONTAINER, DAY_VIEW_CONTAINER, DETAILS_ROOT,
    // Booking screens (PLAN-ROOM-BOOKING.md §2.3–2.7); the Compose event form has no ids.
    OutlookSelectors.PICKER_ROOT, OutlookSelectors.LOCATION_ROOT, OutlookSelectors.PEOPLE_ROOT,
    OutlookSelectors.DESCRIPTION_FIELD, OutlookSelectors.BUILDING_LIST, OutlookSelectors.ROOM_LIST,
)

/**
 * A tree dump for the log that keeps to the calendar (PLAN.md: calendar only). Texts are replaced
 * by their lengths on any other Outlook screen (a mail list, a message), and, on the calendar, for
 * off-screen nodes outside its own containers, which could be a covered mail screen. The screens
 * of a room booking (event form, time picker, Add Location, Room Finder, Add People, description,
 * alert) count as calendar.
 */
fun UiNode.calendarOnlyDump(maxText: Int = 80): String {
    val onCalendar = CalendarReader.isCalendar(this) || DetailsReader.isDetails(this) || BookingScreens.isAny(this)
    return dump(maxText, CALENDAR_AREAS) { node, inArea -> !onCalendar || !(node.visible || inArea) }
}

/** What the event details screen shows; null fields were not on screen. */
data class DetailsRead(
    val title: String?,
    val dateText: String?,
    val date: LocalDate?,
    val start: LocalTime?,
    /** One entry per location row, as shown (a Zoom link and a room are two). */
    val locations: List<String>,
    val categoryRowFound: Boolean,
    val categories: List<String>,
) {
    /** The locations joined as the Day view's description joins them. */
    val location: String? get() = locations.joinToString("; ").ifEmpty { null }

    val locationRows: Int get() = locations.size

    /** Fills gaps from a later read of the same screen, e.g. after scrolling it. */
    fun merge(later: DetailsRead) = DetailsRead(
        title = title ?: later.title,
        dateText = dateText ?: later.dateText,
        date = date ?: later.date,
        start = start ?: later.start,
        locations = if (later.locations.size > locations.size) later.locations else locations,
        categoryRowFound = categoryRowFound || later.categoryRowFound,
        categories = if (categoryRowFound) categories else later.categories,
    )
}

/** Reads Outlook's event details screen (PLAN.md §2.5). Pure: no Android calls. */
object DetailsReader {
    /** An event's details are on screen. */
    fun isDetails(root: UiNode): Boolean = root.find { it.visible && it.viewId == DETAILS_TITLE } != null

    fun read(root: UiNode): DetailsRead {
        val dateText = cleanUiText(root.byId(DETAILS_START_DATE)?.text)
        val endDate = root.byId(DETAILS_END_DATE)
        // A same-day event shows "14:00 ▸ 14:55 (55 minutes)" in the *end* date field (its desc
        // says "14:00 to 14:55, …"); a multi-day event has a separate start time field.
        val start = EventParser.parseTime(root.byId(DETAILS_START_TIME)?.text)
            ?: EventParser.parseTime(endDate?.desc)
            ?: EventParser.parseTime(endDate?.text)
        // One row per location, e.g. a Zoom link and a room ("KS-121"), joined as in the Day view's description.
        val rows = texts(root, DETAILS_LOCATION_NAME) { it.text }
            .ifEmpty { texts(root, DETAILS_LOCATION_CAPTION) { it.desc?.removePrefix("at location ") } }
        // A set category is a TextView with no id under the row; no category reads "Categorise", "None".
        val row = root.byId(CATEGORY_ROW)
        val categories = row?.let { r -> EventParser.categoriesFrom(r.findAll { it.isTextView }.map { it.text }) }.orEmpty()
        return DetailsRead(
            title = cleanUiText(root.byId(DETAILS_TITLE)?.text)?.ifEmpty { null },
            dateText = dateText,
            date = EventParser.parseDetailsDate(dateText),
            start = start,
            locations = rows,
            categoryRowFound = row != null,
            categories = categories,
        )
    }

    private fun texts(root: UiNode, id: String, pick: (UiNode) -> String?): List<String> =
        root.findAll { it.viewId == id }.mapNotNull { cleanUiText(pick(it))?.ifEmpty { null } }.distinct()

    /** The room's reply under its location, e.g. "Reserved" (PLAN-ROOM-BOOKING.md §2.7). */
    fun locationResponse(root: UiNode): String? =
        cleanUiText(root.byId(OutlookSelectors.DETAILS_LOCATION_RESPONSE)?.text)?.ifEmpty { null }

    /** The pencil that opens the Edit Event form. */
    fun editButton(root: UiNode): UiNode? = root.find { it.viewId == OutlookSelectors.DETAILS_EDIT && it.visible }
}
