package com.thomaswcode.calendareventtimers.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads what Outlook syncs into Android's calendar provider (PLAN-ROOM-BOOKING.md §2.1): events,
 * their invitees with addresses, descriptions and the rooms' replies. Read only; every change to
 * the calendar goes through Outlook's own screens. Needs READ_CALENDAR.
 */
class CalendarStore(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    fun hasAccess(): Boolean = hasAccess(app)

    /** Outlook's main calendar, or null when Outlook isn't syncing it (or access is missing). */
    fun mainCalendar(): ProviderCalendar? {
        if (!hasAccess()) return null
        val rows = query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf("_id", "name", "calendar_displayName", "account_name", "account_type", "ownerAccount", "calendar_access_level"),
            "account_type = ?", arrayOf(CalendarRows.OUTLOOK_ACCOUNT_TYPE),
        )
        return CalendarRows.mainCalendar(CalendarRows.calendars(rows)).also {
            if (it == null) ScanLog.w("No single LSHTM Outlook calendar in the phone's calendar store (${rows.size} Outlook calendars)")
        }
    }

    /** Occurrences in the main calendar overlapping [from, to), in start order. */
    fun occurrences(calendar: ProviderCalendar, from: Instant, to: Instant, zone: ZoneId): List<CalEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilli())
            ContentUris.appendId(it, to.toEpochMilli())
        }.build()
        val instances = query(
            uri,
            arrayOf("event_id", "begin", "end", "title", "eventLocation", "allDay", "organizer", "selfAttendeeStatus", "rrule", "eventStatus"),
            "calendar_id = ?", arrayOf(calendar.id.toString()),
        )
        val ids = instances.mapNotNull { it["event_id"]?.toLongOrNull() }.distinct()
        val events = byIds(
            CalendarContract.Events.CONTENT_URI, "_id", ids,
            arrayOf("_id", "_sync_id", "sync_data3", "original_sync_id", "original_id", "eventStatus", "deleted"),
        ).associateBy { it["_id"]!!.toLong() }
        return CalendarRows.events(instances, events, zone)
    }

    /** The occurrences of whole days [first]..[last] (device time zone). */
    fun occurrencesOn(calendar: ProviderCalendar, first: LocalDate, last: LocalDate, zone: ZoneId): List<CalEvent> =
        occurrences(calendar, first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant(), zone)

    fun attendees(eventIds: Collection<Long>): Map<Long, List<Attendee>> =
        CalendarRows.attendees(
            byIds(
                CalendarContract.Attendees.CONTENT_URI, "event_id", eventIds.distinct(),
                arrayOf("event_id", "attendeeName", "attendeeEmail", "attendeeType", "attendeeStatus"),
            ),
        )

    /** Event bodies (HTML for events made in Outlook), by event row. */
    fun descriptions(eventIds: Collection<Long>): Map<Long, String> =
        byIds(CalendarContract.Events.CONTENT_URI, "_id", eventIds.distinct(), arrayOf("_id", "description"))
            .mapNotNull { r -> r["_id"]?.toLongOrNull()?.let { id -> r["description"]?.let { id to it } } }
            .toMap()

    /** `WHERE column IN (…)` in chunks, so long id lists stay within SQLite's limits. */
    private fun byIds(uri: Uri, column: String, ids: List<Long>, projection: Array<String>): List<Row> =
        ids.chunked(400).flatMap { chunk ->
            query(uri, projection, "$column IN (${chunk.joinToString(",") { "?" }})", chunk.map { it.toString() }.toTypedArray())
        }

    private fun query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?): List<Row> =
        try {
            resolver.query(uri, projection, selection, args, null)?.use { it.rows() }.orEmpty()
        } catch (e: SecurityException) {
            ScanLog.e("No access to the phone's calendar", e)
            emptyList()
        }

    private fun Cursor.rows(): List<Row> {
        val names = columnNames
        val out = ArrayList<Row>(count)
        while (moveToNext()) {
            val row = HashMap<String, String?>(names.size)
            for (i in names.indices) row[names[i]] = if (isNull(i)) null else getString(i)
            out += row
        }
        return out
    }

    companion object {
        fun hasAccess(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }
}
