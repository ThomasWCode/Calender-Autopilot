package com.thomaswcode.calendareventtimers.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** A calendar that Outlook syncs into Android's calendar provider. */
data class ProviderCalendar(
    val id: Long,
    val name: String?,
    val displayName: String?,
    val accountName: String?,
    val accountType: String?,
    val ownerAccount: String?,
    val accessLevel: Int,
)

/**
 * One occurrence of an event in the provider (a recurring event has one per day it happens).
 * [eventId] is the provider's row: the series, or a changed single occurrence of it.
 */
data class CalEvent(
    val eventId: Long,
    /** Outlook's id for the event (series); stable. */
    val syncId: String?,
    /** Exchange's change key (`sync_data3`): changes whenever the event does, labels included. */
    val changeKey: String?,
    /** Set on a changed occurrence of a series: the series' [syncId]. */
    val originalSyncId: String?,
    val recurring: Boolean,
    val title: String,
    val begin: Instant,
    val end: Instant,
    /** [begin] and [end] in the device's time zone. */
    val date: LocalDate,
    val start: LocalTime,
    val endDate: LocalDate,
    val endTime: LocalTime,
    val allDay: Boolean,
    val location: String?,
    val organizer: String?,
    /** The user's own reply: [Attendee.STATUS_DECLINED] etc. */
    val selfStatus: Int,
    val cancelled: Boolean,
) {
    /** Labels (categories) belong to the provider row, so a series shares them. */
    val labelKey: String get() = syncId ?: "id:$eventId"

    /** This occurrence; a moved occurrence gets a new key and counts as a new event. */
    val occurrenceKey: String get() = "$labelKey@${begin.toEpochMilli()}"

    /** The same meeting week after week: the series for a recurring event, else its title. */
    val seriesKey: String
        get() = originalSyncId ?: if (recurring && syncId != null) syncId else "title:" + normaliseTitle(title)

    /** Starts and ends on the same day (ending at midnight counts). */
    val sameDay: Boolean
        get() = endDate == date || (endDate == date.plusDays(1) && endTime == LocalTime.MIDNIGHT)

    companion object {
        fun normaliseTitle(title: String): String = title.trim().replace(Regex("""\s+"""), " ").lowercase()
    }
}

/** An invitee row of the provider. Rooms are [TYPE_RESOURCE]. */
data class Attendee(val name: String?, val email: String?, val type: Int, val status: Int) {
    val isResource: Boolean get() = type == TYPE_RESOURCE

    companion object {
        const val TYPE_REQUIRED = 1
        const val TYPE_OPTIONAL = 2
        const val TYPE_RESOURCE = 3

        const val STATUS_NONE = 0
        const val STATUS_ACCEPTED = 1
        const val STATUS_DECLINED = 2
        const val STATUS_INVITED = 3
        const val STATUS_TENTATIVE = 4
    }
}
