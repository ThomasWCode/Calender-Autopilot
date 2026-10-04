package com.thomaswcode.calendareventtimers.scan

import android.content.Context
import android.content.Intent
import com.thomaswcode.calendareventtimers.data.AlarmStore
import com.thomaswcode.calendareventtimers.domain.EventParser
import com.thomaswcode.calendareventtimers.domain.ExistingAlarm
import com.thomaswcode.calendareventtimers.domain.Review
import com.thomaswcode.calendareventtimers.domain.ReviewItem
import com.thomaswcode.calendareventtimers.domain.TriggerTime
import com.thomaswcode.calendareventtimers.outlook.OutlookNavigator
import com.thomaswcode.calendareventtimers.outlook.OutlookReaderService
import com.thomaswcode.calendareventtimers.outlook.ScanFailure
import com.thomaswcode.calendareventtimers.outlook.ScanOverlay
import com.thomaswcode.calendareventtimers.outlook.UiDriver
import com.thomaswcode.calendareventtimers.ui.MainActivity
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
    /** Events whose details were read. */
    val eventsRead: Int,
    /** Of those, the ones without a Moveable/Immoveable category. */
    val unlabelled: Int,
    /** Today only: events that had already started, which were not opened. */
    val startedSkipped: Int,
    val problems: List<String>,
    /** Set when the scan stopped early; the items are then what was read until then. */
    val incomplete: String?,
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
 * Runs one scan at a time in the accessibility service's scope (so it outlives the activity),
 * and holds its state for the UI: Idle → Running → Done (review) or Failed.
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
        val service = OutlookReaderService.instance
            ?: return "Turn on the Outlook reader (Settings → Accessibility) first."
        val app = context.applicationContext
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val target = if (isToday) today else today.plusDays(1)
        _state.value = State.Running(target, isToday, "Opening Outlook…")
        ScanLog.i("Scan started for ${if (isToday) "today" else "tomorrow"}, ${EventParser.dayLabel(target)}")

        job = service.scope.launch {
            val overlay = ScanOverlay(service) {
                ScanLog.i("Stop pressed")
                stop()
            }
            val navigator = OutlookNavigator(service, UiDriver(service)) { step ->
                _state.update { if (it is State.Running) it.copy(step = step) else it }
                overlay.post(step)
            }
            try {
                withContext(Dispatchers.Main) { overlay.show("Reading Outlook…") }
                runScan(app, target, isToday, navigator, zone)
            } finally {
                // Whatever happened, never leave the overlay up or the home screen saying "Reading…".
                withContext(NonCancellable) {
                    if (_state.value is State.Running) {
                        _state.value = State.Failed(target, isToday, "The scan ended unexpectedly.", null)
                    }
                    withContext(Dispatchers.Main) {
                        overlay.hide()
                        // Allowed from the background while the accessibility service is bound.
                        runCatching {
                            service.startActivity(
                                Intent(service, MainActivity::class.java).addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                ),
                            )
                        }.onFailure { ScanLog.e("Couldn't bring the app back", it) }
                    }
                }
                ScanLog.i("Scan finished: ${_state.value.javaClass.simpleName}")
            }
        }
        return null
    }

    /** Runs the navigator and publishes the outcome (review, or failure with whatever was read). */
    private suspend fun runScan(context: Context, target: LocalDate, isToday: Boolean, navigator: OutlookNavigator, zone: ZoneId) {
        val failure: String? = try {
            withTimeout(SCAN_TIMEOUT_MS) {
                navigator.scan(target) { start -> isToday && !TriggerTime.eventStart(target, start, zone).isAfter(Instant.now()) }
            }
            null
        } catch (e: TimeoutCancellationException) {
            ScanLog.w("Scan timed out")
            "The scan took too long and was stopped."
        } catch (e: CancellationException) {
            ScanLog.i("Scan cancelled")
            "Scan stopped."
        } catch (e: ScanFailure) {
            ScanLog.e("Scan failed: ${e.message}")
            e.message ?: "The scan failed."
        } catch (e: Exception) {
            ScanLog.e("Scan crashed", e)
            "Something went wrong: ${e.message}"
        }
        // After Stop the coroutine is cancelled, and a withContext() that switches dispatcher then
        // throws on its way back instead of returning (prompt cancellation). So the outcome is both
        // built and published inside NonCancellable, never handed back out of it.
        withContext(NonCancellable) {
            _state.value = if (failure == null) {
                State.Done(buildResult(context, target, isToday, navigator, zone, incomplete = null))
            } else {
                failed(context, target, isToday, navigator, zone, failure)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** Back to the home screen. */
    fun clear() {
        if (job?.isActive != true) _state.value = State.Idle
    }

    private suspend fun failed(
        context: Context, target: LocalDate, isToday: Boolean, navigator: OutlookNavigator, zone: ZoneId, message: String,
    ): State {
        val partial = if (navigator.events.isEmpty()) null else buildResult(context, target, isToday, navigator, zone, message)
        return State.Failed(target, isToday, message, partial)
    }

    private suspend fun buildResult(
        context: Context, target: LocalDate, isToday: Boolean, navigator: OutlookNavigator, zone: ZoneId, incomplete: String?,
    ): ScanResult = withContext(NonCancellable + Dispatchers.IO) {
        ScanLog.i("Checking which events already have alarms")
        val existing = AlarmStore.get(context).existingFor(target)
        ScanLog.i("${existing.size} alarm(s) already set for $target")
        val events = navigator.events.toList()
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
            startedSkipped = navigator.startedSkipped,
            problems = navigator.problems.toList(),
            incomplete = incomplete,
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
