package com.thomaswcode.calendareventtimers.booking

/** Fixed rules of the room bookings the app makes (PLAN-ROOM-BOOKING.md §1, §6). */
object BookingRules {
    const val TITLE_PREFIX = "Room Booking - "

    /** The booking event's title for an event called [title]. */
    fun bookingTitle(title: String): String = TITLE_PREFIX + title.trim()

    /** One of the app's own booking events (never offered for a room or an alarm itself). */
    fun isRoomBooking(title: String): Boolean = title.trimStart().startsWith(TITLE_PREFIX, ignoreCase = true)
}
