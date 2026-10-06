package com.thomaswcode.calendareventtimers.booking

import com.thomaswcode.calendareventtimers.calendar.Attendee
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.data.AnswerKind
import com.thomaswcode.calendareventtimers.data.RoomReply
import com.thomaswcode.calendareventtimers.domain.EventParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The last answers for a meeting that comes back (by series key). */
data class SeriesAnswers(val bookRoom: Boolean, val notify: Boolean, val removed: Set<String>)

/** The wizard's answers for one event. [removed] are addresses not to tell. */
data class Answers(val bookRoom: Boolean, val notify: Boolean, val removed: Set<String> = emptySet())

/** One event the wizard can ask about. */
data class BookingCandidate(
    val event: CalEvent,
    /** Moveable or Immoveable. */
    val label: String,
    /** LSHTM people who may be told (§3.6). */
    val people: List<Person>,
    val defaults: Answers,
    /** [defaults] come from the last time this meeting was answered. */
    val remembered: Boolean,
    /** What this occurrence got before, if it was asked about. */
    val previous: AnswerKind?,
    /** An event with a room for part of the time. */
    val partial: PartialCover?,
    /** The room declined the app's booking for it. */
    val roomDeclined: Boolean,
    /** The app's booking for it has no room any more (taken off in Outlook). */
    val roomRemoved: Boolean = false,
) {
    val key: String get() = event.occurrenceKey

    /** Who will be told with [answers]. */
    fun notifyList(answers: Answers): List<Person> =
        if (!answers.bookRoom || !answers.notify) emptyList() else people.filter { it.email !in answers.removed }
}

/** What the calendar says about a candidate just before it is booked ([BookingPlanner.recheck]). */
sealed interface Recheck {
    /** Still the event that was answered: book [event], telling [people]; [notes] say what changed. */
    data class Go(val event: CalEvent, val people: List<String>, val notes: List<String>) : Recheck

    /** It changed after the wizard was answered, so it isn't booked; [reason] says how. */
    data class Skip(val reason: String) : Recheck
}

/**
 * Which events a booking run offers and how the wizard starts for each (PLAN-ROOM-BOOKING.md
 * §3.4–3.8). Pure: everything it needs is passed in.
 */
object BookingPlanner {
    data class Input(
        /** Every occurrence in the range's days (main calendar), bookings included. */
        val events: List<CalEvent>,
        /** Labels by label key; events missing here couldn't be read. */
        val labels: Map<String, List<String>>,
        val attendees: Map<Long, List<Attendee>>,
        /** The app's bookings by the original's occurrence key. */
        val known: Map<String, KnownBooking>,
        val answers: Map<String, AnswerKind>,
        val memory: Map<String, SeriesAnswers>,
        val rooms: List<String>,
        val myAddresses: Set<String>,
        val days: Set<LocalDate>,
        val now: Instant,
        val zone: ZoneId,
    )

    data class Output(
        /** New events for the wizard, in time order. */
        val toAsk: List<BookingCandidate>,
        /** Events the user said no room for before: in the summary only, changeable there. */
        val answeredBefore: List<BookingCandidate>,
        /** Events that already have a room: never shown (only logged). */
        val covered: List<Pair<CalEvent, Cover>>,
        val unlabelled: Int,
        /** Events whose labels couldn't be read. */
        val labelUnknown: List<CalEvent>,
    )

    /** Timed, same-day, not cancelled or declined, not started, not one of the app's bookings. */
    fun isCandidateEvent(e: CalEvent, days: Set<LocalDate>, now: Instant): Boolean =
        !e.allDay && e.sameDay && !e.cancelled && e.selfStatus != Attendee.STATUS_DECLINED &&
            !BookingRules.isRoomBooking(e.title) && e.date in days && e.begin.isAfter(now)

    fun plan(input: Input): Output {
        val attendeesOf = { e: CalEvent -> input.attendees[e.eventId].orEmpty() }
        val mine = input.myAddresses.map { it.trim().lowercase() }.toSet()
        val toAsk = ArrayList<BookingCandidate>()
        val answeredBefore = ArrayList<BookingCandidate>()
        val covered = ArrayList<Pair<CalEvent, Cover>>()
        val unknown = ArrayList<CalEvent>()
        var unlabelled = 0
        for (e in input.events.filter { isCandidateEvent(it, input.days, input.now) }.sortedWith(compareBy({ it.begin }, { it.title }))) {
            val categories = input.labels[e.labelKey]
            if (categories == null) {
                unknown += e
                continue
            }
            val label = EventParser.matchLabel(categories)
            if (label == null) {
                unlabelled++
                continue
            }
            val attendees = attendeesOf(e)
            val known = input.known[e.occurrenceKey]
            val cover = RoomCover.cover(e, attendees, known, input.events, attendeesOf, input.rooms, mine)
            if (cover != null) {
                covered += e to cover
                continue
            }
            val previous = input.answers[e.occurrenceKey]
            val memory = input.memory[e.seriesKey]
            val declined = known?.reply == RoomReply.DECLINED
            val removed = known?.reply == RoomReply.NO_ROOM
            val people = People.toNotify(attendees, e.organizer, input.myAddresses, input.rooms)
            val defaults = when {
                previous == AnswerKind.NO_ROOM_WANTED -> Answers(bookRoom = false, notify = memory?.notify ?: false, removed = memory?.removed.orEmpty())
                memory != null -> Answers(memory.bookRoom || declined || removed, memory.notify, memory.removed)
                else -> Answers(bookRoom = true, notify = false)
            }
            val candidate = BookingCandidate(
                event = e,
                label = label,
                people = people,
                defaults = defaults.copy(removed = defaults.removed.filter { r -> people.any { it.email == r } }.toSet()),
                remembered = memory != null,
                previous = previous,
                // Its own booking for part of the time first: it may not be in the calendar yet.
                partial = RoomCover.partialFromKnown(known, e) ?: RoomCover.partial(e, input.events, attendeesOf, input.rooms, input.zone, mine),
                roomDeclined = declined,
                roomRemoved = removed,
            )
            if (previous == AnswerKind.NO_ROOM_WANTED) answeredBefore += candidate else toAsk += candidate
        }
        return Output(toAsk, answeredBefore, covered, unlabelled, unknown)
    }

    /**
     * [c] against the calendar as it is now, just before booking it: the wizard may have been
     * answered long before Book Rooms. [events] are the occurrences of its day, [attendees] by event
     * row. A room is booked only for the event as answered: not moved, renamed, cancelled, declined,
     * started or given a room since, by the same rules as planning ([RoomCover.cover]), except that
     * the bookings this run has made ([madeThisRun]: booking title and start) don't count: the
     * user asked for those rooms together. People who are no longer invitees aren't told.
     */
    fun recheck(
        c: BookingCandidate, answers: Answers, events: List<CalEvent>, attendees: Map<Long, List<Attendee>>,
        known: KnownBooking?, rooms: List<String>, myAddresses: Set<String>, now: Instant,
        madeThisRun: Set<Pair<String, Instant>> = emptySet(),
    ): Recheck {
        val e = events.firstOrNull { it.occurrenceKey == c.key }
            ?: events.firstOrNull { it.eventId == c.event.eventId && it.begin == c.event.begin }
            ?: return Recheck.Skip("it was moved, changed or deleted after you answered")
        val title = CalEvent.normaliseTitle(e.title)
        when {
            e.cancelled -> return Recheck.Skip("it has been cancelled")
            e.selfStatus == Attendee.STATUS_DECLINED -> return Recheck.Skip("you have declined it")
            !e.begin.isAfter(now) -> return Recheck.Skip("it has started")
            e.end != c.event.end || e.allDay != c.event.allDay -> return Recheck.Skip("its time has changed")
            title != CalEvent.normaliseTitle(c.event.title) -> return Recheck.Skip("it has been renamed “${e.title.trim()}”")
        }
        val own = attendees[e.eventId].orEmpty()
        val mine = myAddresses.map { it.trim().lowercase() }.toSet()
        val others = events.filterNot { (CalEvent.normaliseTitle(it.title) to it.begin) in madeThisRun }
        RoomCover.cover(e, own, known, others, { x -> attendees[x.eventId].orEmpty() }, rooms, mine)?.let { cover ->
            return Recheck.Skip("it has a room now${cover.room?.let { r -> " ($r)" }.orEmpty()}")
        }
        // Never the user (an address of theirs may have been learnt since the wizard).
        val planned = c.notifyList(answers).map { it.email }.filter { it !in mine }
        val invited = People.toNotify(own, e.organizer, myAddresses, rooms).map { it.email }.toSet()
        val gone = planned.filter { it !in invited }
        val notes = if (gone.isEmpty()) emptyList() else listOf("not told, as no longer invited: ${gone.joinToString()}")
        return Recheck.Go(e, planned.filter { it in invited }, notes)
    }

    /** Rough time Outlook will be on screen for [bookings], in seconds (§3.17). */
    fun estimateSeconds(bookings: List<Pair<BookingCandidate, Answers>>, withDescription: (BookingCandidate) -> Boolean): Int {
        val booked = bookings.filter { it.second.bookRoom }
        if (booked.isEmpty()) return 0
        val people = booked.count { (c, a) -> c.notifyList(a).isNotEmpty() }
        val descriptions = booked.count { withDescription(it.first) }
        val days = booked.map { it.first.event.date }.distinct().size
        return 6 + days * 3 + booked.size * 16 + people * 3 + descriptions * 3
    }

    fun describeSeconds(seconds: Int): String = when {
        seconds <= 0 -> "no time"
        seconds < 60 -> "about ${((seconds + 4) / 5) * 5} s"
        else -> "about ${(seconds + 30) / 60} min"
    }
}
