package com.thomaswcode.calendareventtimers.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.thomaswcode.calendareventtimers.alarm.AlarmRingService
import com.thomaswcode.calendareventtimers.alarm.Notifications
import com.thomaswcode.calendareventtimers.data.AlarmStore
import com.thomaswcode.calendareventtimers.scan.ScanController
import com.thomaswcode.calendareventtimers.scan.ScanResult
import com.thomaswcode.calendareventtimers.util.Prefs
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Prefs.load(this)
        resyncAlarms()
        setContent { AppTheme { AppRoot() } }
    }

    /** A force-stop wipes AlarmManager's alarms until the app next runs, so re-register them on every launch. */
    private fun resyncAlarms() {
        val app = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val ringing = AlarmRingService.busyIds()
                AlarmStore.get(app).resync(Instant.now(), ringing).forEach { Notifications.postMissed(app, it) }
            }.onFailure { ScanLog.e("Re-registering alarms failed", it) }
        }
    }
}

private enum class Page { HOME, SETUP, LOG }

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val scan by ScanController.state.collectAsStateWithLifecycle()
    val dryRun by Prefs.dryRun.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // A scan that stopped part-way still offers what it read.
    val review: ScanResult? = when (val s = scan) {
        is ScanController.State.Done -> s.result
        is ScanController.State.Failed -> s.partial
        else -> null
    }

    when {
        page == Page.SETUP -> SetupScreen(onBack = { page = Page.HOME })
        page == Page.LOG -> LogScreen(onBack = { page = Page.HOME })
        review != null -> ReviewScreen(
            result = review,
            dryRun = dryRun,
            onCancel = { ScanController.clear() },
            onConfirm = { choices ->
                scope.launch {
                    val summary = ScanController.confirm(context, review, choices, dryRun)
                    ScanController.clear()
                    snackbar.showNow(summary.text + summary.failed.joinToString("") { "\n• $it" })
                }
            },
        )
        else -> HomeScreen(
            scan = scan,
            snackbar = snackbar,
            onOpenSetup = { page = Page.SETUP },
            onOpenLog = { page = Page.LOG },
        )
    }
}
