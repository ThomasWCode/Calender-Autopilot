package com.thomaswcode.calendareventtimers.outlook

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.view.accessibility.AccessibilityEvent
import com.thomaswcode.calendareventtimers.util.ScanLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The accessibility service that drives Outlook. It does nothing by itself: scans run only when the
 * user taps a button in the app. Its config limits it to Outlook's package. While it is bound the
 * app may also start activities from the background, which is how the review screen comes back
 * after a scan.
 */
class OutlookReaderService : AccessibilityService() {
    /** Scans run here, so they outlive the activity that started them. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Debug builds only: `adb shell am broadcast -a com.thomaswcode.calendareventtimers.DEBUG_DUMP`
     * writes Outlook's tree, as this service sees it, to logcat. (`uiautomator dump` can't stand in:
     * it suspends accessibility services while it runs, and leaves out off-screen nodes.)
     */
    private val debugDump = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch { ScanLog.dump("Debug dump", UiDriver(this@OutlookReaderService).snapshot().calendarOnlyDump(maxText = 200)) }
        }
    }

    /** Debug builds only: one booking step on the current screen ([DebugProbes], PHONE-CHECKS.md). */
    private val debugProbe = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch { runCatching { DebugProbes.run(this@OutlookReaderService, intent) }.onFailure { ScanLog.e("Probe failed", it) } }
        }
    }
    private var debugDumpRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Only senders holding DUMP (adb's shell has it; ordinary apps don't) can ask.
            registerReceiver(debugDump, IntentFilter(ACTION_DEBUG_DUMP), Manifest.permission.DUMP, null, RECEIVER_EXPORTED)
            registerReceiver(debugProbe, IntentFilter(DebugProbes.ACTION), Manifest.permission.DUMP, null, RECEIVER_EXPORTED)
            debugDumpRegistered = true
        }
        ScanLog.i("Outlook reader connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (debugDumpRegistered) {
            runCatching { unregisterReceiver(debugDump) }
            runCatching { unregisterReceiver(debugProbe) }
            debugDumpRegistered = false
        }
        if (instance === this) {
            instance = null
            _connected.value = false
            ScanLog.i("Outlook reader disconnected")
        }
        scope.cancel()
    }

    companion object {
        private const val ACTION_DEBUG_DUMP = "com.thomaswcode.calendareventtimers.DEBUG_DUMP"

        @Volatile
        var instance: OutlookReaderService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
