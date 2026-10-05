package com.thomaswcode.calendareventtimers.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.calendareventtimers.outlook.OutlookReaderService
import com.thomaswcode.calendareventtimers.util.ScanLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val connected by OutlookReaderService.connected.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf(SetupChecks.items(context, connected)) }
    LifecycleResumeEffect(connected) {
        items = SetupChecks.items(context, connected)
        onPauseOrDispose { }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Once refused, Android stops asking; the app's notification settings are the way then.
        if (!granted) SetupChecks.open(context, SetupAction.NOTIFICATIONS)
        items = SetupChecks.items(context, connected)
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Likewise: after a refusal, App info → Permissions.
        if (!granted) SetupChecks.open(context, SetupAction.APP_INFO)
        items = SetupChecks.items(context, connected)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = { Text("Setup checklist") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.title }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (item.done) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = if (item.done) "Done" else "To do",
                                tint = when {
                                    item.done -> MaterialTheme.colorScheme.primary
                                    item.required -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.tertiary
                                },
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                item.title + if (!item.required) " (recommended)" else "",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Text(item.detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        if (!item.done && item.actions.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            ) {
                                item.actions.forEach { (label, action) ->
                                    OutlinedButton(onClick = {
                                        when {
                                            action == SetupAction.NOTIFICATIONS && !SetupChecks.notificationPermissionGranted(context) ->
                                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            action == SetupAction.CALENDAR -> calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                                            else -> SetupChecks.open(context, action)
                                        }
                                    }) { Text(label) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val lines by ScanLog.lines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = { Text("Activity log") },
                actions = {
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Calendar Autopilot log", lines.joinToString("\n")))
                    }) { Text("Copy") }
                    TextButton(onClick = { ScanLog.clear() }) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        if (lines.isEmpty()) {
            Text(
                "Nothing logged since the app started. Window dumps, when something goes wrong, are in logcat (adb logcat -s CET).",
                Modifier.padding(padding).padding(16.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                items(lines) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}
