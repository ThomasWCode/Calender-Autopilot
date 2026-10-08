package com.thomaswcode.calendareventtimers.ui

import com.thomaswcode.calendareventtimers.booking.RoomCover
import com.thomaswcode.calendareventtimers.booking.RowOutcome
import com.thomaswcode.calendareventtimers.data.BookingEntity
import com.thomaswcode.calendareventtimers.data.RoomReply

/** How a booking's room reply reads, the same in the results, the week summaries and Manage bookings. */
object ReplyText {
    fun short(reply: RoomReply): String = when (reply) {
        RoomReply.RESERVED -> "Reserved"
        RoomReply.TENTATIVE -> "Tentative"
        RoomReply.DECLINED -> "Declined"
        RoomReply.WAITING -> "Waiting for a reply"
        RoomReply.NOT_FOUND -> "Missing from the calendar"
        RoomReply.NO_ROOM -> "No room on it"
    }

    /** The booking needs the user: its room declined or went, or it is missing. */
    fun needsAttention(reply: RoomReply): Boolean = !RoomCover.holds(reply)

    /** "3 booked · 1 waiting for a reply · 1 declined": [bookings] by their replies; booked counts only those holding a room. */
    fun summary(bookings: List<BookingEntity>): String = listOfNotNull(
        "${bookings.count { RoomCover.holds(it.roomReply) }} booked",
        bookings.count { it.roomReply == RoomReply.WAITING }.takeIf { it > 0 }?.let { "$it waiting for a reply" },
        bookings.count { it.roomReply == RoomReply.DECLINED }.takeIf { it > 0 }?.let { "$it declined" },
        bookings.count { it.roomReply == RoomReply.NO_ROOM }.takeIf { it > 0 }?.let { "$it without a room" },
        bookings.count { it.roomReply == RoomReply.NOT_FOUND }.takeIf { it > 0 }?.let { "$it missing" },
    ).joinToString(" · ")

    /**
     * The results' summary line: "1 booked · 1 declined · 2 not done". As [summary], a saved booking
     * counts as booked only while its room holds it ([replies] by booking id, as they arrive).
     */
    fun resultsSummary(outcomes: List<RowOutcome>, replies: Map<Long, RoomReply>, dryRun: Boolean): String {
        val booked = outcomes.filterIsInstance<RowOutcome.Booked>()
        val reply = { b: RowOutcome.Booked -> b.bookingId?.let { replies[it] } }
        val holding = booked.count { b -> !b.saved || reply(b)?.let { RoomCover.holds(it) } != false }
        fun count(n: Int, what: String) = n.takeIf { it > 0 }?.let { "$it $what" }
        return listOfNotNull(
            if (dryRun) "$holding would be booked" else "$holding booked",
            count(booked.count { reply(it) == RoomReply.DECLINED }, "declined"),
            count(booked.count { reply(it) == RoomReply.NO_ROOM }, "without a room"),
            count(booked.count { reply(it) == RoomReply.NOT_FOUND }, "missing"),
            count(outcomes.count { it is RowOutcome.NoRoom }, "with no free room"),
            count(outcomes.count { it is RowOutcome.Failed }, "failed"),
            count(outcomes.count { it is RowOutcome.Uncertain }, "to check in Outlook"),
            count(outcomes.count { it is RowOutcome.NotDone }, "not done"),
        ).joinToString(" · ")
    }
}
