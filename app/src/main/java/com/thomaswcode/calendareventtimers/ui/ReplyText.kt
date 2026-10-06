package com.thomaswcode.calendareventtimers.ui

import com.thomaswcode.calendareventtimers.booking.RoomCover
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
}
