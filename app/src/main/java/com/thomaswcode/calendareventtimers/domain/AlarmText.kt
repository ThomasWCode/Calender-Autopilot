package com.thomaswcode.calendareventtimers.domain

/**
 * The alarm label: `Title (Label) @ Location`, with meeting URLs shortened to the service name.
 *
 * - `https://lshtm.zoom.us/j/859…&from=addon` → `Zoom`
 * - `https://lshtm.zoom.us/j/868…; KS-121` → `Zoom; KS-121`
 * - Teams and Google Meet links become `Teams` / `Meet`; any other link becomes its host name.
 */
object AlarmText {
    /** Length for the notification and the Upcoming list; the ring screen shows the full text. */
    const val LIST_MAX = 60

    private val url = Regex("""https?://[^\s;]+""", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("""\s+""")

    fun label(title: String, label: String, location: String?): String {
        val where = shortLocation(location)
        return if (where.isNullOrEmpty()) "$title ($label)" else "$title ($label) @ $where"
    }

    /** Location with links replaced, line breaks flattened and `;`-separated parts kept. */
    fun shortLocation(location: String?): String? {
        val flat = location?.replace(whitespace, " ")?.trim()
        if (flat.isNullOrEmpty()) return null
        return flat.split(';')
            .map { part -> url.replace(part) { serviceName(it.value) }.replace(whitespace, " ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("; ")
            .ifEmpty { null }
    }

    fun ellipsize(text: String, max: Int = LIST_MAX): String =
        if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    fun serviceName(link: String): String {
        val host = link.substringAfter("://").substringBefore('/').substringBefore('?')
            .substringBefore('#').substringAfterLast('@').substringBefore(':').lowercase()
        fun isOrUnder(domain: String) = host == domain || host.endsWith(".$domain")
        return when {
            isOrUnder("zoom.us") || isOrUnder("zoom.com") || isOrUnder("zoomgov.com") -> "Zoom"
            isOrUnder("teams.microsoft.com") || isOrUnder("teams.live.com") || isOrUnder("teams.microsoft.us") -> "Teams"
            isOrUnder("meet.google.com") -> "Meet"
            else -> host.removePrefix("www.").ifEmpty { "link" }
        }
    }
}
