package com.thomaswcode.calendareventtimers.booking

/** The rooms to try, in order (Settings), and the operations Settings offers on them. */
object RoomList {
    /** As given on 2026-10-05; all are in Room Finder's KS-Rooms list. */
    val DEFAULT = listOf(
        "KS-103D", "KS-117", "KS-119a", "KS-121", "KS-123", "KS-129", "KS-136", "KS-182", "KS-183", "KS-184",
        "KS-185", "KS-207A", "KS-233", "KS-248b", "KS-259", "KS-260", "KS-265", "KS-481", "KS-482", "KS-483",
        "KS-G19", "KS-G20", "KS-G21", "KS-G22", "KS-G31",
    )

    const val DEFAULT_BUILDING = "KS-Rooms"

    fun clean(name: String): String = name.trim().replace(Regex("""\s+"""), " ")

    /** The list with [name] added at the end, or why it can't be. */
    fun add(rooms: List<String>, name: String): Result<List<String>> {
        val room = clean(name)
        return when {
            room.isEmpty() -> Result.failure(IllegalArgumentException("Type a room name, as Room Finder shows it."))
            rooms.any { it.equals(room, ignoreCase = true) } -> Result.failure(IllegalArgumentException("$room is already in the list."))
            else -> Result.success(rooms + room)
        }
    }

    fun remove(rooms: List<String>, index: Int): List<String> = rooms.filterIndexed { i, _ -> i != index }

    /** [index] moved [by] places (negative: up), kept within the list. */
    fun move(rooms: List<String>, index: Int, by: Int): List<String> {
        if (index !in rooms.indices) return rooms
        val to = (index + by).coerceIn(0, rooms.lastIndex)
        if (to == index) return rooms
        return rooms.toMutableList().apply { add(to, removeAt(index)) }
    }
}

/** A room's state in Room Finder for the form's time. */
enum class RoomStatus { FREE, BUSY, UNKNOWN }

/** One row of Room Finder's list or Add Location's Recent list. */
data class RoomRow(val name: String, val status: RoomStatus)

sealed interface RoomDecision {
    /** Book [row] (Room Finder's name) for the setting [room]. */
    data class Chosen(val row: String, val room: String) : RoomDecision

    /** A room before any free one hasn't been seen yet: scroll on. */
    data object NeedMore : RoomDecision

    /** Nothing free; [missing] are rooms Room Finder never listed. */
    data class NoneFree(val missing: List<String>) : RoomDecision
}

/** Choosing the room (PLAN-ROOM-BOOKING.md §3.10): the first free one in the Settings order. */
object RoomChoice {
    /**
     * Room Finder's [row] name is the setting [room] when equal ignoring case, or the setting
     * followed by a note in brackets (`KS-106 (EPH staff only)`). So `KS-G32` never matches `KS-G32A`.
     */
    fun matches(row: String, room: String): Boolean {
        val r = row.trim()
        val s = RoomList.clean(room)
        return r.equals(s, ignoreCase = true) || r.startsWith("$s (", ignoreCase = true)
    }

    /** "Free" / "Busy" as Outlook shows them; anything else isn't known (e.g. still loading). */
    fun status(detail: String?): RoomStatus {
        val d = detail?.trim()?.lowercase() ?: return RoomStatus.UNKNOWN
        return when {
            d.startsWith("free") -> RoomStatus.FREE
            d.startsWith("busy") -> RoomStatus.BUSY
            else -> RoomStatus.UNKNOWN
        }
    }

    /**
     * Walks [rooms] in order over the rows [seen] so far. A room whose status isn't known counts as
     * busy: a room is only ever booked when Outlook said it was free.
     */
    fun decide(rooms: List<String>, seen: Collection<RoomRow>, endReached: Boolean): RoomDecision {
        val missing = ArrayList<String>()
        for (room in rooms) {
            val row = seen.firstOrNull { matches(it.name, room) }
            if (row == null) {
                if (!endReached) return RoomDecision.NeedMore
                missing += room
                continue
            }
            if (row.status == RoomStatus.FREE) return RoomDecision.Chosen(row.name, room)
        }
        return RoomDecision.NoneFree(missing)
    }

    /**
     * Add Location's Recent list as a shortcut: only for the **first** room in the list, shown as
     * free there. Recent leaves busy rooms out, so it can't show that the rooms before a later one
     * are busy (PLAN-ROOM-BOOKING.md §3.7).
     */
    fun recentShortcut(rooms: List<String>, recent: Collection<RoomRow>): RoomRow? {
        val first = rooms.firstOrNull() ?: return null
        return recent.firstOrNull { matches(it.name, first) && it.status == RoomStatus.FREE }
    }

    /** A room named in free text (a location, an attendee's name or address), from [rooms]. */
    fun roomIn(text: String?, rooms: List<String>): String? {
        if (text.isNullOrBlank()) return null
        val parts = text.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
        for (room in rooms) {
            val key = key(room)
            for (part in parts) {
                // An address's local part: ks-121@lshtm.ac.uk.
                val local = part.substringBefore('@')
                if (key(part) == key || key(local) == key || matches(part, room) || part.startsWith("$room ", ignoreCase = true)) return room
            }
        }
        return null
    }

    /** Case and punctuation ignored: KS-103D, ks-103d and KS103D are the same room. */
    private fun key(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
}
