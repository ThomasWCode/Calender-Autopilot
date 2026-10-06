package com.thomaswcode.calendareventtimers.data

import android.annotation.SuppressLint
import android.content.Context
import com.thomaswcode.calendareventtimers.booking.Answers
import com.thomaswcode.calendareventtimers.booking.BookingCandidate
import com.thomaswcode.calendareventtimers.booking.BookingRules
import com.thomaswcode.calendareventtimers.booking.KnownBooking
import com.thomaswcode.calendareventtimers.booking.RoomChoice
import com.thomaswcode.calendareventtimers.booking.RoomCover
import com.thomaswcode.calendareventtimers.booking.SeriesAnswers
import com.thomaswcode.calendareventtimers.calendar.Attendee
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.calendar.CalendarStore
import com.thomaswcode.calendareventtimers.domain.TriggerTime
import com.thomaswcode.calendareventtimers.util.Prefs
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The app's bookings and what it remembers about answers (PLAN-ROOM-BOOKING.md §3.7, §3.15), and
 * the rooms' replies, read back from the phone's calendar (§3.11).
 */
class BookingStore private constructor(private val context: Context) {
    private val db = AutopilotDatabase.get(context)

    fun observeFrom(date: LocalDate): Flow<List<BookingEntity>> = db.bookings().observeSavedFrom(date.toString())

    suspend fun savedFrom(date: LocalDate): List<BookingEntity> = db.bookings().savedFrom(date.toString())

    suspend fun get(id: Long): BookingEntity? = db.bookings().get(id)

    /** The app's bookings for these occurrences, with the rooms' latest replies. */
    suspend fun known(occurrenceKeys: Collection<String>): Map<String, KnownBooking> =
        occurrenceKeys.toList().chunked(500).flatMap { db.bookings().savedForOccurrences(it) }
            .associate { it.occurrenceKey to KnownBooking(it.room, it.roomReply) }

    suspend fun answers(occurrenceKeys: Collection<String>): Map<String, AnswerKind> =
        occurrenceKeys.toList().chunked(500).flatMap { db.answers().get(it) }.associate { it.occurrenceKey to it.answer }

    suspend fun memory(seriesKeys: Collection<String>): Map<String, SeriesAnswers> =
        seriesKeys.toList().chunked(500).flatMap { db.seriesMemory().get(it) }
            .associate { it.seriesKey to SeriesAnswers(it.bookRoom, it.notify, ListCodec.decode(it.removed).toSet()) }

    /** The answers given for these events, for next time (series) and for re-runs (occurrences). */
    suspend fun rememberAnswers(answered: List<Pair<BookingCandidate, Answers>>, now: Instant) = withContext(NonCancellable) {
        db.seriesMemory().put(
            answered.map { (c, a) ->
                SeriesMemoryEntity(c.event.seriesKey, a.bookRoom, a.notify, ListCodec.encode(a.removed), now.toEpochMilli())
            },
        )
        db.answers().put(
            answered.filter { !it.second.bookRoom }.map { (c, _) ->
                AnswerEntity(c.key, c.event.seriesKey, c.event.date.toString(), AnswerKind.NO_ROOM_WANTED, now.toEpochMilli())
            },
        )
    }

    suspend fun recordAnswer(candidate: BookingCandidate, answer: AnswerKind, now: Instant) = withContext(NonCancellable) {
        db.answers().put(listOf(AnswerEntity(candidate.key, candidate.event.seriesKey, candidate.event.date.toString(), answer, now.toEpochMilli())))
    }

    /** A booking saved in Outlook: written straight away, so a re-run never books it twice. */
    suspend fun recordBooking(candidate: BookingCandidate, room: String, notified: List<String>, now: Instant): Long = withContext(NonCancellable) {
        val e = candidate.event
        val id = db.bookings().insert(
            BookingEntity(
                occurrenceKey = e.occurrenceKey,
                seriesKey = e.seriesKey,
                originalEventId = e.eventId,
                originalTitle = e.title.trim(),
                eventDate = e.date.toString(),
                start = TriggerTime.formatHhMm(e.start),
                end = TriggerTime.formatHhMm(e.endTime),
                bookingTitle = BookingRules.bookingTitle(e.title),
                room = room,
                notified = ListCodec.encode(notified),
                state = BookingState.SAVED,
                roomReply = RoomReply.WAITING,
                bookingSyncId = null,
                createdAt = now.toEpochMilli(),
                checkedAt = null,
            ),
        )
        recordAnswer(candidate, AnswerKind.BOOKED, now)
        ScanLog.i("Booking $id recorded: ${e.title.trim()} ${e.date} ${e.start} in $room")
        id
    }

    suspend fun setRoom(id: Long, room: String, now: Instant = Instant.now()) = db.bookings().setRoom(id, room, now.toEpochMilli())

    suspend fun setNotified(id: Long, notified: List<String>) = db.bookings().setNotified(id, ListCodec.encode(notified))

    suspend fun markDeleted(id: Long) = db.bookings().markDeleted(id)

    /** Names for addresses seen among invitees. */
    suspend fun rememberPeople(attendees: Collection<Attendee>, now: Instant) {
        val people = attendees.mapNotNull { a ->
            val email = a.email?.trim()?.lowercase() ?: return@mapNotNull null
            val name = a.name?.trim()?.takeUnless { it.isEmpty() || it.equals(email, ignoreCase = true) } ?: return@mapNotNull null
            PersonEntity(email, name, now.toEpochMilli())
        }.distinctBy { it.email }
        if (people.isNotEmpty()) db.people().put(people)
    }

    suspend fun names(emails: Collection<String>): Map<String, String> =
        emails.map { it.lowercase() }.distinct().chunked(500).flatMap { db.people().get(it) }
            .mapNotNull { p -> p.name?.let { p.email to it } }.toMap()

    /**
     * Reads every upcoming booking's room reply from the phone's calendar (§3.11). A while after it
     * was saved (time for Outlook to sync it), a booking that can't be found is NOT_FOUND (deleted in
     * Outlook, or never saved), and one found without a room is NO_ROOM (taken off in Outlook).
     * Neither holds a room, so its event is offered again.
     */
    suspend fun refreshReplies(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Int = withContext(NonCancellable + Dispatchers.IO) {
        val calendar = CalendarStore(context)
        if (!calendar.hasAccess()) return@withContext 0
        val main = calendar.mainCalendar() ?: return@withContext 0
        val today = LocalDate.now(zone)
        val bookings = db.bookings().savedFrom(today.toString())
        if (bookings.isEmpty()) return@withContext 0
        val dates = bookings.map { LocalDate.parse(it.eventDate) }
        val events = calendar.occurrencesOn(main, dates.min(), dates.max(), zone).filter { BookingRules.isRoomBooking(it.title) }
        val attendees = calendar.attendees(events.map { it.eventId })
        Prefs.ensureLoaded(context)
        val rooms = Prefs.rooms.value
        val paired = pair(bookings, events) { e -> RoomCover.roomReply(attendees[e.eventId].orEmpty(), rooms)?.first }
        var changed = 0
        for (b in bookings) {
            val match = paired[b.id]
            val reply = replyFor(b, found = match != null, room = match?.let { RoomCover.roomReply(attendees[it.eventId].orEmpty(), rooms)?.second }, now)
            if (reply != b.roomReply || (match?.syncId != null && match.syncId != b.bookingSyncId)) {
                db.bookings().setReply(b.id, reply, match?.syncId, now.toEpochMilli())
                ScanLog.i("Booking ${b.id} (${b.originalTitle}, ${b.eventDate}): ${b.roomReply} → $reply")
                changed++
            }
        }
        changed
    }

    /** Forgets every remembered answer (Settings). Bookings themselves are kept. */
    suspend fun forgetAnswers() = withContext(NonCancellable) {
        db.answers().clear()
        db.seriesMemory().clear()
    }

    /** How much the app remembers, for Settings → Memory. */
    data class MemoryCounts(val meetings: Int, val events: Int, val labels: Int, val people: Int) {
        val isEmpty: Boolean get() = meetings == 0 && events == 0 && labels == 0 && people == 0
    }

    suspend fun memoryCounts(): MemoryCounts = withContext(Dispatchers.IO) {
        MemoryCounts(db.seriesMemory().count(), db.answers().count(), db.labels().count(), db.people().count())
    }

    /**
     * Clear memory (Settings): forgets everything kept to save presses and Outlook time: answers
     * for each meeting and each event, the labels read in Outlook, and people's names. Bookings,
     * alarms and settings stay. The next runs ask about every event again and read each label in
     * Outlook again.
     */
    suspend fun clearMemory() = withContext(NonCancellable + Dispatchers.IO) {
        db.answers().clear()
        db.seriesMemory().clear()
        db.labels().clear()
        db.people().clear()
        ScanLog.i("Memory cleared: answers, labels and names forgotten")
    }

    /** Drops records of days long gone. */
    suspend fun prune(today: LocalDate) = withContext(NonCancellable) {
        val before = today.minusWeeks(8).toString()
        db.bookings().prune(before)
        db.answers().prune(before)
    }

    companion object {
        /** Long enough for Outlook to have synced a new or changed booking into the phone's calendar. */
        private val SYNC_GRACE: Duration = Duration.ofMinutes(15)

        /**
         * [b]'s reply, from its event in the calendar: [found] or not, and its room's reply ([room],
         * null when the event has no room). Until Outlook has had time to sync the booking, a missing
         * event is still WAITING, and a missing room keeps the reply it had (it may be mid-change).
         */
        fun replyFor(b: BookingEntity, found: Boolean, room: RoomReply?, now: Instant): RoomReply {
            if (found && room != null) return room
            fun settled(since: Long) = Duration.between(Instant.ofEpochMilli(since), now) > SYNC_GRACE
            return when {
                !found -> if (settled(b.createdAt)) RoomReply.NOT_FOUND else RoomReply.WAITING
                settled(maxOf(b.createdAt, b.checkedAt ?: 0L)) -> RoomReply.NO_ROOM
                else -> b.roomReply
            }
        }

        fun matches(event: CalEvent, booking: BookingEntity): Boolean =
            CalEvent.normaliseTitle(event.title) == CalEvent.normaliseTitle(booking.bookingTitle) &&
                event.date.toString() == booking.eventDate && TriggerTime.formatHhMm(event.start) == booking.start

        /**
         * Each booking's event in the calendar, never one event for two bookings: by its own sync id
         * once known; otherwise by title, date and start, preferring the event whose room ([roomOf]) is
         * the booking's. (A re-booked event leaves the declined booking at the same time and title.)
         */
        fun pair(bookings: List<BookingEntity>, events: List<CalEvent>, roomOf: (CalEvent) -> String?): Map<Long, CalEvent> {
            val used = HashSet<Long>()
            val out = HashMap<Long, CalEvent>()
            for (b in bookings) {
                val id = b.bookingSyncId ?: continue
                val e = events.firstOrNull { it.syncId == id && it.eventId !in used } ?: continue
                out[b.id] = e
                used += e.eventId
            }
            // Room matches first, for every booking; only then whatever is left, newest booking first.
            for (byRoom in listOf(true, false)) {
                for (b in bookings.filter { it.id !in out }.sortedByDescending { it.createdAt }) {
                    val e = events.firstOrNull { it.eventId !in used && matches(it, b) && (!byRoom || RoomChoice.sameRoom(roomOf(it), b.room)) } ?: continue
                    out[b.id] = e
                    used += e.eventId
                }
            }
            return out
        }

        // Holds the application context only (see get()), which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: BookingStore? = null

        fun get(context: Context): BookingStore = instance ?: synchronized(this) {
            instance ?: BookingStore(context.applicationContext).also { instance = it }
        }
    }
}
