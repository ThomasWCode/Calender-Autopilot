package com.thomaswcode.calendareventtimers.booking

import com.thomaswcode.calendareventtimers.domain.EventParser
import com.thomaswcode.calendareventtimers.domain.cleanUiText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** The Choose Time wheels (PLAN-ROOM-BOOKING.md §2.4): stepped one value at a time. */
object Wheel {
    /**
     * Signed steps from [from] to [to] on a wheel of [size] values: the shorter way round when it
     * wraps (hours 0–23, minutes 0–59). Positive means forward (later values).
     */
    fun steps(from: Int, to: Int, size: Int, wraps: Boolean = true): Int {
        val d = to - from
        if (!wraps) return d
        val forward = ((d % size) + size) % size
        return if (forward <= size / 2) forward else forward - size
    }
}

/** Texts on Outlook's event form and its wheels, as shown in UK English. */
object FormText {
    private val UK = Locale.UK
    private val dayMonth = Regex("""(\d{1,2})\s+([A-Za-z]{3,})""")
    private val twoTimes = Regex("""(\d{1,2}[:.]\d{2})\D+?(\d{1,2}[:.]\d{2})""")

    /**
     * The form's or date wheel's day, e.g. "Mon 12 Oct" (September may read "Sept"), resolved to
     * the year that puts it nearest [near] and, when a weekday is shown, matches it.
     */
    fun parseDate(text: String?, near: LocalDate): LocalDate? {
        val t = cleanUiText(text) ?: return null
        val m = dayMonth.find(t) ?: return null
        val day = m.groupValues[1].toInt()
        val prefix = m.groupValues[2].take(3).lowercase()
        val month = Month.entries.firstOrNull { it.getDisplayName(TextStyle.SHORT, UK).take(3).lowercase() == prefix } ?: return null
        val weekday = DayOfWeek.entries.firstOrNull { d ->
            t.startsWith(d.getDisplayName(TextStyle.SHORT, UK).take(3), ignoreCase = true)
        }
        val candidates = (near.year - 1..near.year + 1).mapNotNull { y -> runCatching { LocalDate.of(y, month, day) }.getOrNull() }
        val matching = if (weekday == null) candidates else candidates.filter { it.dayOfWeek == weekday }.ifEmpty { candidates }
        return matching.minByOrNull { abs(it.toEpochDay() - near.toEpochDay()) }
    }

    /** The form's time row, e.g. "09:05 ▸ 10:00". */
    fun parseTimes(text: String?): Pair<LocalTime, LocalTime>? {
        val t = cleanUiText(text) ?: return null
        val m = twoTimes.find(t) ?: return null
        val start = EventParser.parseTime(m.groupValues[1]) ?: return null
        val end = EventParser.parseTime(m.groupValues[2]) ?: return null
        return start to end
    }

    /** An hour or minute wheel's value: "8", "05". */
    fun parseNumber(text: String?): Int? = cleanUiText(text)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toInt()

    /**
     * The same text as Outlook shows it: invisible marks dropped, non-breaking and repeated spaces
     * made single. Case is kept. (What Outlook reads back is cleaned; what was typed may not be.)
     */
    fun sameText(a: String?, b: String?): Boolean {
        fun norm(s: String?) = cleanUiText(s)?.replace(Regex("""\s+"""), " ")
        return norm(a) == norm(b)
    }

    /** How the form and the date wheel show a day: "Mon 12 Oct". */
    fun formDate(date: LocalDate): String =
        "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, UK).take(3)} ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, UK).take(3)}"
}

/** Event bodies from the calendar provider: Word-filtered HTML for events made in Outlook. */
object DescriptionText {
    private val html = Regex("""<\s*(html|body|div|p|br|span|table|a)\b""", RegexOption.IGNORE_CASE)
    private val comments = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val blocks = Regex("""<\s*(head|style|script|xml)\b.*?<\s*/\s*\1\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val breaks = Regex("""<\s*(br|/p|/div|/tr|/h\d|/li)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val tags = Regex("""<[^>]+>""")
    private val numeric = Regex("""&#(x?[0-9a-fA-F]+);""")
    private val blankLines = Regex("""\n[ \t ]*(\n[ \t ]*)+""")

    fun isHtml(text: String): Boolean = html.containsMatchIn(text)

    /** The HTML without comments, Office conditionals, `<head>`, `<style>` and scripts. */
    fun cleanHtml(text: String): String = blocks.replace(comments.replace(text, ""), "").trim()

    /** Plain text for the clipboard's plain alternative and the fallback (links become their text). */
    fun plain(text: String?): String {
        if (text.isNullOrBlank()) return ""
        if (!isHtml(text)) return text.trim()
        var s = cleanHtml(text).replace("\r", "").replace("\n", " ")
        s = breaks.replace(s, "\n")
        s = tags.replace(s, "")
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        s = numeric.replace(s) { m ->
            val v = m.groupValues[1]
            val code = if (v.startsWith("x") || v.startsWith("X")) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
            // An out-of-range or surrogate code point (&#x110000;) is left as written.
            code?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }?.let { String(Character.toChars(it)) } ?: m.value
        }
        s = s.replace("&amp;", "&")
        s = s.lines().joinToString("\n") { it.replace(Regex("""[ \t ]+"""), " ").trim() }
        return blankLines.replace(s, "\n\n").trim()
    }

    fun isEmpty(text: String?): Boolean = plain(text).isBlank()
}
