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
     * Stored for one occurrence the phone's calendar has but Outlook doesn't show
     * ([com.thomaswcode.calendareventtimers.outlook.LabelRead.Absent]), under [absentKey]: never for
     * the whole series, whose other occurrences may be fine. Trusted for [ABSENT_MAX_AGE] only, in
     * case Outlook just hadn't shown it yet.
     */
    const val ABSENT = "<not in Outlook>"
    val ABSENT_MAX_AGE: Duration = Duration.ofDays(7)

    fun isAbsent(categories: List<String>?): Boolean = categories == listOf(ABSENT)

    /** The cache key of an occurrence found absent (label keys never start like this). */
    fun absentKey(occurrenceKey: String): String = "absent:$occurrenceKey"

    fun reuse(cached: CachedLabels?, changeKey: String?, now: Instant): List<String>? {
        if (cached == null || changeKey == null || cached.changeKey != changeKey) return null
        if (cached.readAt.isBefore(now.minus(if (isAbsent(cached.categories)) ABSENT_MAX_AGE else MAX_AGE))) return null
        // Read "in the future": the clock was wrong then or now; don't trust it.
        if (cached.readAt.isAfter(now.plus(Duration.ofDays(1)))) return null
        return cached.categories
    }
}

/** One event to open in Outlook to read its labels: an occurrence ([occurrenceKey]) of [labelKey]. */
data class LabelTarget(
    val labelKey: String,
    val changeKey: String?,
    val date: LocalDate,
    val start: LocalTime,
    val title: String,
    val occurrenceKey: String = labelKey,
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
            .map { LabelTarget(it.labelKey, it.changeKey, it.date, it.start, it.title, it.occurrenceKey) }
    }

    /**
     * After a round of reads: a series whose chosen occurrence turned out [absent] still needs its
     * labels, so another of its occurrences among [events] is read instead, never one already
     * [tried] or known to be absent ([knownAbsent], occurrence keys). Series read some other way
     * (labels, or a problem reported) aren't tried again.
     */
    fun retry(events: List<CalEvent>, knownAbsent: Set<String>, tried: Collection<LabelTarget>, absent: Collection<LabelTarget>): List<LabelTarget> {
        val triedKeys = tried.map { it.occurrenceKey }.toSet()
        val settled = (tried - absent.toSet()).map { it.labelKey }.toSet()
        val series = absent.map { it.labelKey }.toSet() - settled
        return choose(events.filter { it.labelKey in series && it.occurrenceKey !in triedKeys && it.occurrenceKey !in knownAbsent })
    }
}
