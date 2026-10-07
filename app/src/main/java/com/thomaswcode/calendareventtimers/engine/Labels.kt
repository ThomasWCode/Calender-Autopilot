package com.thomaswcode.calendareventtimers.engine

import com.thomaswcode.calendareventtimers.calendar.CalEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Labels as read in Outlook for one version of an event. */
data class CachedLabels(val changeKey: String, val categories: List<String>, val readAt: Instant)

/**
 * When a label read earlier can stand in for opening the event again (PLAN-ROOM-BOOKING.md §3.3):
 * same change key, read within [MAX_AGE]. The change key is an undocumented provider column, so
 * entries also expire, and without a key nothing is reused.
 */
object LabelCachePolicy {
    val MAX_AGE: Duration = Duration.ofDays(30)

    /**
     * Stored in place of labels for an event the phone's calendar has but Outlook doesn't show
     * ([com.thomaswcode.calendareventtimers.outlook.LabelRead.Absent]). It matches no label, so the
     * event is simply not offered; it is trusted for [ABSENT_MAX_AGE] only, in case Outlook just
     * hadn't shown it yet.
     */
    const val ABSENT = "<not in Outlook>"
    val ABSENT_MAX_AGE: Duration = Duration.ofDays(7)

    fun isAbsent(categories: List<String>?): Boolean = categories == listOf(ABSENT)

    fun reuse(cached: CachedLabels?, changeKey: String?, now: Instant): List<String>? {
        if (cached == null || changeKey == null || cached.changeKey != changeKey) return null
        if (cached.readAt.isBefore(now.minus(if (isAbsent(cached.categories)) ABSENT_MAX_AGE else MAX_AGE))) return null
        // Read "in the future": the clock was wrong then or now; don't trust it.
        if (cached.readAt.isAfter(now.plus(Duration.ofDays(1)))) return null
        return cached.categories
    }
}

/** One event to open in Outlook to read its labels: an occurrence of [labelKey]. */
data class LabelTarget(
    val labelKey: String,
    val changeKey: String?,
    val date: LocalDate,
    val start: LocalTime,
    val title: String,
)

object LabelTargets {
    /**
     * One occurrence per label key (a series shares its labels), chosen so that as few days as
     * possible are visited: events with a single possible day fix their days first; the others
     * take their earliest occurrence on a day already being visited, else their earliest.
     */
    fun choose(misses: List<CalEvent>): List<LabelTarget> {
        val groups = misses.groupBy { it.labelKey }.values.map { occ -> occ.sortedBy { it.begin } }
        val days = HashSet<LocalDate>()
        val chosen = ArrayList<CalEvent>()
        for (occ in groups.sortedWith(compareBy({ g -> g.map { it.date }.distinct().size }, { it.first().begin }))) {
            val pick = occ.firstOrNull { it.date in days } ?: occ.first()
            days += pick.date
            chosen += pick
        }
        return chosen.sortedWith(compareBy({ it.begin }, { it.title }))
            .map { LabelTarget(it.labelKey, it.changeKey, it.date, it.start, it.title) }
    }
}
