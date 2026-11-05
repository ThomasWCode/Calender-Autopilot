package com.thomaswcode.calendareventtimers.util

import android.content.Context
import androidx.core.content.edit
import com.thomaswcode.calendareventtimers.domain.RingTimeout
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Alarm settings the ring service reads. Unlike [Prefs] these are kept in device-protected storage,
 * as the alarm database is, because alarms ring (and the service reads them) before the first
 * unlock after a reboot, when credential-protected storage can't be opened.
 */
object AlarmPrefs {
    private const val FILE = "alarm_settings"
    private const val KEY_RING_TIMEOUT_SEC = "ring_timeout_sec"

    private val _ringTimeoutSec = MutableStateFlow(RingTimeout.DEFAULT_SEC)

    /** Seconds an unanswered alarm rings before it falls silent (one of [RingTimeout.OPTIONS_SEC]). */
    val ringTimeoutSec: StateFlow<Int> = _ringTimeoutSec.asStateFlow()

    /** Refreshes the values the settings screen shows. */
    fun load(context: Context) {
        _ringTimeoutSec.value = readRingTimeoutSec(context)
    }

    /** Read from the file each time, so a ringing alarm always gets the latest choice. */
    fun ringTimeout(context: Context): Duration = Duration.ofSeconds(readRingTimeoutSec(context).toLong())

    fun setRingTimeoutSec(context: Context, seconds: Int) {
        val value = RingTimeout.normalise(seconds)
        prefs(context).edit { putInt(KEY_RING_TIMEOUT_SEC, value) }
        _ringTimeoutSec.value = value
    }

    private fun readRingTimeoutSec(context: Context): Int =
        RingTimeout.normalise(prefs(context).getInt(KEY_RING_TIMEOUT_SEC, RingTimeout.DEFAULT_SEC))

    private fun prefs(context: Context) =
        context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
