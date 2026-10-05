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

    /** Another of the user's events covers the whole time with an accepted room (`call` with KS-121). */
    data class OtherEvent(val title: String, override val room: String) : Cover
}

/** Another event has a room for part of this one's time: shown as a note. */
data class PartialCover(val title: String, val room: String, val from: LocalTime, val to: LocalTime)

/** A booking the app knows about for an occurrence, with the room's latest reply. */
data class KnownBooking(val room: String?, val reply: RoomReply)

/** PLAN-ROOM-BOOKING.md §3.5, worked out from the phone's calendar without opening Outlook. */
object RoomCover {
    /** These replies mean the room is (or may still be) held for the booking. */
    private val HOLDING = setOf(RoomReply.WAITING, RoomReply.RESERVED, RoomReply.TENTATIVE)

    fun cover(
        event: CalEvent,
        attendees: List<Attendee>,
        known: KnownBooking?,
        others: List<CalEvent>,
        attendeesOf: (CalEvent) -> List<Attendee>,
        rooms: List<String>,
    ): Cover? {
        if (known != null && known.reply in HOLDING) return Cover.AppBooking(known.room, known.reply)

        // The app's booking found in the calendar (e.g. made before a reinstall).
        val bookingTitle = CalEvent.normaliseTitle(BookingRules.bookingTitle(event.title))
        for (other in others) {
            if (other.cancelled || other.begin != event.begin || CalEvent.normaliseTitle(other.title) != bookingTitle) continue
            val room = roomOf(attendeesOf(other), rooms, accepted = false)
            if (room != null) return Cover.AppBooking(room.first, reply(room.second))
        }

        roomOf(attendees, rooms, accepted = false)?.let { return Cover.RoomOnEvent(it.first) }
        RoomChoice.roomIn(event.location, rooms)?.let { return Cover.LocationNamesRoom(it) }

        for (other in others) {
            if (other.cancelled || other.occurrenceKey == event.occurrenceKey || other.allDay) continue
            if (other.begin.isAfter(event.begin) || other.end.isBefore(event.end)) continue
            val room = roomOf(attendeesOf(other), rooms, accepted = true) ?: continue
            return Cover.OtherEvent(other.title.trim(), room.first)
        }
        return null
    }

    /** The first event with an accepted room overlapping part of [event]'s time. */
    fun partial(
        event: CalEvent, others: List<CalEvent>, attendeesOf: (CalEvent) -> List<Attendee>, rooms: List<String>, zone: ZoneId,
    ): PartialCover? {
        for (other in others) {
            if (other.cancelled || other.occurrenceKey == event.occurrenceKey || other.allDay) continue
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
            val listed = RoomChoice.roomIn(a.name, rooms) ?: RoomChoice.roomIn(a.email, rooms)
            val room = listed ?: if (a.isResource) (a.name ?: a.email?.substringBefore('@') ?: "a room") else null
            if (room != null) return room to a.status
        }
        return null
    }

    fun reply(status: Int): RoomReply = when (status) {
        Attendee.STATUS_ACCEPTED -> RoomReply.RESERVED
        Attendee.STATUS_DECLINED -> RoomReply.DECLINED
        Attendee.STATUS_TENTATIVE -> RoomReply.TENTATIVE
        else -> RoomReply.WAITING
    }
}
