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

    /** [known] labels by label key; [targets] to read in Outlook (empty: no need to open it). */
    data class Plan(val known: Map<String, List<String>>, val targets: List<LabelTarget>) {
        val remembered: Int get() = known.size
    }

    suspend fun plan(events: List<CalEvent>, now: Instant): Plan {
        val keys = events.map { it.labelKey }.distinct()
        val cached = if (keys.isEmpty()) emptyMap() else keys.chunked(500).flatMap { dao.get(it) }.associateBy { it.labelKey }
        val known = HashMap<String, List<String>>()
        val misses = ArrayList<CalEvent>()
        for (event in events) {
            if (event.labelKey in known) continue
            val entry = cached[event.labelKey]?.let { CachedLabels(it.changeKey, ListCodec.decode(it.categories), Instant.ofEpochMilli(it.readAt)) }
            val reused = LabelCachePolicy.reuse(entry, event.changeKey, now)
            if (reused != null) known[event.labelKey] = reused else misses += event
        }
        return Plan(known, LabelTargets.choose(misses))
    }

    /**
     * Stores labels read in Outlook. An entry that had expired but kept the same change key is
     * compared first: if the labels differ, the change key can't be trusted to notice label
     * changes, so the whole cache is dropped (and the log says so). Returns true then: the labels
     * this run took from the cache ([Plan.known]) can't be trusted either.
     */
    suspend fun remember(reads: Map<LabelTarget, List<String>>, now: Instant): Boolean {
        val keyed = reads.filterKeys { it.changeKey != null }
        if (keyed.isEmpty()) return false
        val before = dao.get(keyed.keys.map { it.labelKey }).associateBy { it.labelKey }
        val stale = keyed.filter { (t, cats) ->
            val old = before[t.labelKey]
            old != null && old.changeKey == t.changeKey && ListCodec.decode(old.categories).toSet() != cats.toSet()
        }
        if (stale.isNotEmpty()) {
            ScanLog.w("Labels changed without a new change key (${stale.keys.joinToString { it.title }}); forgetting all remembered labels")
            dao.clear()
        }
        dao.put(keyed.map { (t, cats) -> LabelCacheEntity(t.labelKey, t.changeKey!!, ListCodec.encode(cats), t.title, now.toEpochMilli()) })
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
