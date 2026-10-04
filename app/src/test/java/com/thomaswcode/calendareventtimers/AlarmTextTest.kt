package com.thomaswcode.calendareventtimers

import com.thomaswcode.calendareventtimers.domain.AlarmText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmTextTest {
    @Test
    fun plainLocation() {
        assertEquals(
            "Zumba (Immoveable) @ Pancras Square Leisure, 5 Pancras Square, London, Camden, N1C 4AG",
            AlarmText.label("Zumba", "Immoveable", "Pancras Square Leisure, 5 Pancras Square, London, Camden, N1C 4AG"),
        )
    }

    @Test
    fun noLocation() {
        assertEquals("Phone cat (Moveable)", AlarmText.label("Phone cat", "Moveable", null))
        assertEquals("Phone cat (Moveable)", AlarmText.label("Phone cat", "Moveable", "  "))
    }

    @Test
    fun zoomLinkBecomesZoom() {
        assertEquals(
            "Kristian pdr (Moveable) @ Zoom",
            AlarmText.label("Kristian pdr", "Moveable", "https://lshtm.zoom.us/j/85988210188?pwd=fcXj2stOK1mtxeavOtAix3a9h2uRGb.1&from=addon"),
        )
    }

    @Test
    fun roomAfterTheLinkIsKept() {
        assertEquals(
            "Chat about funding (Moveable) @ Zoom; KS-121",
            AlarmText.label("Chat about funding", "Moveable", "https://lshtm.zoom.us/j/86875902372?pwd=UT5l9BOwiyLXl2kHSe8wehiTDfAubL.1&from=addon; KS-121"),
        )
    }

    @Test
    fun teamsMeetAndOtherLinks() {
        assertEquals("Teams", AlarmText.shortLocation("https://teams.microsoft.com/l/meetup-join/19%3ameeting_abc%40thread.v2/0?context=%7b%7d"))
        assertEquals(
            "Lower Ground Floor X-Ray Dept, University College Hospital, 235 Euston Road London NW1 2BU; Meet",
            AlarmText.shortLocation("Lower Ground Floor X-Ray Dept, University College Hospital, 235 Euston Road \r\nLondon NW1 2BU; https://meet.google.com/evt-oviw-qoi"),
        )
        assertEquals("example.org", AlarmText.shortLocation("https://www.example.org/some/long/path?x=1"))
        assertEquals("Microsoft Teams Meeting", AlarmText.shortLocation("Microsoft Teams Meeting"))
        assertEquals("Zoom", AlarmText.shortLocation("https://zoom.us/j/1; https://lshtm.zoom.us/j/2"))
        assertNull(AlarmText.shortLocation(""))
    }

    @Test
    fun ellipsizesForLists() {
        val long = AlarmText.label("Invitation to contribute to a Liber Amicorum for Frank Cobelens", "Immoveable", "KS-121")
        val short = AlarmText.ellipsize(long)
        assertEquals(AlarmText.LIST_MAX, short.length)
        assertTrue(short.endsWith("…"))
        assertEquals("Zumba (Immoveable)", AlarmText.ellipsize("Zumba (Immoveable)"))
    }
}
