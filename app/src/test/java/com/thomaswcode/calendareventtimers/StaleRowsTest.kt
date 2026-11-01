package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.booking.BookingPlanner
import com.thomaswcode.calendareventtimers.booking.RoomList
import com.thomaswcode.calendareventtimers.calendar.Attendee
import com.thomaswcode.calendareventtimers.calendar.CalEvent
import com.thomaswcode.calendareventtimers.engine.LabelTarget
import com.thomaswcode.calendareventtimers.engine.LabelTargets
import com.thomaswcode.calendareventtimers.outlook.LabelRead
import com.thomaswcode.calendareventtimers.outlook.LabelSlots
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rows of the phone's calendar that repeat an event Outlook shows once (2026-11-01): one meeting,
 * edited by its organiser, had four rows left from its old item as well as its real one, so the
 * Day view had one block for five rows.
 */
class StaleRowsTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val time = "17:00"
    private val start = LocalTime.of(17, 0)

    private fun row(id: Long, status: Int = Attendee.STATUS_TENTATIVE) = calEvent(id, "Team call", day, "17:00", "17:30", recurring = true, selfStatus = status)

    private val ghosts = listOf(row(101), row(102), row(103), row(104))
    private val real = row(205, Attendee.STATUS_ACCEPTED)
    private val rows = ghosts + real

    private fun target(e: CalEvent): LabelTarget = LabelTargets.target(e)
    private fun read(vararg labels: String) = LabelRead.Read("Team call", labels.toList())

    @Test
    fun oneBlockForFiveRowsIsAReadNotAProblem() {
        val r = LabelSlots.decide("Team call", time, 5, listOf(read("Immoveable")), emptyList(), null, allRead = true)
        assertEquals(read("Immoveable").copy(newestRows = 1), r)
        assertFalse((r as LabelRead.Read).remember)
    }

    @Test
    fun anythingUnaccountedForOnTheDayViewStaysAProblem() {
        // A block that couldn't be read, or wasn't looked at, may be one of the rows.
        val one = listOf(read("Immoveable"))
        assertTrue(LabelSlots.decide("Team call", time, 5, one, listOf("didn't open"), null, allRead = true) is LabelRead.Problem)
        assertTrue(LabelSlots.decide("Team call", time, 5, one, emptyList(), "opened another", allRead = true) is LabelRead.Problem)
        assertTrue(LabelSlots.decide("Team call", time, 5, one, emptyList(), null, allRead = false) is LabelRead.Problem)
    }

    @Test
    fun blocksThatDisagreeStayAProblemWhateverTheRows() {
        val r = LabelSlots.decide("Team call", time, 5, listOf(read("Moveable"), read("Immoveable")), emptyList(), null, allRead = true)
        assertTrue(r is LabelRead.Problem && r.message.contains("different labels"))
    }

    @Test
    fun asManyBlocksAsRowsIsTheOrdinaryRead() {
        val r = LabelSlots.decide("Team call", time, 2, listOf(read("Moveable"), read("Moveable")), emptyList(), null, allRead = true)
        assertEquals(read("Moveable"), r)
        assertTrue((r as LabelRead.Read).remember)
    }

    @Test
    fun theNewestRowGetsTheLabelsAndTheOthersAreStale() {
        val read = read("Immoveable").copy(newestRows = 1)
        val out = LabelSlots.assign(rows.map(::target), rows, read)
        assertEquals(5, out.size)
        assertEquals(read, out[target(real)])
        ghosts.forEach { assertTrue(out[target(it)] is LabelRead.Stale) }
    }

    @Test
    fun twoBlocksKeepTheTwoNewestRows() {
        val out = LabelSlots.assign(rows.map(::target), rows, read("Moveable").copy(newestRows = 2))
        assertEquals(setOf(target(real), target(ghosts.last())), out.filterValues { it is LabelRead.Read }.keys)
    }

    @Test
    fun aRowWithRememberedLabelsIsStaleToo() {
        // Only the newest row needed reading: the older one's cached labels mustn't offer it as well.
        val out = LabelSlots.assign(listOf(target(real)), listOf(ghosts.first(), real), read("Moveable").copy(newestRows = 1))
        assertTrue(out[target(ghosts.first())] is LabelRead.Stale)
        assertTrue(out[target(real)] is LabelRead.Read)
    }

    @Test
    fun whenTheRealRowIsKnownEveryTargetIsStale() {
        // The real row's labels came from the cache, so only stale rows were read.
        val out = LabelSlots.assign(ghosts.map(::target), rows, read("Moveable").copy(newestRows = 1))
        ghosts.forEach { assertTrue(out[target(it)] is LabelRead.Stale) }
    }

    @Test
    fun anOrdinaryReadOrProblemGoesToItsTargetsAlone() {
        val group = listOf(target(ghosts[0]), target(ghosts[1]))
        assertEquals(group.associateWith { read("Moveable") }, LabelSlots.assign(group, rows, read("Moveable")))
        val problem = LabelRead.Problem("didn't open")
        assertEquals(group.associateWith { problem }, LabelSlots.assign(group, rows, problem))
    }

    @Test
    fun readsFromAStaleSlotAreNotRemembered() {
        val out = LabelSlots.assign(rows.map(::target), rows, read("Moveable").copy(newestRows = 1))
        assertTrue(LabelSlots.toRemember(out).isEmpty())
        val plain = target(row(300))
        assertEquals(mapOf(plain to listOf("Moveable")), LabelSlots.toRemember(mapOf(plain to read("Moveable"))))
    }

    @Test
    fun staleRowsAreNotReadAgainElsewhere() {
        val later = calEvent(101, "Team call", day.plusDays(7), "17:00", "17:30", recurring = true)
        val out = LabelSlots.assign(rows.map(::target), rows, read("Moveable").copy(newestRows = 1))
        assertTrue(LabelTargets.retry(rows + later, emptySet(), out.keys, emptyList()).isEmpty())
    }

    /** Room booking offers one candidate per row: the reason stale rows must go, not just share labels. */
    @Test
    fun oneMeetingIsOfferedOnce() {
        val now = Instant.parse("2026-10-11T18:00:00Z")
        val labels = rows.associate { it.labelKey to listOf("Moveable") }
        fun planned(events: List<CalEvent>) = BookingPlanner.plan(
            BookingPlanner.Input(
                events, labels, emptyMap(), emptyMap(), emptyMap(), emptyMap(), RoomList.DEFAULT, setOf("me@example.com"), setOf(day), now, ZoneId.of("Europe/London"),
            ),
        ).toAsk
        assertEquals(5, planned(rows).size)

        val out = LabelSlots.assign(rows.map(::target), rows, read("Moveable").copy(newestRows = 1))
        val stale = LabelSlots.staleRows(out)
        assertEquals(listOf(real.eventId), planned(rows.filterNot { it.rowKey in stale }).map { it.event.eventId })
    }

    @Test
    fun copiesSharingASyncIdAreToldApartByTheirRows() {
        // Nothing makes a sync id unique, so they can share an occurrence key too.
        val old = calEvent(101, "Team call", day, "17:00", "17:30", syncId = "shared", recurring = true)
        val new = calEvent(205, "Team call", day, "17:00", "17:30", syncId = "shared", recurring = true)
        assertEquals(old.occurrenceKey, new.occurrenceKey)

        val out = LabelSlots.assign(listOf(target(old)), listOf(old, new), read("Moveable").copy(newestRows = 1))
        assertEquals(setOf(old.rowKey), LabelSlots.staleRows(out))
        assertEquals(setOf(target(new)), out.filterValues { it is LabelRead.Read }.keys)
    }

    private fun block(title: String) = start to "Monday 12 October, 17:00 to 17:30, $title"

    @Test
    fun aBlockStandsForOneEvent() {
        val (notInOutlook, notInProvider) = LabelSlots.unpaired(rows, listOf(block("Team call")))
        assertEquals(4, notInOutlook.size)
        assertTrue(notInProvider.isEmpty())
    }

    @Test
    fun aRoomBookingKeepsItsOwnBlock() {
        // The short title is listed first and is mentioned by both blocks.
        val call = calEvent(1, "Team call", day, "17:00", "17:30")
        val booking = calEvent(2, "Room Booking - Team call", day, "17:00", "17:30")
        val blocks = listOf(block("Room Booking - Team call"), block("Team call"))
        assertEquals(emptyList<CalEvent>() to emptyList<Pair<LocalTime, String>>(), LabelSlots.unpaired(listOf(call, booking), blocks))
    }

    @Test
    fun whatOnlyOneSideHasIsReported() {
        val call = calEvent(1, "Team call", day, "17:00", "17:30")
        val other = block("Lunch")
        assertEquals(listOf(call) to listOf(other), LabelSlots.unpaired(listOf(call), listOf(other)))
    }
}
