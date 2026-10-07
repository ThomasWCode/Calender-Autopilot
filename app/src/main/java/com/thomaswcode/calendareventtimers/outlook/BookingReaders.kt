package com.thomaswcode.calendareventtimers.outlook

import com.thomaswcode.calendareventtimers.booking.RoomChoice
import com.thomaswcode.calendareventtimers.booking.RoomRow
import com.thomaswcode.calendareventtimers.domain.cleanUiText
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.ACTION_DONE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.BUILDING_LIST
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.BUILDING_NAME
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.BUILDING_SEARCH
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.CONTACT_CHIP_TEXT
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DATE_TIME_PICKER
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DESCRIPTION_EDITOR
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DESCRIPTION_FIELD
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DESC_CANCEL
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DESC_CLOSE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DESC_SAVE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DESC_SEND
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DIALOG_MESSAGE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DIALOG_NEGATIVE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.DIALOG_POSITIVE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.FORM_EDIT_TITLE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.FORM_NEW_TITLE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.LOCATION_CHIP_TEXT
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.LOCATION_CLEAR
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.LOCATION_RESULTS
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.LOCATION_ROOT
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.NUMBER_PICKER_INPUT
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.PEOPLE_INPUT
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.PEOPLE_REQUIRED_TAB
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.PEOPLE_ROOT
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.PICKER_MODE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.PICKER_ROOT
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.RESULT_DETAIL
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.RESULT_NAME
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.ROOM_FINDER
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.ROOM_LIST
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.START_END_TABS
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.TEXT_ALERT
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.TEXT_ALERT_AT_TIME
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.TEXT_ALERT_NONE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.TEXT_DATE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.TEXT_DELETE_EVENT
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.TEXT_DESCRIPTION
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.TEXT_DISCARD
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.TEXT_DISCARD_PROMPT
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.TEXT_LOCATION
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.TEXT_ONLINE_MEETING
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.TEXT_PEOPLE_HINT
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.TEXT_PEOPLE
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.TEXT_TIME_PREFIX
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors.TEXT_TIME_ZONE

/*
 * Readers for the screens a booking goes through (PLAN-ROOM-BOOKING.md §2.3–2.7). Pure, like
 * CalendarReader: tested against the dumps in reference/room_booking.
 */

private fun UiNode.texts(): List<String> = findAll { it.isTextView }.mapNotNull { cleanUiText(it.text)?.ifEmpty { null } }

/**
 * A screen is open only when its marker is on screen: Outlook can keep covered or closing screens in
 * the tree, and the "only the form" check after closing a sub-screen must not see them.
 */
private fun UiNode.visibleId(id: String): Boolean = find { it.viewId == id && it.visible } != null

/** The top window holding a node that matches [predicate], so a sheet's texts aren't confused with the form's below. */
private fun UiNode.windowWith(predicate: (UiNode) -> Boolean): UiNode? = children.firstOrNull { w -> w.find(predicate) != null }

/** A clickable row of the event form and the texts inside it, in order ("Alert", "15 minutes before"). */
class FormRow(val node: UiNode, val texts: List<String>) {
    val label: String? get() = texts.firstOrNull()
    val value: String? get() = texts.getOrNull(1)
}

/** Outlook's New Event / Edit Event form: Jetpack Compose, found by texts and descriptions. */
object EventFormReader {
    fun isForm(root: UiNode): Boolean = formTitle(root) != null && saveButton(root) != null

    /** "New Event" or "Edit Event". */
    fun formTitle(root: UiNode): String? = root.find { n ->
        n.isTextView && cleanUiText(n.text).let { it == FORM_NEW_TITLE || it == FORM_EDIT_TITLE }
    }?.let { cleanUiText(it.text) }

    fun isEdit(root: UiNode): Boolean = formTitle(root) == FORM_EDIT_TITLE

    fun saveButton(root: UiNode): UiNode? = root.find { it.desc == DESC_SAVE || it.desc == DESC_SEND }

    fun cancelButton(root: UiNode): UiNode? = root.find { it.desc == DESC_CANCEL }

    /** The account the event is made in: desc "Richard White, richard.white@lshtm.ac.uk". */
    fun accountAddress(root: UiNode): String? = root.findAll { it.desc != null }
        .firstNotNullOfOrNull { n -> Regex("""^[^,]+,\s*([^\s,]+@[^\s,]+)$""").find(n.desc!!.trim())?.groupValues?.get(1) }

    private fun form(root: UiNode): UiNode? = root.find { it.shortClass == "ScrollView" && it.find { n -> n.shortClass == "EditText" || n.isTextView && cleanUiText(n.text) == TEXT_DATE } != null }

    /** The title field (an EditText with a "Title" placeholder while empty). */
    fun titleField(root: UiNode): UiNode? = form(root)?.find { it.shortClass == "EditText" }

    /** The title typed so far ("" when the placeholder shows). */
    fun title(root: UiNode): String? = titleField(root)?.let { cleanUiText(it.text).orEmpty() }

    /** Every clickable row below the title, in order (switches included, flagged [UiNode.checkable]). */
    fun rows(root: UiNode): List<FormRow> {
        val form = form(root) ?: return emptyList()
        return form.findAll { it.clickable && it.shortClass != "EditText" && it.shortClass != "Spinner" }
            .map { FormRow(it, it.texts()) }
    }

    fun row(root: UiNode, label: String): FormRow? = rows(root).firstOrNull { it.label.equals(label, ignoreCase = true) }

    /** People: labelled "People" until someone is added, and always the first ordinary row. */
    fun peopleRow(root: UiNode): FormRow? = row(root, TEXT_PEOPLE) ?: rows(root).firstOrNull { !it.node.checkable && it.texts.isNotEmpty() }

    fun dateRow(root: UiNode): FormRow? = row(root, TEXT_DATE)

    /** "Time (GMT+1)", "09:05 ▸ 10:00", "Duration: 55 minutes". */
    fun timeRow(root: UiNode): FormRow? = rows(root).firstOrNull { r ->
        val l = r.label ?: return@firstOrNull false
        l.startsWith(TEXT_TIME_PREFIX, ignoreCase = true) && !l.equals(TEXT_TIME_ZONE, ignoreCase = true)
    }

    /** Location: labelled "Location" while empty; once set it shows the room, just above "Online Meeting". */
    fun locationRow(root: UiNode): FormRow? {
        row(root, TEXT_LOCATION)?.let { return it }
        val rows = rows(root)
        val online = rows.indexOfFirst { it.label.equals(TEXT_ONLINE_MEETING, ignoreCase = true) }
        return if (online > 0) rows.subList(0, online).lastOrNull { !it.node.checkable && it.texts.isNotEmpty() } else null
    }

    /** The location shown, or null while it is still the "Location" placeholder. */
    fun location(root: UiNode): String? = locationRow(root)?.label?.takeUnless { it.equals(TEXT_LOCATION, ignoreCase = true) }

    /** Description: labelled "Description" while empty; it follows the Online Meeting row and its switch. */
    fun descriptionRow(root: UiNode): FormRow? {
        row(root, TEXT_DESCRIPTION)?.let { return it }
        val rows = rows(root)
        val online = rows.indexOfFirst { it.label.equals(TEXT_ONLINE_MEETING, ignoreCase = true) }
        return if (online >= 0) rows.drop(online + 1).firstOrNull { !it.node.checkable } else null
    }

    fun alertRow(root: UiNode): FormRow? = row(root, TEXT_ALERT)

    /** The Online Meeting (Zoom) switch: the switch row right after "Online Meeting" (off by default). */
    fun onlineMeetingSwitch(root: UiNode): UiNode? {
        val rows = rows(root)
        val online = rows.indexOfFirst { it.label.equals(TEXT_ONLINE_MEETING, ignoreCase = true) }
        if (online < 0) return null
        rows[online].node.find { it.checkable }?.let { return it }
        return rows.getOrNull(online + 1)?.node?.takeIf { it.checkable }
    }

    fun deleteRow(root: UiNode): FormRow? = row(root, TEXT_DELETE_EVENT)
}

/** One Choose Time wheel: a NumberPicker, its value and the rows above (previous) and below (next). */
class TimeWheel(val picker: UiNode, val input: UiNode?, val previous: UiNode?, val next: UiNode?) {
    val value: String? get() = cleanUiText(input?.text)?.ifEmpty { null } ?: cleanUiText(input?.desc)
}

/** The Time row's picker: a drag editor that switches to Choose Time wheels (reference/room_booking/time_picker_service_dumps.txt). */
object TimePickerReader {
    fun isPicker(root: UiNode): Boolean = root.visibleId(PICKER_MODE)

    fun isWheelMode(root: UiNode): Boolean = root.visibleId(DATE_TIME_PICKER)

    fun modeButton(root: UiNode): UiNode? = root.byId(PICKER_MODE)

    fun doneButton(root: UiNode): UiNode? = (root.byId(PICKER_ROOT) ?: root).find { it.viewId == ACTION_DONE }

    /** The X, which closes the picker without applying. */
    fun closeButton(root: UiNode): UiNode? = root.byId(PICKER_ROOT)?.find { it.shortClass == "ImageButton" && it.clickable }

    /** The "Start time" or "End time" tab. */
    fun tab(root: UiNode, name: String): UiNode? = root.byId(START_END_TABS)?.find { it.desc == name }
        ?: root.byId(START_END_TABS)?.find { it.isTextView && cleanUiText(it.text) == name }

    fun isTabSelected(root: UiNode, name: String): Boolean {
        val tab = tab(root, name) ?: return false
        return tab.selected || tab.find { it.selected } != null
    }

    /** Date, hour and minute, in that order. */
    fun wheels(root: UiNode): List<TimeWheel> = root.byId(DATE_TIME_PICKER)?.findAll { it.shortClass == "NumberPicker" }?.map { p ->
        val input = p.find { it.viewId == NUMBER_PICKER_INPUT }
        val buttons = p.findAll { it.isButton }
        TimeWheel(
            picker = p,
            input = input,
            previous = input?.let { i -> buttons.firstOrNull { it.bounds.bottom <= i.bounds.top + 2 } },
            next = input?.let { i -> buttons.firstOrNull { it.bounds.top >= i.bounds.bottom - 2 } },
        )
    }.orEmpty()
}

/** Rows of a room list (Add Location's Recent, Room Finder's rooms): the room, its status, the row to click. */
class RoomListRow(val room: RoomRow, val node: UiNode)

private fun roomRows(container: UiNode?): List<RoomListRow> = container?.findAll { it.clickable && it.byId(RESULT_NAME) != null }
    ?.mapNotNull { row ->
        val name = cleanUiText(row.byId(RESULT_NAME)?.text)?.ifEmpty { null } ?: return@mapNotNull null
        RoomListRow(RoomRow(name, RoomChoice.status(row.byId(RESULT_DETAIL)?.text)), row)
    }.orEmpty()

/** Add Location: a place field, Room Finder, and recent rooms with their status for the form's time. */
object LocationReader {
    fun isOpen(root: UiNode): Boolean = root.visibleId(LOCATION_ROOT)

    fun roomFinderButton(root: UiNode): UiNode? = root.byId(ROOM_FINDER)

    fun recentRows(root: UiNode): List<RoomListRow> = roomRows(root.byId(LOCATION_RESULTS))

    /** The places chosen so far (chips), e.g. "KS-103D". */
    fun chips(root: UiNode): List<String> = root.findAll { it.viewId == LOCATION_CHIP_TEXT }.mapNotNull { cleanUiText(it.text)?.ifEmpty { null } }

    fun clearButton(root: UiNode): UiNode? = root.byId(LOCATION_CLEAR)

    fun doneButton(root: UiNode): UiNode? = root.byId(LOCATION_ROOT)?.find { it.viewId == ACTION_DONE }

    fun closeButton(root: UiNode): UiNode? = root.byId(LOCATION_ROOT)?.find { it.desc == DESC_CLOSE && it.clickable }
}

/** Room Finder: buildings, then a building's rooms with Free/Busy. */
object RoomFinderReader {
    fun isBuildingList(root: UiNode): Boolean = root.visibleId(BUILDING_LIST)

    fun searchField(root: UiNode): UiNode? = root.byId(BUILDING_SEARCH)

    /** Buildings by name, Recent and All sections together (the same building may be in both). */
    fun buildings(root: UiNode): List<Pair<String, UiNode>> = root.byId(BUILDING_LIST)
        ?.findAll { it.clickable && it.byId(BUILDING_NAME) != null }
        ?.mapNotNull { row -> cleanUiText(row.byId(BUILDING_NAME)?.text)?.ifEmpty { null }?.let { it to row } }.orEmpty()

    fun building(root: UiNode, name: String): UiNode? = buildings(root).firstOrNull { it.first.equals(name.trim(), ignoreCase = true) }?.second

    fun isRoomList(root: UiNode): Boolean = root.visibleId(ROOM_LIST)

    fun roomList(root: UiNode): UiNode? = root.byId(ROOM_LIST)

    fun rooms(root: UiNode): List<RoomListRow> = roomRows(root.byId(ROOM_LIST))

    fun closeButton(root: UiNode): UiNode? = root.find { it.desc == DESC_CLOSE && it.clickable && it.visible }
}

/** Add People: Required/Optional tabs and an address field that turns "a@b.c," into a chip. */
object PeopleReader {
    private val address = Regex("""<([^<>\s]+@[^<>\s]+)>""")

    fun isOpen(root: UiNode): Boolean = root.visibleId(PEOPLE_ROOT)

    fun input(root: UiNode): UiNode? = root.byId(PEOPLE_ROOT)?.find { it.viewId == PEOPLE_INPUT }

    /** What is typed in the address field: "" while it shows its placeholder. */
    fun inputText(root: UiNode): String =
        cleanUiText(input(root)?.text).orEmpty().takeUnless { it.equals(TEXT_PEOPLE_HINT, ignoreCase = true) }.orEmpty()

    /** One chip: the node to tap, its label (description, else text) and the address it shows, if any. */
    class Chip(val node: UiNode, val label: String, val address: String?)

    /** The chips, in order: the innermost clickable layout around each #contact_chip_text. */
    fun chips(root: UiNode): List<Chip> {
        val people = root.byId(PEOPLE_ROOT) ?: return emptyList()
        val around = people.findAll { n -> n.clickable && n.find { it.viewId == CONTACT_CHIP_TEXT } != null }
        // Not a layout holding a chip (or the only chip) that is itself clickable.
        val innermost = around.filter { c -> around.none { o -> o !== c && c.find { it === o } != null } }
        return innermost.map { n ->
            val text = cleanUiText(n.find { it.viewId == CONTACT_CHIP_TEXT }?.text).orEmpty()
            val desc = cleanUiText(n.desc).orEmpty()
            val addr = address.find(desc)?.groupValues?.get(1) ?: text.takeIf { it.contains('@') && !it.contains(' ') }
            Chip(n, desc.ifEmpty { text }, addr?.lowercase())
        }
    }

    fun requiredTab(root: UiNode): UiNode? = root.byId(PEOPLE_REQUIRED_TAB)

    /** Addresses on the chips (lower case): from the chip's description `<a@b.c>`, else its text. */
    fun chipAddresses(root: UiNode): List<String> {
        val fromDesc = root.findAll { it.desc != null && address.containsMatchIn(it.desc!!) }
            .map { address.find(it.desc!!)!!.groupValues[1].lowercase() }
        val fromText = root.findAll { it.viewId == CONTACT_CHIP_TEXT }.mapNotNull { cleanUiText(it.text) }
            .filter { it.contains('@') }.map { it.lowercase() }
        return (fromDesc + fromText).distinct()
    }

    fun chipCount(root: UiNode): Int = root.findAll { it.viewId == CONTACT_CHIP_TEXT }.size

    fun doneButton(root: UiNode): UiNode? = root.byId(PEOPLE_ROOT)?.find { it.viewId == ACTION_DONE }
}

/** The Description dialog: a rich-text editor in a WebView. */
object DescriptionReader {
    fun isOpen(root: UiNode): Boolean = root.visibleId(DESCRIPTION_EDITOR) || root.visibleId(DESCRIPTION_FIELD)

    /** The editor's WebView; it doesn't always carry its id (seen on 2026-10-07), so also the WebView inside the field. */
    fun webView(root: UiNode): UiNode? =
        root.byId(DESCRIPTION_EDITOR) ?: root.byId(DESCRIPTION_FIELD)?.find { it.shortClass == "WebView" }

    /** The editable node inside the WebView (the service sees it; dumps don't), else the WebView itself. */
    fun editor(root: UiNode): UiNode? = webView(root)?.let { wv -> wv.find { it !== wv && it.editable } ?: wv }

    /** Text the editor exposes, when it exposes any. */
    fun text(root: UiNode): String? = editor(root)?.let { e -> cleanUiText(e.text)?.ifEmpty { null } ?: e.texts().joinToString(" ").ifEmpty { null } }

    fun doneButton(root: UiNode): UiNode? = if (!isOpen(root)) null else root.find { it.viewId == ACTION_DONE && it.desc?.startsWith("Done") == true }
}

/** The Alert sheet: "None", "At time of event", "5 minutes before", … */
object AlertSheetReader {
    private val OPTIONS = setOf(TEXT_ALERT_NONE, TEXT_ALERT_AT_TIME, "5 minutes before", "10 minutes before", "15 minutes before", "30 minutes before")

    /**
     * The window listing the options. One option alone isn't enough: the form's own Alert row shows
     * the current one ("At time of event" when that is the default).
     */
    private fun sheet(root: UiNode): UiNode? = root.children.firstOrNull { w ->
        w.findAll { it.isTextView && cleanUiText(it.text) in OPTIONS }.mapNotNull { cleanUiText(it.text) }.distinct().size >= 3
    }

    fun isOpen(root: UiNode): Boolean = sheet(root) != null

    fun option(root: UiNode, text: String): UiNode? = sheet(root)?.find { it.isTextView && cleanUiText(it.text) == text }
}

/** Outlook's questions: "Discard event?" (Compose) and the platform dialog "Delete the event?". */
object PromptReader {
    fun discardButton(root: UiNode): UiNode? =
        root.windowWith { it.isTextView && cleanUiText(it.text) == TEXT_DISCARD_PROMPT }
            ?.find { it.isTextView && cleanUiText(it.text) == TEXT_DISCARD }

    fun dialogMessage(root: UiNode): String? = cleanUiText(root.byId(DIALOG_MESSAGE)?.text)

    fun positiveButton(root: UiNode): UiNode? = root.byId(DIALOG_POSITIVE)

    fun negativeButton(root: UiNode): UiNode? = root.byId(DIALOG_NEGATIVE)
}

/** Any screen of a booking run: logged in full when something goes wrong (it's calendar, not mail). */
object BookingScreens {
    fun isAny(root: UiNode): Boolean =
        EventFormReader.isForm(root) || TimePickerReader.isPicker(root) || LocationReader.isOpen(root) ||
            RoomFinderReader.isBuildingList(root) || RoomFinderReader.isRoomList(root) || PeopleReader.isOpen(root) ||
            DescriptionReader.isOpen(root) || AlertSheetReader.isOpen(root) || PromptReader.discardButton(root) != null
}
