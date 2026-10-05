package com.thomaswcode.calendareventtimers.calendar

import java.time.Instant
import java.time.ZoneId

/** One provider row: column name → value, as the provider returns it (numbers as text). */
typealias Row = Map<String, String?>

/**
 * Turns calendar-provider rows into [ProviderCalendar], [CalEvent] and [Attendee]. Pure, so it runs
 * in plain JVM tests; [CalendarStore] does the querying. Column names are CalendarContract's.
 */
object CalendarRows {
    const val OUTLOOK_ACCOUNT_TYPE = "com.microsoft.office.outlook.USER_ACCOUNT"

    /** CalendarContract.Calendars.CAL_ACCESS_OWNER */
    private const val ACCESS_OWNER = 700

    /** CalendarContract.Events.STATUS_CANCELED */
    private const val STATUS_CANCELED = 2

    fun calendars(rows: List<Row>): List<ProviderCalendar> = rows.mapNotNull { r ->
        ProviderCalendar(
            id = r.long("_id") ?: return@mapNotNull null,
            name = r["name"],
            displayName = r["calendar_displayName"],
            accountName = r["account_name"],
            accountType = r["account_type"],
            ownerAccount = r["ownerAccount"],
            accessLevel = r.int("calendar_access_level") ?: 0,
        )
    }

    /**
     * Outlook's main calendar: the one its event details call "Calendar (<account>)". Room and group
     * calendars are synced too, so it is picked by name among the ones the user owns; never by id,
     * which is local to the phone.
     */
    fun mainCalendar(calendars: List<ProviderCalendar>): ProviderCalendar? {
        val owned = calendars.filter { it.accountType == OUTLOOK_ACCOUNT_TYPE && it.accessLevel >= ACCESS_OWNER }
        return owned.firstOrNull { it.name.equals("Calendar", ignoreCase = true) }
            ?: owned.firstOrNull { it.displayName.equals("Calendar", ignoreCase = true) }
            ?: owned.singleOrNull()
    }

    /**
     * Occurrences from the `instances` table, completed with sync columns from the `events` table
     * ([eventRows] by `_id`). Occurrences whose event row is missing or deleted are dropped.
     */
    fun events(instanceRows: List<Row>, eventRows: Map<Long, Row>, zone: ZoneId): List<CalEvent> =
        instanceRows.mapNotNull { r ->
            val id = r.long("event_id") ?: return@mapNotNull null
            val e = eventRows[id] ?: return@mapNotNull null
            if (e.int("deleted") == 1) return@mapNotNull null
            val begin = Instant.ofEpochMilli(r.long("begin") ?: return@mapNotNull null)
            val end = Instant.ofEpochMilli(r.long("end") ?: return@mapNotNull null)
            val b = begin.atZone(zone)
            val en = end.atZone(zone)
            val originalSyncId = e["original_sync_id"]?.ifBlank { null }
            CalEvent(
                eventId = id,
                syncId = e["_sync_id"]?.ifBlank { null },
                changeKey = e["sync_data3"]?.ifBlank { null },
                originalSyncId = originalSyncId,
                recurring = !r["rrule"].isNullOrBlank() || originalSyncId != null || e.long("original_id") != null,
                title = r["title"].orEmpty(),
                begin = begin,
                end = end,
                date = b.toLocalDate(),
                start = b.toLocalTime().withSecond(0).withNano(0),
                endDate = en.toLocalDate(),
                endTime = en.toLocalTime().withSecond(0).withNano(0),
                allDay = r.int("allDay") == 1,
                location = r["eventLocation"]?.trim()?.ifEmpty { null },
                organizer = r["organizer"]?.trim()?.ifEmpty { null },
                selfStatus = r.int("selfAttendeeStatus") ?: Attendee.STATUS_NONE,
                cancelled = (e.int("eventStatus") ?: r.int("eventStatus")) == STATUS_CANCELED,
            )
        }.sortedWith(compareBy({ it.begin }, { it.title }))

    fun attendees(rows: List<Row>): Map<Long, List<Attendee>> =
        rows.mapNotNull { r ->
            val id = r.long("event_id") ?: return@mapNotNull null
            id to Attendee(
                name = r["attendeeName"]?.trim()?.ifEmpty { null },
                email = r["attendeeEmail"]?.trim()?.ifEmpty { null },
                type = r.int("attendeeType") ?: 0,
                status = r.int("attendeeStatus") ?: Attendee.STATUS_NONE,
            )
        }.groupBy({ it.first }, { it.second })

    private fun Row.long(key: String): Long? = this[key]?.trim()?.toLongOrNull()

    private fun Row.int(key: String): Int? = this[key]?.trim()?.toIntOrNull()
}
