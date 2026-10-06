package com.thomaswcode.calendareventtimers.booking

import com.thomaswcode.calendareventtimers.calendar.Attendee
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.data.RoomReply
import java.time.LocalTime
import java.time.ZoneId

/** Why an event already has a room. Such events are not shown at all (user's decision, 2026-10-05). */
sealed interface Cover {
    val room: String?

    /** A booking the app made, saved and not declined. */
    data class AppBooking(override val room: String?, val reply: RoomReply) : Cover

    /** A room is an invitee of the event itself (`… [room added]`, a colleague's meeting in a room). */
    data class RoomOnEvent(override val room: String) : Cover

    /** The location names a room from the list, though no room is an invitee. */
    data class LocationNamesRoom(override val room: String) : Cover

    /** Another event the user organised covers the whole time with an accepted room (`call` with KS-121). */
    data class OtherEvent(val title: String, override val room: String) : Cover
}

/** Another event has a room for part of this one's time: shown as a note. */
data class PartialCover(val title: String, val room: String, val from: LocalTime, val to: LocalTime)

/** A booking the app knows about for an occurrence, with the room's latest reply and the booked times. */
data class KnownBooking(val room: String?, val reply: RoomReply, val start: LocalTime? = null, val end: LocalTime? = null) {
    /** Whether it covers [event]'s whole time: the meeting may have been made longer since. */
    fun covers(event: CalEvent): Boolean =
        (start == null || !start.isAfter(event.start)) && (end == null || minutes(end) >= minutes(event.endTime))

    /** Minutes into the day; an end at midnight is the day's end. */
    private fun minutes(t: LocalTime) = if (t == LocalTime.MIDNIGHT) 24 * 60 else t.hour * 60 + t.minute
}

/** PLAN-ROOM-BOOKING.md §3.5, worked out from the phone's calendar without opening Outlook. */
object RoomCover {
    /** These replies mean the room is (or may still be) held for the booking. */
    private val HOLDING = setOf(RoomReply.WAITING, RoomReply.RESERVED, RoomReply.TENTATIVE)

    fun holds(reply: RoomReply): Boolean = reply in HOLDING

    /**
     * [mine] are the user's addresses: only events the user organised count as their room bookings
     * (a colleague's seminar in a room at the same time doesn't mean the user has a room).
     */
    fun cover(
        event: CalEvent,
        attendees: List<Attendee>,
        known: KnownBooking?,
        others: List<CalEvent>,
        attendeesOf: (CalEvent) -> List<Attendee>,
        rooms: List<String>,
        mine: Set<String>,
    ): Cover? {
        if (known != null && known.reply in HOLDING && known.covers(event)) return Cover.AppBooking(known.room, known.reply)

        // The app's booking found in the calendar (e.g. made before a reinstall): the user's own, for
        // the whole meeting. A colleague's "Room Booking - …" with the same name is theirs.
        val bookingTitle = CalEvent.normaliseTitle(BookingRules.bookingTitle(event.title))
        for (other in others) {
            if (other.cancelled || other.begin != event.begin || CalEvent.normaliseTitle(other.title) != bookingTitle) continue
            if (!isMine(other, mine) || other.end.isBefore(event.end)) continue
            val room = roomOf(attendeesOf(other), rooms, accepted = false)
            if (room != null) return Cover.AppBooking(room.first, reply(room.second))
        }

        roomOf(attendees, rooms, accepted = false)?.let { return Cover.RoomOnEvent(it.first) }
        // Only a room that isn't an invitee: Outlook leaves a room that declined in the location.
        RoomChoice.roomIn(event.location, rooms)
            ?.takeIf { named -> attendees.none { listedRoom(it, rooms) == named } }
            ?.let { return Cover.LocationNamesRoom(it) }

        for (other in others) {
            if (!usable(other, event, mine)) continue
            if (other.begin.isAfter(event.begin) || other.end.isBefore(event.end)) continue
            val room = roomOf(attendeesOf(other), rooms, accepted = true) ?: continue
            return Cover.OtherEvent(other.title.trim(), room.first)
        }
        return null
    }

    /** Another, live, timed event the user organised. */
    private fun usable(other: CalEvent, event: CalEvent, mine: Set<String>): Boolean =
        !other.cancelled && other.occurrenceKey != event.occurrenceKey && !other.allDay && isMine(other, mine)

    private fun isMine(e: CalEvent, mine: Set<String>): Boolean = e.organizer?.trim()?.lowercase() in mine

    /**
     * The meeting's own booking ([known]), still holding its room but for only part of [event]'s
     * time (the meeting was made longer since): known to the app even before the calendar shows it.
     */
    fun partialFromKnown(known: KnownBooking?, event: CalEvent): PartialCover? {
        if (known == null || !holds(known.reply) || known.covers(event)) return null
        return PartialCover(BookingRules.bookingTitle(event.title), known.room ?: return null, known.start ?: return null, known.end ?: return null)
    }

    /** The first event with an accepted room overlapping part of [event]'s time. */
    fun partial(
        event: CalEvent, others: List<CalEvent>, attendeesOf: (CalEvent) -> List<Attendee>, rooms: List<String>, zone: ZoneId, mine: Set<String>,
    ): PartialCover? {
        for (other in others) {
            if (!usable(other, event, mine)) continue
            if (!other.begin.isBefore(event.end) || !other.end.isAfter(event.begin)) continue
            val room = roomOf(attendeesOf(other), rooms, accepted = true) ?: continue
            val from = maxOf(other.begin, event.begin).atZone(zone).toLocalTime().withSecond(0).withNano(0)
            val to = minOf(other.end, event.end).atZone(zone).toLocalTime().withSecond(0).withNano(0)
            return PartialCover(other.title.trim(), room.first, from, to)
        }
        return null
    }

    /**
     * A room among [attendees] with its reply: a resource invitee, or an invitee whose name or
     * address is a room from the list. Declined rooms don't count; with [accepted], only accepted ones.
     */
    private fun roomOf(attendees: List<Attendee>, rooms: List<String>, accepted: Boolean): Pair<String, Int>? {
        for (a in attendees) {
            if (a.status == Attendee.STATUS_DECLINED) continue
            if (accepted && a.status != Attendee.STATUS_ACCEPTED) continue
            val room = listedRoom(a, rooms) ?: if (a.isResource) (a.name ?: a.email?.substringBefore('@') ?: "a room") else null
            if (room != null) return room to a.status
        }
        return null
    }

    /** The room from the list that invitee [a] is, by name or address. */
    private fun listedRoom(a: Attendee, rooms: List<String>): String? = RoomChoice.roomIn(a.name, rooms) ?: RoomChoice.roomIn(a.email, rooms)

    /** A booking event's room and its reply, from its invitees; null when it has no room. */
    fun roomReply(attendees: List<Attendee>, rooms: List<String>): Pair<String, RoomReply>? = roomReplies(attendees, rooms).firstOrNull()

    /** Every room among a booking event's invitees, with its reply. */
    fun roomReplies(attendees: List<Attendee>, rooms: List<String>): List<Pair<String, RoomReply>> =
        attendees.filter { a -> a.isResource || listedRoom(a, rooms) != null }.map { a ->
            (listedRoom(a, rooms) ?: a.name ?: a.email?.substringBefore('@') ?: "a room") to reply(a.status)
        }

    fun reply(status: Int): RoomReply = when (status) {
        Attendee.STATUS_ACCEPTED -> RoomReply.RESERVED
        Attendee.STATUS_DECLINED -> RoomReply.DECLINED
        Attendee.STATUS_TENTATIVE -> RoomReply.TENTATIVE
        else -> RoomReply.WAITING
    }
}
