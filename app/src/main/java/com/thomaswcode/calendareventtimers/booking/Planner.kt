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
) {
    val key: String get() = event.occurrenceKey

    /** Who will be told with [answers]. */
    fun notifyList(answers: Answers): List<Person> =
        if (!answers.bookRoom || !answers.notify) emptyList() else people.filter { it.email !in answers.removed }
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
            val cover = RoomCover.cover(e, attendees, known, input.events, attendeesOf, input.rooms)
            if (cover != null) {
                covered += e to cover
                continue
            }
            val previous = input.answers[e.occurrenceKey]
            val memory = input.memory[e.seriesKey]
            val declined = known?.reply == RoomReply.DECLINED
            val people = People.toNotify(attendees, e.organizer, input.myAddresses, input.rooms)
            val defaults = when {
                previous == AnswerKind.NO_ROOM_WANTED -> Answers(bookRoom = false, notify = memory?.notify ?: false, removed = memory?.removed.orEmpty())
                memory != null -> Answers(memory.bookRoom || declined, memory.notify, memory.removed)
                else -> Answers(bookRoom = true, notify = false)
            }
            val candidate = BookingCandidate(
                event = e,
                label = label,
                people = people,
                defaults = defaults.copy(removed = defaults.removed.filter { r -> people.any { it.email == r } }.toSet()),
                remembered = memory != null,
                previous = previous,
                partial = RoomCover.partial(e, input.events, attendeesOf, input.rooms, input.zone),
                roomDeclined = declined,
            )
            if (previous == AnswerKind.NO_ROOM_WANTED) answeredBefore += candidate else toAsk += candidate
        }
        return Output(toAsk, answeredBefore, covered, unlabelled, unknown)
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
