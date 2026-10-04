package com.thomaswcode.calendareventtimers.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.TextStyle
import java.util.Locale

/** The categories (Outlook "labels") that get alarms, in their canonical spelling. */
val TARGET_LABELS = listOf("Moveable", "Immoveable")

/** Bidi isolates and marks, zero-width space and BOM: Outlook wraps some texts in them. */
private val INVISIBLE: Set<Char> =
    listOf(0x2066, 0x2067, 0x2068, 0x2069, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x200B, 0xFEFF)
        .map { Char(it) }.toSet()
private val NO_BREAK_SPACE = Char(0x00A0)

/**
 * Removes the invisible bidi marks Outlook wraps some texts in (the alert row reads U+2068 followed
 * by "None"), turns non-breaking spaces into spaces and trims.
 */
fun cleanUiText(s: CharSequence?): String? {
    if (s == null) return null
    val out = StringBuilder(s.length)
    for (ch in s) {
        when (ch) {
            in INVISIBLE -> Unit
            NO_BREAK_SPACE -> out.append(' ')
            else -> out.append(ch)
        }
    }
    return out.toString().trim()
}

/** Pure parsing of the texts Outlook's calendar shows. Outlook runs in UK English on this phone. */
object EventParser {
    private val UK = Locale.UK

    private val dayLabelFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", UK)
    private val detailsDateFormat = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("EEEE, d MMMM uuuu")
        .toFormatter(UK)

    private val weekdayNames = DayOfWeek.entries.map { it.getDisplayName(TextStyle.FULL, UK) }
    private val monthNames = Month.entries.map { it.getDisplayName(TextStyle.FULL, UK) }

    private const val TIME_PATTERN = """\d{1,2}[:.]\d{2}(?:\s*[AaPp]\.?\s*[Mm]\.?)?"""
    private val time = Regex("""(\d{1,2})[:.](\d{2})(?:\s*([AaPp])\.?\s*[Mm]\.?)?""")

    /** "Monday 5 October" at the start of a week-strip or Day-view description. */
    val dateLabelAtStart = Regex(
        "^((?:${weekdayNames.joinToString("|")}) (\\d{1,2}) (${monthNames.joinToString("|")}))",
    )
    private val startTimeAfterDate = Regex("""^($TIME_PATTERN)\s+to\s""")
    private val titleInDesc = Regex(
        """^[^,]+, $TIME_PATTERN to (?:[A-Za-z]+ \d{1,2} [A-Za-z]+, )?$TIME_PATTERN, (.*)$""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val descTail = Regex("""(?:, private event)?(?:, in \d+ (?:min|mins|minute|minutes|hour|hours))?$""")
    private val countdown = Regex(""", (?:in \d+ (?:min|mins|minute|minutes|hr|hrs|hour|hours)|now|happening now)$""")
    private val locationInDesc = Regex(
        """, at location (.*?)(?: with .*|, private event.*|, in \d+ \w+)?$""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** How Outlook names a day in its week strip and Day view, e.g. "Monday 5 October". */
    fun dayLabel(date: LocalDate): String = dayLabelFormat.format(date)

    /**
     * A Day-view description without the countdown Outlook appends shortly before an event starts
     * (", in 2 mins", which becomes ", in 1 min" a minute later), so one event keeps one identity.
     */
    fun stableDesc(desc: String): String = countdown.replace(desc, "")

    /**
     * How many locations a Day-view description lists ("…, at location <Zoom link>; KS-121 with …"
     * is two). The details screen shows one row per location but adds some of them a second or so
     * after opening, so this says when it is complete. Best effort: a location that itself contains
     * " with " is cut short, which only shortens the wait.
     */
    fun locationCountInDesc(desc: String): Int = locationsInDesc(desc).size

    /** The locations a Day-view description lists, in its order, e.g. [Zoom link, "KS-121"]. */
    fun locationsInDesc(desc: String): List<String> =
        locationInDesc.find(desc)?.groupValues?.get(1)?.split(';')?.map(::squash)?.filter { it.isNotEmpty() }.orEmpty()

    /**
     * [rows] (the details screen's location rows, which Outlook adds in varying order) sorted into
     * the order of [reference] (the Day view's), so an alarm reads the same on every scan. Rows the
     * reference doesn't mention go last, in their own order.
     */
    fun orderLike(reference: List<String>, rows: List<String>): List<String> {
        val position = reference.map(::squash)
        return rows.sortedBy { row -> position.indexOf(squash(row)).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    /** Whether a Day-view description is about an event called [title] (it always contains the title). */
    fun descMentions(desc: String, title: String): Boolean = squash(desc).contains(squash(title))

    private fun squash(s: String) = s.replace(Regex("""\s+"""), " ").trim()

    /**
     * Start time of a timed event that starts on [date], read from its Day-view description
     * ("Monday 5 October, 17:35 to 18:00, Kristian pdr, …"). Null for all-day events
     * ("… to …, All Day, …"), work-location chips ("…, Work location: …") and events that
     * started on an earlier day, because none of those have "<label>, HH:MM to " at the start.
     */
    fun startTimeIfStartsOn(desc: String, date: LocalDate): LocalTime? {
        val prefix = dayLabel(date) + ", "
        if (!desc.startsWith(prefix)) return null
        val match = startTimeAfterDate.find(desc.substring(prefix.length)) ?: return null
        return parseTime(match.groupValues[1])
    }

    /**
     * Best-effort title from a Day-view description, for log lines and as a last resort.
     * Titles can contain commas, so the details screen is the real source.
     */
    fun titleFromDesc(desc: String): String? {
        val rest = titleInDesc.find(desc)?.groupValues?.get(1) ?: return null
        val noLocation = rest.substringBefore(", at location ")
        return descTail.replace(noLocation, "").trim().ifEmpty { null }
    }

    /** First time in [text]: "14:00", "6:00", "14:00 to 14:55, duration: …", "2:00 PM". */
    fun parseTime(text: String?): LocalTime? {
        val m = time.find(cleanUiText(text) ?: return null) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        val amPm = m.groupValues[3]
        if (amPm.isNotEmpty()) {
            if (hour !in 1..12) return null
            hour = hour % 12 + if (amPm.equals("p", ignoreCase = true)) 12 else 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    /** The details screen's date, e.g. "Sunday, 4 October 2026". */
    fun parseDetailsDate(text: String?): LocalDate? {
        val t = cleanUiText(text) ?: return null
        runCatching { return LocalDate.parse(t, detailsDateFormat) }
        // Lenient fallback: "4 October 2026" anywhere in the text.
        val m = Regex("""(\d{1,2}) ([A-Za-z]+) (\d{4})""").find(t) ?: return null
        val month = Month.entries.firstOrNull {
            it.getDisplayName(TextStyle.FULL, UK).equals(m.groupValues[2], ignoreCase = true)
        } ?: return null
        return runCatching { LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) }.getOrNull()
    }

    /**
     * Category names from the texts under the details screen's category row. With no category,
     * Outlook shows "Categorise" + "None"; otherwise each text is one category name.
     */
    fun categoriesFrom(texts: List<CharSequence?>): List<String> {
        val cleaned = texts.mapNotNull { cleanUiText(it) }.filter { it.isNotEmpty() }
        val hadPrompt = cleaned.any { it.equals("Categorise", true) || it.equals("Categorize", true) }
        val rest = cleaned.filterNot { it.equals("Categorise", true) || it.equals("Categorize", true) }
        if (hadPrompt && rest.all { it.equals("None", ignoreCase = true) }) return emptyList()
        return rest
    }

    /**
     * The target label among an event's categories (exact name, case-insensitive), in canonical
     * spelling, or null. Other categories ("Urgent", "BCC BCC BCC", …) never match.
     */
    fun matchLabel(categories: List<String>): String? =
        categories.firstNotNullOfOrNull { c -> TARGET_LABELS.firstOrNull { it.equals(c.trim(), ignoreCase = true) } }

    /**
     * Resolves a year-less label such as "Monday 28 September" to the date nearest [near]
     * (the week strip never shows the year). Prefers the year whose weekday matches.
     */
    fun resolveLabel(label: String, near: LocalDate): LocalDate? {
        val m = dateLabelAtStart.find(label) ?: return null
        val day = m.groupValues[2].toInt()
        val month = Month.entries.firstOrNull { it.getDisplayName(TextStyle.FULL, UK) == m.groupValues[3] } ?: return null
        val weekday = m.groupValues[1].substringBefore(' ')
        val candidates = (near.year - 1..near.year + 1).mapNotNull { y -> runCatching { LocalDate.of(y, month, day) }.getOrNull() }
        val matching = candidates.filter { it.dayOfWeek.getDisplayName(TextStyle.FULL, UK) == weekday }.ifEmpty { candidates }
        return matching.minByOrNull { kotlin.math.abs(it.toEpochDay() - near.toEpochDay()) }
    }
}
