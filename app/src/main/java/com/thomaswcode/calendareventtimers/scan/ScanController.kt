package com.thomaswcode.calendareventtimers.scan

import android.content.Context
import com.thomaswcode.calendareventtimers.booking.BookingRules
import com.thomaswcode.calendareventtimers.calendar.Attendee
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.calendar.CalendarStore
import com.thomaswcode.calendareventtimers.data.AlarmStore
import com.thomaswcode.calendareventtimers.domain.EventParser
import com.thomaswcode.calendareventtimers.domain.ExistingAlarm
import com.thomaswcode.calendareventtimers.domain.Review
import com.thomaswcode.calendareventtimers.domain.ReviewItem
import com.thomaswcode.calendareventtimers.domain.ScannedEvent
import com.thomaswcode.calendareventtimers.domain.TriggerTime
import com.thomaswcode.calendareventtimers.domain.cleanUiText
import com.thomaswcode.calendareventtimers.engine.LabelPass
import com.thomaswcode.calendareventtimers.engine.LabelTarget
import com.thomaswcode.calendareventtimers.outlook.AppScope
import com.thomaswcode.calendareventtimers.outlook.LabelRead
import com.thomaswcode.calendareventtimers.outlook.OutlookBusy
import com.thomaswcode.calendareventtimers.outlook.OutlookNavigator
import com.thomaswcode.calendareventtimers.outlook.OutlookReaderService
import com.thomaswcode.calendareventtimers.outlook.OutlookSession
import com.thomaswcode.calendareventtimers.outlook.ScanFailure
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

/** What a scan found, for the review screen. */
data class ScanResult(
    val target: LocalDate,
    val isToday: Boolean,
    val items: List<ReviewItem>,
    /** Events whose labels are known (read now or remembered). */
    val eventsRead: Int,
    /** Of those, the ones without a Moveable/Immoveable category. */
    val unlabelled: Int,
    /** Today only: events that had already started, which were not looked at. */
    val startedSkipped: Int,
    val problems: List<String>,
    /** Set when the scan stopped early; the items are then what was read until then. */
    val incomplete: String?,
    /** Labels reused from earlier runs, and labels read in Outlook this time. */
    val labelsRemembered: Int = 0,
    val labelsRead: Int = 0,
    /** False when everything came from the phone's calendar and Outlook wasn't opened. */
    val usedOutlook: Boolean = true,
)

/** One review row's ticks. */
data class Choice(val setAlarm: Boolean, val fiveMinBefore: Boolean)

data class ConfirmSummary(val set: Int, val alreadySet: Int, val skipped: Int, val failed: List<String>, val dryRun: Boolean) {
    val text: String
        get() {
            val parts = mutableListOf(
                when {
                    dryRun -> "Dry run: ${plural(set, "alarm")} would be set"
                    set == 0 -> "No alarms set"
                    else -> "${plural(set, "alarm")} set"
                },
            )
            if (alreadySet > 0) parts += "$alreadySet already set"
            if (skipped > 0) parts += "$skipped skipped"
            if (failed.isNotEmpty()) parts += "${failed.size} failed"
            return parts.joinToString(" · ")
        }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
}

/**
 * Runs one scan at a time (so it outlives the activity) and holds its state for the UI:
 * Idle → Running → Done (review) or Failed.
 *
 * With calendar access the day's events come from the phone's calendar and Outlook is opened only
 * for events whose labels aren't remembered (PLAN-ROOM-BOOKING.md §3.14); often not at all.
 * Without it, the original scan opens every event of the day in Outlook.
 */
object ScanController {
    sealed interface State {
        data object Idle : State
        data class Running(val target: LocalDate, val isToday: Boolean, val step: String) : State
        data class Done(val result: ScanResult) : State
        data class Failed(val target: LocalDate, val isToday: Boolean, val message: String, val partial: ScanResult?) : State
    }

    private const val SCAN_TIMEOUT_MS = 5 * 60_000L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    /** Starts a scan of today or tomorrow; returns why it can't, or null. */
    fun start(context: Context, isToday: Boolean): String? {
        if (job?.isActive == true) return null
        if (OutlookSession.isBusy) return OutlookBusy().message
        val app = context.applicationContext
        val useCalendar = CalendarStore.hasAccess(app)
        if (!useCalendar && OutlookReaderService.instance == null) {
            return "Turn on the Outlook reader (Settings → Accessibility) first."
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val target = if (isToday) today else today.plusDays(1)
        _state.value = State.Running(target, isToday, if (useCalendar) "Reading the phone's calendar…" else "Opening Outlook…")
        ScanLog.i("Scan started for ${if (isToday) "today" else "tomorrow"}, ${EventParser.dayLabel(target)}")

        job = AppScope.scope.launch {
            try {
                val calendar = if (useCalendar) withContext(Dispatchers.IO) { CalendarStore(app).mainCalendar() } else null
                if (calendar != null) {
                    runFromCalendar(app, target, isToday, zone, calendar)
                } else {
                    if (useCalendar) ScanLog.w("Falling back to reading the whole day in Outlook")
                    val service = OutlookReaderService.instance
                    if (service == null) {
                        _state.value = State.Failed(target, isToday, "Outlook's calendar isn't in the phone's calendar store, and the Outlook reader is off.", null)
                    } else {
                        runFullScan(app, service, target, isToday, zone)
                    }
                }
            } finally {
                // Whatever happened, never leave the home screen saying "Reading…".
                withContext(NonCancellable) {
                    if (_state.value is State.Running) {
                        _state.value = State.Failed(target, isToday, "The scan ended unexpectedly.", null)
                    }
                }
                ScanLog.i("Scan finished: ${_state.value.javaClass.simpleName}")
            }
        }
        return null
    }

    private fun step(text: String) {
        _state.update { if (it is State.Running) it.copy(step = text) else it }
    }

    /** The engine: the phone's calendar for the events, remembered labels, Outlook only for the rest. */
    private suspend fun runFromCalendar(
        context: Context, target: LocalDate, isToday: Boolean, zone: ZoneId,
        calendar: com.thomaswcode.calendareventtimers.calendar.ProviderCalendar,
    ) {
        val store = CalendarStore(context)
        val now = Instant.now()
        val all = withContext(Dispatchers.IO) { store.occurrencesOn(calendar, target, target, zone) }
        val timed = all.filter { it.date == target && isAlarmCandidate(it) }
        val (started, upcoming) = timed.partition { isToday && !it.begin.isAfter(now) }
        val labels = LabelPass(context)
        val plan = labels.plan(upcoming, now)
        ScanLog.i("${EventParser.dayLabel(target)}: ${upcoming.size} event(s) to check, ${plan.remembered} label(s) remembered, ${plan.targets.size} to read in Outlook")

        val reads = LinkedHashMap<LabelTarget, LabelRead>()
        val failure: String? = if (plan.targets.isEmpty()) null else readInOutlook(plan.targets, all, reads)
        withContext(NonCancellable) {
            val read = reads.mapNotNull { (t, r) -> (r as? LabelRead.Read)?.let { t to it.categories } }.toMap()
            // The cache proved unreliable: what it gave this run is left out too.
            val distrusted = labels.remember(read, Instant.now()) && plan.known.isNotEmpty()
            val reused = if (distrusted) emptyMap() else plan.known
            val known = reused + read.mapKeys { it.key.labelKey }
            val events = upcoming.mapNotNull { e ->
                // Cleaned as Outlook's screens are (bidi marks, non-breaking spaces), so an alarm set by
                // a whole-day scan is recognised as this event's, not set twice.
                known[e.labelKey]?.let { cats ->
                    ScannedEvent(cleanUiText(e.title).orEmpty(), e.date, e.start, cleanUiText(e.location)?.ifEmpty { null }, cats)
                }
            }
            val problems = reads.values.mapNotNull { (it as? LabelRead.Problem)?.message } +
                listOfNotNull(LabelPass.distrusted(upcoming.count { it.labelKey in plan.known }).takeIf { distrusted })
            val result = buildResult(
                context, target, isToday, events, problems, started.size, zone, failure,
                remembered = reused.size, readNow = read.size, usedOutlook = plan.targets.isNotEmpty(),
            )
            _state.value = if (failure == null) {
                State.Done(result)
            } else {
                State.Failed(target, isToday, failure, result.takeIf { events.isNotEmpty() })
            }
        }
    }

    /** Reads [targets]' labels in Outlook into [into]; returns why it stopped early, or null. */
    private suspend fun readInOutlook(targets: List<LabelTarget>, all: List<CalEvent>, into: MutableMap<LabelTarget, LabelRead>): String? {
        val service = OutlookReaderService.instance
            ?: return "${targets.size} event(s) need their labels read in Outlook: turn on the Outlook reader (Settings → Accessibility)."
        step("Reading labels in Outlook…")
        return outcome {
            OutlookSession.run(service, "Reading labels in Outlook…", onStop = { stop() }) { session ->
                val navigator = OutlookNavigator(service, session.driver) { s ->
                    step(s)
                    session.progress(s)
                }
                withTimeout(SCAN_TIMEOUT_MS) {
                    navigator.readLabels(targets, all.groupBy { it.date }, into)
                }
            }
        }
    }

    /** The original scan: every timed event of the day opened in Outlook. */
    private suspend fun runFullScan(context: Context, service: OutlookReaderService, target: LocalDate, isToday: Boolean, zone: ZoneId) {
        var navigator: OutlookNavigator? = null
        val failure = outcome {
            OutlookSession.run(service, "Reading Outlook…", onStop = { stop() }) { session ->
                val nav = OutlookNavigator(service, session.driver) { s ->
                    step(s)
                    session.progress(s)
                }
                navigator = nav
                withTimeout(SCAN_TIMEOUT_MS) {
                    nav.scan(target) { start -> isToday && !TriggerTime.eventStart(target, start, zone).isAfter(Instant.now()) }
                }
            }
        }
        // After Stop the coroutine is cancelled, and a withContext() that switches dispatcher then
        // throws on its way back instead of returning (prompt cancellation). So the outcome is both
        // built and published inside NonCancellable, never handed back out of it.
        withContext(NonCancellable) {
            val nav = navigator
            val events = nav?.events?.toList().orEmpty()
            val result = buildResult(
                context, target, isToday, events, nav?.problems?.toList().orEmpty(), nav?.startedSkipped ?: 0, zone, failure,
                remembered = 0, readNow = events.size, usedOutlook = true,
            )
            _state.value = if (failure == null) {
                State.Done(result)
            } else {
                State.Failed(target, isToday, failure, result.takeIf { events.isNotEmpty() })
            }
        }
    }

    /** Runs [block] and turns how it ended into a message for the user (null: it finished). */
    private suspend fun outcome(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: TimeoutCancellationException) {
        ScanLog.w("Scan timed out")
        "The scan took too long and was stopped."
    } catch (e: CancellationException) {
        ScanLog.i("Scan cancelled")
        "Scan stopped."
    } catch (e: OutlookBusy) {
        e.message
    } catch (e: ScanFailure) {
        ScanLog.e("Scan failed: ${e.message}")
        e.message ?: "The scan failed."
    } catch (e: Exception) {
        ScanLog.e("Scan crashed", e)
        "Something went wrong: ${e.message}"
    }

    /** Timed, not cancelled or declined, and not one of the app's own room bookings. */
    private fun isAlarmCandidate(e: CalEvent): Boolean =
        !e.allDay && !e.cancelled && e.selfStatus != Attendee.STATUS_DECLINED && !BookingRules.isRoomBooking(e.title)

    fun stop() {
        job?.cancel()
    }

    /** Back to the home screen. */
    fun clear() {
        if (job?.isActive != true) _state.value = State.Idle
    }

    private suspend fun buildResult(
        context: Context, target: LocalDate, isToday: Boolean, events: List<ScannedEvent>, problems: List<String>,
        startedSkipped: Int, zone: ZoneId, incomplete: String?, remembered: Int, readNow: Int, usedOutlook: Boolean,
    ): ScanResult = withContext(NonCancellable + Dispatchers.IO) {
        ScanLog.i("Checking which events already have alarms")
        val existing = AlarmStore.get(context).existingFor(target)
        ScanLog.i("${existing.size} alarm(s) already set for $target")
        val items = Review.build(events, Instant.now(), zone) { event ->
            existing.firstOrNull { it.eventStart == TriggerTime.formatHhMm(event.start) && it.title == event.title }
                ?.let { ExistingAlarm(it.id, Instant.ofEpochMilli(it.triggerAt), it.offsetMin) }
        }
        ScanResult(
            target = target,
            isToday = isToday,
            items = items,
            eventsRead = events.size,
            unlabelled = events.count { EventParser.matchLabel(it.categories) == null },
            startedSkipped = startedSkipped,
            problems = problems,
            incomplete = incomplete,
            labelsRemembered = remembered,
            labelsRead = readNow,
            usedOutlook = usedOutlook,
        )
    }

    /** Sets the ticked alarms (or, in a dry run, only counts them). */
    suspend fun confirm(context: Context, result: ScanResult, choices: Map<String, Choice>, dryRun: Boolean): ConfirmSummary =
        // Runs to the end even if the screen goes away meanwhile, so no alarm is half set.
        withContext(NonCancellable + Dispatchers.IO) {
            val store = AlarmStore.get(context)
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            var set = 0
            var alreadySet = 0
            var skipped = 0
            val failed = mutableListOf<String>()
            for (item in result.items) {
                if (item.existing != null) {
                    alreadySet++
                    continue
                }
                val choice = choices[item.key] ?: Choice(setAlarm = true, fiveMinBefore = false)
                if (!choice.setAlarm) {
                    skipped++
                    continue
                }
                var offset = if (choice.fiveMinBefore) TriggerTime.EARLY_MINUTES else 0
                // The review may have sat open past the 5-minutes-before time: ring at the start instead.
                if (offset > 0 && !TriggerTime.trigger(item.event.date, item.event.start, offset, zone).isAfter(now)) offset = 0
                if (dryRun) {
                    ScanLog.i("Dry run: would set '${item.alarmText}' ${if (offset > 0) "5 min before" else "at"} ${TriggerTime.formatHhMm(item.event.start)}")
                    set++
                    continue
                }
                when (val r = store.add(item.event, item.label, offset, zone, now)) {
                    is AlarmStore.AddOutcome.Added -> set++
                    is AlarmStore.AddOutcome.AlreadySet -> alreadySet++
                    AlarmStore.AddOutcome.TooLate -> failed += "${item.event.title}: it has already started"
                    is AlarmStore.AddOutcome.Failed -> failed += "${item.event.title}: ${r.reason}"
                }
            }
            // Done with this review, even if the screen that asked has gone (never touches a new scan).
            _state.update { if (it is State.Done || it is State.Failed) State.Idle else it }
            ConfirmSummary(set, alreadySet, skipped, failed, dryRun).also { ScanLog.i(it.text) }
        }
}
