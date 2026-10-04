package com.thomaswcode.calendareventtimers.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.thomaswcode.calendareventtimers.R
import com.thomaswcode.calendareventtimers.data.AlarmEntity
import com.thomaswcode.calendareventtimers.domain.AlarmText
import com.thomaswcode.calendareventtimers.ui.MainActivity
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Notifications {
    const val CHANNEL_RINGING = "ringing"
    const val CHANNEL_MISSED = "missed"
    const val ID_RINGING = 1
    private const val ID_MISSED_BASE = 10_000

    private val dayTime = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

    fun createChannels(context: Context) {
        val ringing = NotificationChannel(CHANNEL_RINGING, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "The alarm screen and its Snooze and Dismiss buttons while an alarm rings."
            // The ring service plays the alarm sound (on the alarm stream) and vibrates itself.
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val missed = NotificationChannel(CHANNEL_MISSED, "Missed alarms", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Alarms that rang unanswered, or were due while the phone was off."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(ringing, missed))
    }

    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun postMissed(context: Context, alarm: AlarmEntity) {
        if (!canPost(context)) return
        val text = AlarmText.label(alarm.title, alarm.label, alarm.location)
        val due = dayTime.format(Instant.ofEpochMilli(alarm.triggerAt).atZone(ZoneId.systemDefault()))
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Missed alarm · $due")
            .setContentText(AlarmText.ellipsize(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_MISSED_BASE + (alarm.id % 50_000).toInt(), notification)
        } catch (e: SecurityException) {
            ScanLog.w("Couldn't post the missed-alarm notification: ${e.message}")
        }
    }
}
