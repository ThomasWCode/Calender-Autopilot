package com.thomaswcode.calendareventtimers.booking

import android.content.Context
import com.thomaswcode.calendareventtimers.calendar.CalendarStore
import com.thomaswcode.calendareventtimers.calendar.ProviderCalendar
import com.thomaswcode.calendareventtimers.data.AnswerKind
import com.thomaswcode.calendareventtimers.data.BookingStore
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
    /** [saved] is false in a dry run. */
    data class Booked(val room: String, val saved: Boolean, val notes: List<String>, val bookingId: Long?) : RowOutcome
    data class NoRoom(val missing: List<String>) : RowOutcome
    data class Failed(val reason: String) : RowOutcome
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
            ?: return State.Failed(range, "Outlook's calendar isn't in the phone's calendar store. Open Outlook once and check that it syncs its calendar to the phone.")
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
        labels.remember(read, Instant.now())
        reads.values.forEach { if (it is LabelRead.Problem) problems += it.message }

        val bookings = BookingStore.get(app)
        bookings.refreshReplies(now, zone)
        val keys = candidates.map { it.occurrenceKey }
        val out = BookingPlanner.plan(
            BookingPlanner.Input(
                events = events,
                labels = labelPlan.known + read.mapKeys { it.key.labelKey },
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
            labelsRemembered = labelPlan.remembered,
            labelsRead = read.size,
            withDescription = shown.filter { !DescriptionText.isEmpty(descriptions[it.event.eventId]) }.map { it.key }.toSet(),
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
                if (toBook.isNotEmpty() && service != null) {
                    stopped = outcome {
                        OutlookSession.run(service, "Booking rooms…", hideKeyboard = true, returnTo = OutlookSession.RETURN_BOOKING, onStop = { stop() }) { session ->
                            val navigator = OutlookNavigator(service, session.driver) { s ->
                                session.progress(s)
                                _state.update { if (it is State.Booking) it.copy(step = s) else it }
                            }
                            val booker = BookingNavigator(navigator, service)
                            withTimeout(BOOKING_TIMEOUT_MS) {
                                navigator.openDay(toBook.first().event.date)
                                for ((i, c) in toBook.withIndex()) {
                                    val a = answers[c.key] ?: c.defaults
                                    val title = BookingRules.bookingTitle(c.event.title)
                                    val text = "Booking ${i + 1} of ${toBook.size}: ${c.event.title.trim()}"
                                    session.progress(text)
                                    _state.update { if (it is State.Booking) it.copy(step = text, done = i) else it }
                                    val description = withContext(Dispatchers.IO) { CalendarStore(app).descriptions(listOf(c.event.eventId))[c.event.eventId] }
                                    val people = c.notifyList(a).map { it.email }
                                    val outcome = booker.createBooking(
                                        BookingJob(c.event.date, c.event.start, c.event.endTime, title, people, description), settings, dryRun,
                                    )
                                    rows[c.key] = withContext(NonCancellable) { record(store, c, a, outcome, people, dryRun) }
                                    booker.accountAddress?.let { if (!dryRun) Prefs.learnMyAddress(app, it) }
                                }
                            }
                        }
                    }
                }
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

    private suspend fun record(
        store: BookingStore, c: BookingCandidate, a: Answers, outcome: BookingOutcome, people: List<String>, dryRun: Boolean,
    ): ResultRow {
        val now = Instant.now()
        val row = when (outcome) {
            is BookingOutcome.Booked -> {
                val id = if (outcome.saved && !dryRun) store.recordBooking(c, outcome.room, people, now) else null
                RowOutcome.Booked(outcome.room, outcome.saved, outcome.notes, id)
            }
            is BookingOutcome.NoRoom -> {
                if (!dryRun) store.recordAnswer(c, AnswerKind.NO_ROOM_FREE, now)
                RowOutcome.NoRoom(outcome.missing)
            }
            is BookingOutcome.Failed -> {
                if (!dryRun) store.recordAnswer(c, AnswerKind.FAILED, now)
                RowOutcome.Failed(outcome.reason)
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
