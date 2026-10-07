package com.thomaswcode.calendareventtimers.calendar

import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * Outlook writes some events' time zones under names Android no longer knows (seen on the phone on
 * 2026-10-07: `US/Pacific-New`, removed from the tz database in 2020). For a repeating event the
 * provider then works out every occurrence in GMT, so in summer time each one comes out an hour
 * late (a monthly call Outlook shows at 16:05 BST came out at 17:05). Occurrences are corrected
 * here, when the name can be mapped to the zone it stands for.
 */
object TimeZones {
    /** Names gone from the tz database, and the zone each was an alias of. */
    private val REMOVED = mapOf(
        "US/Pacific-New" to "America/Los_Angeles",
        "Canada/East-Saskatchewan" to "America/Regina",
    )

    /** Whether Android knows [id]: an unknown name comes back from [TimeZone.getTimeZone] as GMT. */
    fun known(id: String): Boolean = id.isBlank() || id.equals("GMT", ignoreCase = true) || TimeZone.getTimeZone(id).id != "GMT"

    /** The zone [id] stands for, when it can be worked out. */
    fun intended(id: String): ZoneId? =
        REMOVED[id]?.let { ZoneId.of(it) } ?: runCatching { ZoneId.of(id, ZoneId.SHORT_IDS) }.getOrNull()

    /**
     * One occurrence ([begin]..[end], epoch ms) of a repeating event that starts at [dtstart] in zone
     * [id], as the provider gave it; corrected when Android doesn't know [id] ([knownToAndroid]): the
     * provider then repeats [dtstart]'s GMT time, and the occurrence is out by the change in the
     * zone's offset since [dtstart]. Null when there is nothing to correct, or no way to.
     */
    fun correct(begin: Long, end: Long, dtstart: Long, id: String, knownToAndroid: (String) -> Boolean = ::known): Pair<Long, Long>? {
        if (id.isBlank() || knownToAndroid(id)) return null
        val rules = intended(id)?.rules ?: return null
        val first = rules.getOffset(Instant.ofEpochMilli(dtstart)).totalSeconds
        var shift = first - rules.getOffset(Instant.ofEpochMilli(begin)).totalSeconds
        // Once more at the corrected time, in case the correction crosses a clock change.
        shift = first - rules.getOffset(Instant.ofEpochMilli(begin + shift * 1000L)).totalSeconds
        if (shift == 0) return null
        return (begin + shift * 1000L) to (end + shift * 1000L)
    }
}
