package com.thomaswcode.calendareventtimers.domain

/**
 * The auto-silence setting: how long an alarm rings unanswered before it falls silent and is
 * reported as missed. A fixed set of choices, so a stored value that isn't one of them (an older or
 * edited file) falls back to the default instead of ringing for an odd length.
 */
object RingTimeout {
    /** Minutes the user can choose from. */
    val OPTIONS_MIN = listOf(1, 2, 5, 10, 15, 30)

    const val DEFAULT_MIN = 10

    fun normalise(minutes: Int): Int = if (minutes in OPTIONS_MIN) minutes else DEFAULT_MIN

    /** "1 minute", "10 minutes". */
    fun label(minutes: Int): String = if (minutes == 1) "1 minute" else "$minutes minutes"
}
