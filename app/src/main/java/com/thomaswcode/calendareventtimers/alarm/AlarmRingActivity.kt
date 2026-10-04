package com.thomaswcode.calendareventtimers.alarm

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.calendareventtimers.R
import com.thomaswcode.calendareventtimers.data.AlarmStore
import com.thomaswcode.calendareventtimers.domain.AlarmText
import com.thomaswcode.calendareventtimers.ui.AppTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** Full-screen alarm, shown over the lock screen; it closes itself when the ringing stops. */
class AlarmRingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val ringing by AlarmRingService.ringing.collectAsStateWithLifecycle()
                LaunchedEffect(ringing.isEmpty()) { if (ringing.isEmpty()) finish() }
                RingScreen(
                    ringing = ringing,
                    onSnooze = { AlarmRingService.snooze(this, ringing.map { it.id }); finish() },
                    onDismiss = { AlarmRingService.dismiss(this, ringing.map { it.id }); finish() },
                )
            }
        }
    }
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun RingScreen(ringing: List<AlarmRingService.Ringing>, onSnooze: () -> Unit, onDismiss: () -> Unit) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1_000)
        }
    }
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(painterResource(R.drawable.ic_stat_alarm), contentDescription = null, Modifier.size(56.dp))
                Text(clockFormat.format(now), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(32.dp))
                for (alarm in ringing) {
                    Text(alarm.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                    if (alarm.label != AlarmRingService.TEST_LABEL) {
                        Text("(${alarm.label})", style = MaterialTheme.typography.titleMedium)
                    }
                    AlarmText.shortLocation(alarm.location)?.let {
                        Text("@ $it", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    }
                    Text(alarm.whenText(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(24.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onSnooze, modifier = Modifier.weight(1f).height(72.dp)) {
                    Icon(painterResource(R.drawable.ic_snooze), contentDescription = null, Modifier.size(24.dp))
                    Text("  Snooze ${AlarmStore.SNOOZE_MINUTES} min", style = MaterialTheme.typography.titleMedium)
                }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f).height(72.dp)) {
                    Text("Dismiss", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
