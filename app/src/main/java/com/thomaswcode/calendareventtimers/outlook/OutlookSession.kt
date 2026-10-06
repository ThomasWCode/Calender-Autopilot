package com.thomaswcode.calendareventtimers.outlook

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import com.thomaswcode.calendareventtimers.ui.MainActivity
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where the app's longer jobs run, so they outlive the screen that started them. */
object AppScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** Outlook is in use by one of the app's runs; another must wait. */
class OutlookBusy : Exception("Outlook is busy with another run; try again when it has finished.")

/**
 * One stretch of time with Outlook in front, driven by the accessibility service. Only one runs at
 * a time (timers, labels, bookings). It shows the strip over the status bar with STOP, which also
 * holds the screen upright (no landscape mid-run), can hide the soft keyboard so it never covers
 * lists, and whatever happens it removes the strip, restores the keyboard and brings the app back
 * (allowed while the service is bound).
 */
class OutlookSession private constructor(
    val service: OutlookReaderService,
    val driver: UiDriver,
    private val overlay: ScanOverlay,
) {
    fun progress(text: String) = overlay.post(text)

    companion object {
        private val busy = AtomicBoolean(false)

        val isBusy: Boolean get() = busy.get()

        /** Extra on the intent that brings the app back: which screen the run belongs to. */
        const val EXTRA_RETURN_TO = "com.thomaswcode.calendareventtimers.RETURN_TO"
        const val RETURN_TIMERS = "timers"
        const val RETURN_BOOKING = "booking"
        const val RETURN_MANAGE = "manage"

        suspend fun <T> run(
            service: OutlookReaderService,
            firstStep: String,
            hideKeyboard: Boolean = false,
            returnTo: String = RETURN_TIMERS,
            onStop: () -> Unit,
            block: suspend (OutlookSession) -> T,
        ): T {
            if (!busy.compareAndSet(false, true)) throw OutlookBusy()
            val overlay = ScanOverlay(service) {
                ScanLog.i("Stop pressed")
                onStop()
            }
            val session = OutlookSession(service, UiDriver(service), overlay)
            try {
                withContext(Dispatchers.Main) {
                    overlay.show(firstStep)
                    if (hideKeyboard) keyboard(service, AccessibilityService.SHOW_MODE_HIDDEN)
                }
                if (overlay.isShowing && !isUpright(service)) waitUntilUpright(service, overlay)
                return block(session)
            } finally {
                try {
                    // Two steps: the main thread is left for the NonCancellable context, which isn't
                    // cancelled, and that one returns without a dispatch. One step (NonCancellable +
                    // Main) would throw on its way back into a stopped run, replacing whatever the run
                    // was ending with (a ScanFailure saying a form is still open) by "stopped".
                    withContext(NonCancellable) {
                        withContext(Dispatchers.Main) {
                            overlay.hide()
                            if (hideKeyboard) keyboard(service, AccessibilityService.SHOW_MODE_AUTO)
                            bringAppBack(service, returnTo)
                        }
                    }
                } finally {
                    busy.set(false)
                }
            }
        }

        private fun isUpright(service: AccessibilityService): Boolean =
            service.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation?.let { it == Surface.ROTATION_0 } ?: true

        /**
         * The run started with the screen sideways: the strip turns it upright, which takes a moment,
         * and Outlook is driven only once it has (taps and swipes assume portrait).
         */
        private suspend fun waitUntilUpright(service: AccessibilityService, overlay: ScanOverlay) {
            ScanLog.i("Turning the screen upright for the run")
            val upright = withTimeoutOrNull(3_000) {
                while (!isUpright(service)) delay(100)
                true
            }
            if (upright == null) ScanLog.w("The screen didn't turn upright; going on anyway")
            delay(400) // apps lay themselves out again after turning
            withContext(Dispatchers.Main) { overlay.fitToScreen() }
        }

        private fun keyboard(service: AccessibilityService, mode: Int) {
            runCatching { service.softKeyboardController.setShowMode(mode) }
                .onFailure { ScanLog.w("Couldn't change the keyboard's show mode: ${it.message}") }
        }

        private fun bringAppBack(service: AccessibilityService, returnTo: String) {
            runCatching {
                service.startActivity(
                    Intent(service, MainActivity::class.java)
                        .putExtra(EXTRA_RETURN_TO, returnTo)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }.onFailure { ScanLog.e("Couldn't bring the app back", it) }
        }
    }
}
