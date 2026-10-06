package com.thomaswcode.calendareventtimers.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.calendareventtimers.data.AlarmEntity
import com.thomaswcode.calendareventtimers.data.AlarmStore
import com.thomaswcode.calendareventtimers.data.BookingEntity
import com.thomaswcode.calendareventtimers.data.BookingStore
import com.thomaswcode.calendareventtimers.data.RoomReply
import com.thomaswcode.calendareventtimers.domain.AlarmText
import com.thomaswcode.calendareventtimers.outlook.OutlookReaderService
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val nextAlarmFormat = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK)

/** What opening the app shows (requirement R1): Timers or Room booking. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    snackbar: SnackbarHostState,
    onTimers: () -> Unit,
    onBooking: () -> Unit,
    onSettings: () -> Unit,
    onSetup: () -> Unit,
    onLog: () -> Unit,
) {
    val context = LocalContext.current
    val connected by OutlookReaderService.connected.collectAsStateWithLifecycle()
    var setup by remember { mutableStateOf(SetupChecks.items(context, connected)) }
    LifecycleResumeEffect(connected) {
        setup = SetupChecks.items(context, connected)
        onPauseOrDispose { }
    }
    val upcoming by AlarmStore.get(context).upcoming.collectAsStateWithLifecycle(initialValue = emptyList())
    val today = LocalDate.now()
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val bookings by remember(monday) { BookingStore.get(context).observeFrom(monday) }.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendar Autopilot") },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
                        DropdownMenuItem(text = { Text("Setup checklist") }, onClick = { menu = false; onSetup() })
                        DropdownMenuItem(text = { Text("Activity log") }, onClick = { menu = false; onLog() })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val missing = setup.filter { it.required && !it.done }
            if (missing.isNotEmpty()) item { SetupCard(missing, onSetup) }
            item { FeatureCard(Icons.Default.Notifications, "Timers", nextAlarm(upcoming), onTimers) }
            item { FeatureCard(Icons.Default.DateRange, "Room booking", bookingSummary(bookings, monday), onBooking) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeatureCard(icon: ImageVector, title: String, status: String, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SetupCard(missing: List<SetupItem>, onSetup: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
            missing.forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = onSetup, modifier = Modifier.padding(top = 8.dp)) { Text("Open the setup checklist") }
        }
    }
}

private fun nextAlarm(upcoming: List<AlarmEntity>): String {
    val next = upcoming.firstOrNull() ?: return "No alarms set"
    val at = Instant.ofEpochMilli(next.triggerAt).atZone(ZoneId.systemDefault())
    return "Next alarm: ${nextAlarmFormat.format(at)} ${AlarmText.ellipsize(next.title, 40)}"
}

/** "This week: 4 booked · Next week: not booked yet", from the app's saved bookings. */
private fun bookingSummary(bookings: List<BookingEntity>, monday: LocalDate): String {
    fun inWeek(b: BookingEntity, start: LocalDate) = LocalDate.parse(b.eventDate).let { !it.isBefore(start) && it.isBefore(start.plusDays(7)) }
    val thisWeek = bookings.filter { inWeek(it, monday) }
    val nextWeek = bookings.filter { inWeek(it, monday.plusWeeks(1)) }
    val parts = mutableListOf<String>()
    if (thisWeek.isNotEmpty()) parts += "This week: ${thisWeek.size} booked"
    parts += if (nextWeek.isEmpty()) "Next week: not booked yet" else "Next week: ${nextWeek.size} booked"
    val declined = (thisWeek + nextWeek).count { it.roomReply == RoomReply.DECLINED }
    if (declined > 0) parts += "$declined declined"
    val roomless = (thisWeek + nextWeek).count { it.roomReply == RoomReply.NO_ROOM }
    if (roomless > 0) parts += "$roomless without a room"
    return parts.joinToString(" · ")
}
