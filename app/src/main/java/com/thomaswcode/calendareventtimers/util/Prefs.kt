package com.thomaswcode.calendareventtimers.util

import android.content.Context
import androidx.core.content.edit
import com.thomaswcode.calendareventtimers.booking.RoomList
import com.thomaswcode.calendareventtimers.data.ListCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User settings. Kept in credential-protected storage, so only the UI and the Outlook runs (after
 * unlock) read them, never the boot or alarm path. [load] is cheap and may be called repeatedly.
 */
object Prefs {
    private const val FILE = "settings"
    private const val KEY_DRY_RUN = "dry_run"
    private const val KEY_ROOMS = "rooms"
    private const val KEY_BUILDING = "building"
    private const val KEY_RECENT_SHORTCUT = "recent_shortcut"
    private const val KEY_MY_ADDRESSES = "my_addresses"

    @Volatile
    private var loaded = false

    private val _dryRun = MutableStateFlow(false)
    private val _rooms = MutableStateFlow(RoomList.DEFAULT)
    private val _building = MutableStateFlow(RoomList.DEFAULT_BUILDING)
    private val _recentShortcut = MutableStateFlow(true)
    private val _myAddresses = MutableStateFlow<List<String>>(emptyList())

    /** Scans list events but set no alarms; booking runs fill each booking in and discard it. */
    val dryRun: StateFlow<Boolean> = _dryRun.asStateFlow()

    /** Rooms to try, in order (PLAN-ROOM-BOOKING.md §3.13). */
    val rooms: StateFlow<List<String>> = _rooms.asStateFlow()

    /** Room Finder's building for the rooms. */
    val building: StateFlow<String> = _building.asStateFlow()

    /** Take the first-choice room from Add Location's Recent list when it shows it free. */
    val recentShortcut: StateFlow<Boolean> = _recentShortcut.asStateFlow()

    /** The user's own addresses (never offered for notifying), learnt or added by hand. */
    val myAddresses: StateFlow<List<String>> = _myAddresses.asStateFlow()

    fun load(context: Context) {
        val p = prefs(context)
        _dryRun.value = p.getBoolean(KEY_DRY_RUN, false)
        _rooms.value = ListCodec.decode(p.getString(KEY_ROOMS, null)).ifEmpty { RoomList.DEFAULT }
        _building.value = p.getString(KEY_BUILDING, null)?.trim()?.ifEmpty { null } ?: RoomList.DEFAULT_BUILDING
        _recentShortcut.value = p.getBoolean(KEY_RECENT_SHORTCUT, true)
        _myAddresses.value = ListCodec.decode(p.getString(KEY_MY_ADDRESSES, null))
        loaded = true
    }

    fun ensureLoaded(context: Context) {
        if (!loaded) load(context)
    }

    fun setDryRun(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_DRY_RUN, on) }
        _dryRun.value = on
    }

    fun setRooms(context: Context, rooms: List<String>) {
        prefs(context).edit { putString(KEY_ROOMS, ListCodec.encode(rooms)) }
        _rooms.value = rooms.ifEmpty { RoomList.DEFAULT }
    }

    fun setBuilding(context: Context, building: String) {
        val value = building.trim().ifEmpty { RoomList.DEFAULT_BUILDING }
        prefs(context).edit { putString(KEY_BUILDING, value) }
        _building.value = value
    }

    fun setRecentShortcut(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_RECENT_SHORTCUT, on) }
        _recentShortcut.value = on
    }

    fun setMyAddresses(context: Context, addresses: List<String>) {
        val clean = addresses.map { it.trim().lowercase() }.filter { it.contains('@') }.distinct()
        prefs(context).edit { putString(KEY_MY_ADDRESSES, ListCodec.encode(clean)) }
        _myAddresses.value = clean
    }

    /** Adds [address] to the user's own addresses (learnt from Outlook's form); true if it was new. */
    fun learnMyAddress(context: Context, address: String): Boolean {
        ensureLoaded(context)
        val a = address.trim().lowercase()
        if (!a.contains('@') || a in _myAddresses.value) return false
        setMyAddresses(context, _myAddresses.value + a)
        ScanLog.i("Learnt one of your addresses: $a")
        return true
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
