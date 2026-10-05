package com.thomaswcode.calendareventtimers.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thomaswcode.calendareventtimers.domain.AlarmText
import com.thomaswcode.calendareventtimers.domain.EventParser
import com.thomaswcode.calendareventtimers.domain.ReviewItem
import com.thomaswcode.calendareventtimers.domain.TriggerTime
import com.thomaswcode.calendareventtimers.scan.Choice
import com.thomaswcode.calendareventtimers.scan.ScanResult
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

/** PLAN.md §4.3 step 8: what the scan found, with per-event ticks (alarm on, 5 min before off). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(result: ScanResult, dryRun: Boolean, onCancel: () -> Unit, onConfirm: (Map<String, Choice>) -> Unit) {
    BackHandler(onBack = onCancel)
    val choices = remember(result) {
        mutableStateMapOf<String, Choice>().apply {
            result.items.forEach { put(it.key, Choice(setAlarm = true, fiveMinBefore = false)) }
        }
    }
    var busy by remember(result) { mutableStateOf(false) }
    val toSet = result.items.count { it.existing == null && choices[it.key]?.setAlarm == true }
    val confirmText = when {
        dryRun -> "Done (dry run)"
        toSet == 0 -> "Done"
        toSet == 1 -> "Set 1 alarm"
        else -> "Set $toSet alarms"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel") }
                },
                title = {
                    Column {
                        Text(if (result.isToday) "Today" else "Tomorrow")
                        Text(EventParser.dayLabel(result.target), style = MaterialTheme.typography.bodyMedium)
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = {
                            busy = true
                            onConfirm(choices.toMap())
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(confirmText) }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            result.incomplete?.let { reason ->
                item {
                    NoticeCard(
                        "The scan stopped early",
                        "$reason These are the events it read before stopping; others that day may be missing.",
                    )
                }
            }
            item { Text(summary(result), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (result.items.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "No ${if (result.isToday) "upcoming " else ""}events labelled Moveable or Immoveable on ${EventParser.dayLabel(result.target)}.",
                            Modifier.padding(16.dp),
                        )
                    }
                }
            }
            items(result.items, key = { it.key }) { item ->
                ReviewRow(item, choices[item.key] ?: Choice(true, false)) { choices[item.key] = it }
            }
            if (result.problems.isNotEmpty()) {
                item { NoticeCard("Some events couldn't be read", result.problems.joinToString("\n") { "• $it" }) }
            }
        }
    }
}

private fun summary(result: ScanResult): String {
    val labelled = result.eventsRead - result.unlabelled
    val parts = mutableListOf("Checked ${result.eventsRead} event${if (result.eventsRead == 1) "" else "s"}", "$labelled labelled Moveable or Immoveable")
    if (result.startedSkipped > 0) parts += "${result.startedSkipped} already started"
    when {
        !result.usedOutlook -> parts += "all labels remembered, Outlook not opened"
        result.labelsRemembered > 0 -> parts += "${result.labelsRemembered} labels remembered, ${result.labelsRead} read in Outlook"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun ReviewRow(item: ReviewItem, choice: Choice, onChange: (Choice) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    TriggerTime.formatHhMm(item.event.start),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(64.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(item.event.title, style = MaterialTheme.typography.titleMedium)
                    AlarmText.shortLocation(item.event.location)?.let {
                        Text("@ $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                LabelChip(item.label)
            }
            Spacer(Modifier.height(8.dp))
            val existing = item.existing
            if (existing != null) {
                val at = existing.triggerAt.atZone(ZoneId.systemDefault())
                Text("✓ Alarm already set (${hhmm.format(at)})", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TickBox("Set alarm", choice.setAlarm, enabled = true) { onChange(choice.copy(setAlarm = it)) }
                    Spacer(Modifier.width(16.dp))
                    TickBox("5 min before", choice.fiveMinBefore && item.earlyAllowed, enabled = choice.setAlarm && item.earlyAllowed) {
                        onChange(choice.copy(fiveMinBefore = it))
                    }
                }
                if (!item.earlyAllowed) {
                    Text("Too late for 5 minutes before", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TickBox(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun LabelChip(label: String) {
    val immoveable = label.equals("Immoveable", ignoreCase = true)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (immoveable) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun NoticeCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
