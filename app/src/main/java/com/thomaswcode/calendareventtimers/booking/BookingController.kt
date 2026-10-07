package com.thomaswcode.calendareventtimers.booking

import android.content.Context
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.calendar.CalendarStore
import com.thomaswcode.calendareventtimers.calendar.ProviderCalendar
import com.thomaswcode.calendareventtimers.data.AnswerKind
import com.thomaswcode.calendareventtimers.data.BookingStore
import com.thomaswcode.calendareventtimers.engine.LabelCachePolicy
import com.thomaswcode.calendareventtimers.engine.LabelPass
import com.thomaswcode.calendareventtimers.engine.LabelTarget
import com.thomaswcode.calendareventtimers.outlook.AppScope
import com.thomaswcode.calendareventtimers.outlook.BookingJob
import com.thomaswcode.calendareventtimers.outlook.BookingNavigator
import com.thomaswcode.calendareventtimers.outlook.BookingOutcome
import com.thomaswcode.calendareventtimers.outlook.LabelRead
import com.thomaswcode.calendareventtimers.outlook.OutlookBusy
import com.thomaswcode.calendareventtimers.outlook.OutlookNavigator
import com.thomaswcode.calendareventtimers.outlook.OutlookReaderService
import com.thomaswcode.calendareventtimers.outlook.OutlookSession
import com.thomaswcode.calendareventtimers.outlook.RoomSettings
import com.thomaswcode.calendareventtimers.outlook.ScanFailure
import com.thomaswcode.calendareventtimers.util.Prefs
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A prepared run: what the wizard asks about (PLAN-ROOM-BOOKING.md §3.8). */
data class BookingPlan(
    val range: BookingRange,
    val days: List<LocalDate>,
    /** New events, for the wizard. */
    val toAsk: List<BookingCandidate>,
    /** Events the user said no room for before: in the summary only. */
    val answeredBefore: List<BookingCandidate>,
    /** Events that already have a room: never shown (decided 2026-10-05), only counted in the log. */
    val hiddenWithRoom: Int,
    val unlabelled: Int,
    /** Why some labels couldn't be read (those events aren't offered). */
    val labelProblems: List<String>,
    val labelsRemembered: Int,
    val labelsRead: Int,
    /** Occurrence keys of events whose description isn't empty (for the time estimate). */
    val withDescription: Set<String>,
) {
    val all: List<BookingCandidate> get() = (toAsk + answeredBefore).sortedWith(compareBy({ it.event.begin }, { it.event.title }))
}

/** What became of one event in a run. */
sealed interface RowOutcome {
    /** [saved] is false in a dry run. [told]: the addresses put on the booking. */
    data class Booked(val room: String, val saved: Boolean, val notes: List<String>, val bookingId: Long?, val told: List<String> = emptyList()) : RowOutcome
    data class NoRoom(val missing: List<String>) : RowOutcome
    data class Failed(val reason: String) : RowOutcome

    /** Outlook may or may not have saved it ([room] on the form); kept as a booking until the calendar says. */
    data class Uncertain(val reason: String, val room: String?, val bookingId: Long?) : RowOutcome
    data object NoRoomWanted : RowOutcome
    data class NotDone(val reason: String) : RowOutcome
}

data class ResultRow(val candidate: BookingCandidate, val answers: Answers, val outcome: RowOutcome)

data class BookingResults(val range: BookingRange, val rows: List<ResultRow>, val dryRun: Boolean, val stopped: String?) {
    /** Rooms in Settings that Room Finder never listed. */
    val missingRooms: List<String> get() = rows.flatMap { (it.outcome as? RowOutcome.NoRoom)?.missing.orEmpty() }.distinct()
}

/**
 * One booking run at a time (so it outlives the screen): read the range's events, read labels
 * Outlook has to show, let the user answer, then book the rooms in one stretch of Outlook.
 * Idle → Preparing → Ready (wizard) → Booking → Done (results), or Failed.
 */
object BookingController {
    sealed interface State {
        data object Idle : State
        data class Preparing(val range: BookingRange, val step: String) : State
        data class Ready(val plan: BookingPlan, val answers: Map<String, Answers>) : State
        data class Booking(val plan: BookingPlan, val step: String, val done: Int, val total: Int) : State
        data class Done(val results: BookingResults) : State
        data class Failed(val range: BookingRange, val message: String) : State
    }

    private const val LABEL_TIMEOUT_MS = 6 * 60_000L
    private const val BOOKING_TIMEOUT_MS = 20 * 60_000L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    /** Starts preparing [range]; returns why it can't, or null. */
    fun prepare(context: Context, range: BookingRange): String? {
        if (job?.isActive == true) return "A booking run is already going."
        if (OutlookSession.isBusy) return OutlookBusy().message
        val app = context.applicationContext
        if (!CalendarStore.hasAccess(app)) return "Allow calendar access first (Setup checklist)."
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val days = BookingRanges.days(range, today)
        if (days.isEmpty()) return BookingRanges.unavailable(range, today)
        _state.value = State.Preparing(range, "Reading the phone's calendar…")
        ScanLog.i("Room booking: preparing ${BookingRanges.title(range)} (${BookingRanges.describe(days)})")
        job = AppScope.scope.launch {
            try {
                val next = prepareRun(app, range, days, zone)
                withContext(NonCancellable) { _state.value = next }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { _state.value = State.Failed(range, "Stopped.") }
            } catch (e: Exception) {
                ScanLog.e("Preparing room booking failed", e)
                withContext(NonCancellable) { _state.value = State.Failed(range, "Something went wrong: ${e.message}") }
            } finally {
                withContext(NonCancellable) {
                    if (_state.value is State.Preparing) _state.value = State.Failed(range, "Preparing ended unexpectedly.")
                }
            }
        }
        return null
    }

    private suspend fun prepareRun(app: Context, range: BookingRange, days: List<LocalDate>, zone: ZoneId): State {
        Prefs.ensureLoaded(app)
        val store = CalendarStore(app)
        val calendar = withContext(Dispatchers.IO) { store.mainCalendar() }
            ?: return State.Failed(range, "Outlook's LSHTM calendar isn't in the phone's calendar store (or there is more than one). Open Outlook once and check that it syncs your LSHTM calendar to the phone.")
        val now = Instant.now()
        val daySet = days.toSet()
        val events = withContext(Dispatchers.IO) { store.occurrencesOn(calendar, days.first(), days.last(), zone) }
        val candidates = events.filter { BookingPlanner.isCandidateEvent(it, daySet, now) }
        val attendees = withContext(Dispatchers.IO) { store.attendees(events.map { it.eventId }) }

        val labels = LabelPass(app)
        val labelPlan = labels.plan(candidates, now)
        ScanLog.i("Room booking: ${candidates.size} event(s), ${labelPlan.remembered} label(s) remembered, ${labelPlan.targets.size} to read in Outlook")
        val reads = LinkedHashMap<LabelTarget, LabelRead>()
        val problems = ArrayList<String>()
        if (labelPlan.targets.isNotEmpty()) {
            val service = OutlookReaderService.instance
                ?: return State.Failed(range, "${labelPlan.targets.size} event(s) need their labels read in Outlook: turn on the Outlook reader (Settings → Accessibility).")
            step("Reading labels in Outlook…")
            val stopped = outcome {
                OutlookSession.run(service, "Reading labels…", returnTo = OutlookSession.RETURN_BOOKING, onStop = { stop() }) { session ->
                    val navigator = OutlookNavigator(service, session.driver) { s ->
                        step(s)
                        session.progress(s)
                    }
                    withTimeout(LABEL_TIMEOUT_MS) { navigator.readLabels(labelPlan.targets, events.groupBy { it.date }, reads) }
                }
            }
            if (stopped == STOPPED) return State.Failed(range, "Stopped.")
            if (stopped != null) problems += "Reading labels stopped early: $stopped"
        }
        val read = reads.mapNotNull { (t, r) -> (r as? LabelRead.Read)?.let { t to it.categories } }.toMap()
        // In the phone's calendar but not in Outlook: remembered, and counted as unlabelled (never offered).
        val absent = reads.filterValues { it is LabelRead.Absent }.keys
        absent.forEach { ScanLog.i("Not in Outlook, left out: ${it.title} ${it.date} ${it.start}") }
        // The cache proved unreliable: what it gave this run is left out too.
        val distrusted = labels.remember(read, Instant.now(), absent) && labelPlan.known.isNotEmpty()
        val reused = if (distrusted) emptyMap() else labelPlan.known
        reads.values.forEach { if (it is LabelRead.Problem) problems += it.message }
        if (distrusted) problems += LabelPass.distrusted(candidates.count { it.labelKey in labelPlan.known })

        val bookings = BookingStore.get(app)
        bookings.refreshReplies(now, zone)
        val keys = candidates.map { it.occurrenceKey }
        val out = BookingPlanner.plan(
            BookingPlanner.Input(
                events = events,
                labels = reused + read.mapKeys { it.key.labelKey } + absent.associate { it.labelKey to listOf(LabelCachePolicy.ABSENT) },
                attendees = attendees,
                known = bookings.known(keys),
                answers = bookings.answers(keys),
                memory = bookings.memory(candidates.map { it.seriesKey }),
                rooms = Prefs.rooms.value,
                myAddresses = myAddresses(calendar),
                days = daySet,
                now = now,
                zone = zone,
            ),
        )
        out.covered.forEach { (e, c) -> ScanLog.i("Already has a room, not shown: ${e.date} ${e.start} ${e.title.trim()} ($c)") }
        if (out.labelUnknown.isNotEmpty()) ScanLog.w("Labels unknown, not offered: ${out.labelUnknown.joinToString { it.title.trim() }}")
        bookings.rememberPeople(attendees.values.flatten(), now)
        val shown = out.toAsk + out.answeredBefore
        val descriptions = withContext(Dispatchers.IO) { store.descriptions(shown.map { it.event.eventId }) }
        val plan = BookingPlan(
            range = range,
            days = days,
            toAsk = out.toAsk,
            answeredBefore = out.answeredBefore,
            hiddenWithRoom = out.covered.size,
            unlabelled = out.unlabelled,
            labelProblems = problems,
            labelsRemembered = reused.size,
            labelsRead = read.size,
            // Only for the time estimate: an odd description never stops the run.
            withDescription = shown.filter { runCatching { !DescriptionText.isEmpty(descriptions[it.event.eventId]) }.getOrDefault(true) }.map { it.key }.toSet(),
        )
        ScanLog.i("Room booking: ${plan.toAsk.size} to ask about, ${plan.answeredBefore.size} answered before, ${plan.hiddenWithRoom} with a room")
        return State.Ready(plan, shown.associate { it.key to it.defaults })
    }

    /** The user's own addresses: the Outlook account's, plus those learnt or added in Settings. */
    fun myAddresses(calendar: ProviderCalendar?): Set<String> =
        (Prefs.myAddresses.value + listOfNotNull(calendar?.accountName, calendar?.ownerAccount)).map { it.trim().lowercase() }.filter { it.contains('@') }.toSet()

    /** The wizard's answer for one event. */
    fun setAnswers(key: String, answers: Answers) {
        _state.update { if (it is State.Ready) it.copy(answers = it.answers + (key to answers)) else it }
    }

    /** Book Rooms: books every event answered yes, in one stretch of Outlook. Returns why it can't, or null. */
    fun book(context: Context): String? {
        val ready = _state.value as? State.Ready ?: return null
        if (job?.isActive == true) return "A booking run is already going."
        if (OutlookSession.isBusy) return OutlookBusy().message
        val app = context.applicationContext
        Prefs.ensureLoaded(app)
        val plan = ready.plan
        val answers = ready.answers
        val dryRun = Prefs.dryRun.value
        val shown = plan.all
        val toBook = shown.filter { answers[it.key]?.bookRoom == true }
        val service = OutlookReaderService.instance
        if (toBook.isNotEmpty() && service == null) return "Turn on the Outlook reader (Settings → Accessibility) first."
        val settings = RoomSettings(Prefs.rooms.value, Prefs.building.value, Prefs.recentShortcut.value)
        _state.value = State.Booking(plan, "Opening Outlook…", 0, toBook.size)
        ScanLog.i("Room booking: booking ${toBook.size} room(s)${if (dryRun) " (dry run)" else ""}")
        job = AppScope.scope.launch {
            val rows = LinkedHashMap<String, ResultRow>()
            for (c in shown) if (answers[c.key]?.bookRoom != true) rows[c.key] = ResultRow(c, answers[c.key] ?: c.defaults, RowOutcome.NoRoomWanted)
            var stopped: String? = null
            try {
                val store = BookingStore.get(app)
                if (!dryRun) store.rememberAnswers(shown.map { it to (answers[it.key] ?: it.defaults) }, Instant.now())
                // Each event is checked against the calendar again just before it is booked.
                val calendarStore = CalendarStore(app)
                val calendar = if (toBook.isEmpty()) null else withContext(Dispatchers.IO) { calendarStore.mainCalendar() }
                if (toBook.isNotEmpty() && calendar == null) {
                    stopped = "Couldn't read the phone's calendar to check the events again; nothing was booked."
                } else if (toBook.isNotEmpty() && service != null && calendar != null) {
                    // Grows when Outlook's form shows another of the user's addresses.
                    val mine = myAddresses(calendar).toMutableSet()
                    val zone = ZoneId.systemDefault()
                    // The bookings this run has saved (title, start): not cover for the run's other events.
                    val made = HashSet<Pair<String, Instant>>()
                    // Also before Outlook opens, so a run whose events have all changed doesn't open it.
                    val stillOn = toBook.filter { c ->
                        val a = answers[c.key] ?: c.defaults
                        val check = recheck(app, calendarStore, calendar, c, a, mine, settings.rooms, zone, made)
                        if (check is Recheck.Skip) rows[c.key] = skipped(c, a, check)
                        check is Recheck.Go
                    }
                    _state.update { if (it is State.Booking) it.copy(total = stillOn.size) else it }
                    if (stillOn.isNotEmpty()) stopped = outcome {
                        OutlookSession.run(service, "Booking rooms…", hideKeyboard = true, returnTo = OutlookSession.RETURN_BOOKING, onStop = { stop() }) { session ->
                            val navigator = OutlookNavigator(service, session.driver) { s ->
                                session.progress(s)
                                _state.update { if (it is State.Booking) it.copy(step = s) else it }
                            }
                            val booker = BookingNavigator(navigator, service)
                            withTimeout(BOOKING_TIMEOUT_MS) {
                                navigator.openDay(stillOn.first().event.date)
                                for ((i, c) in stillOn.withIndex()) {
                                    val a = answers[c.key] ?: c.defaults
                                    val text = "Booking ${i + 1} of ${stillOn.size}: ${c.event.title.trim()}"
                                    session.progress(text)
                                    _state.update { if (it is State.Booking) it.copy(step = text, done = i) else it }
                                    val check = recheck(app, calendarStore, calendar, c, a, mine, settings.rooms, zone, made)
                                    if (check is Recheck.Skip) {
                                        rows[c.key] = skipped(c, a, check)
                                        continue
                                    }
                                    val go = check as Recheck.Go
                                    val e = go.event
                                    // Recorded as the occurrence is now: its key may have changed since the wizard.
                                    val current = c.copy(event = e)
                                    val title = BookingRules.bookingTitle(e.title)
                                    val description = withContext(Dispatchers.IO) { calendarStore.descriptions(listOf(e.eventId))[e.eventId] }
                                    val outcome = booker.createBooking(BookingJob(e.date, e.start, e.endTime, title, go.people, description), settings, dryRun)
                                    // The form leaves out the user's own address (BookingNavigator.createBooking).
                                    val told = go.people.filterNot { p -> booker.accountAddress?.let { p.equals(it, ignoreCase = true) } == true }
                                    rows[c.key] = withContext(NonCancellable) { record(store, current, a, outcome, told, go.notes, dryRun) }
                                    // Recorded (a booking that may have been saved isn't lost); now stop if Outlook is lost.
                                    booker.stopRun?.let { throw ScanFailure(it) }
                                    if ((outcome is BookingOutcome.Booked && outcome.saved) || outcome is BookingOutcome.Uncertain) {
                                        made += CalEvent.normaliseTitle(title) to e.begin
                                    }
                                    booker.accountAddress?.let { address ->
                                        mine += address.lowercase()
                                        if (!dryRun) Prefs.learnMyAddress(app, address)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // STOP before Outlook opened (while the events were checked again).
                stopped = STOPPED
            } catch (e: Exception) {
                ScanLog.e("Booking run failed", e)
                stopped = "Something went wrong: ${e.message}"
            } finally {
                withContext(NonCancellable) {
                    for (c in toBook) if (c.key !in rows) rows[c.key] = ResultRow(c, answers[c.key] ?: c.defaults, RowOutcome.NotDone(stopped ?: "not reached"))
                    val results = BookingResults(plan.range, shown.mapNotNull { rows[it.key] }, dryRun, stopped)
                    _state.value = State.Done(results)
                    ScanLog.i("Room booking finished: ${results.rows.count { it.outcome is RowOutcome.Booked }} booked, ${results.rows.count { it.outcome is RowOutcome.NoRoom }} without a free room, ${results.rows.count { it.outcome is RowOutcome.Failed }} failed")
                }
            }
        }
        return null
    }

    private fun skipped(c: BookingCandidate, a: Answers, check: Recheck.Skip): ResultRow {
        ScanLog.w("Not booked: ${c.event.title.trim()} ${c.event.date} ${c.event.start}: ${check.reason}")
        return ResultRow(c, a, RowOutcome.NotDone("${check.reason}; run room booking again to book it"))
    }

    /** [c] as the phone's calendar has it now ([BookingPlanner.recheck]). */
    private suspend fun recheck(
        app: Context, store: CalendarStore, calendar: ProviderCalendar, c: BookingCandidate, a: Answers,
        mine: Set<String>, rooms: List<String>, zone: ZoneId, made: Set<Pair<String, Instant>>,
    ): Recheck = withContext(Dispatchers.IO) {
        val events = store.occurrencesOn(calendar, c.event.date, c.event.date, zone)
        val known = BookingStore.get(app).known(listOf(c.key))[c.key]
        BookingPlanner.recheck(c, a, events, store.attendees(events.map { it.eventId }.distinct()), known, rooms, mine, Instant.now(), made)
    }

    private suspend fun record(
        store: BookingStore, c: BookingCandidate, a: Answers, outcome: BookingOutcome, people: List<String>, notes: List<String>, dryRun: Boolean,
    ): ResultRow {
        val now = Instant.now()
        val row = when (outcome) {
            is BookingOutcome.Booked -> {
                val id = if (outcome.saved && !dryRun) store.recordBooking(c, outcome.room, people, now) else null
                RowOutcome.Booked(outcome.room, outcome.saved, notes + outcome.notes, id, people)
            }
            is BookingOutcome.NoRoom -> {
                if (!dryRun) store.recordAnswer(c, AnswerKind.NO_ROOM_FREE, now)
                RowOutcome.NoRoom(outcome.missing)
            }
            is BookingOutcome.Failed -> {
                if (!dryRun) store.recordAnswer(c, AnswerKind.FAILED, now)
                RowOutcome.Failed(outcome.reason)
            }
            is BookingOutcome.Uncertain -> {
                // Kept as a booking until the calendar shows whether Outlook saved it: if it didn't,
                // the reply check finds it missing after the sync grace, and the event is offered again.
                val id = if (!dryRun && outcome.room != null) store.recordBooking(c, outcome.room, people, now) else null
                if (!dryRun && outcome.room == null) store.recordAnswer(c, AnswerKind.FAILED, now)
                RowOutcome.Uncertain(outcome.reason, outcome.room, id)
            }
            is BookingOutcome.Changed -> RowOutcome.Failed("unexpected: ${outcome.what}")
        }
        return ResultRow(c, a, row)
    }

    private fun step(text: String) {
        _state.update { if (it is State.Preparing) it.copy(step = text) else it }
    }

    private const val STOPPED = "Stopped."

    /** Runs [block]; how it ended, as a message (null: it finished). */
    private suspend fun outcome(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: TimeoutCancellationException) {
        ScanLog.w("Room booking timed out")
        "It took too long and was stopped."
    } catch (e: CancellationException) {
        ScanLog.i("Room booking stopped")
        STOPPED
    } catch (e: OutlookBusy) {
        e.message
    } catch (e: ScanFailure) {
        ScanLog.e("Room booking: ${e.message}")
        e.message ?: "Outlook couldn't be driven."
    }

    fun stop() {
        job?.cancel()
    }

    /** Back to the Room booking screen. */
    fun clear() {
        if (job?.isActive != true) _state.value = State.Idle
    }

    /** Reads the rooms' replies again (the results and bookings screens do, while open). */
    suspend fun refreshReplies(context: Context): Int =
        runCatching { BookingStore.get(context).refreshReplies() }.getOrElse { ScanLog.e("Reading room replies failed", it); 0 }
}
