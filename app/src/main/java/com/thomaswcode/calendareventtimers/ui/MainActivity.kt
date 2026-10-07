package com.thomaswcode.calendareventtimers.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.thomaswcode.calendareventtimers.booking.BookingController
import com.thomaswcode.calendareventtimers.data.AlarmStore
import com.thomaswcode.calendareventtimers.data.BookingStore
import com.thomaswcode.calendareventtimers.outlook.OutlookSession
import com.thomaswcode.calendareventtimers.scan.ScanController
import com.thomaswcode.calendareventtimers.scan.ScanResult
import com.thomaswcode.calendareventtimers.util.Prefs
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The app's screens. Opening the app shows [LAUNCHER]: Timers or Room booking. */
enum class Page { LAUNCHER, TIMERS, BOOKING, MANAGE, SETTINGS, SETUP, LOG }

/** Requests to change screen from outside Compose (the activity's intents). */
object AppNav {
    val request = MutableStateFlow<Page?>(null)

    /** The app sent the user to another screen itself (Android's settings, from the setup checklist). */
    @Volatile
    var awayOnPurpose = false

    /**
     * The screen to show as the app comes back into view: the launcher when the user opened it after
     * it was out of sight ([wasStopped]), unless an Outlook run brought it back to a screen
     * ([returnedTo]) or the user is back from a screen the app sent them to ([awayOnPurpose]).
     */
    fun pageOnResume(wasStopped: Boolean, returnedTo: Page?, awayOnPurpose: Boolean): Page? =
        Page.LAUNCHER.takeIf { wasStopped && returnedTo == null && !awayOnPurpose }
}

class MainActivity : ComponentActivity() {
    /** Out of sight since the last onResume. */
    private var stopped = false

    /** The screen an Outlook run brought the app back to (onNewIntent), until onResume. */
    private var returnedTo: Page? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Prefs.load(this)
        resyncAlarms()
        if (savedInstanceState == null) AppNav.request.value = pageFor(intent) ?: Page.LAUNCHER
        setContent { AppTheme { AppRoot() } }
    }

    /** Brought back after an Outlook run: show that run's screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pageFor(intent)?.let {
            AppNav.request.value = it
            returnedTo = it
        }
    }

    /**
     * Opened by the user: start from the choice of Timers or Room booking (the user's answer to
     * QUESTIONS.md Q14), wherever the app was. Not when the app brought itself back after an Outlook
     * run (that run's screen, from onNewIntent, which always comes before onResume), nor on coming
     * back from a settings screen the app opened. A run waiting to be reviewed is one tap away.
     */
    override fun onResume() {
        super.onResume()
        AppNav.pageOnResume(stopped, returnedTo, AppNav.awayOnPurpose)?.let { AppNav.request.value = it }
        // Cleared on every resume, so neither carries over to the next time the user opens the app.
        stopped = false
        returnedTo = null
        AppNav.awayOnPurpose = false
    }

    override fun onStop() {
        super.onStop()
        stopped = true
    }

    private fun pageFor(intent: Intent?): Page? = when (intent?.getStringExtra(OutlookSession.EXTRA_RETURN_TO)) {
        OutlookSession.RETURN_TIMERS -> Page.TIMERS
        OutlookSession.RETURN_BOOKING -> Page.BOOKING
        OutlookSession.RETURN_MANAGE -> Page.MANAGE
        else -> null
    }

    /** A force-stop wipes AlarmManager's alarms until the app next runs, so re-register them on every launch. */
    private fun resyncAlarms() {
        val app = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val ringing = AlarmRingService.busyIds()
                AlarmStore.get(app).resync(Instant.now(), ringing).forEach { Notifications.postMissed(app, it) }
                BookingStore.get(app).prune(LocalDate.now())
            }.onFailure { ScanLog.e("Re-registering alarms failed", it) }
        }
    }

}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val scan by ScanController.state.collectAsStateWithLifecycle()
    val booking by BookingController.state.collectAsStateWithLifecycle()
    val dryRun by Prefs.dryRun.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(Page.LAUNCHER) }
    val request by AppNav.request.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        request?.let {
            page = it
            AppNav.request.value = null
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // A scan that stopped part-way still offers what it read.
    val review: ScanResult? = when (val s = scan) {
        is ScanController.State.Done -> s.result
        is ScanController.State.Failed -> s.partial
        else -> null
    }

    // Setup, log and settings go back to the screen they were opened from.
    var previous by rememberSaveable { mutableStateOf(Page.LAUNCHER) }
    fun open(to: Page) {
        if (to == Page.SETUP || to == Page.LOG || to == Page.SETTINGS) {
            if (page != Page.SETUP && page != Page.LOG && page != Page.SETTINGS) previous = page
        }
        page = to
    }

    when (page) {
        Page.LAUNCHER -> LauncherScreen(
            snackbar = snackbar,
            onTimers = { open(Page.TIMERS) },
            onBooking = { open(Page.BOOKING) },
            onSettings = { open(Page.SETTINGS) },
            onSetup = { open(Page.SETUP) },
            onLog = { open(Page.LOG) },
        )
        Page.SETUP -> SetupScreen(onBack = { page = previous })
        Page.LOG -> LogScreen(onBack = { page = previous })
        Page.SETTINGS -> SettingsScreen(snackbar = snackbar, onBack = { page = previous })
        Page.MANAGE -> ManageBookingsScreen(snackbar = snackbar, onBack = { open(Page.BOOKING) })
        Page.TIMERS -> if (review != null) {
            ReviewScreen(
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
        } else {
            TimersScreen(
                scan = scan,
                snackbar = snackbar,
                onBack = { open(Page.LAUNCHER) },
                onOpenSetup = { open(Page.SETUP) },
                onOpenLog = { open(Page.LOG) },
            )
        }
        Page.BOOKING -> when (val b = booking) {
            is BookingController.State.Ready -> BookingWizard(b, snackbar = snackbar, dryRun = dryRun)
            is BookingController.State.Done -> BookingResultsScreen(b.results, onDone = { BookingController.clear() })
            else -> RoomBookingScreen(
                state = b,
                snackbar = snackbar,
                onBack = { open(Page.LAUNCHER) },
                onManage = { open(Page.MANAGE) },
                onSettings = { open(Page.SETTINGS) },
                onSetup = { open(Page.SETUP) },
                onLog = { open(Page.LOG) },
            )
        }
    }
}
