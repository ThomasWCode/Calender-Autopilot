package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.booking.FormText
import com.thomaswcode.calendareventtimers.booking.RoomChoice
import com.thomaswcode.calendareventtimers.booking.RoomDecision
import com.thomaswcode.calendareventtimers.booking.RoomList
import com.thomaswcode.calendareventtimers.booking.RoomRow
import com.thomaswcode.calendareventtimers.booking.RoomStatus
import com.thomaswcode.calendareventtimers.domain.cleanUiText
import com.thomaswcode.calendareventtimers.outlook.AlertSheetReader
import com.thomaswcode.calendareventtimers.outlook.BookingScreens
import com.thomaswcode.calendareventtimers.outlook.Box
import com.thomaswcode.calendareventtimers.outlook.DescriptionReader
import com.thomaswcode.calendareventtimers.outlook.DetailsReader
import com.thomaswcode.calendareventtimers.outlook.EventFormReader
import com.thomaswcode.calendareventtimers.outlook.LocationReader
import com.thomaswcode.calendareventtimers.outlook.PeopleReader
import com.thomaswcode.calendareventtimers.outlook.PromptReader
import com.thomaswcode.calendareventtimers.outlook.RoomFinderReader
import com.thomaswcode.calendareventtimers.outlook.TimePickerReader
import com.thomaswcode.calendareventtimers.outlook.calendarOnlyDump
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The booking screens' readers against the dumps captured on 2026-10-05 (reference/room_booking). */
class EventFormReaderTest {
    @Test
    fun newEventForm() {
        val form = Fixtures.load("booking/new_event_form.xml")
        assertTrue(EventFormReader.isForm(form))
        assertFalse(EventFormReader.isEdit(form))
        assertEquals("richard.white@lshtm.ac.uk", EventFormReader.accountAddress(form))
        assertEquals("", EventFormReader.title(form))
        assertEquals("People", EventFormReader.peopleRow(form)!!.label)
        assertEquals("Mon 12 Oct", EventFormReader.dateRow(form)!!.value)
        assertEquals(LocalTime.of(8, 5) to LocalTime.of(9, 0), FormText.parseTimes(EventFormReader.timeRow(form)!!.value))
        assertEquals("Location", EventFormReader.locationRow(form)!!.label)
        assertNull("still the placeholder", EventFormReader.location(form))
        assertEquals("Description", EventFormReader.descriptionRow(form)!!.label)
        assertEquals("15 minutes before", EventFormReader.alertRow(form)!!.value)
        assertNotNull(EventFormReader.saveButton(form))
        assertNotNull(EventFormReader.cancelButton(form))
        assertNull(EventFormReader.deleteRow(form))
    }

    @Test
    fun formWithARoom() {
        val form = Fixtures.load("booking/new_event_form_with_room.xml")
        // Once set, the Location row shows the room instead of its label.
        assertEquals("KS-103D", EventFormReader.location(form))
        assertEquals("Description", EventFormReader.descriptionRow(form)!!.label)
        assertEquals(LocalTime.of(9, 5) to LocalTime.of(10, 0), FormText.parseTimes(EventFormReader.timeRow(form)!!.value))
    }

    @Test
    fun editForm() {
        val form = Fixtures.load("booking/edit_event_form_bottom.xml")
        assertTrue(EventFormReader.isForm(form))
        assertTrue(EventFormReader.isEdit(form))
        assertEquals("Delete event", EventFormReader.deleteRow(form)!!.label)
        // Here the time-zone row reads "British Summer Time", which is not the time row.
        assertEquals("Time (GMT+1)", EventFormReader.timeRow(form)!!.label)
        assertEquals("Location", EventFormReader.locationRow(form)!!.label)
        assertEquals("Sun 18 Oct", EventFormReader.dateRow(form)!!.value)
    }

    @Test
    fun otherScreensAreNotTheForm() {
        assertFalse(EventFormReader.isForm(Fixtures.load("day_view.xml")))
        assertFalse(EventFormReader.isForm(Fixtures.load("event_details_moveable.xml")))
        assertFalse(EventFormReader.isForm(Fixtures.load("booking/time_picker_wheels.xml")))
    }
}

class TimePickerReaderTest {
    @Test
    fun chooseTimeWheels() {
        val picker = Fixtures.load("booking/time_picker_wheels.xml")
        assertTrue(TimePickerReader.isPicker(picker))
        assertTrue(TimePickerReader.isWheelMode(picker))
        assertTrue(TimePickerReader.isTabSelected(picker, "Start time"))
        assertFalse(TimePickerReader.isTabSelected(picker, "End time"))
        assertNotNull(TimePickerReader.tab(picker, "End time"))
        val wheels = TimePickerReader.wheels(picker)
        assertEquals(listOf("Mon 12 Oct", "8", "05"), wheels.map { it.value })
        assertEquals("Sun 11 Oct", wheels[0].previous!!.text)
        assertEquals("Tue 13 Oct", wheels[0].next!!.text)
        assertEquals("9", wheels[1].next!!.text)
        assertEquals("04", wheels[2].previous!!.text)
        assertNotNull(TimePickerReader.doneButton(picker))
        assertEquals(Box(0, 121, 147, 268), TimePickerReader.closeButton(picker)!!.bounds)
    }

    @Test
    fun dragEditorAsItOpens() {
        val drag = Fixtures.load("booking/time_picker_drag.xml")
        assertTrue(TimePickerReader.isPicker(drag))
        assertFalse(TimePickerReader.isWheelMode(drag))
        assertTrue(TimePickerReader.wheels(drag).isEmpty())
        assertNotNull(TimePickerReader.modeButton(drag))
    }
}

class LocationAndRoomFinderReaderTest {
    private val free = RoomStatus.FREE

    @Test
    fun addLocationWithRecentRooms() {
        val loc = Fixtures.load("booking/add_location_recent.xml")
        assertTrue(LocationReader.isOpen(loc))
        assertNotNull(LocationReader.roomFinderButton(loc))
        assertEquals(
            listOf(RoomRow("KS-117", free), RoomRow("KS-119a", free), RoomRow("KS-103D", free), RoomRow("KS-184", free), RoomRow("KS-185", free)),
            LocationReader.recentRows(loc).map { it.room },
        )
        assertTrue(LocationReader.chips(loc).isEmpty())
        // KS-103D, first in the user's list, is free in Recent: Room Finder can be skipped.
        assertEquals("KS-103D", RoomChoice.recentShortcut(RoomList.DEFAULT, LocationReader.recentRows(loc).map { it.room })!!.name)
    }

    @Test
    fun aRoomChosen() {
        val chip = Fixtures.load("booking/add_location_room_chip.xml")
        assertEquals(listOf("KS-103D"), LocationReader.chips(chip))
        assertNotNull(LocationReader.clearButton(chip))
        assertNotNull(LocationReader.doneButton(chip))
        assertNotNull(LocationReader.closeButton(chip))
    }

    @Test
    fun buildings() {
        val b = Fixtures.load("booking/room_finder_buildings.xml")
        assertTrue(RoomFinderReader.isBuildingList(b))
        assertEquals(listOf("KS-Rooms", "TP2-Rooms", "KS-Rooms", "Kyamulibwa Station Rooms", "Masaka Station Rooms"), RoomFinderReader.buildings(b).map { it.first })
        assertNotNull(RoomFinderReader.building(b, "ks-rooms "))
        assertNull(RoomFinderReader.building(b, "LSHTM"))
        assertNotNull(RoomFinderReader.searchField(b))
    }

    @Test
    fun ksRoomsFreeAndBusy() {
        val allFree = RoomFinderReader.rooms(Fixtures.load("booking/room_finder_ks_rooms_all_free.xml")).map { it.room }
        assertEquals("KS-103D", allFree.first().name)
        assertTrue(allFree.all { it.status == free })
        assertEquals(RoomDecision.Chosen("KS-103D", "KS-103D"), RoomChoice.decide(RoomList.DEFAULT, allFree, endReached = false))

        val someBusy = RoomFinderReader.rooms(Fixtures.load("booking/room_finder_ks_rooms_some_busy.xml")).map { it.room }
        assertEquals(
            listOf("KS-105c (EPH only)", "KS-106 (EPH staff only)", "KS-121", "KS-123"),
            someBusy.filter { it.status == RoomStatus.BUSY }.map { it.name },
        )
        assertEquals(RoomDecision.Chosen("KS-117", "KS-117"), RoomChoice.decide(listOf("KS-121", "KS-123", "KS-117"), someBusy, endReached = false))
    }

    @Test
    fun everyDefaultRoomIsInRoomFinder() {
        val pages = listOf("all_free", "page2", "page3", "end")
            .flatMap { RoomFinderReader.rooms(Fixtures.load("booking/room_finder_ks_rooms_$it.xml")) }
            .map { it.room }.distinctBy { it.name }
        // 38 rooms over four screens (KS-G19 and KS-G20 were on two of them).
        assertEquals(38, pages.size)
        val missing = RoomList.DEFAULT.filter { room -> pages.none { RoomChoice.matches(it.name, room) } }
        assertEquals(emptyList<String>(), missing)
        // The list runs alphabetically to KS-LG05.
        assertEquals("KS-LG05", pages.last().name)
    }
}

class PeopleDescriptionAlertReaderTest {
    @Test
    fun addPeople() {
        val typed = Fixtures.load("booking/add_people_typed.xml")
        assertTrue(PeopleReader.isOpen(typed))
        assertEquals("nobody@example.com", cleanUiText(PeopleReader.input(typed)!!.text))
        assertEquals(0, PeopleReader.chipCount(typed))
        assertTrue(PeopleReader.requiredTab(typed)!!.selected)

        val chip = Fixtures.load("booking/add_people_chip.xml")
        assertEquals(listOf("nobody@example.com"), PeopleReader.chipAddresses(chip))
        assertEquals(1, PeopleReader.chipCount(chip))
        assertEquals("Type a name or an email address", PeopleReader.input(chip)!!.text)
        assertNotNull(PeopleReader.doneButton(chip))
    }

    @Test
    fun descriptionEditor() {
        val d = Fixtures.load("booking/description_editor.xml")
        assertTrue(DescriptionReader.isOpen(d))
        // uiautomator doesn't show the WebView's insides; the editor falls back to the WebView.
        assertEquals("WebView", DescriptionReader.editor(d)!!.className?.substringAfterLast('.'))
        assertNotNull(DescriptionReader.doneButton(d))
        assertNull(DescriptionReader.text(d))
        assertNull(DescriptionReader.doneButton(Fixtures.load("booking/new_event_form.xml")))
    }

    @Test
    fun alertSheet() {
        val sheet = Fixtures.load("booking/alert_sheet.xml")
        assertTrue(AlertSheetReader.isOpen(sheet))
        assertNotNull(AlertSheetReader.option(sheet, "None"))
        assertFalse(AlertSheetReader.isOpen(Fixtures.load("booking/new_event_form.xml")))
    }

    @Test
    fun prompts() {
        assertNotNull(PromptReader.discardButton(Fixtures.load("booking/discard_prompt.xml")))
        assertNull(PromptReader.discardButton(Fixtures.load("booking/new_event_form.xml")))
        val delete = Fixtures.load("booking/delete_prompt.xml")
        assertEquals("Delete the event?", PromptReader.dialogMessage(delete))
        assertEquals("Delete", PromptReader.positiveButton(delete)!!.text)
        assertEquals("Cancel", PromptReader.negativeButton(delete)!!.text)
    }

    @Test
    fun aBookingsDetails() {
        val details = Fixtures.load("booking/booking_details_reserved.xml")
        assertEquals("Reserved", DetailsReader.locationResponse(details))
        assertEquals("KS-121", DetailsReader.read(details).location)
        assertNotNull(DetailsReader.editButton(details))
    }

    @Test
    fun bookingScreensAreLoggedInFull() {
        for (name in listOf("new_event_form", "add_location_recent", "room_finder_buildings", "room_finder_ks_rooms_some_busy",
            "add_people_chip", "description_editor", "alert_sheet", "discard_prompt", "time_picker_wheels")) {
            assertTrue(name, BookingScreens.isAny(Fixtures.load("booking/$name.xml")))
        }
        assertFalse(BookingScreens.isAny(Fixtures.load("day_view.xml")))
        assertTrue(Fixtures.load("booking/room_finder_ks_rooms_some_busy.xml").calendarOnlyDump().contains("t='KS-121'"))
        assertTrue(Fixtures.load("booking/new_event_form.xml").calendarOnlyDump().contains("t='Location'"))
    }
}
