package com.thomaswcode.calendareventtimers.util

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User settings. Kept in credential-protected storage, so only the UI (after unlock) reads them,
 * never the boot or alarm path.
 */
object Prefs {
    private const val FILE = "settings"
    private const val KEY_DRY_RUN = "dry_run"

    private val _dryRun = MutableStateFlow(false)

    /** Scans list events but set no alarms. */
    val dryRun: StateFlow<Boolean> = _dryRun.asStateFlow()

    fun load(context: Context) {
        _dryRun.value = prefs(context).getBoolean(KEY_DRY_RUN, false)
    }

    fun setDryRun(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_DRY_RUN, on) }
        _dryRun.value = on
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
