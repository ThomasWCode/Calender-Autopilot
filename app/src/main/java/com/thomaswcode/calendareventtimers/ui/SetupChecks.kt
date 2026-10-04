package com.thomaswcode.calendareventtimers.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.thomaswcode.calendareventtimers.alarm.AlarmScheduler
import com.thomaswcode.calendareventtimers.alarm.Notifications
import com.thomaswcode.calendareventtimers.outlook.OutlookReaderService
import com.thomaswcode.calendareventtimers.outlook.OutlookSelectors
import com.thomaswcode.calendareventtimers.util.ScanLog

enum class SetupAction { ACCESSIBILITY, APP_INFO, NOTIFICATIONS, FULL_SCREEN, EXACT_ALARMS, SOUND }

data class SetupItem(
    val title: String,
    val detail: String,
    val done: Boolean,
    /** Required items block scanning or ringing; the rest are advice. */
    val required: Boolean,
    val actions: List<Pair<String, SetupAction>> = emptyList(),
)

/** PLAN.md §4.4: what must be granted on the phone, checked at every launch and resume. */
object SetupChecks {
    fun items(context: Context, readerConnected: Boolean): List<SetupItem> {
        val outlook = context.packageManager.getLaunchIntentForPackage(OutlookSelectors.PACKAGE) != null
        val readerOn = accessibilityEnabled(context)
        val notifications = Notifications.canPost(context)
        val fullScreen = Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        val exact = AlarmScheduler(context).canScheduleExact()
        val volume = context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)
        return listOf(
            SetupItem(
                "Outlook",
                if (outlook) "Installed." else "Install Microsoft Outlook and sign in to your calendar.",
                outlook, required = true,
            ),
            SetupItem(
                "Outlook reader (accessibility service)",
                when {
                    readerConnected -> "On. It only works inside Outlook, and only when you tap a button here."
                    readerOn -> "Switched on but not running. Turn it off and on again in Accessibility settings."
                    else -> "Lets the app read Outlook's calendar. In Accessibility settings, open Calendar Event Timers " +
                        "and turn it on. If Android says it's a restricted setting, open App info, tap ⋮ (top right), " +
                        "choose Allow restricted settings, then try again."
                },
                readerConnected, required = true,
                actions = listOf("Accessibility settings" to SetupAction.ACCESSIBILITY, "App info" to SetupAction.APP_INFO),
            ),
            SetupItem(
                "Notifications",
                if (notifications) "Allowed." else "Needed to show a ringing alarm.",
                notifications, required = true,
                actions = listOf("Allow" to SetupAction.NOTIFICATIONS),
            ),
            SetupItem(
                "Full-screen alarms",
                if (fullScreen) "Allowed." else "Lets a ringing alarm fill the screen when the phone is locked.",
                fullScreen, required = true,
                actions = listOf("Allow" to SetupAction.FULL_SCREEN),
            ),
            SetupItem(
                "Exact alarms",
                if (exact) "Allowed." else "Needed to ring at the exact minute.",
                exact, required = true,
                actions = listOf("Allow" to SetupAction.EXACT_ALARMS),
            ),
            SetupItem(
                "Alarm volume",
                if (volume > 0) "Not muted." else "The alarm volume is 0, so alarms would make no sound.",
                volume > 0, required = false,
                actions = listOf("Sound settings" to SetupAction.SOUND),
            ),
        )
    }

    fun accessibilityEnabled(context: Context): Boolean {
        val setting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, OutlookReaderService::class.java)
        return setting.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun notificationPermissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun open(context: Context, action: SetupAction) {
        val pkg = Uri.fromParts("package", context.packageName, null)
        val intents = when (action) {
            // The service's own settings page needs a system permission, so this is the general list.
            SetupAction.ACCESSIBILITY -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupAction.APP_INFO -> listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            SetupAction.NOTIFICATIONS -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
            // Android 13 has no such page (nor the restriction); app info is the fallback below.
            SetupAction.FULL_SCREEN ->
                if (Build.VERSION.SDK_INT >= 34) listOf(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)) else emptyList()
            SetupAction.EXACT_ALARMS -> listOf(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            SetupAction.SOUND -> listOf(Intent(Settings.ACTION_SOUND_SETTINGS))
        } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                ScanLog.w("Couldn't open ${intent.action}: ${e.message}")
            }
        }
    }
}
