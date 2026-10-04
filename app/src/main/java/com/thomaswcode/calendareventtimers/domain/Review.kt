package com.thomaswcode.calendareventtimers.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Trigger-time maths. All arithmetic is on the instant timeline, so DST changes are handled. */
object TriggerTime {
    const val EARLY_MINUTES = 5

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    fun eventStart(date: LocalDate, start: LocalTime, zone: ZoneId): Instant =
        ZonedDateTime.of(date, start, zone).toInstant()

    /** Event start minus [offsetMinutes]; an event at 00:02 with a 5-minute offset rings the evening before. */
    fun trigger(date: LocalDate, start: LocalTime, offsetMinutes: Int, zone: ZoneId): Instant =
        eventStart(date, start, zone).minus(Duration.ofMinutes(offsetMinutes.toLong()))

    fun formatHhMm(time: LocalTime): String = hhmm.format(time)
}

/** What the scan read from one event's details screen. */
data class ScannedEvent(
    val title: String,
    val date: LocalDate,
    val start: LocalTime,
    val location: String?,
    val categories: List<String>,
) {
    /** Same date + start + title means the same event (an edited time or title is a new event). */
    val key: String get() = "$date|${TriggerTime.formatHhMm(start)}|$title"
}

/** An alarm the app already holds for an event. */
data class ExistingAlarm(val id: Long, val triggerAt: Instant, val offsetMinutes: Int)

/** One row of the review screen. */
data class ReviewItem(
    val event: ScannedEvent,
    val label: String,
    val alarmText: String,
    val existing: ExistingAlarm?,
    /** False when the 5-minutes-before time has already passed (today only). */
    val earlyAllowed: Boolean,
) {
    val key: String get() = event.key
}

object Review {
    /**
     * Rows for the review screen: labelled events that have not started yet, in time order, one
     * row per event.
     */
    fun build(
        events: List<ScannedEvent>,
        now: Instant,
        zone: ZoneId,
        existing: (ScannedEvent) -> ExistingAlarm?,
    ): List<ReviewItem> = events
        .mapNotNull { event ->
            val label = EventParser.matchLabel(event.categories) ?: return@mapNotNull null
            if (!TriggerTime.eventStart(event.date, event.start, zone).isAfter(now)) return@mapNotNull null
            val early = TriggerTime.trigger(event.date, event.start, TriggerTime.EARLY_MINUTES, zone)
            ReviewItem(
                event = event,
                label = label,
                alarmText = AlarmText.label(event.title, label, event.location),
                existing = existing(event),
                earlyAllowed = early.isAfter(now),
            )
        }
        .distinctBy { it.key }
        .sortedWith(compareBy({ it.event.date }, { it.event.start }, { it.event.title }))
}
