package com.thomaswcode.calendareventtimers.util

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The app's activity log: every automation step and alarm event goes to logcat (tag `CET`, read
 * with `adb logcat -s CET`) and to an in-memory list shown on the Activity log screen.
 */
object ScanLog {
    const val TAG = "CET"
    private const val MAX_LINES = 500
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun i(message: String) = add('I', message).also { Log.i(TAG, message) }

    fun w(message: String) = add('W', message).also { Log.w(TAG, message) }

    fun e(message: String, error: Throwable? = null) {
        add('E', message + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
        Log.e(TAG, message, error)
    }

    /** A window tree for diagnosis: logcat gets all of it, the on-screen log a note. */
    fun dump(title: String, tree: String) {
        add('D', "$title: window tree written to logcat")
        Log.d(TAG, "---- $title ----")
        tree.lineSequence().forEach { Log.d(TAG, it) }
        Log.d(TAG, "---- end of $title ----")
    }

    fun clear() {
        _lines.value = emptyList()
    }

    private fun add(level: Char, message: String) {
        val line = "${time.format(LocalTime.now())} $level $message"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }
}
