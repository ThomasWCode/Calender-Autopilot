package com.thomaswcode.calendareventtimers.domain

/**
 * The auto-silence setting: how long an alarm rings unanswered before it falls silent and is
 * reported as missed. A fixed set of choices, so a stored value that isn't one of them (an older or
 * edited file) falls back to the default instead of ringing for an odd length.
 */
object RingTimeout {
    /** Seconds the user can choose from. */
    val OPTIONS_SEC = listOf(20, 60, 120, 300, 600)

    const val DEFAULT_SEC = 600

    fun normalise(seconds: Int): Int = if (seconds in OPTIONS_SEC) seconds else DEFAULT_SEC

    /** "20 seconds", "1 minute", "10 minutes". */
    fun label(seconds: Int): String {
        if (seconds % 60 != 0) return "$seconds seconds"
        val minutes = seconds / 60
        return if (minutes == 1) "1 minute" else "$minutes minutes"
    }
}
