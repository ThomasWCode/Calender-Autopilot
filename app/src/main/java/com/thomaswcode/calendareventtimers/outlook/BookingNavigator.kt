package com.thomaswcode.calendareventtimers.outlook

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.thomaswcode.calendareventtimers.booking.DescriptionText
import com.thomaswcode.calendareventtimers.booking.FormText
import com.thomaswcode.calendareventtimers.booking.RoomChoice
import com.thomaswcode.calendareventtimers.booking.RoomDecision
import com.thomaswcode.calendareventtimers.booking.RoomRow
import com.thomaswcode.calendareventtimers.booking.RoomStatus
import com.thomaswcode.calendareventtimers.booking.Wheel
import com.thomaswcode.calendareventtimers.domain.TriggerTime
import com.thomaswcode.calendareventtimers.domain.cleanUiText
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A room-booking event to make in Outlook (PLAN-ROOM-BOOKING.md §3.9). */
data class BookingJob(
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    /** "Room Booking - {title}" */
    val title: String,
    /** Addresses to tell (lower case); empty: nobody. */
    val people: List<String>,
    /** The original's description as the calendar provider has it (HTML or text); blank: none. */
    val description: String?,
)

/** Which rooms to try and how (Settings). */
data class RoomSettings(val rooms: List<String>, val building: String, val recentShortcut: Boolean)

sealed interface BookingOutcome {
    /** [room] as Room Finder names it. [saved] is false in a dry run (filled in, then discarded). */
    data class Booked(val room: String, val saved: Boolean, val notes: List<String>) : BookingOutcome

    /** None of the rooms was free; [missing] are rooms Room Finder didn't list. Nothing was saved. */
    data class NoRoom(val missing: List<String>) : BookingOutcome

    /** Nothing was saved; the form was discarded. */
    data class Failed(val reason: String) : BookingOutcome

    /** A change to an existing booking went through. */
    data class Changed(val what: String) : BookingOutcome
}

/** A step of one booking that didn't work: that booking fails (its form is discarded); the run goes on. */
class BookingStepFailure(message: String) : Exception(message)

/**
 * Fills in Outlook's event form to book a room, and changes or deletes bookings (Manage bookings).
 * Every step is checked by reading the screen back; a form is saved only once every field reads as
 * intended, and is discarded otherwise, so a half-made event is never left behind.
 *
 * The order matters: time first, because Room Finder shows availability for the form's time; then
 * the room, so an unavailable room costs least; then title, people, description and alert.
 */
class BookingNavigator(private val nav: OutlookNavigator, private val context: Context) {
    private val driver: UiDriver get() = nav.driver

    /** The account address shown on the form (the user's own), once seen. */
    var accountAddress: String? = null
        private set

    private val notes = mutableListOf<String>()
    private var missingRooms: List<String> = emptyList()

    suspend fun createBooking(job: BookingJob, settings: RoomSettings, dryRun: Boolean): BookingOutcome {
        notes.clear()
        nav.ensureOnDay(job.date)
        return withFormGuard(job.title) {
            openNewEventForm()
            setTime(job.date, job.start, job.end)
            val room = chooseRoom(settings, clearFirst = false)
            if (room == null) {
                discard()
                return@withFormGuard BookingOutcome.NoRoom(missingRooms)
            }
            setTitle(job.title)
            if (job.people.isNotEmpty()) addPeople(job.people)
            if (!DescriptionText.isEmpty(job.description)) setDescription(job.description!!)
            setAlertNone()
            verifyForm(job.title, job.date, job.start, job.end, room, job.people.size)
            if (dryRun) {
                ScanLog.i("Dry run: '${job.title}' filled in with $room; discarding it")
                discard()
            } else {
                save()
                ScanLog.i("Saved '${job.title}' with $room")
            }
            BookingOutcome.Booked(room, saved = !dryRun, notes.toList())
        }
    }

    /**
     * Manage bookings: the next free room for an existing booking (its current room cleared first).
     * [room] is the booking's room as the app recorded it, to tell it from another booking with the
     * same title and time; [settings] should leave that room out (it may have declined).
     */
    suspend fun changeRoom(date: LocalDate, start: LocalTime, title: String, room: String?, settings: RoomSettings): BookingOutcome {
        notes.clear()
        return withFormGuard(title) {
            openForEdit(date, start, title, room)
            val chosen = chooseRoom(settings, clearFirst = true)
            if (chosen == null) {
                discard()
                return@withFormGuard BookingOutcome.NoRoom(missingRooms)
            }
            save()
            BookingOutcome.Booked(chosen, saved = true, notes.toList())
        }
    }

    /** Manage bookings: tell [add] about a booking, and take [remove] off it. */
    suspend fun editPeople(
        date: LocalDate, start: LocalTime, title: String, room: String?, add: List<String>, remove: List<String>,
    ): BookingOutcome {
        notes.clear()
        return withFormGuard(title) {
            openForEdit(date, start, title, room)
            openPeople()
            for (email in remove) removePerson(email)
            if (add.isNotEmpty()) typePeople(add)
            closePeople()
            save()
            BookingOutcome.Changed("people updated")
        }
    }

    /** Manage bookings: deletes the booking event (Outlook sends the cancellations). */
    suspend fun deleteBooking(date: LocalDate, start: LocalTime, title: String, room: String?): BookingOutcome {
        notes.clear()
        return withFormGuard(title) {
            openForEdit(date, start, title, room)
            val form = driver.snapshot()
            var row = EventFormReader.deleteRow(form)
            var scrolls = 0
            while ((row == null || !row.node.visible) && scrolls++ < 6) {
                val scroller = form.find { it.shortClass == "ScrollView" && it.scrollable } ?: break
                if (!driver.scroll(scroller, forward = true)) break
                delay(400)
                row = EventFormReader.deleteRow(driver.snapshot())
            }
            row ?: fail("Couldn't find 'Delete event' on the Edit Event form")
            driver.click(row.node, "Delete event")
            val dialog = driver.waitFor(4_000) { r -> PromptReader.positiveButton(r) }
                ?: fail("Outlook didn't ask to confirm the delete")
            val message = PromptReader.dialogMessage(driver.snapshot())
            val label = cleanUiText(dialog.text).orEmpty()
            ScanLog.i("Delete prompt: '$message' [$label]")
            if (label.lowercase() !in DELETE_CONFIRMATIONS) {
                ScanLog.dump("Unexpected delete prompt", driver.snapshot().calendarOnlyDump())
                fail("Outlook asked '$message' with '$label'; not answered")
            }
            driver.click(dialog, label)
            if (!driver.waitUntil(8_000) { CalendarReader.isCalendar(driver.snapshot()) && !EventFormReader.isForm(driver.snapshot()) }) {
                fail("Outlook didn't go back to the calendar after deleting")
            }
            BookingOutcome.Changed("deleted")
        }
    }

    /**
     * Runs one booking's steps. A failed step fails only this booking; STOP (cancellation) still
     * leaves Outlook without a half-made event. Navigation failures (ScanFailure) end the run.
     */
    private suspend fun withFormGuard(title: String, block: suspend () -> BookingOutcome): BookingOutcome = try {
        block()
    } catch (e: BookingStepFailure) {
        ScanLog.e("'$title': ${e.message}")
        runCatching { ScanLog.dump("Booking step failed: ${e.message}", driver.snapshot().calendarOnlyDump()) }
        withContext(NonCancellable) { discardQuietly() }
        BookingOutcome.Failed(e.message ?: "a step failed")
    } catch (e: CancellationException) {
        withContext(NonCancellable) { discardQuietly() }
        throw e
    }

    // ---- The form ----

    private suspend fun openNewEventForm() {
        val button = driver.waitFor(5_000) { r -> r.find { it.desc == OutlookSelectors.DESC_NEW_EVENT && it.visible } }
            ?: fail("Couldn't find Outlook's New event button")
        driver.click(button, "New event")
        waitForForm("The new event form didn't open", 6_000)
        delay(300)
        accountAddress = EventFormReader.accountAddress(driver.snapshot()) ?: accountAddress
    }

    /**
     * Opens the booking's Edit Event form. The Day view only narrows it down (a title can be part of
     * another, and a re-booked event leaves the declined booking at the same time), so each candidate
     * is opened and its details checked: exactly [title], at [start], and in [room] when there is more
     * than one such event. Only then is Edit pressed, since editing or deleting the wrong event sends
     * mail to its invitees.
     */
    private suspend fun openForEdit(date: LocalDate, start: LocalTime, title: String, room: String?) {
        nav.ensureOnDay(date)
        val time = TriggerTime.formatHhMm(start)
        val count = nav.findBlocks(date, start, title).size
        if (count == 0) fail("Couldn't find '$title' at $time in the Day view")
        var titleOnly: Int? = null
        for (i in 0 until count) {
            when (openCandidate(date, start, title, room, i)) {
                Candidate.EXACT -> return pressEdit(title)
                // A second one with the right title but another room: can't tell which (-1).
                Candidate.OTHER_ROOM -> titleOnly = if (titleOnly == null) i else -1
                Candidate.NO -> Unit
            }
            // Back to the Day view; Back on the calendar itself would leave Outlook.
            if (DetailsReader.isDetails(driver.snapshot())) nav.closeDetails()
            nav.ensureOnDay(date)
        }
        // The room may have been changed in Outlook since: fine if it is the only event so called.
        val only = titleOnly?.takeIf { it >= 0 } ?: fail("None of the events at $time is '$title'${room?.let { " in $it" } ?: ""}")
        notes += "the booking's room in Outlook isn't the one the app recorded"
        if (openCandidate(date, start, title, null, only) != Candidate.EXACT) fail("'$title' at $time couldn't be opened again")
        pressEdit(title)
    }

    private enum class Candidate { EXACT, OTHER_ROOM, NO }

    /** Opens the [nth] block that may be [title] at [start] and says whether it is. */
    private suspend fun openCandidate(date: LocalDate, start: LocalTime, title: String, room: String?, nth: Int): Candidate {
        val block = nav.findBlocks(date, start, title).getOrNull(nth) ?: return Candidate.NO
        if (!nav.openEvent(block, title)) {
            nav.returnToCalendar()
            return Candidate.NO
        }
        val details = driver.snapshot()
        val read = DetailsReader.read(details)
        ScanLog.i("Opened '${read.title}' at ${read.start} in ${read.location} ${DetailsReader.locationResponse(details) ?: ""}")
        if (!FormText.sameText(read.title, title) || (read.start != null && read.start != start)) return Candidate.NO
        // One location row must be the room itself: KS-103 must not pass for KS-103D.
        if (room == null || read.locations.any { RoomChoice.sameRoom(it, room) }) return Candidate.EXACT
        return Candidate.OTHER_ROOM
    }

    private suspend fun pressEdit(title: String) {
        val edit = driver.waitFor(3_000) { DetailsReader.editButton(it) } ?: fail("No Edit button on '$title'")
        driver.click(edit, "Edit")
        waitForForm("The Edit Event form didn't open", 6_000)
        if (!EventFormReader.isEdit(driver.snapshot())) fail("Expected the Edit Event form")
    }

    private suspend fun waitForForm(error: String, timeoutMs: Long = 4_000) {
        driver.waitFor(timeoutMs) { if (EventFormReader.isForm(it) && onlyForm(it)) true else null } ?: fail(error)
    }

    /** The form, with none of its sub-screens over it. */
    private fun onlyForm(r: UiNode): Boolean =
        !TimePickerReader.isPicker(r) && !LocationReader.isOpen(r) && !RoomFinderReader.isBuildingList(r) &&
            !RoomFinderReader.isRoomList(r) && !PeopleReader.isOpen(r) && !DescriptionReader.isOpen(r) && !AlertSheetReader.isOpen(r)

    // ---- Time ----

    private suspend fun setTime(date: LocalDate, start: LocalTime, end: LocalTime) {
        val form = driver.snapshot()
        if (formDate(form, date) == date && formTimes(form) == (start to end)) return
        val row = EventFormReader.timeRow(form) ?: fail("Couldn't find the form's Time row")
        nav.onProgress("Setting the time to ${TriggerTime.formatHhMm(start)}–${TriggerTime.formatHhMm(end)}…")
        driver.click(row.node, "Time row")
        driver.waitFor(4_000) { if (TimePickerReader.isPicker(it)) true else null } ?: fail("The time picker didn't open")
        if (!TimePickerReader.isWheelMode(driver.snapshot())) {
            // It opens as a drag editor; its toolbar button switches to the Choose Time wheels.
            val mode = TimePickerReader.modeButton(driver.snapshot()) ?: fail("No button to switch the time picker to wheels")
            driver.click(mode, "picker mode")
            driver.waitFor(3_000) { if (TimePickerReader.isWheelMode(it)) true else null } ?: fail("The time picker didn't switch to wheels")
        }
        selectTab(OutlookSelectors.TEXT_START_TIME)
        setWheels(date, start)
        // The end follows the start (the duration is kept), so this is usually already right.
        selectTab(OutlookSelectors.TEXT_END_TIME)
        setWheels(if (end == LocalTime.MIDNIGHT && start != LocalTime.MIDNIGHT) date.plusDays(1) else date, end)
        val done = TimePickerReader.doneButton(driver.snapshot()) ?: fail("No Done button on the time picker")
        driver.click(done, "time Done")
        waitForForm("The time picker didn't close")
        val after = driver.snapshot()
        val shownDate = formDate(after, date)
        val shownTimes = formTimes(after)
        if (shownDate != date || shownTimes != (start to end)) {
            fail("The form shows ${shownDate ?: "?"} ${shownTimes?.let { "${it.first}–${it.second}" } ?: "?"}, not $date $start–$end")
        }
    }

    private fun formDate(root: UiNode, near: LocalDate): LocalDate? = FormText.parseDate(EventFormReader.dateRow(root)?.value, near)

    private fun formTimes(root: UiNode): Pair<LocalTime, LocalTime>? = FormText.parseTimes(EventFormReader.timeRow(root)?.value)

    private suspend fun selectTab(name: String) {
        val root = driver.snapshot()
        if (TimePickerReader.isTabSelected(root, name)) return
        val tab = TimePickerReader.tab(root, name) ?: fail("No '$name' tab on the time picker")
        driver.click(tab, name)
        if (!driver.waitUntil(2_000) { TimePickerReader.isTabSelected(driver.snapshot(), name) }) fail("The '$name' tab didn't open")
        delay(200)
    }

    private suspend fun setWheels(date: LocalDate, time: LocalTime) {
        stepWheel(0, "date", target = 0, size = 0, wraps = false) { v ->
            FormText.parseDate(v, date)?.let { ChronoUnit.DAYS.between(date, it).toInt() }
        }
        stepWheel(1, "hour", target = time.hour, size = 24, wraps = true) { FormText.parseNumber(it) }
        stepWheel(2, "minute", target = time.minute, size = 60, wraps = true) { FormText.parseNumber(it) }
    }

    /**
     * Steps wheel [index] one value at a time to [target], the shorter way round, reading it back
     * after every step. If a step doesn't move it (the wheel doesn't wrap), the long way is taken.
     */
    private suspend fun stepWheel(index: Int, what: String, target: Int, size: Int, wraps: Boolean, read: (String?) -> Int?) {
        var wrapping = wraps
        var stuck = 0
        repeat(MAX_WHEEL_STEPS) {
            val wheel = TimePickerReader.wheels(driver.snapshot()).getOrNull(index) ?: fail("Couldn't find the $what wheel")
            val value = wheel.value
            val current = read(value) ?: fail("Couldn't read the $what wheel ('$value')")
            if (current == target) return
            val steps = if (wrapping) Wheel.steps(current, target, size) else target - current
            val forward = steps > 0
            val moved = driver.scroll(wheel.picker, forward) ||
                ((if (forward) wheel.next else wheel.previous)?.let { driver.click(it, "$what ${if (forward) "next" else "previous"}") } ?: false)
            if (!moved) fail("The $what wheel can't be moved")
            val changed = driver.waitUntil(1_500) { TimePickerReader.wheels(driver.snapshot()).getOrNull(index)?.value != value }
            if (!changed) {
                if (wrapping) {
                    ScanLog.w("The $what wheel doesn't wrap; going the long way")
                    wrapping = false
                } else if (++stuck > 2) {
                    fail("The $what wheel doesn't move")
                }
            } else {
                stuck = 0
            }
        }
        fail("The $what wheel didn't reach $target")
    }

    // ---- Room ----

    /** Chooses the room (PLAN-ROOM-BOOKING.md §3.10); null when none is free (back on the form). */
    private suspend fun chooseRoom(settings: RoomSettings, clearFirst: Boolean): String? {
        missingRooms = emptyList()
        val row = EventFormReader.locationRow(driver.snapshot()) ?: fail("Couldn't find the form's Location row")
        nav.onProgress("Looking for a free room…")
        driver.click(row.node, "Location row")
        driver.waitFor(4_000) { if (LocationReader.isOpen(it)) true else null } ?: fail("Add Location didn't open")
        if (clearFirst) clearLocations()

        if (settings.recentShortcut) {
            // Recent can be empty: don't wait long for it.
            val recent = waitForStatuses(1_500) { LocationReader.recentRows(it) }
            val pick = RoomChoice.recentShortcut(settings.rooms, recent.map { it.room })
            if (pick != null) {
                ScanLog.i("${pick.name} is free in Add Location's Recent list")
                return confirmRoom(recent.first { it.room.name == pick.name }.node, pick.name)
            }
        }

        val finder = LocationReader.roomFinderButton(driver.snapshot()) ?: fail("No 'Or browse with Room Finder' in Add Location")
        driver.click(finder, "Room Finder")
        driver.waitFor(5_000) { if (RoomFinderReader.isBuildingList(it)) true else null } ?: fail("Room Finder didn't open")
        val building = findBuilding(settings.building)
        driver.click(building, settings.building)
        driver.waitFor(6_000) { if (RoomFinderReader.isRoomList(it)) true else null } ?: fail("${settings.building} didn't open in Room Finder")

        val seen = LinkedHashMap<String, RoomStatus>()
        var end = false
        repeat(MAX_ROOM_PAGES) {
            val rows = waitForStatuses(4_000) { RoomFinderReader.rooms(it) }
            rows.forEach { seen[it.room.name] = it.room.status }
            when (val d = RoomChoice.decide(settings.rooms, seen.map { (n, s) -> RoomRow(n, s) }, end)) {
                is RoomDecision.Chosen -> {
                    ScanLog.i("Room Finder: ${d.row} is free (${seen.size} rooms seen)")
                    return confirmRoom(findRoomRow(d.row), d.row)
                }
                is RoomDecision.NoneFree -> {
                    ScanLog.i("Room Finder: no room in the list is free (${seen.size} rooms seen; not listed: ${d.missing})")
                    missingRooms = d.missing
                    closeRoomScreens()
                    return null
                }
                RoomDecision.NeedMore -> {
                    val list = RoomFinderReader.roomList(driver.snapshot()) ?: fail("Room Finder's list disappeared")
                    val before = rows.map { it.room.name }
                    if (!driver.scroll(list, forward = true)) {
                        end = true
                    } else {
                        delay(500)
                        // A scroll that shows the same rows means the end of the list.
                        end = RoomFinderReader.rooms(driver.snapshot()).map { it.room.name } == before
                    }
                }
            }
        }
        fail("Room Finder's list didn't end")
    }

    /** Reads rows until every one shows Free or Busy (or [timeoutMs] passes: unknown then counts as busy). */
    private suspend fun waitForStatuses(timeoutMs: Long, read: (UiNode) -> List<RoomListRow>): List<RoomListRow> {
        var rows = read(driver.snapshot())
        driver.waitUntil(timeoutMs) {
            rows = read(driver.snapshot())
            rows.isNotEmpty() && rows.all { it.room.status != RoomStatus.UNKNOWN }
        }
        return rows
    }

    private suspend fun findBuilding(name: String): UiNode {
        RoomFinderReader.building(driver.snapshot(), name)?.let { return it }
        // Not offered among Recent/All on screen: search for it.
        val search = RoomFinderReader.searchField(driver.snapshot()) ?: fail("Room Finder has no building search")
        driver.setText(search, name)
        return driver.waitFor(5_000) { RoomFinderReader.building(it, name) } ?: fail("Room Finder has no building '$name'")
    }

    /** The row of room [name] in Room Finder's list, scrolling back up if it has gone off screen. */
    private suspend fun findRoomRow(name: String): UiNode {
        repeat(MAX_ROOM_PAGES) {
            RoomFinderReader.rooms(driver.snapshot()).firstOrNull { it.room.name == name }?.let { return it.node }
            val list = RoomFinderReader.roomList(driver.snapshot()) ?: fail("Room Finder's list disappeared")
            if (!driver.scroll(list, forward = false)) fail("Couldn't scroll back to $name")
            delay(500)
        }
        fail("Couldn't find $name in Room Finder again")
    }

    /** Taps a room row; Outlook returns to Add Location with its chip; Done back to the form. */
    private suspend fun confirmRoom(rowNode: UiNode, name: String): String {
        driver.click(rowNode, name)
        if (!driver.waitUntil(5_000) { LocationReader.chips(driver.snapshot()).any { it.equals(name, ignoreCase = true) } }) {
            fail("$name didn't appear as the location")
        }
        val done = LocationReader.doneButton(driver.snapshot()) ?: fail("No Done in Add Location")
        driver.click(done, "location Done")
        waitForForm("Add Location didn't close")
        val shown = EventFormReader.location(driver.snapshot())
        if (!RoomChoice.namesRoom(shown, name)) fail("The form's location reads '$shown', not $name")
        return name
    }

    private suspend fun clearLocations() {
        if (LocationReader.chips(driver.snapshot()).isEmpty()) return
        val clear = LocationReader.clearButton(driver.snapshot()) ?: fail("No 'Clear location' in Add Location")
        driver.click(clear, "Clear location")
        if (!driver.waitUntil(3_000) { LocationReader.chips(driver.snapshot()).isEmpty() }) fail("The old location didn't clear")
    }

    /** Room list → buildings → Add Location → the form, with nothing chosen. */
    private suspend fun closeRoomScreens() {
        repeat(5) {
            val r = driver.snapshot()
            if (EventFormReader.isForm(r) && onlyForm(r)) return
            val close = RoomFinderReader.closeButton(r) ?: LocationReader.closeButton(r)
            if (close != null) driver.click(close, "Close") else driver.back()
            delay(600)
        }
        waitForForm("Couldn't get back to the form from Room Finder")
    }

    // ---- Title, people, description, alert ----

    private suspend fun setTitle(title: String) {
        val field = EventFormReader.titleField(driver.snapshot()) ?: fail("Couldn't find the title field")
        if (!driver.setText(field, title)) {
            driver.click(field, "title")
            delay(300)
            val again = EventFormReader.titleField(driver.snapshot()) ?: fail("Couldn't find the title field")
            if (!driver.setText(again, title)) fail("Couldn't type the title")
        }
        if (!driver.waitUntil(2_000) { FormText.sameText(EventFormReader.title(driver.snapshot()), title) }) {
            fail("The title reads '${EventFormReader.title(driver.snapshot())}'")
        }
    }

    private suspend fun addPeople(emails: List<String>) {
        nav.onProgress("Adding ${emails.size} ${if (emails.size == 1) "person" else "people"} to notify…")
        openPeople()
        typePeople(emails)
        closePeople()
    }

    private suspend fun openPeople() {
        val row = EventFormReader.peopleRow(driver.snapshot()) ?: fail("Couldn't find the form's People row")
        driver.click(row.node, "People row")
        driver.waitFor(4_000) { if (PeopleReader.isOpen(it)) true else null } ?: fail("Add People didn't open")
        PeopleReader.requiredTab(driver.snapshot())?.let { if (!it.selected) driver.click(it, "Required") }
    }

    /** Types the addresses, each followed by a comma, which turns it into a chip (§2.6). */
    private suspend fun typePeople(emails: List<String>) {
        val before = PeopleReader.chipCount(driver.snapshot())
        val input = PeopleReader.input(driver.snapshot()) ?: fail("No address field in Add People")
        driver.setText(input, emails.joinToString(", ", postfix = ","))
        fun added(r: UiNode) = PeopleReader.chipAddresses(r).containsAll(emails) || PeopleReader.chipCount(r) >= before + emails.size
        if (!driver.waitUntil(3_000) { added(driver.snapshot()) }) {
            // One at a time instead.
            for (email in emails.filter { it !in PeopleReader.chipAddresses(driver.snapshot()) }) {
                val field = PeopleReader.input(driver.snapshot()) ?: fail("No address field in Add People")
                driver.setText(field, "$email,")
                driver.waitUntil(3_000) { email in PeopleReader.chipAddresses(driver.snapshot()) }
            }
        }
        val r = driver.snapshot()
        if (!added(r)) fail("Couldn't add ${(emails - PeopleReader.chipAddresses(r).toSet()).joinToString()}")
        if (!PeopleReader.chipAddresses(r).containsAll(emails)) notes += "the people's addresses weren't shown on their chips"
    }

    private suspend fun removePerson(email: String) {
        val chip = driver.snapshot().find { it.desc?.contains("<$email>", ignoreCase = true) == true }
        if (chip == null) {
            ScanLog.i("$email isn't on the booking")
            return
        }
        driver.click(chip, "chip $email")
        val remove = driver.waitFor(2_000) { r -> r.find { n -> (n.desc ?: n.text)?.let { REMOVE.containsMatchIn(it) } == true && (n.clickable || n.isButton) } }
            ?: fail("Removing someone from a booking isn't automated yet (no Remove after tapping $email's chip)")
        driver.click(remove, "Remove $email")
        if (!driver.waitUntil(2_000) { email !in PeopleReader.chipAddresses(driver.snapshot()) }) fail("$email is still on the booking")
    }

    private suspend fun closePeople() {
        val done = PeopleReader.doneButton(driver.snapshot()) ?: fail("No Done in Add People")
        driver.click(done, "people Done")
        waitForForm("Add People didn't close")
    }

    private suspend fun setDescription(raw: String) {
        nav.onProgress("Copying the description…")
        val row = EventFormReader.descriptionRow(driver.snapshot()) ?: fail("Couldn't find the form's Description row")
        driver.click(row.node, "Description row")
        driver.waitFor(4_000) { if (DescriptionReader.isOpen(it)) true else null } ?: fail("The description editor didn't open")
        delay(700) // the WebView's editor loads
        val editor = DescriptionReader.editor(driver.snapshot()) ?: fail("No description editor")
        driver.click(editor, "description editor")
        driver.focus(editor)
        delay(300)
        val plain = DescriptionText.plain(raw)
        val html = if (DescriptionText.isHtml(raw)) DescriptionText.cleanHtml(raw) else null
        var ok = false
        if (html != null && setClipboard(plain, html)) {
            ok = driver.paste(DescriptionReader.editor(driver.snapshot()) ?: editor)
            delay(800)
        }
        if (!ok) ok = driver.setText(DescriptionReader.editor(driver.snapshot()) ?: editor, plain)
        if (!ok && setClipboard(plain, null)) ok = driver.paste(DescriptionReader.editor(driver.snapshot()) ?: editor)
        clearClipboard()
        if (!ok) fail("Couldn't put the description in")
        DescriptionReader.text(driver.snapshot())?.let { ScanLog.i("Description editor now starts '${it.take(40)}'") }
        val done = DescriptionReader.doneButton(driver.snapshot()) ?: fail("No Done on the description editor")
        driver.click(done, "description Done")
        waitForForm("The description editor didn't close")
        val shown = EventFormReader.descriptionRow(driver.snapshot())?.label
        if (shown == null || shown.equals(OutlookSelectors.TEXT_DESCRIPTION, ignoreCase = true)) {
            notes += "the description may not have been copied"
            ScanLog.w("The form still says 'Description' after copying it")
        }
    }

    private fun setClipboard(plain: String, html: String?): Boolean = runCatching {
        val clip = if (html != null) ClipData.newHtmlText("Event description", plain, html) else ClipData.newPlainText("Event description", plain)
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        true
    }.getOrElse {
        ScanLog.w("Couldn't use the clipboard: ${it.message}")
        false
    }

    private fun clearClipboard() {
        runCatching { context.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
    }

    private suspend fun setAlertNone() {
        val row = EventFormReader.alertRow(driver.snapshot()) ?: fail("Couldn't find the form's Alert row")
        if (row.value.equals(OutlookSelectors.TEXT_ALERT_NONE, ignoreCase = true)) return
        driver.click(row.node, "Alert row")
        driver.waitFor(3_000) { if (AlertSheetReader.isOpen(it)) true else null } ?: fail("The Alert list didn't open")
        val none = AlertSheetReader.option(driver.snapshot(), OutlookSelectors.TEXT_ALERT_NONE) ?: fail("No 'None' in the Alert list")
        driver.click(none, "Alert None")
        if (!driver.waitUntil(2_000) { !AlertSheetReader.isOpen(driver.snapshot()) }) driver.back()
        waitForForm("The Alert list didn't close")
        val value = EventFormReader.alertRow(driver.snapshot())?.value
        if (!value.equals(OutlookSelectors.TEXT_ALERT_NONE, ignoreCase = true)) fail("The alert reads '$value', not None")
    }

    /**
     * Reads the whole form back before saving (PLAN-ROOM-BOOKING.md §3.9 step 8). The people were
     * checked on Add People's chips; how the form's People row shows them isn't known yet
     * (PHONE-CHECKS.md E), so a row still reading "People" is only noted.
     */
    private fun verifyForm(title: String, date: LocalDate, start: LocalTime, end: LocalTime, room: String, people: Int) {
        val f = driver.snapshot()
        if (people > 0 && EventFormReader.peopleRow(f)?.label.equals(OutlookSelectors.TEXT_PEOPLE, ignoreCase = true)) {
            notes += "the form's People row didn't show the people added"
            ScanLog.w("People row still reads 'People' after adding $people")
        }
        val wrong = listOfNotNull(
            "title '${EventFormReader.title(f)}'".takeIf { !FormText.sameText(EventFormReader.title(f), title) },
            "date ${formDate(f, date)}".takeIf { formDate(f, date) != date },
            "time ${formTimes(f)}".takeIf { formTimes(f) != (start to end) },
            "location '${EventFormReader.location(f)}'".takeIf { !RoomChoice.namesRoom(EventFormReader.location(f), room) },
            "alert '${EventFormReader.alertRow(f)?.value}'".takeIf { !EventFormReader.alertRow(f)?.value.equals(OutlookSelectors.TEXT_ALERT_NONE, ignoreCase = true) },
        )
        if (wrong.isNotEmpty()) fail("Before saving, the form had the wrong ${wrong.joinToString()}")
    }

    /**
     * Saves the form. Once Save is pressed this runs to the end even after STOP, so a booking saved in
     * Outlook is never left unrecorded by the app. Saved means: the form has gone and the calendar
     * (a new event) or the event's details (an edited one, which are then closed) show.
     */
    private suspend fun save() = withContext(NonCancellable) {
        val save = EventFormReader.saveButton(driver.snapshot()) ?: fail("No Save button on the form")
        driver.click(save, "Save")
        val outcome = driver.waitFor(10_000) { r ->
            when {
                PromptReader.positiveButton(r) != null || PromptReader.discardButton(r) != null -> "dialog"
                !EventFormReader.isForm(r) && (CalendarReader.isCalendar(r) || DetailsReader.isDetails(r)) -> "saved"
                else -> null
            }
        }
        when (outcome) {
            "saved" -> if (DetailsReader.isDetails(driver.snapshot())) nav.closeDetails()
            "dialog" -> {
                val message = PromptReader.dialogMessage(driver.snapshot())
                ScanLog.dump("Outlook asked after Save: $message", driver.snapshot().calendarOnlyDump())
                // Not answered by guessing; the form is discarded and the question logged for the
                // phone checks (PHONE-CHECKS.md D1).
                fail("Outlook asked '$message' after Save; nothing was saved")
            }
            else -> if (EventFormReader.isForm(driver.snapshot())) {
                fail("The form was still open 10 s after Save; nothing was saved")
            } else {
                // Gone somewhere unexpected: it may well be saved. Not recorded, but the next run finds
                // a saved booking in the calendar by its title and time, and won't book it again.
                ScanLog.dump("After Save", driver.snapshot().calendarOnlyDump())
                fail("Outlook went to an unexpected screen after Save; the booking may have been saved, check Outlook")
            }
        }
    }

    // ---- Leaving ----

    /**
     * Leaves the form without saving, from wherever in it Outlook is. Returns only once Outlook's
     * calendar shows again; otherwise this booking fails, so a dry run or a booking without a free
     * room is never reported as done while a filled-in form is still open.
     */
    private suspend fun discard() {
        repeat(MAX_DISCARD_STEPS) {
            val r = driver.snapshot()
            val discardButton = PromptReader.discardButton(r)
            when {
                discardButton != null -> {
                    driver.click(discardButton, "Discard")
                    driver.waitUntil(4_000) { !EventFormReader.isForm(driver.snapshot()) }
                }
                PromptReader.negativeButton(r) != null -> driver.click(PromptReader.negativeButton(r)!!, "dialog Cancel")
                AlertSheetReader.isOpen(r) -> driver.back()
                TimePickerReader.isPicker(r) -> TimePickerReader.closeButton(r)?.let { driver.click(it, "picker X") } ?: driver.back()
                RoomFinderReader.isRoomList(r) || RoomFinderReader.isBuildingList(r) || LocationReader.isOpen(r) || PeopleReader.isOpen(r) ->
                    (RoomFinderReader.closeButton(r) ?: LocationReader.closeButton(r))?.let { driver.click(it, "Close") } ?: driver.back()
                DescriptionReader.isOpen(r) -> driver.back()
                EventFormReader.isForm(r) -> EventFormReader.cancelButton(r)?.let { driver.click(it, "Cancel") } ?: driver.back()
                CalendarReader.isCalendar(r) && !DetailsReader.isDetails(r) -> return
                DetailsReader.isDetails(r) -> CalendarReader.closeButton(r)?.let { driver.click(it, "Close") } ?: driver.back()
                !driver.outlookInFront() -> fail("Outlook left the screen before the form was discarded; check Outlook for an unsaved event")
                // Nothing readable yet (a screen changing): look again.
                r.children.isEmpty() -> Unit
                else -> {
                    // A screen the app doesn't know, such as a dialog over the form: Back closes most.
                    ScanLog.dump("Unknown screen while discarding", r.calendarOnlyDump())
                    driver.back()
                }
            }
            delay(500)
        }
        val r = driver.snapshot()
        if (CalendarReader.isCalendar(r) && !DetailsReader.isDetails(r)) return
        ScanLog.dump("Couldn't leave the event form", r.calendarOnlyDump())
        fail("Couldn't leave the event form; check Outlook for an unsaved event")
    }

    private suspend fun discardQuietly() {
        runCatching { discard() }.onFailure { ScanLog.w("Discarding the form failed: ${it.message}") }
    }

    private fun fail(message: String): Nothing = throw BookingStepFailure(message)

    private companion object {
        const val MAX_WHEEL_STEPS = 80
        const val MAX_ROOM_PAGES = 8
        const val MAX_DISCARD_STEPS = 10
        val DELETE_CONFIRMATIONS = setOf("delete", "yes", "ok", "send", "cancel event", "cancel meeting", "delete event")
        val REMOVE = Regex("""\b(remove|delete)\b""", RegexOption.IGNORE_CASE)
    }
}
