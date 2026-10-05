package com.thomaswcode.calendareventtimers.outlook

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import com.thomaswcode.calendareventtimers.ui.MainActivity
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** Where the app's longer jobs run, so they outlive the screen that started them. */
object AppScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** Outlook is in use by one of the app's runs; another must wait. */
class OutlookBusy : Exception("Outlook is busy with another run; try again when it has finished.")

/**
 * One stretch of time with Outlook in front, driven by the accessibility service. Only one runs at
 * a time (timers, labels, bookings). It shows the strip over the status bar with STOP, can hide the
 * soft keyboard so it never covers lists, and whatever happens it removes the strip, restores the
 * keyboard and brings the app back (allowed while the service is bound).
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

        suspend fun <T> run(
            service: OutlookReaderService,
            firstStep: String,
            hideKeyboard: Boolean = false,
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
                return block(session)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    overlay.hide()
                    if (hideKeyboard) keyboard(service, AccessibilityService.SHOW_MODE_AUTO)
                    bringAppBack(service)
                }
                busy.set(false)
            }
        }

        private fun keyboard(service: AccessibilityService, mode: Int) {
            runCatching { service.softKeyboardController.setShowMode(mode) }
                .onFailure { ScanLog.w("Couldn't change the keyboard's show mode: ${it.message}") }
        }

        private fun bringAppBack(service: AccessibilityService) {
            runCatching {
                service.startActivity(
                    Intent(service, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                )
            }.onFailure { ScanLog.e("Couldn't bring the app back", it) }
        }
    }
}
