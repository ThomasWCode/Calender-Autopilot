package com.thomaswcode.calendareventtimers.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.calendareventtimers.alarm.AlarmRingService
import com.thomaswcode.calendareventtimers.data.AlarmEntity
import com.thomaswcode.calendareventtimers.data.AlarmState
import com.thomaswcode.calendareventtimers.data.AlarmStore
import com.thomaswcode.calendareventtimers.domain.AlarmText
import com.thomaswcode.calendareventtimers.domain.EventParser
import com.thomaswcode.calendareventtimers.outlook.OutlookReaderService
import com.thomaswcode.calendareventtimers.scan.ScanController
import com.thomaswcode.calendareventtimers.util.Prefs
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val alarmDay = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val alarmTime = DateTimeFormatter.ofPattern("HH:mm")
private val testTime = DateTimeFormatter.ofPattern("HH:mm:ss")

/** Timers (formerly the home screen): today's and tomorrow's scans, and the upcoming alarms. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimersScreen(
    scan: ScanController.State,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenLog: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AlarmStore.get(context) }
    val upcoming by store.upcoming.collectAsStateWithLifecycle(initialValue = emptyList())
    val dryRun by Prefs.dryRun.collectAsStateWithLifecycle()
    val connected by OutlookReaderService.connected.collectAsStateWithLifecycle()
    var setup by remember { mutableStateOf(SetupChecks.items(context, connected)) }
    // The buttons name the dates they scan, so keep them right across midnight.
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleResumeEffect(connected) {
        setup = SetupChecks.items(context, connected)
        today = LocalDate.now()
        onPauseOrDispose { }
    }
    LaunchedEffect(today) {
        delay(Duration.between(LocalDateTime.now(), today.plusDays(1).atStartOfDay()).toMillis().coerceAtLeast(0) + 500)
        today = LocalDate.now()
    }

    // Ask for notifications once (needed for the ringing alarm).
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        setup = SetupChecks.items(context, connected)
    }
    LaunchedEffect(Unit) {
        if (!askedForNotifications && !SetupChecks.notificationPermissionGranted(context)) {
            askedForNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun startScan(isToday: Boolean) {
        ScanController.start(context, isToday)?.let { problem -> scope.launch { snackbar.showNow(problem) } }
    }

    fun testAlarm(minutes: Long) {
        scope.launch {
            val alarm = withContext(Dispatchers.IO) { store.addTest(minutes, Instant.now(), ZoneId.systemDefault()) }
            val at = Instant.ofEpochMilli(alarm.triggerAt).atZone(ZoneId.systemDefault())
            snackbar.showNow("Test alarm set for ${testTime.format(at)}")
        }
    }

    fun cancelAlarm(alarm: AlarmEntity) {
        scope.launch {
            val before = withContext(Dispatchers.IO) { store.cancel(alarm.id) } ?: return@launch
            AlarmRingService.stopOne(context, alarm.id)
            val answer = snackbar.showNow(
                "Alarm cancelled: ${AlarmText.ellipsize(alarm.title, 32)}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
            if (answer == SnackbarResult.ActionPerformed) {
                val restored = withContext(Dispatchers.IO) { store.restore(before, Instant.now()) }
                if (!restored) snackbar.showNow("Too late to restore: its time has passed.")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = { Text("Timers") },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Dry run (set no alarms)") },
                            trailingIcon = { Checkbox(checked = dryRun, onCheckedChange = null) },
                            onClick = { Prefs.setDryRun(context, !dryRun) },
                        )
                        DropdownMenuItem(text = { Text("Test alarm in 1 minute") }, onClick = { menu = false; testAlarm(1) })
                        DropdownMenuItem(text = { Text("Test alarm in 2 minutes") }, onClick = { menu = false; testAlarm(2) })
                        DropdownMenuItem(text = { Text("Setup checklist") }, onClick = { menu = false; onOpenSetup() })
                        DropdownMenuItem(text = { Text("Activity log") }, onClick = { menu = false; onOpenLog() })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val missing = setup.filter { it.required && !it.done }
            if (missing.isNotEmpty()) item { SetupNeededCard(missing, onOpenSetup) }
            if (dryRun) item { DryRunCard(onTurnOff = { Prefs.setDryRun(context, false) }) }
            when (scan) {
                is ScanController.State.Running -> item { RunningCard(scan, onStop = { ScanController.stop() }) }
                is ScanController.State.Failed -> item {
                    FailedCard(scan, onRetry = { startScan(scan.isToday) }, onDismiss = { ScanController.clear() }, onOpenLog = onOpenLog)
                }
                else -> Unit
            }
            item {
                ScanButtons(
                    today = today,
                    enabled = scan !is ScanController.State.Running,
                    onToday = { startScan(true) },
                    onTomorrow = { startScan(false) },
                )
            }
            item {
                Text(
                    "Upcoming alarms",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (upcoming.isEmpty()) {
                item {
                    Text("No upcoming alarms.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(upcoming, key = { it.id }) { alarm -> UpcomingRow(alarm, onCancel = { cancelAlarm(alarm) }) }
        }
    }
}

@Composable
private fun ScanButtons(today: LocalDate, enabled: Boolean, onToday: () -> Unit, onTomorrow: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onToday, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
            ButtonLabel("Set alarms for today", EventParser.dayLabel(today))
        }
        FilledTonalButton(onClick = onTomorrow, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
            ButtonLabel("Set alarms for tomorrow", EventParser.dayLabel(today.plusDays(1)))
        }
    }
}

@Composable
private fun ButtonLabel(title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun UpcomingRow(alarm: AlarmEntity, onCancel: () -> Unit) {
    val at = Instant.ofEpochMilli(alarm.triggerAt).atZone(ZoneId.systemDefault())
    val notes = listOfNotNull(
        "5 min before ${alarm.eventStart}".takeIf { alarm.offsetMin > 0 && alarm.state == AlarmState.SCHEDULED },
        "Snoozed".takeIf { alarm.state == AlarmState.SNOOZED },
        "Ringing".takeIf { alarm.state == AlarmState.RINGING },
    )
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${alarmDay.format(at)}  ${alarmTime.format(at)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    AlarmText.ellipsize(AlarmText.label(alarm.title, alarm.label, alarm.location)),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (notes.isNotEmpty()) {
                    Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Cancel this alarm") }
        }
    }
}

@Composable
private fun SetupNeededCard(missing: List<SetupItem>, onOpenSetup: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
            missing.forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = onOpenSetup, modifier = Modifier.padding(top = 8.dp)) { Text("Open the setup checklist") }
        }
    }
}

@Composable
private fun DryRunCard(onTurnOff: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Dry run is on: scans list events but set no alarms.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onTurnOff, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer)) { Text("Turn off") }
        }
    }
}

@Composable
private fun RunningCard(scan: ScanController.State.Running, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Checking ${EventParser.dayLabel(scan.target)}…", style = MaterialTheme.typography.titleSmall)
                Text(scan.step, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onStop) { Text("Stop") }
        }
    }
}

@Composable
private fun FailedCard(scan: ScanController.State.Failed, onRetry: () -> Unit, onDismiss: () -> Unit, onOpenLog: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("Couldn't check ${EventParser.dayLabel(scan.target)}", style = MaterialTheme.typography.titleMedium)
            Text(scan.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            Text("No alarms were set.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenLog, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("Log") }
                TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("Dismiss") }
                Button(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}

/** Shows [message] at once: a newer message replaces the one on screen instead of queueing behind it. */
suspend fun SnackbarHostState.showNow(
    message: String,
    actionLabel: String? = null,
    duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(message, actionLabel, duration = duration)
}
