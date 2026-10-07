package com.thomaswcode.calendareventtimers.engine

import android.content.Context
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.data.AutopilotDatabase
import com.thomaswcode.calendareventtimers.data.LabelCacheEntity
import com.thomaswcode.calendareventtimers.data.ListCodec
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.time.Instant

/**
 * The label half of the shared engine: which events' labels are already known, which must be
 * read in Outlook, and remembering what was read (PLAN-ROOM-BOOKING.md §3.3).
 */
class LabelPass(context: Context) {
    private val dao = AutopilotDatabase.get(context).labels()

    /**
     * [known] labels by label key; [targets] to read in Outlook (empty: no need to open it);
     * [absent]: occurrences (keys) found missing from Outlook lately, to leave out.
     */
    data class Plan(val known: Map<String, List<String>>, val targets: List<LabelTarget>, val absent: Set<String> = emptySet()) {
        val remembered: Int get() = known.size
    }

    suspend fun plan(events: List<CalEvent>, now: Instant): Plan {
        val keys = events.flatMap { listOf(it.labelKey, LabelCachePolicy.absentKey(it.occurrenceKey)) }.distinct()
        val cached = if (keys.isEmpty()) emptyMap() else keys.chunked(500).flatMap { dao.get(it) }.associateBy { it.labelKey }
        fun entry(key: String) = cached[key]?.let { CachedLabels(it.changeKey, ListCodec.decode(it.categories), Instant.ofEpochMilli(it.readAt)) }
        val known = HashMap<String, List<String>>()
        val absent = HashSet<String>()
        val misses = ArrayList<CalEvent>()
        for (event in events) {
            if (LabelCachePolicy.reuse(entry(LabelCachePolicy.absentKey(event.occurrenceKey)), event.changeKey, now) != null) {
                absent += event.occurrenceKey
                continue
            }
            if (event.labelKey in known) continue
            // A series marked absent as a whole (before 2026-10-08) is read again.
            val reused = LabelCachePolicy.reuse(entry(event.labelKey), event.changeKey, now)?.takeUnless { LabelCachePolicy.isAbsent(it) }
            if (reused != null) known[event.labelKey] = reused else misses += event
        }
        return Plan(known, LabelTargets.choose(misses), absent)
    }

    /**
     * Stores labels read in Outlook, and [absent] occurrences (not in Outlook) under their own keys
     * ([LabelCachePolicy.absentKey]). An entry that had expired but kept the same change key is
     * compared first: if the labels differ, the change key can't be trusted to notice label
     * changes, so the whole cache is dropped (and the log says so). Returns true then: the labels
     * this run took from the cache ([Plan.known]) can't be trusted either.
     */
    suspend fun remember(reads: Map<LabelTarget, List<String>>, now: Instant, absent: Collection<LabelTarget> = emptyList()): Boolean {
        val keyed = reads.filterKeys { it.changeKey != null }
        val gone = absent.filter { it.changeKey != null }
        if (keyed.isEmpty() && gone.isEmpty()) return false
        val before = if (keyed.isEmpty()) emptyMap() else dao.get(keyed.keys.map { it.labelKey }).associateBy { it.labelKey }
        val stale = keyed.filter { (t, cats) ->
            val old = before[t.labelKey]
            val oldCats = old?.let { ListCodec.decode(it.categories) }
            old != null && old.changeKey == t.changeKey && !LabelCachePolicy.isAbsent(oldCats) && oldCats.orEmpty().toSet() != cats.toSet()
        }
        if (stale.isNotEmpty()) {
            ScanLog.w("Labels changed without a new change key (${stale.keys.joinToString { it.title }}); forgetting all remembered labels")
            dao.clear()
        }
        dao.put(
            keyed.map { (t, cats) -> LabelCacheEntity(t.labelKey, t.changeKey!!, ListCodec.encode(cats), t.title, now.toEpochMilli()) } +
                gone.map { t ->
                    LabelCacheEntity(LabelCachePolicy.absentKey(t.occurrenceKey), t.changeKey!!, ListCodec.encode(listOf(LabelCachePolicy.ABSENT)), t.title, now.toEpochMilli())
                },
        )
        dao.prune(now.minus(LabelCachePolicy.MAX_AGE).toEpochMilli())
        return stale.isNotEmpty()
    }

    suspend fun forgetAll() = dao.clear()

    companion object {
        /** Said when the cache was dropped mid-run and the labels it gave this run were left out. */
        fun distrusted(count: Int): String =
            "Outlook changed a label without the phone's calendar noticing, so remembered labels can't be trusted: " +
                "$count event(s) whose labels were remembered are left out this time. Run again to read them in Outlook."
    }
}
