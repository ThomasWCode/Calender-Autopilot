package com.thomaswcode.calendareventtimers.outlook

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import com.thomaswcode.calendareventtimers.booking.DescriptionText
import com.thomaswcode.calendareventtimers.calendar.CalendarStore
import com.thomaswcode.calendareventtimers.calendar.TimeZones
import com.thomaswcode.calendareventtimers.util.ScanLog
import kotlinx.coroutines.delay

/**
 * Debug builds only: single steps of a booking, tried on whatever Outlook screen is showing, so
 * each open question in PHONE-CHECKS.md can be answered from adb without running a whole booking.
 *
 * ```
 * adb shell am broadcast -a com.thomaswcode.calendareventtimers.DEBUG_PROBE --es probe screen
 * adb shell am broadcast -a com.thomaswcode.calendareventtimers.DEBUG_PROBE --es probe title --es text "Probe title"
 * adb shell am broadcast -a com.thomaswcode.calendareventtimers.DEBUG_PROBE --es probe wheel_step --ei wheel 1 --es dir forward --es how scroll
 * adb shell am broadcast -a com.thomaswcode.calendareventtimers.DEBUG_PROBE --es probe wheel_text --ei wheel 1 --es text 14
 * adb shell am broadcast -a com.thomaswcode.calendareventtimers.DEBUG_PROBE --es probe people --es text "a@b.c, d@e.f,"
 * adb shell am broadcast -a com.thomaswcode.calendareventtimers.DEBUG_PROBE --es probe paste --es html "<p>Hi <a href='https://x.y'>link</a></p>"
 * adb shell am broadcast -a com.thomaswcode.calendareventtimers.DEBUG_PROBE --es probe keyboard --es mode hidden
 * ```
 * Results go to logcat (`adb logcat -s CET`). Nothing is ever saved by a probe.
 */
object DebugProbes {
    const val ACTION = "com.thomaswcode.calendareventtimers.DEBUG_PROBE"

    suspend fun run(service: AccessibilityService, intent: Intent) {
        val probe = intent.getStringExtra("probe") ?: "screen"
        val driver = UiDriver(service)
        val root = driver.freshSnapshot()
        ScanLog.i("Probe '$probe'")
        when (probe) {
            "screen" -> describe(root)
            "title" -> {
                val field = EventFormReader.titleField(root) ?: return ScanLog.w("Probe: no title field (open a new event form first)")
                val ok = driver.setText(field, intent.getStringExtra("text") ?: "Probe title")
                delay(500)
                ScanLog.i("Probe title: set=$ok, the form now reads '${EventFormReader.title(driver.snapshot())}'")
            }
            "wheel_step" -> {
                val index = intent.getIntExtra("wheel", 1)
                val forward = intent.getStringExtra("dir") != "back"
                val wheel = TimePickerReader.wheels(root).getOrNull(index) ?: return ScanLog.w("Probe: no wheel $index (open Choose Time first)")
                val before = wheel.value
                val ok = if (intent.getStringExtra("how") == "click") {
                    (if (forward) wheel.next else wheel.previous)?.let { driver.click(it, "wheel row") } ?: false
                } else {
                    driver.scroll(wheel.picker, forward)
                }
                delay(800)
                ScanLog.i("Probe wheel $index: action=$ok, '$before' → '${TimePickerReader.wheels(driver.freshSnapshot()).getOrNull(index)?.value}'")
            }
            "wheel_text" -> {
                val index = intent.getIntExtra("wheel", 1)
                val wheel = TimePickerReader.wheels(root).getOrNull(index) ?: return ScanLog.w("Probe: no wheel $index")
                val input = wheel.input ?: return ScanLog.w("Probe: wheel $index has no input")
                val ok = driver.setText(input, intent.getStringExtra("text") ?: "14")
                delay(800)
                ScanLog.i("Probe wheel $index text: set=$ok, now '${TimePickerReader.wheels(driver.freshSnapshot()).getOrNull(index)?.value}'")
            }
            "people_type" -> {
                // Typed through the service's input method, as a keyboard types.
                val input = PeopleReader.input(root) ?: return ScanLog.w("Probe: no Add People screen")
                driver.click(input, "address field")
                driver.focus(input)
                driver.setText(input, "")
                delay(500)
                val text = intent.getStringExtra("text") ?: "nobody@example.com,"
                val ok = when (intent.getStringExtra("how")) {
                    "each" -> driver.typeEach(text)
                    "key" -> driver.type(text.trimEnd(',')) && driver.key(android.view.KeyEvent.KEYCODE_COMMA)
                    "enter" -> driver.type(text.trimEnd(',')) && driver.key(android.view.KeyEvent.KEYCODE_ENTER)
                    else -> driver.type(text)
                }
                delay(1_500)
                val after = driver.freshSnapshot()
                ScanLog.i("Probe people_type: typed=$ok, chips ${PeopleReader.chipCount(after)}: ${PeopleReader.chipAddresses(after)}, field '${PeopleReader.input(after)?.text}'")
                ScanLog.dump("Probe people_type", after.calendarOnlyDump(maxText = 120))
            }
            "people_flow" -> {
                // The booking's own people steps (BookingNavigator.typePeople / removePerson).
                fun list(key: String) = intent.getStringExtra(key).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (!PeopleReader.isOpen(root)) return ScanLog.w("Probe: no Add People screen")
                val booker = BookingNavigator(OutlookNavigator(service, driver) {}, service)
                runCatching { booker.probePeople(list("add"), list("remove")) }
                    .onFailure { ScanLog.w("Probe people_flow failed: ${it.message}") }
                val after = driver.freshSnapshot()
                ScanLog.i("Probe people_flow: chips ${PeopleReader.chips(after).map { "${it.label}=${it.address}" }}, field '${PeopleReader.inputText(after)}'")
            }
            "people_enter" -> {
                // One address at a time, each followed by the keyboard's Enter (a set comma isn't typed).
                for (address in (intent.getStringExtra("text") ?: "nobody@example.com").split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                    val input = PeopleReader.input(driver.freshSnapshot()) ?: return ScanLog.w("Probe: no Add People screen")
                    driver.focus(input)
                    val set = driver.setText(input, address)
                    delay(800)
                    val enter = driver.imeEnter(PeopleReader.input(driver.freshSnapshot()) ?: input)
                    delay(1_500)
                    val after = driver.freshSnapshot()
                    ScanLog.i("Probe people_enter '$address': set=$set enter=$enter, chips ${PeopleReader.chipCount(after)}: ${PeopleReader.chipAddresses(after)}, field '${PeopleReader.input(after)?.text}'")
                }
            }
            "people" -> {
                val input = PeopleReader.input(root) ?: return ScanLog.w("Probe: no Add People screen")
                val ok = driver.setText(input, intent.getStringExtra("text") ?: "nobody@example.com,")
                delay(1_500)
                val after = driver.snapshot()
                ScanLog.i("Probe people: set=$ok, chips ${PeopleReader.chipCount(after)}: ${PeopleReader.chipAddresses(after)}")
            }
            "paste", "set_description" -> {
                val editor = DescriptionReader.editor(root) ?: return ScanLog.w("Probe: no description editor")
                val html = intent.getStringExtra("html") ?: "<p>Probe <b>bold</b> <a href=\"https://example.com\">link</a></p>"
                driver.click(editor, "editor")
                driver.focus(editor)
                delay(300)
                val ok = if (probe == "paste") {
                    service.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newHtmlText("Probe", DescriptionText.plain(html), html))
                    when (intent.getStringExtra("how")) {
                        "ime" -> driver.imePaste()
                        else -> driver.paste(DescriptionReader.editor(driver.snapshot()) ?: editor)
                    }
                } else if (intent.getStringExtra("how") == "type") {
                    driver.type(DescriptionText.plain(html))
                } else {
                    driver.setText(DescriptionReader.editor(driver.snapshot()) ?: editor, DescriptionText.plain(html))
                }
                delay(800)
                ScanLog.i("Probe $probe: ok=$ok, editor reads '${DescriptionReader.text(driver.snapshot())}'")
                ScanLog.dump("Probe $probe", driver.snapshot().calendarOnlyDump(maxText = 200))
            }
            "keyboard" -> {
                val mode = if (intent.getStringExtra("mode") == "hidden") AccessibilityService.SHOW_MODE_HIDDEN else AccessibilityService.SHOW_MODE_AUTO
                ScanLog.i("Probe keyboard: ${service.softKeyboardController.setShowMode(mode)}")
            }
            "zones" -> {
                // Which time zones of the main calendar's events Android knows; an unknown one makes the
                // provider repeat events in GMT (CalendarRows.events corrects the known aliases).
                val store = CalendarStore(service)
                val main = store.mainCalendar() ?: return ScanLog.w("Probe: no main calendar")
                store.timeZones(main).forEach { (id, count) ->
                    val tz = java.util.TimeZone.getTimeZone(id).id
                    val jt = runCatching { java.time.ZoneId.of(id).id }.getOrElse { "unknown" }
                    ScanLog.i("Probe zone '$id' (${count.first} events, ${count.second} repeating): TimeZone=$tz, java.time=$jt, provider knows=${TimeZones.known(id)}")
                }
            }
            else -> ScanLog.w("Probe '$probe' unknown: screen, title, wheel_step, wheel_text, people, paste, set_description, keyboard, zones")
        }
    }

    /** What every booking reader sees on the current screen, plus the tree. */
    private fun describe(root: UiNode) {
        val lines = mutableListOf<String>()
        if (EventFormReader.isForm(root)) {
            lines += "Form '${EventFormReader.formTitle(root)}', account ${EventFormReader.accountAddress(root)}, title '${EventFormReader.title(root)}'"
            EventFormReader.rows(root).forEach { lines += "  row ${it.texts}${if (it.node.checkable) " (switch)" else ""}" }
            lines += "  people=${EventFormReader.peopleRow(root)?.texts} location='${EventFormReader.location(root)}' " +
                "description=${EventFormReader.descriptionRow(root)?.texts} alert=${EventFormReader.alertRow(root)?.value}"
        }
        if (TimePickerReader.isPicker(root)) {
            lines += "Time picker, wheels=${TimePickerReader.isWheelMode(root)}, start tab selected=${TimePickerReader.isTabSelected(root, OutlookSelectors.TEXT_START_TIME)}"
            TimePickerReader.wheels(root).forEachIndexed { i, w ->
                lines += "  wheel $i '${w.value}' previous='${w.previous?.text}' next='${w.next?.text}'"
            }
        }
        if (LocationReader.isOpen(root)) lines += "Add Location: chips ${LocationReader.chips(root)}, recent ${LocationReader.recentRows(root).map { it.room }}"
        if (RoomFinderReader.isBuildingList(root)) lines += "Room Finder buildings: ${RoomFinderReader.buildings(root).map { it.first }}"
        if (RoomFinderReader.isRoomList(root)) lines += "Room Finder rooms: ${RoomFinderReader.rooms(root).map { it.room }}"
        if (PeopleReader.isOpen(root)) lines += "Add People: chips ${PeopleReader.chipCount(root)} ${PeopleReader.chipAddresses(root)}"
        if (DescriptionReader.isOpen(root)) lines += "Description: editor ${DescriptionReader.editor(root)?.shortClass} editable=${DescriptionReader.editor(root)?.editable} text='${DescriptionReader.text(root)}'"
        if (AlertSheetReader.isOpen(root)) lines += "Alert sheet open"
        PromptReader.dialogMessage(root)?.let { lines += "Dialog '$it' [${PromptReader.positiveButton(root)?.text}]" }
        if (DetailsReader.isDetails(root)) lines += "Details: location ${DetailsReader.read(root).location} reply '${DetailsReader.locationResponse(root)}'"
        if (lines.isEmpty()) lines += "No booking screen recognised"
        lines.forEach { ScanLog.i("Probe: $it") }
        ScanLog.dump("Probe screen", root.calendarOnlyDump(maxText = 200))
    }
}
