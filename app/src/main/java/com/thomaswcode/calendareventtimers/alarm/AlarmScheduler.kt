package com.thomaswcode.calendareventtimers.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import androidx.core.net.toUri
import com.thomaswcode.calendareventtimers.data.AlarmEntity
import com.thomaswcode.calendareventtimers.ui.MainActivity

/**
 * Registers alarms with `AlarmManager.setAlarmClock()`: exact even in Doze, and shown as the
 * system's next alarm (status-bar icon, lock screen, Quick Settings). One PendingIntent per alarm,
 * keyed by its database id, so registering again replaces rather than duplicates.
 */
class AlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = alarmManager.canScheduleExactAlarms()

    /** A trigger time in the past fires at once. Throws SecurityException without the exact-alarm permission. */
    // USE_EXACT_ALARM (granted at install) covers setAlarmClock; lint only knows SCHEDULE_EXACT_ALARM.
    @SuppressLint("MissingPermission")
    fun schedule(alarm: AlarmEntity) {
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(alarm.triggerAt, showIntent()), fireIntent(alarm.id))
    }

    fun cancel(alarmId: Long) {
        alarmManager.cancel(fireIntent(alarmId))
    }

    private fun fireIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        (id and 0x7fffffff).toInt(),
        Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .setData("cet://alarm/$id".toUri())
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** What tapping the system's next-alarm display opens: the Upcoming list. */
    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
