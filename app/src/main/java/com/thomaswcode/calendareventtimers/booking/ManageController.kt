package com.thomaswcode.calendareventtimers.booking

import android.content.Context
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.calendar.CalendarStore
import com.thomaswcode.calendareventtimers.data.AnswerEntity
import com.thomaswcode.calendareventtimers.data.AnswerKind
import com.thomaswcode.calendareventtimers.data.AutopilotDatabase
import com.thomaswcode.calendareventtimers.data.BookingEntity
import com.thomaswcode.calendareventtimers.data.BookingStore
import com.thomaswcode.calendareventtimers.data.ListCodec
import com.thomaswcode.calendareventtimers.data.RoomReply
import com.thomaswcode.calendareventtimers.domain.EventParser
import com.thomaswcode.calendareventtimers.domain.TriggerTime
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

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    /** The bookings from today on, with warnings, after reading the rooms' replies again. */
    suspend fun load(context: Context, zone: ZoneId = ZoneId.systemDefault()): List<ManagedBooking> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        Prefs.ensureLoaded(app)
        val store = BookingStore.get(app)
        runCatching { store.refreshReplies(Instant.now(), zone) }
        val bookings = store.savedFrom(LocalDate.now(zone))
        if (bookings.isEmpty()) return@withContext emptyList()
        val calendar = CalendarStore(app)
        val main = if (calendar.hasAccess()) calendar.mainCalendar() else null
        val dates = bookings.map { LocalDate.parse(it.eventDate) }
        val events = main?.let { calendar.occurrencesOn(it, dates.min(), dates.max(), zone) }.orEmpty()
        val attendees = calendar.attendees(bookings.map { it.originalEventId })
        val labels = AutopilotDatabase.get(app).labels().get(bookings.map { it.occurrenceKey.substringBeforeLast('@') }.distinct())
            .associate { it.labelKey to ListCodec.decode(it.categories) }
        val rooms = Prefs.rooms.value
        val mine = BookingController.myAddresses(main)
        val names = store.names(bookings.flatMap { ListCodec.decode(it.notified) })
        bookings.map { b ->
            val notified = ListCodec.decode(b.notified).map { Person(it, names[it]) }
            val original = events.firstOrNull { it.occurrenceKey == b.occurrenceKey }
            val offered = original?.let { People.toNotify(attendees[b.originalEventId].orEmpty(), it.organizer, mine, rooms) }.orEmpty()
            ManagedBooking(b, notified, (offered + notified).distinctBy { it.email }, warnings(b, original, events, labels, main != null))
        }
    }

    /** What has changed since [b] was made: a declined room, the original moved, gone or relabelled. */
    internal fun warnings(
        b: BookingEntity, original: CalEvent?, events: List<CalEvent>, labels: Map<String, List<String>>, canCheck: Boolean,
    ): List<String> {
        val out = ArrayList<String>()
        when (b.roomReply) {
            RoomReply.DECLINED -> out += "The room declined: change the room, or delete the booking."
            RoomReply.NOT_FOUND -> out += "The booking isn't in your calendar any more (deleted in Outlook?)."
            else -> Unit
        }
        if (!canCheck) return out
        if (original == null) {
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
            val others = settings.rooms.filterNot { r -> booking.room?.let { RoomChoice.matches(it, r) || it.equals(r, ignoreCase = true) } == true }
            nav.changeRoom(date(booking), start(booking), booking.bookingTitle, booking.room, settings.copy(rooms = others))
        }

    fun editPeople(context: Context, booking: BookingEntity, keep: List<String>): String? {
        val current = ListCodec.decode(booking.notified)
        val add = keep.filter { it !in current }
        val remove = current.filter { it !in keep }
        if (add.isEmpty() && remove.isEmpty()) return "Nothing to change."
        return run(context, booking, "Updating the people", peopleAfter = keep) { nav, _ ->
            nav.editPeople(date(booking), start(booking), booking.bookingTitle, booking.room, add, remove)
        }
    }

    fun delete(context: Context, booking: BookingEntity): String? =
        run(context, booking, "Deleting the booking") { nav, _ ->
            nav.deleteBooking(date(booking), start(booking), booking.bookingTitle, booking.room)
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
            var message: String
            try {
                val outcome = OutlookSession.run(service, "$what…", hideKeyboard = true, returnTo = OutlookSession.RETURN_MANAGE, onStop = { stop() }) { session ->
                    val navigator = OutlookNavigator(service, session.driver) { s ->
                        session.progress(s)
                        _state.update { if (it is State.Running) it.copy(step = s) else it }
                    }
                    action(BookingNavigator(navigator, service), settings)
                }
                message = withContext(NonCancellable) { apply(app, booking, outcome, peopleAfter) }
            } catch (e: CancellationException) {
                message = "Stopped; check the booking in Outlook."
            } catch (e: OutlookBusy) {
                message = e.message ?: "Outlook is busy."
            } catch (e: ScanFailure) {
                message = e.message ?: "Outlook couldn't be driven."
            } catch (e: Exception) {
                ScanLog.e("Manage bookings failed", e)
                message = "Something went wrong: ${e.message}"
            }
            withContext(NonCancellable) {
                _state.value = State.Finished(message)
                ScanLog.i("Manage bookings: $message")
            }
        }
        return null
    }

    /** Records what the change did; the message for the user. */
    private suspend fun apply(context: Context, booking: BookingEntity, outcome: BookingOutcome, peopleAfter: List<String>?): String {
        val store = BookingStore.get(context)
        return when (outcome) {
            is BookingOutcome.Booked -> {
                store.setRoom(booking.id, outcome.room)
                "${booking.originalTitle}: now ${outcome.room}" + outcome.notes.joinToString("") { " ($it)" }
            }
            is BookingOutcome.NoRoom -> "${booking.originalTitle}: no other room in your list is free; the booking is unchanged."
            is BookingOutcome.Failed -> "${booking.originalTitle}: ${outcome.reason}. Nothing was changed."
            is BookingOutcome.Changed -> when (outcome.what) {
                "deleted" -> {
                    store.markDeleted(booking.id)
                    // Deleted on purpose: later runs list it under "answered before" instead of asking again.
                    AutopilotDatabase.get(context).answers().put(
                        listOf(AnswerEntity(booking.occurrenceKey, booking.seriesKey, booking.eventDate, AnswerKind.NO_ROOM_WANTED, Instant.now().toEpochMilli())),
                    )
                    "${booking.originalTitle}: booking deleted"
                }
                else -> {
                    peopleAfter?.let { store.setNotified(booking.id, it) }
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
}
