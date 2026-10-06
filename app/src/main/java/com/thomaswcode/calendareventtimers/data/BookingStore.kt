package com.thomaswcode.calendareventtimers.data

import android.annotation.SuppressLint
import android.content.Context
import com.thomaswcode.calendareventtimers.booking.Answers
import com.thomaswcode.calendareventtimers.booking.BookingCandidate
import com.thomaswcode.calendareventtimers.booking.BookingController
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
import java.time.LocalTime
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

    /** A booking's reply as the calendar has it; [room]: the room Outlook has now, when it was changed there. */
    data class ReplyCheck(val reply: RoomReply, val room: String? = null)

    fun observeFrom(date: LocalDate): Flow<List<BookingEntity>> = db.bookings().observeSavedFrom(date.toString())

    suspend fun savedFrom(date: LocalDate): List<BookingEntity> = db.bookings().savedFrom(date.toString())

    suspend fun get(id: Long): BookingEntity? = db.bookings().get(id)

    /** The app's bookings for these occurrences, with the rooms' latest replies and the booked times. */
    suspend fun known(occurrenceKeys: Collection<String>): Map<String, KnownBooking> =
        occurrenceKeys.toList().chunked(500).flatMap { db.bookings().savedForOccurrences(it) }
            .associate { it.occurrenceKey to KnownBooking(it.room, it.roomReply, time(it.start), time(it.end)) }

    private fun time(hhmm: String): LocalTime? = runCatching { LocalTime.parse(hhmm) }.getOrNull()

    suspend fun answers(occurrenceKeys: Collection<String>): Map<String, AnswerKind> =
        occurrenceKeys.toList().chunked(500).flatMap { db.answers().get(it) }.associate { it.occurrenceKey to it.answer }

    suspend fun memory(seriesKeys: Collection<String>): Map<String, SeriesAnswers> =
        seriesKeys.toList().chunked(500).flatMap { db.seriesMemory().get(it) }
            .associate { it.seriesKey to SeriesAnswers(it.bookRoom, it.notify, ListCodec.decode(it.removed).toSet()) }

    /**
     * The answers given for these events, for next time (series) and for re-runs (occurrences). An
     * event now answered yes loses what it got before (a "no room" in particular), so a run that
     * stops before booking it doesn't leave the old answer standing; its booking records the new one.
     */
    suspend fun rememberAnswers(answered: List<Pair<BookingCandidate, Answers>>, now: Instant) = withContext(NonCancellable) {
        db.seriesMemory().put(
            answered.map { (c, a) ->
                SeriesMemoryEntity(c.event.seriesKey, a.bookRoom, a.notify, ListCodec.encode(a.removed), now.toEpochMilli())
            },
        )
        answered.filter { it.second.bookRoom }.map { it.first.key }.chunked(500).forEach { db.answers().remove(it) }
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
     * Reads every upcoming booking's room reply from the phone's calendar (§3.11), for the booking's
     * own room. A while after it was saved or changed (time for Outlook to sync), a booking that
     * can't be found at its time is NOT_FOUND (deleted or moved in Outlook, or never saved), one
     * found without a room is NO_ROOM, and one with another room set in Outlook follows that room.
     * NOT_FOUND and NO_ROOM hold no room, so the event is offered again.
     */
    suspend fun refreshReplies(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Int = withContext(NonCancellable + Dispatchers.IO) {
        val calendar = CalendarStore(context)
        if (!calendar.hasAccess()) return@withContext 0
        val main = calendar.mainCalendar() ?: return@withContext 0
        val today = LocalDate.now(zone)
        val bookings = db.bookings().savedFrom(today.toString())
        if (bookings.isEmpty()) return@withContext 0
        val dates = bookings.map { LocalDate.parse(it.eventDate) }
        Prefs.ensureLoaded(context)
        val rooms = Prefs.rooms.value
        val events = ownBookingEvents(calendar.occurrencesOn(main, dates.min(), dates.max(), zone), BookingController.myAddresses(main))
        val attendees = calendar.attendees(events.map { it.eventId })
        val paired = pair(bookings, events) { e -> RoomCover.roomReply(attendees[e.eventId].orEmpty(), rooms)?.first }
        var changed = 0
        for (b in bookings) {
            val match = paired[b.id]
            // Made shorter or longer in Outlook: the booking covers what its event covers now.
            match?.let { TriggerTime.formatHhMm(it.endTime) }?.takeIf { it != b.end }?.let { end ->
                db.bookings().setEnd(b.id, end)
                ScanLog.i("Booking ${b.id} (${b.originalTitle}, ${b.eventDate}): now ends at $end in Outlook")
                changed++
            }
            val check = replyFor(b, found = match != null, rooms = match?.let { RoomCover.roomReplies(attendees[it.eventId].orEmpty(), rooms) }.orEmpty(), now)
            val newSyncId = match?.syncId?.takeIf { it != b.bookingSyncId }
            when {
                check.room != null -> {
                    db.bookings().setRoomFromCalendar(b.id, check.room, check.reply, match?.syncId, now.toEpochMilli())
                    ScanLog.i("Booking ${b.id} (${b.originalTitle}, ${b.eventDate}): room ${b.room} → ${check.room} in Outlook, ${check.reply}")
                    changed++
                }
                check.reply != b.roomReply || newSyncId != null -> {
                    db.bookings().setReply(b.id, check.reply, match?.syncId, now.toEpochMilli())
                    ScanLog.i("Booking ${b.id} (${b.originalTitle}, ${b.eventDate}): ${b.roomReply} → ${check.reply}")
                    changed++
                }
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
         * [b]'s reply from its event in the calendar: [found] or not, and the rooms on it with their
         * replies. Only the booking's own room's reply counts: just after a room change the calendar
         * may still show the old room, whose reply isn't the new one's. Until Outlook has had time to
         * sync the booking (after it was made, or its room or reply last changed), a missing event or
         * a missing room keeps the reply it had; after that, a missing event is NOT_FOUND, a booking
         * without a room is NO_ROOM, and one with another room (changed in Outlook) follows it.
         */
        fun replyFor(b: BookingEntity, found: Boolean, rooms: List<Pair<String, RoomReply>>, now: Instant): ReplyCheck {
            // Synced: long enough since the booking was made, or since its room or reply last changed.
            val synced = Duration.between(Instant.ofEpochMilli(maxOf(b.createdAt, b.checkedAt ?: 0L)), now) > SYNC_GRACE
            if (!found) return ReplyCheck(if (synced) RoomReply.NOT_FOUND else b.roomReply)
            rooms.firstOrNull { RoomChoice.sameRoom(it.first, b.room) }?.let { return ReplyCheck(it.second) }
            if (!synced) return ReplyCheck(b.roomReply)
            val other = rooms.firstOrNull() ?: return ReplyCheck(RoomReply.NO_ROOM)
            return ReplyCheck(other.second, room = other.first)
        }

        /**
         * The user's own booking events among [events]: a colleague's invitation called "Room Booking - …"
         * is never one of the app's bookings ([mine]: the user's addresses; Outlook's account is the
         * organiser of the user's events).
         */
        fun ownBookingEvents(events: List<CalEvent>, mine: Set<String>): List<CalEvent> =
            events.filter { BookingRules.isRoomBooking(it.title) && it.organizer?.trim()?.lowercase() in mine }

        fun matches(event: CalEvent, booking: BookingEntity): Boolean =
            CalEvent.normaliseTitle(event.title) == CalEvent.normaliseTitle(booking.bookingTitle) && sameSlot(event, booking)

        private fun sameSlot(event: CalEvent, booking: BookingEntity): Boolean =
            event.date.toString() == booking.eventDate && TriggerTime.formatHhMm(event.start) == booking.start

        /**
         * Each booking's event in the calendar, never one event for two bookings, and never a cancelled
         * one. A booking whose event has been seen is followed by that event's sync id only, and only at
         * the booking's own time: moved in Outlook, or gone, it is missing rather than given another
         * event that looks like it. A booking not seen yet is found by title, date and start, preferring
         * the event whose room ([roomOf]) is the booking's (a re-booked event leaves the declined booking
         * at the same time and title), and never one another booking has seen.
         */
        fun pair(bookings: List<BookingEntity>, events: List<CalEvent>, roomOf: (CalEvent) -> String?): Map<Long, CalEvent> {
            val live = events.filter { !it.cancelled }
            val claimed = bookings.mapNotNull { it.bookingSyncId }.toSet()
            val used = HashSet<Long>()
            val out = HashMap<Long, CalEvent>()
            for (b in bookings) {
                val id = b.bookingSyncId ?: continue
                val e = live.firstOrNull { it.syncId == id && it.eventId !in used } ?: continue
                if (!sameSlot(e, b)) continue
                out[b.id] = e
                used += e.eventId
            }
            // Room matches first, for every booking; only then whatever is left, newest booking first.
            val unseen = bookings.filter { it.bookingSyncId == null }.sortedByDescending { it.createdAt }
            for (byRoom in listOf(true, false)) {
                for (b in unseen.filter { it.id !in out }) {
                    val e = live.firstOrNull {
                        it.eventId !in used && it.syncId !in claimed && matches(it, b) && (!byRoom || RoomChoice.sameRoom(roomOf(it), b.room))
                    } ?: continue
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
