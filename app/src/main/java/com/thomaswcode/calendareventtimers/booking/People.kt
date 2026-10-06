package com.thomaswcode.calendareventtimers.booking

import com.thomaswcode.calendareventtimers.calendar.Attendee

/** Someone who can be told about a booking. [email] is lower case. */
data class Person(val email: String, val name: String?) {
    val display: String get() = name ?: email
}

/**
 * Who may be offered for notifying (PLAN-ROOM-BOOKING.md §3.6): LSHTM people among the original's
 * invitees and its organiser. "Any LSHTM person" was the answer on 2026-10-05: staff, student,
 * honorary and alumni addresses; never mailing lists, rooms, or the user.
 */
object People {
    val LSHTM_DOMAINS = setOf("lshtm.ac.uk", "student.lshtm.ac.uk", "hon.lshtm.ac.uk", "alumni.lshtm.ac.uk")

    fun domain(email: String): String = email.substringAfterLast('@', "").trim().lowercase()

    fun isLshtmPerson(email: String): Boolean = email.contains('@') && domain(email) in LSHTM_DOMAINS

    fun toNotify(attendees: List<Attendee>, organizer: String?, myAddresses: Set<String>, rooms: List<String>): List<Person> {
        val mine = myAddresses.map { it.trim().lowercase() }.toSet()
        val withOrganizer = if (organizer == null || attendees.any { it.email.equals(organizer, ignoreCase = true) }) {
            attendees
        } else {
            attendees + Attendee(null, organizer, Attendee.TYPE_REQUIRED, Attendee.STATUS_ACCEPTED)
        }
        val byEmail = LinkedHashMap<String, Person>()
        for (a in withOrganizer) {
            val email = a.email?.trim()?.lowercase() ?: continue
            when {
                a.isResource -> continue
                a.status == Attendee.STATUS_DECLINED -> continue
                !isLshtmPerson(email) -> continue
                email in mine -> continue
                RoomChoice.roomIn(email, rooms) != null || RoomChoice.roomIn(a.name, rooms) != null -> continue
            }
            val name = a.name?.trim()?.takeUnless { it.isEmpty() || it.equals(email, ignoreCase = true) }
            val known = byEmail[email]
            if (known == null || (known.name == null && name != null)) byEmail[email] = Person(email, name ?: known?.name)
        }
        return byEmail.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.display })
    }
}
