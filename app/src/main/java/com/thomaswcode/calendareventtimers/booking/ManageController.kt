package com.thomaswcode.calendareventtimers.booking

import android.content.Context
import com.thomaswcode.calendareventtimers.calendar.Attendee
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.calendar.CalendarStore
import com.thomaswcode.calendareventtimers.data.AutopilotDatabase
import com.thomaswcode.calendareventtimers.data.BookingEntity
import com.thomaswcode.calendareventtimers.data.BookingState
import com.thomaswcode.calendareventtimers.data.BookingStore
import com.thomaswcode.calendareventtimers.data.ListCodec
import com.thomaswcode.calendareventtimers.data.RoomReply
import com.thomaswcode.calendareventtimers.domain.EventParser
import com.thomaswcode.calendareventtimers.domain.TriggerTime
import com.thomaswcode.calendareventtimers.engine.CachedLabels
import com.thomaswcode.calendareventtimers.engine.LabelCachePolicy
import com.thomaswcode.calendareventtimers.outlook.AppScope
import com.thomaswcode.calendareventtimers.outlook.BookingNavigator
import com.thomaswcode.calendareventtimers.outlook.BookingOutcome
import com.thomaswcode.calendareventtimers.outlook.OutlookBusy
import com.thomaswcode.calendareventtimers.outlook.OutlookNavigator
import com.thomaswcode.calendareventtimers.outlook.OutlookReaderService
import com.thomaswcode.calendareventtimers.outlook.OutlookSession
import com.thomaswcode.calendareventtimers.outlook.RoomSettings
import com.thomaswcode.calendareventtimers.outlook.ScanFailure
import com.thomaswcode.calendareventtimers.util.Prefs
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A booking as Manage bookings shows it (PLAN-ROOM-BOOKING.md §3.12). */
data class ManagedBooking(
    val booking: BookingEntity,
    /** People told about it, with names where known. */
    val notified: List<Person>,
    /** LSHTM people of the original event, for Edit people. */
    val offered: List<Person>,
    /** Things to look at: declined room, original moved or gone, label removed. */
    val warnings: List<String>,
    /** A booking event found in the calendar that the app has no record of (made before a reinstall, say). */
    val fromCalendar: Boolean = false,
    /** Such a booking was just changed in Outlook and the calendar doesn't show it yet: not to be changed again. */
    val syncing: Boolean = false,
)

/**
 * Manage bookings: loads the app's bookings with what has changed since, and changes them in
 * Outlook (change room, edit people, delete), one at a time.
 */
object ManageController {
    sealed interface State {
        data object Idle : State
        data class Running(val bookingId: Long, val what: String, val step: String) : State
        data class Finished(val message: String) : State
    }

    /** How far ahead the calendar is searched for booking events the app has no record of. */
    private const val CALENDAR_WEEKS = 4L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    /**
     * The bookings from today on, with warnings, after reading the rooms' replies again. Besides the
     * app's records, the user's own `Room Booking - …` events it has no record of are listed: they
     * hide their meetings just the same, so they can be changed or deleted here too.
     */
    suspend fun load(context: Context, zone: ZoneId = ZoneId.systemDefault()): List<ManagedBooking> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        Prefs.ensureLoaded(app)
        val store = BookingStore.get(app)
        runCatching { store.refreshReplies(Instant.now(), zone) }
        val today = LocalDate.now(zone)
        val bookings = store.savedFrom(today)
        val calendar = CalendarStore(app)
        val main = if (calendar.hasAccess()) calendar.mainCalendar() else null
        val rooms = Prefs.rooms.value
        val mine = BookingController.myAddresses(main)
        val last = (bookings.map { LocalDate.parse(it.eventDate) } + today.plusWeeks(CALENDAR_WEEKS)).max()
        val events = main?.let { calendar.occurrencesOn(it, today, last, zone) }.orEmpty()
        val bookingEvents = BookingStore.ownBookingEvents(events, mine)
        val attendees = calendar.attendees((events.map { it.eventId } + bookings.map { it.originalEventId }).distinct())
        val now = Instant.now()
        val settled = bookings.filter { BookingStore.synced(it, now) }.map { it.id }.toSet()
        val pairs = BookingStore.pair(bookings, bookingEvents, settled, { e -> BookingStore.declined(attendees[e.eventId].orEmpty(), rooms) }) { e ->
            RoomCover.roomReply(attendees[e.eventId].orEmpty(), rooms)?.first
        }
        val paired = pairs.values.map { it.occurrenceKey }.toSet()
        val nowMs = now.toEpochMilli()
        // Events that may be one of the app's own (unsure which) aren't listed as found in the calendar,
        // nor are bookings deleted here a moment ago that the calendar still shows.
        val unsure = BookingStore.unsure(bookings, bookingEvents, pairs).let { ids -> bookings.filter { it.id in ids } }
        val found = calendarOnly(
            bookingEvents.filter { e -> e.occurrenceKey !in paired && unsure.none { BookingStore.matches(e, it) } }, events, attendees, rooms, mine,
        ).filter { shadowOf(it, nowMs)?.deleted != true }
        // The people told as the booking event has them now: they may have been changed in Outlook,
        // and Edit people works from this list (anyone left off it couldn't be taken off the booking).
        // Not just after a change here, though: then the record is Outlook's, the calendar not yet.
        val recorded = bookings.map { b ->
            if (shadowOf(b, nowMs) != null) return@map b
            val told = pairs[b.id]?.let { toldOn(attendees[it.eventId].orEmpty(), rooms, mine) } ?: return@map b
            if (told.toSet() == ListCodec.decode(b.notified).toSet()) return@map b
            store.setNotified(b.id, told)
            ScanLog.i("Booking ${b.id} (${b.originalTitle}): people told are now ${told.size}, as in Outlook")
            b.copy(notified = ListCodec.encode(told))
        }
        val all = (recorded.map { it to false } + found.map { it to true }).sortedWith(compareBy({ it.first.eventDate }, { it.first.start }))
        if (all.isEmpty()) return@withContext emptyList()
        val originals = all.associate { (b, _) -> b.id to originalOf(b, events) }
        val cache = AutopilotDatabase.get(app).labels().get(originals.values.mapNotNull { it?.labelKey }.distinct()).associateBy { it.labelKey }
        // Only labels still valid for the meeting as it is now, as the label pass would reuse them.
        val labels = originals.values.filterNotNull().mapNotNull { o ->
            val e = cache[o.labelKey] ?: return@mapNotNull null
            LabelCachePolicy.reuse(CachedLabels(e.changeKey, ListCodec.decode(e.categories), Instant.ofEpochMilli(e.readAt)), o.changeKey, now)
                ?.let { o.labelKey to it }
        }.toMap()
        val names = store.names(all.flatMap { ListCodec.decode(it.first.notified) })
        all.map { (b, fromCalendar) ->
            val notified = ListCodec.decode(b.notified).map { Person(it, names[it]) }
            val original = originals[b.id]
            val offered = original?.let { People.toNotify(attendees[it.eventId].orEmpty(), it.organizer, mine, rooms) }.orEmpty()
            // A booking found in the calendar is for a meeting of its title at its time; with two, unclear which.
            val unclear = fromCalendar && original == null && meetingsLike(b.originalTitle, b.eventDate, b.start, events).size > 1
            val syncing = fromCalendar && shadowOf(b, nowMs) != null
            ManagedBooking(b, notified, (offered + notified).distinctBy { it.email }, warnings(b, original, events, labels, main != null, unclear), fromCalendar, syncing)
        }
    }

    /** Who a booking event tells: its people, not rooms and not the user. */
    internal fun toldOn(attendees: List<Attendee>, rooms: List<String>, mine: Set<String>): List<String> =
        attendees.filter { !it.isResource && RoomChoice.roomIn(it.email, rooms) == null && RoomChoice.roomIn(it.name, rooms) == null }
            .mapNotNull { it.email?.trim()?.lowercase() }.filter { it.contains('@') && it !in mine }.distinct()

    /**
     * Bookings changed here a moment ago: the calendar shows a change only once Outlook syncs it.
     * Until then a recorded booking keeps what the app recorded (not the calendar's old people), a
     * deleted one isn't brought back as found in the calendar, and one found only in the calendar is
     * marked and can't be changed again (that would act on what is already changed).
     */
    private data class Shadow(val until: Long, val deleted: Boolean)

    private val shadows = java.util.concurrent.ConcurrentHashMap<String, Shadow>()
    private const val SYNC_SHADOW_MS = 2 * 60_000L

    /** What a booking is known by: its event's sync id once seen, and its title and time. */
    private fun shadowKeys(b: BookingEntity): List<String> =
        listOfNotNull(b.bookingSyncId, "${b.eventDate} ${b.start} ${CalEvent.normaliseTitle(b.bookingTitle)}")

    private fun shadowOf(b: BookingEntity, nowMs: Long): Shadow? = shadowKeys(b).firstNotNullOfOrNull { shadows[it] }?.takeIf { it.until > nowMs }

    /** Meetings a booking called "Room Booking - [title]" may be for: that title, on [date] at [start]. */
    private fun meetingsLike(title: String, date: String, start: String, events: List<CalEvent>): List<CalEvent> = events.filter {
        !BookingRules.isRoomBooking(it.title) && it.date.toString() == date && TriggerTime.formatHhMm(it.start) == start &&
            CalEvent.normaliseTitle(it.title) == CalEvent.normaliseTitle(title)
    }

    /**
     * The meeting [b] is for: by its occurrence key, else (the key changed with a sync id that came
     * or changed later) the same event row at the same date and start.
     */
    internal fun originalOf(b: BookingEntity, events: List<CalEvent>): CalEvent? =
        events.firstOrNull { it.occurrenceKey == b.occurrenceKey }
            ?: events.firstOrNull {
                it.eventId == b.originalEventId && !BookingRules.isRoomBooking(it.title) &&
                    it.date.toString() == b.eventDate && TriggerTime.formatHhMm(it.start) == b.start
            }

    /**
     * The user's live booking events among [unpaired] (none of the app's records is theirs), as
     * bookings with negative ids: they aren't in the database, and changes to them aren't recorded
     * (the calendar shows them next time). The original is the event of that title at that start,
     * when there is only one: with two, neither's people or warnings are given to the booking.
     */
    internal fun calendarOnly(
        unpaired: List<CalEvent>, events: List<CalEvent>, attendees: Map<Long, List<Attendee>>, rooms: List<String>, mine: Set<String>,
    ): List<BookingEntity> = unpaired
        .filter { !it.cancelled && !it.allDay && it.organizer?.trim()?.lowercase() in mine }
        .sortedBy { it.begin }
        .mapIndexed { i, e ->
            val title = BookingRules.originalTitle(e.title)
            val original = meetingsLike(title, e.date.toString(), TriggerTime.formatHhMm(e.start), events).singleOrNull()
            val on = attendees[e.eventId].orEmpty()
            val reply = RoomCover.roomReply(on, rooms)
            val told = toldOn(on, rooms, mine)
            BookingEntity(
                id = -(i + 1L),
                occurrenceKey = original?.occurrenceKey ?: "calendar:${e.occurrenceKey}",
                seriesKey = original?.seriesKey ?: e.seriesKey,
                originalEventId = original?.eventId ?: -1,
                originalTitle = title,
                eventDate = e.date.toString(),
                start = TriggerTime.formatHhMm(e.start),
                end = TriggerTime.formatHhMm(e.endTime),
                bookingTitle = e.title.trim(),
                room = reply?.first,
                notified = ListCodec.encode(told),
                state = BookingState.SAVED,
                roomReply = reply?.second ?: RoomReply.NO_ROOM,
                bookingSyncId = e.syncId,
                createdAt = 0,
                checkedAt = null,
            )
        }

    /**
     * What has changed since [b] was made: a declined room, the original moved, gone or relabelled.
     * [unclear]: a booking found in the calendar matches more than one meeting.
     */
    internal fun warnings(
        b: BookingEntity, original: CalEvent?, events: List<CalEvent>, labels: Map<String, List<String>>, canCheck: Boolean,
        unclear: Boolean = false,
    ): List<String> {
        val out = ArrayList<String>()
        when (b.roomReply) {
            RoomReply.DECLINED -> out += "The room declined: change the room, or delete the booking."
            RoomReply.NOT_FOUND -> out += "The booking isn't in your calendar at ${b.start} any more (deleted or moved in Outlook?)."
            RoomReply.NO_ROOM -> out += "The booking has no room any more (taken off in Outlook?): change the room, or delete the booking."
            else -> Unit
        }
        if (!canCheck) return out
        if (unclear) {
            out += "More than one “${b.originalTitle}” is at ${b.start}: which this booking is for isn't clear, so nobody from them is offered."
        } else if (original == null) {
            val sameDay = events.filter {
                it.date.toString() == b.eventDate && CalEvent.normaliseTitle(it.title) == CalEvent.normaliseTitle(b.originalTitle) && !it.cancelled
            }
            out += if (sameDay.isNotEmpty()) {
                "“${b.originalTitle}” now starts at ${sameDay.joinToString { TriggerTime.formatHhMm(it.start) }}: the booking is still for ${b.start}."
            } else {
                "“${b.originalTitle}” is no longer on ${b.eventDate} at ${b.start}."
            }
        } else {
            if (original.cancelled) out += "“${b.originalTitle}” was cancelled."
            if (TriggerTime.formatHhMm(original.endTime) != b.end) out += "“${b.originalTitle}” now ends at ${TriggerTime.formatHhMm(original.endTime)}."
            val cats = labels[original.labelKey]
            if (cats != null && EventParser.matchLabel(cats) == null) out += "“${b.originalTitle}” isn't labelled Moveable or Immoveable any more."
        }
        return out
    }

    /** The next free room in the user's order, other than the booking's own (it may have declined). */
    fun changeRoom(context: Context, booking: BookingEntity): String? =
        run(context, booking, "Changing the room") { nav, settings ->
            val others = settings.rooms.filterNot { r -> RoomChoice.sameRoom(booking.room, r) }
            nav.changeRoom(date(booking), start(booking), end(booking), booking.bookingTitle, booking.room, settings.copy(rooms = others))
        }

    fun editPeople(context: Context, booking: BookingEntity, keep: List<String>): String? {
        val current = ListCodec.decode(booking.notified)
        val add = keep.filter { it !in current }
        val remove = current.filter { it !in keep }
        if (add.isEmpty() && remove.isEmpty()) return "Nothing to change."
        return run(context, booking, "Updating the people", peopleAfter = keep) { nav, _ ->
            nav.editPeople(date(booking), start(booking), end(booking), booking.bookingTitle, booking.room, add, remove)
        }
    }

    fun delete(context: Context, booking: BookingEntity): String? =
        run(context, booking, "Deleting the booking") { nav, _ ->
            nav.deleteBooking(date(booking), start(booking), end(booking), booking.bookingTitle, booking.room)
        }

    /** A booking that isn't in the calendar any more: forget it (nothing to do in Outlook). */
    suspend fun forget(context: Context, booking: BookingEntity) = withContext(Dispatchers.IO) {
        BookingStore.get(context).markDeleted(booking.id)
        ScanLog.i("Booking ${booking.id} forgotten (${booking.originalTitle}, ${booking.eventDate})")
    }

    private fun run(
        context: Context,
        booking: BookingEntity,
        what: String,
        peopleAfter: List<String>? = null,
        action: suspend (BookingNavigator, RoomSettings) -> BookingOutcome,
    ): String? {
        if (job?.isActive == true) return "A change is already going."
        if (OutlookSession.isBusy) return OutlookBusy().message
        val service = OutlookReaderService.instance ?: return "Turn on the Outlook reader (Settings → Accessibility) first."
        val app = context.applicationContext
        Prefs.ensureLoaded(app)
        val settings = RoomSettings(Prefs.rooms.value, Prefs.building.value, Prefs.recentShortcut.value)
        _state.value = State.Running(booking.id, what, "Opening Outlook…")
        ScanLog.i("Manage bookings: $what for ${booking.originalTitle} ${booking.eventDate} ${booking.start}")
        job = AppScope.scope.launch {
            // Recorded inside the Outlook run, before it tidies up: STOP while it does (after Save
            // or the delete went through) then can't lose the record of what Outlook did.
            var recorded: String? = null
            val message = try {
                OutlookSession.run(service, "$what…", hideKeyboard = true, returnTo = OutlookSession.RETURN_MANAGE, onStop = { stop() }) { session ->
                    val navigator = OutlookNavigator(service, session.driver) { s ->
                        session.progress(s)
                        _state.update { if (it is State.Running) it.copy(step = s) else it }
                    }
                    val booker = BookingNavigator(navigator, service)
                    val outcome = action(booker, settings)
                    // Anyone Outlook didn't confirm was taken off again, so isn't recorded as told.
                    val people = peopleAfter?.filterNot { it.lowercase() in booker.notAdded }
                    recorded = withContext(NonCancellable) { apply(app, booking, outcome, people) } + booker.stopRun?.let { " $it" }.orEmpty()
                }
                recorded ?: "Nothing was done."
            } catch (e: CancellationException) {
                recorded ?: "Stopped; check the booking in Outlook."
            } catch (e: OutlookBusy) {
                e.message ?: "Outlook is busy."
            } catch (e: ScanFailure) {
                e.message ?: "Outlook couldn't be driven."
            } catch (e: Exception) {
                ScanLog.e("Manage bookings failed", e)
                recorded ?: "Something went wrong: ${e.message}"
            }
            withContext(NonCancellable) {
                _state.value = State.Finished(message)
                ScanLog.i("Manage bookings: $message")
            }
        }
        return null
    }

    /**
     * Records what the change did; the message for the user. A booking found only in the calendar
     * ([BookingEntity.id] ≤ 0) has no record to change: the calendar shows the change next time.
     */
    private suspend fun apply(context: Context, booking: BookingEntity, outcome: BookingOutcome, peopleAfter: List<String>?): String {
        val store = BookingStore.get(context)
        val recorded = booking.id > 0
        if (outcome is BookingOutcome.Booked || outcome is BookingOutcome.Changed || outcome is BookingOutcome.Uncertain) {
            val shadow = Shadow(System.currentTimeMillis() + SYNC_SHADOW_MS, deleted = outcome is BookingOutcome.Changed && outcome.what == "deleted")
            shadowKeys(booking).forEach { shadows[it] = shadow }
        }
        return when (outcome) {
            is BookingOutcome.Booked -> {
                if (recorded) store.setRoom(booking.id, outcome.room)
                "${booking.originalTitle}: now ${outcome.room}" + outcome.notes.joinToString("") { " ($it)" }
            }
            is BookingOutcome.NoRoom -> "${booking.originalTitle}: no other room in your list is free; the booking is unchanged."
            is BookingOutcome.Failed -> "${booking.originalTitle}: ${outcome.reason}. Nothing was changed."
            // Not recorded either way: the next reading of the calendar shows what Outlook did.
            is BookingOutcome.Uncertain -> "${booking.originalTitle}: ${outcome.reason}."
            is BookingOutcome.Changed -> when (outcome.what) {
                "deleted" -> {
                    // Only the booking goes: nothing about deleting it is remembered (the user's choice,
                    // 2026-10-06), so later runs offer the event again like any event without a room.
                    if (recorded) store.markDeleted(booking.id)
                    "${booking.originalTitle}: booking deleted; it will be offered again"
                }
                else -> {
                    if (recorded) peopleAfter?.let { store.setNotified(booking.id, it) }
                    "${booking.originalTitle}: ${outcome.what}"
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun clearMessage() {
        if (job?.isActive != true) _state.value = State.Idle
    }

    private fun date(b: BookingEntity): LocalDate = LocalDate.parse(b.eventDate)

    private fun start(b: BookingEntity): LocalTime = LocalTime.parse(b.start)

    private fun end(b: BookingEntity): LocalTime? = runCatching { LocalTime.parse(b.end) }.getOrNull()
}
