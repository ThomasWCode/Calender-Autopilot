package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.booking.Answers
import com.thomaswcode.calendareventtimers.booking.BookingPlanner
import com.thomaswcode.calendareventtimers.booking.BookingRange
import com.thomaswcode.calendareventtimers.booking.BookingRanges
import com.thomaswcode.calendareventtimers.booking.Cover
import com.thomaswcode.calendareventtimers.booking.DescriptionText
import com.thomaswcode.calendareventtimers.booking.FormText
import com.thomaswcode.calendareventtimers.booking.KnownBooking
import com.thomaswcode.calendareventtimers.booking.People
import com.thomaswcode.calendareventtimers.booking.RoomChoice
import com.thomaswcode.calendareventtimers.booking.RoomCover
import com.thomaswcode.calendareventtimers.booking.RoomDecision
import com.thomaswcode.calendareventtimers.booking.RoomList
import com.thomaswcode.calendareventtimers.booking.RoomRow
import com.thomaswcode.calendareventtimers.booking.RoomStatus
import com.thomaswcode.calendareventtimers.booking.SeriesAnswers
import com.thomaswcode.calendareventtimers.booking.Wheel
import com.thomaswcode.calendareventtimers.calendar.Attendee
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.data.AnswerKind
import com.thomaswcode.calendareventtimers.data.RoomReply
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val london: ZoneId = ZoneId.of("Europe/London")

/** A provider occurrence on [date] from [from] to [to] ("HH:mm"). */
fun calEvent(
    id: Long,
    title: String,
    date: LocalDate,
    from: String,
    to: String,
    syncId: String = "sync-$id",
    recurring: Boolean = false,
    location: String? = null,
    organizer: String? = "eiderwhi@lshtm.ac.uk",
    selfStatus: Int = Attendee.STATUS_NONE,
    cancelled: Boolean = false,
    allDay: Boolean = false,
): CalEvent {
    val s = LocalTime.parse(from)
    val e = LocalTime.parse(to)
    val begin = ZonedDateTime.of(date, s, london).toInstant()
    val endDate = if (e == LocalTime.MIDNIGHT) date.plusDays(1) else date
    return CalEvent(
        eventId = id, syncId = syncId, changeKey = "ck-$id", originalSyncId = null, recurring = recurring, title = title,
        begin = begin, end = ZonedDateTime.of(endDate, e, london).toInstant(), date = date, start = s, endDate = endDate, endTime = e,
        allDay = allDay, location = location, organizer = organizer, selfStatus = selfStatus, cancelled = cancelled,
    )
}

fun person(name: String?, email: String, type: Int = Attendee.TYPE_REQUIRED, status: Int = Attendee.STATUS_ACCEPTED) =
    Attendee(name, email, type, status)

fun room(name: String, status: Int = Attendee.STATUS_ACCEPTED) = Attendee(name, "${name.lowercase()}@lshtm.ac.uk", Attendee.TYPE_RESOURCE, status)

class BookingRangesTest {
    private val mon5 = LocalDate.of(2026, 10, 5)

    @Test
    fun nextWeekIsTheComingMondayToFriday() {
        val expected = (12..16).map { LocalDate.of(2026, 10, it) }
        assertEquals(expected, BookingRanges.days(BookingRange.NEXT_WEEK, mon5))
        assertEquals(expected, BookingRanges.days(BookingRange.NEXT_WEEK, LocalDate.of(2026, 10, 9)))
        assertEquals("on Saturday", expected, BookingRanges.days(BookingRange.NEXT_WEEK, LocalDate.of(2026, 10, 10)))
        assertEquals("on Sunday, from tomorrow", expected, BookingRanges.days(BookingRange.NEXT_WEEK, LocalDate.of(2026, 10, 11)))
        // The week the clocks go back (Sun 25 Oct) is still Mon–Fri.
        assertEquals((26..30).map { LocalDate.of(2026, 10, it) }, BookingRanges.days(BookingRange.NEXT_WEEK, LocalDate.of(2026, 10, 23)))
    }

    @Test
    fun todayTomorrowAndThisWeek() {
        assertEquals(listOf(mon5), BookingRanges.days(BookingRange.TODAY, mon5))
        assertEquals(listOf(LocalDate.of(2026, 10, 6)), BookingRanges.days(BookingRange.TOMORROW, mon5))
        assertEquals((7..9).map { LocalDate.of(2026, 10, it) }, BookingRanges.days(BookingRange.THIS_WEEK, LocalDate.of(2026, 10, 7)))
        val friday = LocalDate.of(2026, 10, 9)
        assertTrue(BookingRanges.days(BookingRange.TOMORROW, friday).isEmpty())
        assertEquals("Tomorrow is a Saturday", BookingRanges.unavailable(BookingRange.TOMORROW, friday))
        val saturday = LocalDate.of(2026, 10, 10)
        assertTrue(BookingRanges.days(BookingRange.TODAY, saturday).isEmpty())
        assertTrue(BookingRanges.days(BookingRange.THIS_WEEK, saturday).isEmpty())
        assertEquals("No working days left this week", BookingRanges.unavailable(BookingRange.THIS_WEEK, saturday))
        assertNull(BookingRanges.unavailable(BookingRange.TODAY, mon5))
    }

    @Test
    fun describesTheDays() {
        assertEquals("Mon 12 – Fri 16 October", BookingRanges.describe((12..16).map { LocalDate.of(2026, 10, it) }))
        assertEquals("Mon 28 September – Fri 2 October", BookingRanges.describe(listOf(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 2))))
        assertEquals("Tue 6 October", BookingRanges.describe(listOf(LocalDate.of(2026, 10, 6))))
    }
}

class RoomsTest {
    @Test
    fun editingTheList() {
        val rooms = listOf("KS-103D", "KS-117", "KS-119a")
        assertEquals(listOf("KS-103D", "KS-117", "KS-119a", "KS-999"), RoomList.add(rooms, "  KS-999 ").getOrThrow())
        assertTrue(RoomList.add(rooms, "ks-117").isFailure)
        assertTrue(RoomList.add(rooms, "   ").isFailure)
        assertEquals(listOf("KS-103D", "KS-119a"), RoomList.remove(rooms, 1))
        assertEquals(listOf("KS-117", "KS-103D", "KS-119a"), RoomList.move(rooms, 1, -1))
        assertEquals(listOf("KS-103D", "KS-119a", "KS-117"), RoomList.move(rooms, 1, +5))
        assertEquals(rooms, RoomList.move(rooms, 0, -1))
        assertEquals(25, RoomList.DEFAULT.size)
    }

    @Test
    fun roomFinderNames() {
        assertTrue(RoomChoice.matches("KS-106 (EPH staff only)", "KS-106"))
        assertTrue(RoomChoice.matches("ks-121", "KS-121"))
        assertFalse(RoomChoice.matches("KS-G32A", "KS-G32"))
        assertFalse(RoomChoice.matches("KS-1210", "KS-121"))
        assertEquals(RoomStatus.FREE, RoomChoice.status(" Free "))
        assertEquals(RoomStatus.BUSY, RoomChoice.status("Busy"))
        assertEquals(RoomStatus.UNKNOWN, RoomChoice.status("Loading…"))
        assertEquals(RoomStatus.UNKNOWN, RoomChoice.status(null))
    }

    @Test
    fun firstFreeRoomInTheUsersOrder() {
        val prefs = listOf("KS-121", "KS-103D", "KS-117")
        val page1 = listOf(
            RoomRow("KS-103D", RoomStatus.FREE), RoomRow("KS-105c (EPH only)", RoomStatus.BUSY),
            RoomRow("KS-117", RoomStatus.FREE), RoomRow("KS-121", RoomStatus.BUSY),
        )
        assertEquals(RoomDecision.Chosen("KS-103D", "KS-103D"), RoomChoice.decide(prefs, page1, endReached = false))
        // The first choice not seen yet: scroll on before deciding.
        assertEquals(RoomDecision.NeedMore, RoomChoice.decide(listOf("KS-G19", "KS-103D"), page1, endReached = false))
        // A room whose status isn't shown counts as busy.
        val loading = listOf(RoomRow("KS-121", RoomStatus.UNKNOWN), RoomRow("KS-103D", RoomStatus.FREE))
        assertEquals(RoomDecision.Chosen("KS-103D", "KS-103D"), RoomChoice.decide(prefs, loading, endReached = true))
    }

    @Test
    fun noneFreeAndRoomsRoomFinderDoesntList() {
        val all = listOf(RoomRow("KS-121", RoomStatus.BUSY), RoomRow("KS-117", RoomStatus.BUSY))
        assertEquals(RoomDecision.NoneFree(listOf("KS-999")), RoomChoice.decide(listOf("KS-121", "KS-999", "KS-117"), all, endReached = true))
    }

    @Test
    fun recentListOnlyForTheFirstChoice() {
        val recent = listOf(RoomRow("KS-117", RoomStatus.FREE), RoomRow("KS-103D", RoomStatus.FREE))
        assertEquals(RoomRow("KS-103D", RoomStatus.FREE), RoomChoice.recentShortcut(listOf("KS-103D", "KS-117"), recent))
        // KS-117 is free in Recent, but whether KS-103D is free isn't known from Recent.
        assertNull(RoomChoice.recentShortcut(listOf("KS-121", "KS-117"), recent))
    }

    @Test
    fun roomsNamedInText() {
        val rooms = RoomList.DEFAULT
        assertEquals("KS-103D", RoomChoice.roomIn("KS-103D", rooms))
        assertEquals("KS-121", RoomChoice.roomIn("https://lshtm.zoom.us/j/868?pwd=x; KS-121", rooms))
        assertEquals("KS-207A", RoomChoice.roomIn("ks-207a@lshtm.ac.uk", rooms))
        assertNull(RoomChoice.roomIn("Hybrid", rooms))
        assertNull(RoomChoice.roomIn("London School of Hygiene and Tropical Medicine; Keppel St; London; WC1E 7HT", rooms))
        assertNull(RoomChoice.roomIn(null, rooms))
    }
}

class PeopleTest {
    private val me = setOf("eiderwhi@lshtm.ac.uk")

    @Test
    fun onlyLshtmPeople() {
        val people = People.toNotify(
            listOf(
                person("Rebecca Clark", "Rebecca.Clark@lshtm.ac.uk"),
                person("Tom Sumner", "Tom.Sumner@LSHTM.ac.uk"),
                person("Hannah McGregor", "Hannah.McGregor1@student.lshtm.ac.uk", status = Attendee.STATUS_INVITED),
                person("Hon Person", "hon.person@hon.lshtm.ac.uk"),
                person("Old Student", "old@alumni.lshtm.ac.uk", type = Attendee.TYPE_OPTIONAL),
                person("TB list", "tb-group@lists.lshtm.ac.uk"),
                person("Outside", "someone@gmail.com"),
                person("Bu", "aportnoy@bu.edu"),
                room("KS-121"),
                person(null, "eiderwhi@lshtm.ac.uk", type = Attendee.TYPE_OPTIONAL),
                person("Declined Person", "declined@lshtm.ac.uk", status = Attendee.STATUS_DECLINED),
                Attendee("KS-103D", "KS-103D@lshtm.ac.uk", Attendee.TYPE_REQUIRED, Attendee.STATUS_ACCEPTED),
            ),
            organizer = "eiderwhi@lshtm.ac.uk", myAddresses = me, rooms = RoomList.DEFAULT,
        )
        assertEquals(
            listOf("hannah.mcgregor1@student.lshtm.ac.uk", "hon.person@hon.lshtm.ac.uk", "old@alumni.lshtm.ac.uk", "rebecca.clark@lshtm.ac.uk", "tom.sumner@lshtm.ac.uk"),
            people.map { it.email },
        )
        assertEquals("Hannah McGregor", people.first().display)
    }

    @Test
    fun organiserIncludedOnceWithAName() {
        val people = People.toNotify(
            listOf(person(null, "kristian.godfrey@lshtm.ac.uk"), person("Kristian Godfrey", "Kristian.Godfrey@lshtm.ac.uk")),
            organizer = "Kristian.Godfrey@lshtm.ac.uk", myAddresses = me, rooms = RoomList.DEFAULT,
        )
        assertEquals(listOf("Kristian Godfrey"), people.map { it.display })

        val organiserOnly = People.toNotify(emptyList(), "Finn.McQuaid@lshtm.ac.uk", me, RoomList.DEFAULT)
        assertEquals(listOf("finn.mcquaid@lshtm.ac.uk"), organiserOnly.map { it.email })
        // Learnt addresses of the user's count too.
        assertTrue(People.toNotify(emptyList(), "Richard.White@lshtm.ac.uk", me + "richard.white@lshtm.ac.uk", RoomList.DEFAULT).isEmpty())
    }
}

class RoomCoverTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val rooms = RoomList.DEFAULT
    private val meeting = calEvent(1, "TB Vx modelling 2-weekly call", day, "14:05", "15:00", location = "https://lshtm.zoom.us/j/1")
    private val none = { _: CalEvent -> emptyList<Attendee>() }
    private val me = setOf("eiderwhi@lshtm.ac.uk")

    @Test
    fun appBookingStillHoldingTheRoom() {
        assertEquals(
            Cover.AppBooking("KS-121", RoomReply.WAITING),
            RoomCover.cover(meeting, emptyList(), KnownBooking("KS-121", RoomReply.WAITING), emptyList(), none, rooms, me),
        )
        // Declined or gone: offered again.
        assertNull(RoomCover.cover(meeting, emptyList(), KnownBooking("KS-121", RoomReply.DECLINED), emptyList(), none, rooms, me))
        assertNull(RoomCover.cover(meeting, emptyList(), KnownBooking("KS-121", RoomReply.NOT_FOUND), emptyList(), none, rooms, me))
    }

    @Test
    fun appBookingFoundInTheCalendar() {
        val booking = calEvent(2, "Room Booking - TB Vx modelling 2-weekly call", day, "14:05", "15:00")
        val attendees = mapOf(2L to listOf(room("KS-117", Attendee.STATUS_TENTATIVE)))
        val cover = RoomCover.cover(meeting, emptyList(), null, listOf(meeting, booking), { attendees[it.eventId].orEmpty() }, rooms, me)
        assertEquals(Cover.AppBooking("KS-117", RoomReply.TENTATIVE), cover)
    }

    @Test
    fun roomOnTheEventItself() {
        assertEquals(Cover.RoomOnEvent("KS-207A"), RoomCover.cover(meeting, listOf(room("KS-207A", Attendee.STATUS_NONE)), null, emptyList(), none, rooms, me))
        assertNull("a room that declined", RoomCover.cover(meeting, listOf(room("KS-207A", Attendee.STATUS_DECLINED)), null, emptyList(), none, rooms, me))
        val inKs103d = meeting.copy(location = "KS-103D")
        assertEquals(Cover.LocationNamesRoom("KS-103D"), RoomCover.cover(inKs103d, emptyList(), null, emptyList(), none, rooms, me))
    }

    @Test
    fun anotherEventWithTheRoom() {
        // The user's manual booking: a "call" with KS-121 at the same time.
        val call = calEvent(3, "call", day, "14:05", "15:00", location = "KS-121")
        val attendees = mapOf(3L to listOf(room("KS-121")))
        assertEquals(
            Cover.OtherEvent("call", "KS-121"),
            RoomCover.cover(meeting, emptyList(), null, listOf(meeting, call), { attendees[it.eventId].orEmpty() }, rooms, me),
        )
        // Not yet accepted by the room: doesn't count.
        val pending = mapOf(3L to listOf(room("KS-121", Attendee.STATUS_INVITED)))
        assertNull(RoomCover.cover(meeting, emptyList(), null, listOf(meeting, call), { pending[it.eventId].orEmpty() }, rooms, me))
    }

    @Test
    fun aColleaguesEventInARoomDoesntCount() {
        val seminar = calEvent(4, "Seminar", day, "14:00", "16:00", organizer = "someone.else@lshtm.ac.uk")
        val attendees = mapOf(4L to listOf(room("KS-G19")))
        val of = { e: CalEvent -> attendees[e.eventId].orEmpty() }
        assertNull(RoomCover.cover(meeting, emptyList(), null, listOf(meeting, seminar), of, rooms, me))
        assertNull(RoomCover.partial(meeting, listOf(meeting, seminar), of, rooms, london, me))
    }

    @Test
    fun partOfTheTimeIsANoteOnly() {
        val short = calEvent(3, "call", day, "14:05", "14:30")
        val attendees = mapOf(3L to listOf(room("KS-121")))
        val of = { e: CalEvent -> attendees[e.eventId].orEmpty() }
        assertNull(RoomCover.cover(meeting, emptyList(), null, listOf(meeting, short), of, rooms, me))
        val partial = RoomCover.partial(meeting, listOf(meeting, short), of, rooms, london, me)!!
        assertEquals("KS-121", partial.room)
        assertEquals(LocalTime.of(14, 5), partial.from)
        assertEquals(LocalTime.of(14, 30), partial.to)
    }
}

class BookingPlannerTest {
    private val mon = LocalDate.of(2026, 10, 12)
    private val tue = LocalDate.of(2026, 10, 13)
    private val now = ZonedDateTime.of(2026, 10, 11, 18, 0, 0, 0, london).toInstant()

    private fun input(
        events: List<CalEvent>,
        labels: Map<String, List<String>>,
        attendees: Map<Long, List<Attendee>> = emptyMap(),
        known: Map<String, KnownBooking> = emptyMap(),
        answers: Map<String, AnswerKind> = emptyMap(),
        memory: Map<String, SeriesAnswers> = emptyMap(),
        at: Instant = now,
    ) = BookingPlanner.Input(events, labels, attendees, known, answers, memory, RoomList.DEFAULT, setOf("eiderwhi@lshtm.ac.uk"), setOf(mon, tue), at, london)

    @Test
    fun asksAboutLabelledEventsWithoutARoom() {
        val planning = calEvent(1, "Planning meeting", mon, "09:00", "09:30")
        val lunch = calEvent(2, "Lunch", mon, "12:00", "13:00")
        val modelling = calEvent(3, "Weekly modelling", tue, "11:05", "12:00", recurring = true)
        val booked = calEvent(4, "Has KS-121", tue, "14:05", "15:00")
        val call = calEvent(5, "call", tue, "14:05", "15:00")
        val unread = calEvent(6, "Not read", tue, "16:00", "16:30")
        val allDay = calEvent(7, "SAGE", mon, "00:00", "00:00", allDay = true)
        val bookingEvent = calEvent(8, "Room Booking - Old", tue, "10:00", "10:30")
        val friday = calEvent(9, "Friday thing", LocalDate.of(2026, 10, 16), "10:00", "11:00")
        val out = BookingPlanner.plan(
            input(
                listOf(planning, lunch, modelling, booked, call, unread, allDay, bookingEvent, friday),
                labels = mapOf(
                    planning.labelKey to listOf("Immoveable"), lunch.labelKey to emptyList(), modelling.labelKey to listOf("Urgent", "Moveable"),
                    booked.labelKey to listOf("Moveable"), call.labelKey to emptyList(), allDay.labelKey to listOf("Moveable"),
                    bookingEvent.labelKey to listOf("Moveable"), friday.labelKey to listOf("Moveable"),
                ),
                attendees = mapOf(5L to listOf(room("KS-121")), 1L to listOf(person("Person A", "person.a@lshtm.ac.uk"))),
            ),
        )
        assertEquals(listOf("Planning meeting", "Weekly modelling"), out.toAsk.map { it.event.title })
        // Events that already have a room are not shown at all.
        assertEquals(listOf("Has KS-121"), out.covered.map { it.first.title })
        // Lunch and the manual "call" booking have no target label.
        assertEquals(2, out.unlabelled)
        assertEquals(listOf("Not read"), out.labelUnknown.map { it.title })
        val first = out.toAsk.first()
        assertEquals("Immoveable", first.label)
        assertEquals(Answers(bookRoom = true, notify = false), first.defaults)
        assertFalse(first.remembered)
        assertEquals(listOf("person.a@lshtm.ac.uk"), first.people.map { it.email })
        assertTrue(first.notifyList(first.defaults).isEmpty())
        assertEquals(1, first.notifyList(first.defaults.copy(notify = true)).size)
    }

    @Test
    fun rememberedAnswersAndEarlierRuns() {
        val weekly = calEvent(1, "Weekly modelling", mon, "11:05", "12:00", recurring = true)
        val again = calEvent(2, "Stand-up", tue, "09:00", "09:15")
        val retry = calEvent(3, "No room last time", tue, "10:00", "11:00")
        val labels = listOf(weekly, again, retry).associate { it.labelKey to listOf("Moveable") }
        val out = BookingPlanner.plan(
            input(
                listOf(weekly, again, retry), labels,
                attendees = mapOf(1L to listOf(person("Person A", "a@lshtm.ac.uk"), person("Person B", "b@lshtm.ac.uk"))),
                answers = mapOf(again.occurrenceKey to AnswerKind.NO_ROOM_WANTED, retry.occurrenceKey to AnswerKind.NO_ROOM_FREE),
                memory = mapOf(weekly.seriesKey to SeriesAnswers(bookRoom = true, notify = true, removed = setOf("b@lshtm.ac.uk", "gone@lshtm.ac.uk"))),
            ),
        )
        assertEquals(listOf("Weekly modelling", "No room last time"), out.toAsk.map { it.event.title })
        val w = out.toAsk.first()
        assertTrue(w.remembered)
        assertEquals(Answers(bookRoom = true, notify = true, removed = setOf("b@lshtm.ac.uk")), w.defaults)
        assertEquals(listOf("a@lshtm.ac.uk"), w.notifyList(w.defaults).map { it.email })
        // Said no before: in the summary, not asked again.
        assertEquals(listOf("Stand-up"), out.answeredBefore.map { it.event.title })
        assertFalse(out.answeredBefore.single().defaults.bookRoom)
    }

    @Test
    fun aDeclinedBookingIsOfferedAgainWithYes() {
        val e = calEvent(1, "Team call", mon, "10:00", "11:00", recurring = true)
        val out = BookingPlanner.plan(
            input(
                listOf(e), mapOf(e.labelKey to listOf("Moveable")),
                known = mapOf(e.occurrenceKey to KnownBooking("KS-121", RoomReply.DECLINED)),
                answers = mapOf(e.occurrenceKey to AnswerKind.BOOKED),
                memory = mapOf(e.seriesKey to SeriesAnswers(bookRoom = false, notify = false, removed = emptySet())),
            ),
        )
        val c = out.toAsk.single()
        assertTrue(c.roomDeclined)
        assertTrue(c.defaults.bookRoom)
    }

    @Test
    fun startedEventsAreLeftOut() {
        val e = calEvent(1, "Earlier", mon, "09:00", "10:00")
        val later = ZonedDateTime.of(2026, 10, 12, 9, 30, 0, 0, london).toInstant()
        assertTrue(BookingPlanner.plan(input(listOf(e), mapOf(e.labelKey to listOf("Moveable")), at = later)).toAsk.isEmpty())
    }

    @Test
    fun timeEstimate() {
        assertEquals("no time", BookingPlanner.describeSeconds(0))
        assertEquals("about 25 s", BookingPlanner.describeSeconds(22))
        assertEquals("about 2 min", BookingPlanner.describeSeconds(100))
    }
}

class FormTextTest {
    private val near = LocalDate.of(2026, 10, 5)

    @Test
    fun wheelSteps() {
        assertEquals(6, Wheel.steps(8, 14, 24))
        assertEquals(2, Wheel.steps(23, 1, 24))
        assertEquals(-2, Wheel.steps(1, 23, 24))
        assertEquals(30, Wheel.steps(5, 35, 60))
        assertEquals(15, Wheel.steps(50, 5, 60))
        assertEquals(-10, Wheel.steps(15, 5, 60))
        assertEquals(-3, Wheel.steps(5, 2, 10, wraps = false))
        assertEquals(0, Wheel.steps(7, 7, 24))
    }

    @Test
    fun formDates() {
        assertEquals(LocalDate.of(2026, 10, 12), FormText.parseDate("Mon 12 Oct", near))
        assertEquals(LocalDate.of(2026, 10, 18), FormText.parseDate("Sun 18 Oct", near))
        assertEquals(LocalDate.of(2026, 9, 29), FormText.parseDate("Tue 29 Sept", near))
        assertEquals(LocalDate.of(2027, 1, 4), FormText.parseDate("Mon 4 Jan", LocalDate.of(2026, 12, 20)))
        assertNull(FormText.parseDate("Next Monday", near))
        assertEquals("Mon 12 Oct", FormText.formDate(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun formTimes() {
        assertEquals(LocalTime.of(9, 5) to LocalTime.of(10, 0), FormText.parseTimes("09:05 ▸ 10:00"))
        assertEquals(LocalTime.of(8, 5) to LocalTime.of(9, 0), FormText.parseTimes("08:05 � 09:00"))
        assertNull(FormText.parseTimes("Duration: 55 minutes"))
        assertEquals(8, FormText.parseNumber("8"))
        assertEquals(5, FormText.parseNumber("05"))
        assertNull(FormText.parseNumber("Mon 12 Oct"))
    }
}

class DescriptionTextTest {
    private val word = """
        <html xmlns:v="urn:schemas-microsoft-com:vml"><head><meta http-equiv="Content-Type" content="text/html; charset=utf-8">
        <style>p.MsoNormal { margin: 0 }</style><!--[if gte mso 9]><xml><o:shapedefaults v:ext="edit" spidmax="1026" /></xml><![endif]--></head>
        <body lang="EN-GB"><div class="WordSection1">
        <p class="MsoNormal">Please can we move this to a new time? I have a clash.<o:p></o:p></p>
        <p class="MsoNormal"><o:p>&nbsp;</o:p></p>
        <p class="MsoNormal">Join Zoom Meeting<o:p></o:p></p>
        <p class="MsoNormal"><a href="https://lshtm.zoom.us/j/93641465569">https://lshtm.zoom.us/j/93641465569</a></p>
        <p class="MsoNormal">Fish &amp; chips &#8211; &lt;tbc&gt;</p>
        </div></body></html>
    """.trimIndent()

    @Test
    fun wordHtmlToPlainText() {
        assertTrue(DescriptionText.isHtml(word))
        val plain = DescriptionText.plain(word)
        assertEquals(
            "Please can we move this to a new time? I have a clash.\n\nJoin Zoom Meeting\nhttps://lshtm.zoom.us/j/93641465569\nFish & chips – <tbc>",
            plain,
        )
        val clean = DescriptionText.cleanHtml(word)
        assertFalse(clean.contains("MsoNormal {") || clean.contains("shapedefaults") || clean.contains("<head"))
        assertTrue(clean.contains("<a href=\"https://lshtm.zoom.us/j/93641465569\">"))
    }

    @Test
    fun emptyBodies() {
        assertTrue(DescriptionText.isEmpty(null))
        assertTrue(DescriptionText.isEmpty("<html><body><p class=\"MsoNormal\"><o:p>&nbsp;</o:p></p></body></html>"))
        assertFalse(DescriptionText.isEmpty("Agenda attached"))
        assertEquals("Agenda attached", DescriptionText.plain(" Agenda attached "))
    }
}
