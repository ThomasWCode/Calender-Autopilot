package com.thomaswcode.calendareventtimers.booking

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** The days a booking run covers (PLAN-ROOM-BOOKING.md §3.4). Working days only: Monday–Friday. */
enum class BookingRange { NEXT_WEEK, TODAY, TOMORROW, THIS_WEEK }

object BookingRanges {
    private val dayMonth = DateTimeFormatter.ofPattern("EEE d MMMM", Locale.UK)
    private val day = DateTimeFormatter.ofPattern("EEE d", Locale.UK)

    fun isWorkingDay(date: LocalDate): Boolean = date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY

    fun days(range: BookingRange, today: LocalDate): List<LocalDate> = when (range) {
        BookingRange.NEXT_WEEK -> {
            // The coming Monday (on a Sunday that is tomorrow; on a Monday, a week on).
            val monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            (0L..4L).map { monday.plusDays(it) }
        }
        BookingRange.TODAY -> listOf(today).filter(::isWorkingDay)
        BookingRange.TOMORROW -> listOf(today.plusDays(1)).filter(::isWorkingDay)
        BookingRange.THIS_WEEK -> {
            val friday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
            if (!isWorkingDay(today)) emptyList() else generateSequence(today) { it.plusDays(1) }.takeWhile { !it.isAfter(friday) }.toList()
        }
    }

    /** Why a range has nothing to book, for a disabled button; null when it has days. */
    fun unavailable(range: BookingRange, today: LocalDate): String? {
        if (days(range, today).isNotEmpty()) return null
        return when (range) {
            BookingRange.TODAY -> "Today is a ${today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}"
            BookingRange.TOMORROW -> "Tomorrow is a ${today.plusDays(1).dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}"
            BookingRange.THIS_WEEK -> "No working days left this week"
            BookingRange.NEXT_WEEK -> null
        }
    }

    /** "Mon 12 – Fri 16 October", "Tue 6 October". */
    fun describe(days: List<LocalDate>): String = when {
        days.isEmpty() -> ""
        days.size == 1 -> dayMonth.format(days.first())
        days.first().month == days.last().month -> "${day.format(days.first())} – ${dayMonth.format(days.last())}"
        else -> "${dayMonth.format(days.first())} – ${dayMonth.format(days.last())}"
    }

    fun title(range: BookingRange): String = when (range) {
        BookingRange.NEXT_WEEK -> "Next week"
        BookingRange.TODAY -> "Today"
        BookingRange.TOMORROW -> "Tomorrow"
        BookingRange.THIS_WEEK -> "This week"
    }
}
