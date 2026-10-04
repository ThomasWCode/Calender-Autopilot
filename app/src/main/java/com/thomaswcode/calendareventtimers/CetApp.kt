package com.thomaswcode.calendareventtimers

import android.app.Application
import com.thomaswcode.calendareventtimers.alarm.Notifications

/**
 * Also runs before the first unlock after a reboot (the alarm components are direct-boot aware),
 * so nothing here may touch credential-protected storage.
 */
class CetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
