package com.thomaswcode.calendareventtimers.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.calendareventtimers.booking.RoomList
import com.thomaswcode.calendareventtimers.data.BookingStore
import com.thomaswcode.calendareventtimers.engine.LabelPass
import com.thomaswcode.calendareventtimers.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings (PLAN-ROOM-BOOKING.md §3.13): the rooms to try, in order, and what the app remembers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(snackbar: SnackbarHostState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rooms by Prefs.rooms.collectAsStateWithLifecycle()
    val building by Prefs.building.collectAsStateWithLifecycle()
    val recentShortcut by Prefs.recentShortcut.collectAsStateWithLifecycle()
    val myAddresses by Prefs.myAddresses.collectAsStateWithLifecycle()
    var newRoom by rememberSaveable { mutableStateOf("") }
    var buildingText by rememberSaveable(building) { mutableStateOf(building) }
    var newAddress by rememberSaveable { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // What the app remembers, counted again after each memory action.
    var memoryVersion by remember { mutableIntStateOf(0) }
    var memory by remember { mutableStateOf<BookingStore.MemoryCounts?>(null) }
    LaunchedEffect(memoryVersion) { memory = BookingStore.get(context).memoryCounts() }

    fun setRooms(list: List<String>) = Prefs.setRooms(context, list)

    fun addRoom() {
        RoomList.add(rooms, newRoom)
            .onSuccess { setRooms(it); newRoom = "" }
            .onFailure { e -> scope.launch { snackbar.showNow(e.message ?: "Couldn't add it") } }
    }

    fun removeRoom(index: Int) {
        val before = rooms
        val name = rooms[index]
        setRooms(RoomList.remove(rooms, index))
        scope.launch {
            if (snackbar.showNow("$name removed", actionLabel = "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) setRooms(before)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = { Text("Settings") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Rooms, in order of preference", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    "Room booking takes the first room on this list that Room Finder shows as free. Use the names as Room Finder shows them; a note in brackets after a name (\"KS-106 (EPH staff only)\") is matched too.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(rooms, key = { _, r -> r }) { i, room ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp))
                        Text(room, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { setRooms(RoomList.move(rooms, i, -1)) }, enabled = i > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move $room up")
                        }
                        IconButton(onClick = { setRooms(RoomList.move(rooms, i, +1)) }, enabled = i < rooms.lastIndex) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move $room down")
                        }
                        IconButton(onClick = { removeRoom(i) }, enabled = rooms.size > 1) {
                            Icon(Icons.Default.Close, contentDescription = "Remove $room")
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newRoom,
                        onValueChange = { newRoom = it },
                        label = { Text("Add a room") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addRoom() }),
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = { addRoom() }, enabled = newRoom.isNotBlank()) { Text("Add") }
                }
            }
            item {
                OutlinedButton(onClick = {
                    val before = rooms
                    setRooms(RoomList.DEFAULT)
                    scope.launch {
                        if (snackbar.showNow("Back to the default 25 rooms", actionLabel = "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) setRooms(before)
                    }
                }) { Text("Reset to the default list") }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { Text("Room Finder", style = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedTextField(
                    value = buildingText,
                    onValueChange = { buildingText = it },
                    label = { Text("Building") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { Prefs.setBuilding(context, buildingText) }),
                    supportingText = { Text("As Room Finder lists it. Saved when you press Done on the keyboard.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SettingSwitch(
                    "Use Add Location's Recent list",
                    "When the first room on your list shows as free there, take it without opening Room Finder (same room, fewer screens).",
                    recentShortcut,
                ) { Prefs.setRecentShortcut(context, it) }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { Text("Your addresses", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    "Never offered for notifying. Your Outlook account's address is always included; others are learnt from Outlook's event form.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(myAddresses, key = { _, a -> "me-$a" }) { _, address ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(address, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 16.dp))
                    IconButton(onClick = { Prefs.setMyAddresses(context, myAddresses - address) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove $address")
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newAddress,
                        onValueChange = { newAddress = it },
                        label = { Text("Add an address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = {
                            Prefs.setMyAddresses(context, myAddresses + newAddress)
                            newAddress = ""
                        },
                        enabled = newAddress.contains('@'),
                    ) { Text("Add") }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { Text("Memory", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    "The app remembers your answers for meetings that come back, what each event got, the labels it has read in Outlook and people's names, so later runs need fewer presses and less time in Outlook.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Text(memorySummary(memory), style = MaterialTheme.typography.bodyMedium) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirmClear = true }, enabled = memory?.isEmpty == false) { Text("Clear memory") }
                    Text("Or only part of it:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { BookingStore.get(context).forgetAnswers() }
                            memoryVersion++
                            snackbar.showNow("Remembered answers forgotten")
                        }
                    }) { Text("Forget remembered answers") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { LabelPass(context).forgetAll() }
                            memoryVersion++
                            snackbar.showNow("Every label will be read in Outlook again next time")
                        }
                    }) { Text("Read all labels again next time") }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear memory?") },
            text = {
                Text(
                    "The app forgets your answers for each meeting and each event, the labels it has read in Outlook and people's names. " +
                        "Your bookings, alarms and settings stay. The next runs ask about every event again and read each label in Outlook again, so they take longer.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        BookingStore.get(context).clearMemory()
                        memoryVersion++
                        snackbar.showNow("Memory cleared")
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
        )
    }
}

/** "Remembered now: answers for 12 meetings and 30 events, the labels of 40 events and 85 names." */
private fun memorySummary(c: BookingStore.MemoryCounts?): String {
    fun n(count: Int, one: String, many: String) = if (count == 1) "1 $one" else "$count $many"
    return when {
        c == null -> "Counting what is remembered…"
        c.isEmpty -> "Nothing is remembered at the moment."
        else -> "Remembered now: answers for ${n(c.meetings, "meeting", "meetings")} and ${n(c.events, "event", "events")}, " +
            "the labels of ${n(c.labels, "event", "events")} and ${n(c.people, "name", "names")}."
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
