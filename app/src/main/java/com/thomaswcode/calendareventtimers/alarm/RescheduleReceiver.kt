package com.thomaswcode.calendareventtimers.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thomaswcode.calendareventtimers.data.AlarmStore
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-registers alarms after a reboot (also before first unlock), an app update, or a clock or
 * time-zone change. Trigger times are absolute instants, so a time-zone change keeps each alarm at
 * the moment its event starts, which is also when Outlook shows it.
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ScanLog.i("Re-registering alarms after ${intent.action?.substringAfterLast('.')}")
                val ringing = AlarmRingService.busyIds()
                AlarmStore.get(context).resync(Instant.now(), ringing).forEach { Notifications.postMissed(context, it) }
            } catch (e: Exception) {
                ScanLog.e("Re-registering alarms failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
