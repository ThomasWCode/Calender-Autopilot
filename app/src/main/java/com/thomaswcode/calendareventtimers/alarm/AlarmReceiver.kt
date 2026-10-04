package com.thomaswcode.calendareventtimers.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thomaswcode.calendareventtimers.data.AlarmState
import com.thomaswcode.calendareventtimers.data.AlarmStore
import com.thomaswcode.calendareventtimers.util.ScanLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fired by AlarmManager. Delivery of a setAlarmClock() alarm briefly allows starting a foreground
 * service from the background, which is how the ring service gets going.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (id < 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val store = AlarmStore.get(context)
            try {
                val alarm = store.claimForRinging(id)
                if (alarm == null) {
                    ScanLog.w("Alarm $id fired but is no longer set; ignored")
                } else {
                    ScanLog.i("Alarm $id ringing: ${alarm.title} (${alarm.label})")
                    try {
                        AlarmRingService.ring(context, alarm)
                    } catch (e: Exception) {
                        // Shouldn't happen (the alarm exempts the start), but never fail silently.
                        ScanLog.e("Alarm $id couldn't start ringing", e)
                        store.finishRinging(id, AlarmState.MISSED)
                        store.get(id)?.let { Notifications.postMissed(context, it) }
                    }
                }
            } catch (e: Exception) {
                ScanLog.e("Alarm $id failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.thomaswcode.calendareventtimers.action.FIRE"
        const val EXTRA_ALARM_ID = "alarmId"
    }
}
