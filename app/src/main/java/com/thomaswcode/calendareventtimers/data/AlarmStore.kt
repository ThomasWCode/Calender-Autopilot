package com.thomaswcode.calendareventtimers.data

import android.content.Context
import com.thomaswcode.calendareventtimers.alarm.AlarmScheduler
import com.thomaswcode.calendareventtimers.domain.ScannedEvent
import com.thomaswcode.calendareventtimers.domain.TriggerTime
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The alarms the app has set, kept in step with AlarmManager. The database is the source of truth:
 * AlarmManager forgets everything on reboot and force-stop, so [resync] re-registers from here.
 *
 * Every change that touches both the database and AlarmManager runs to the end even if the caller
 * is cancelled (say the activity is recreated mid-way), so the two never disagree.
 */
class AlarmStore private constructor(context: Context) {
    private val dao = AlarmDatabase.get(context).alarms()
    private val scheduler = AlarmScheduler(context.applicationContext)

    sealed interface AddOutcome {
        data class Added(val alarm: AlarmEntity) : AddOutcome
        data class AlreadySet(val alarm: AlarmEntity) : AddOutcome
        data object TooLate : AddOutcome
        data class Failed(val reason: String) : AddOutcome
    }

    /** Alarms still to ring, soonest first. Fired, dismissed and cancelled ones drop off. */
    val upcoming: Flow<List<AlarmEntity>> = dao.observeActive()

    suspend fun get(id: Long): AlarmEntity? = dao.get(id)

    /** Alarms already set for [date] (cancelled ones excluded, so a cancelled event can be set again). */
    suspend fun existingFor(date: LocalDate): List<AlarmEntity> = dao.notCancelledOn(date.toString())

    suspend fun add(event: ScannedEvent, label: String, offsetMin: Int, zone: ZoneId, now: Instant): AddOutcome =
        withContext(NonCancellable) {
            val start = TriggerTime.formatHhMm(event.start)
            existingFor(event.date).firstOrNull { it.eventStart == start && it.title == event.title }
                ?.let { return@withContext AddOutcome.AlreadySet(it) }
            val trigger = TriggerTime.trigger(event.date, event.start, offsetMin, zone)
            if (!trigger.isAfter(now)) return@withContext AddOutcome.TooLate
            val draft = AlarmEntity(
                eventDate = event.date.toString(),
                eventStart = start,
                title = event.title,
                label = label,
                location = event.location,
                offsetMin = offsetMin,
                triggerAt = trigger.toEpochMilli(),
                state = AlarmState.SCHEDULED,
                createdAt = now.toEpochMilli(),
            )
            val alarm = draft.copy(id = dao.insert(draft))
            try {
                scheduler.schedule(alarm)
                ScanLog.i("Alarm ${alarm.id} set for ${trigger.atZone(zone).toLocalDateTime()}: ${alarm.title} (${alarm.label})")
                AddOutcome.Added(alarm)
            } catch (e: SecurityException) {
                dao.cancel(alarm.id)
                ScanLog.e("Android refused the exact alarm for ${alarm.title}", e)
                AddOutcome.Failed("Android refused the exact alarm")
            }
        }

    /** A labelled stand-in alarm [minutes] from now, for checking ringing on the lock screen, in DND, … */
    suspend fun addTest(minutes: Long, now: Instant, zone: ZoneId): AlarmEntity = withContext(NonCancellable) {
        val at = now.plus(Duration.ofMinutes(minutes)).truncatedTo(ChronoUnit.SECONDS)
        val local = at.atZone(zone)
        val draft = AlarmEntity(
            eventDate = local.toLocalDate().toString(),
            eventStart = TriggerTime.formatHhMm(local.toLocalTime()),
            title = "Test alarm",
            label = "Test",
            location = null,
            offsetMin = 0,
            triggerAt = at.toEpochMilli(),
            state = AlarmState.SCHEDULED,
            createdAt = now.toEpochMilli(),
        )
        val alarm = draft.copy(id = dao.insert(draft))
        scheduler.schedule(alarm)
        ScanLog.i("Test alarm ${alarm.id} set for ${local.toLocalTime().truncatedTo(ChronoUnit.SECONDS)}")
        alarm
    }

    /** Cancels an alarm (also one ringing now); returns it as it was, for Undo, or null if it had already finished. */
    suspend fun cancel(id: Long): AlarmEntity? = withContext(NonCancellable) {
        val before = dao.get(id) ?: return@withContext null
        scheduler.cancel(id)
        if (dao.cancel(id) == 0) return@withContext null
        ScanLog.i("Alarm $id cancelled: ${before.title}")
        before
    }

    /** Undo for [cancel]; false if its time has passed meanwhile. */
    suspend fun restore(alarm: AlarmEntity, now: Instant): Boolean = withContext(NonCancellable) {
        if (alarm.triggerAt <= now.toEpochMilli()) return@withContext false
        val state = if (alarm.state == AlarmState.SNOOZED) AlarmState.SNOOZED else AlarmState.SCHEDULED
        if (dao.uncancel(alarm.id, state.name) == 0) return@withContext false
        scheduler.schedule(alarm.copy(state = state))
        true
    }

    /** Marks a due alarm as ringing; null if it was cancelled or already dealt with (a stale or repeated fire). */
    suspend fun claimForRinging(id: Long): AlarmEntity? =
        if (dao.claimForRinging(id) == 1) dao.get(id) else null

    /** A ringing alarm was dismissed or went unanswered ([state] DISMISSED or MISSED); false if it wasn't ringing. */
    suspend fun finishRinging(id: Long, state: AlarmState): Boolean = dao.finishRinging(id, state.name) == 1

    /** A ringing alarm rings again [SNOOZE_MINUTES] from [now]; null if it wasn't ringing (e.g. cancelled meanwhile). */
    suspend fun snoozeRinging(id: Long, now: Instant): AlarmEntity? = withContext(NonCancellable) {
        val at = now.plus(Duration.ofMinutes(SNOOZE_MINUTES)).toEpochMilli()
        if (dao.snoozeRinging(id, at) == 0) return@withContext null
        dao.get(id)?.also { scheduler.schedule(it) }
    }

    /**
     * Re-registers every alarm still to ring (after reboot, app update, clock or time-zone change,
     * and on each launch, since a force-stop also clears AlarmManager). An alarm that came due while
     * nothing could ring it rings now if it is less than [RING_GRACE] late; older ones are marked
     * missed and returned so the caller can say so. [busy] are alarms the ring service is handling.
     */
    suspend fun resync(now: Instant, busy: Set<Long>): List<AlarmEntity> = withContext(NonCancellable) {
        val missed = mutableListOf<AlarmEntity>()
        var registered = 0
        for (alarm in dao.active()) {
            if (alarm.id in busy) continue
            val late = Duration.between(Instant.ofEpochMilli(alarm.triggerAt), now)
            if (late < RING_GRACE) {
                // A RINGING row nobody is ringing means the process died mid-ring: ring it again.
                if (alarm.state == AlarmState.RINGING && dao.resetRinging(alarm.id) == 0) continue
                try {
                    scheduler.schedule(alarm)
                    registered++
                } catch (e: SecurityException) {
                    ScanLog.e("Couldn't re-register alarm ${alarm.id}", e)
                }
            } else if (dao.markMissed(alarm.id) == 1) {
                missed += alarm.copy(state = AlarmState.MISSED)
            }
        }
        val pruned = dao.deleteFinishedBefore(now.minus(KEEP_FINISHED).toEpochMilli())
        ScanLog.i("Resync: $registered alarm(s) registered, ${missed.size} missed, $pruned old record(s) pruned")
        missed
    }

    companion object {
        const val SNOOZE_MINUTES = 5L

        /** How long an alarm rings unanswered (auto-silence), and how late a re-registered one may still ring. */
        val RING_GRACE: Duration = Duration.ofMinutes(10)

        /** Fired, missed and cancelled records are kept this long, then pruned. */
        private val KEEP_FINISHED: Duration = Duration.ofDays(7)

        @Volatile
        private var instance: AlarmStore? = null

        fun get(context: Context): AlarmStore = instance ?: synchronized(this) {
            instance ?: AlarmStore(context.applicationContext).also { instance = it }
        }
    }
}
