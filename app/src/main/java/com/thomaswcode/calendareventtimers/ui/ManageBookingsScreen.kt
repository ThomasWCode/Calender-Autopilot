package com.thomaswcode.calendareventtimers.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.calendareventtimers.booking.ManageController
import com.thomaswcode.calendareventtimers.booking.ManagedBooking
import com.thomaswcode.calendareventtimers.data.RoomReply
import com.thomaswcode.calendareventtimers.util.Prefs
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val dayHeading = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

/** Manage bookings (PLAN-ROOM-BOOKING.md §3.12): change the room, the people told, or delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageBookingsScreen(snackbar: SnackbarHostState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ManageController.state.collectAsStateWithLifecycle()
    val dryRun by Prefs.dryRun.collectAsStateWithLifecycle()
    var bookings by remember { mutableStateOf<List<ManagedBooking>?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        reloads++
        onPauseOrDispose { }
    }
    LaunchedEffect(reloads) { bookings = ManageController.load(context) }
    // After a change, the calendar shows it only once Outlook syncs: look again every 5 s for 2 minutes.
    var changes by remember { mutableIntStateOf(0) }
    LaunchedEffect(changes) {
        if (changes == 0) return@LaunchedEffect
        repeat(24) {
            delay(5_000)
            reloads++
        }
    }
    LaunchedEffect(state) {
        val s = state
        if (s is ManageController.State.Finished) {
            // Shown from the screen's scope: clearing the message restarts this effect, which would
            // cancel a snackbar shown from inside it.
            scope.launch { snackbar.showNow(s.message) }
            ManageController.clearMessage()
            reloads++
            changes++
        }
    }
    var deleting by remember { mutableStateOf<ManagedBooking?>(null) }
    var editing by remember { mutableStateOf<ManagedBooking?>(null) }
    val running = state is ManageController.State.Running

    fun act(start: () -> String?) {
        start()?.let { scope.launch { snackbar.showNow(it) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = { Text("Bookings") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (state as? ManageController.State.Running)?.let { r -> item { ProgressCard("${r.what}…", r.step, onStop = { ManageController.stop() }) } }
            val list = bookings
            when {
                list == null -> item { Text("Reading the phone's calendar…") }
                list.isEmpty() -> item { Text("No bookings from today on.") }
                else -> list.groupBy { it.booking.eventDate }.forEach { (date, day) ->
                    item(key = "day-$date") {
                        Text(dayHeading.format(LocalDate.parse(date)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(day, key = { it.booking.id }) { m ->
                        BookingCard(
                            m,
                            enabled = !running,
                            onChangeRoom = { act { ManageController.changeRoom(context, m.booking) } },
                            onEditPeople = { editing = m },
                            onDelete = { deleting = m },
                            onForget = { scope.launch { ManageController.forget(context, m.booking); reloads++ } },
                        )
                    }
                }
            }
            item {
                Text(
                    "Each change opens Outlook briefly. Deleting sends cancellations to the room and to the people told." +
                        // Dry run is for booking runs; nothing here would make sense half done.
                        if (dryRun) " Dry run doesn't apply here: these changes are real." else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this booking?") },
            text = {
                Text(
                    "“${m.booking.bookingTitle}” on ${m.booking.eventDate} at ${m.booking.start}. Outlook sends a cancellation to ${m.booking.room ?: "the room"}" +
                        (if (m.notified.isEmpty()) "." else " and to ${m.notified.joinToString { it.display }}.") +
                        " The event will be offered for a room again next time.",
                )
            },
            confirmButton = { TextButton(onClick = { deleting = null; act { ManageController.delete(context, m.booking) } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }

    editing?.let { m ->
        val keep = remember(m) { mutableStateListOf<String>().apply { addAll(m.notified.map { it.email }) } }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("People told") },
            text = {
                // Scrolls, so a long invitee list keeps every person and the buttons reachable.
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (m.offered.isEmpty()) Text("Nobody at LSHTM was invited to “${m.booking.originalTitle}”.")
                    m.offered.forEach { p ->
                        val checked = p.email in keep
                        Row(
                            Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox) { if (it) keep += p.email else keep -= p.email },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(p.display, style = MaterialTheme.typography.bodyLarge)
                                if (p.name != null) Text(p.email, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = null
                    act { ManageController.editPeople(context, m.booking, keep.toList()) }
                }) { Text("Update") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BookingCard(
    m: ManagedBooking,
    enabled: Boolean,
    onChangeRoom: () -> Unit,
    onEditPeople: () -> Unit,
    onDelete: () -> Unit,
    onForget: () -> Unit,
) {
    val b = m.booking
    val reply = ReplyText.short(b.roomReply)
    val trouble = ReplyText.needsAttention(b.roomReply)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${b.start}–${b.end}  ${b.originalTitle}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${b.room ?: "?"} · $reply" + if (m.notified.isEmpty()) "" else " · told ${m.notified.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (trouble) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (m.notified.isNotEmpty()) {
                    Text(m.notified.joinToString { it.display }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (m.fromCalendar) {
                    Text(
                        if (m.syncing) "Changed just now: waiting for Outlook to update the calendar" else "Found in your calendar; the app has no record of it",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                m.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick = { menu = true }, enabled = enabled && !m.syncing) { Icon(Icons.Default.MoreVert, contentDescription = "Change ${b.originalTitle}'s booking") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (b.roomReply == RoomReply.NOT_FOUND && !m.fromCalendar) {
                    DropdownMenuItem(text = { Text("Forget it") }, onClick = { menu = false; onForget() })
                } else {
                    DropdownMenuItem(text = { Text("Change room") }, onClick = { menu = false; onChangeRoom() })
                    DropdownMenuItem(text = { Text("Edit people") }, onClick = { menu = false; onEditPeople() })
                    DropdownMenuItem(text = { Text("Delete booking") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
