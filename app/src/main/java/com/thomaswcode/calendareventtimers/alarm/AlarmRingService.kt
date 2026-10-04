package com.thomaswcode.calendareventtimers.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.thomaswcode.calendareventtimers.R
import com.thomaswcode.calendareventtimers.data.AlarmEntity
import com.thomaswcode.calendareventtimers.data.AlarmState
import com.thomaswcode.calendareventtimers.data.AlarmStore
import com.thomaswcode.calendareventtimers.domain.AlarmText
import com.thomaswcode.calendareventtimers.domain.TriggerTime
import com.thomaswcode.calendareventtimers.util.ScanLog
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rings due alarms: alarm-stream sound, vibration, a wake lock, and a full-screen notification
 * (heads-up while the phone is in use) with Snooze and Dismiss.
 *
 * It runs as a `systemExempted` foreground service, which apps holding the exact-alarm permission
 * may use. That also satisfies Android 17's background-audio rules for apps targeting it: audio
 * from the background needs a running foreground service, and the exact-alarm permission plus a
 * `USAGE_ALARM` stream waives the while-in-use requirement.
 *
 * Alarms due together ring together. Snooze and Dismiss apply to the alarms the user was shown;
 * one that starts ringing in the same moment keeps ringing. Unanswered alarms fall silent after
 * [AlarmStore.RING_GRACE] and are reported as missed.
 */
class AlarmRingService : Service() {
    data class Ringing(
        val id: Long,
        val title: String,
        val label: String,
        val location: String?,
        val eventDate: String,
        val eventStart: String,
        val offsetMin: Int,
    ) {
        val text: String get() = AlarmText.label(title, label, location)

        /** "Starts at 14:00 (in 5 min)", "Starts now (14:00)" or "Started at 14:00". */
        fun whenText(now: Instant = Instant.now()): String {
            if (label == TEST_LABEL) return "Test alarm"
            val start = runCatching {
                TriggerTime.eventStart(LocalDate.parse(eventDate), LocalTime.parse(eventStart), ZoneId.systemDefault())
            }.getOrNull() ?: return "Starts at $eventStart"
            val minutes = (Duration.between(now, start).seconds + 30).floorDiv(60)
            return when {
                minutes > 0 -> "Starts at $eventStart (in $minutes min)"
                minutes == 0L -> "Starts now ($eventStart)"
                else -> "Started at $eventStart"
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val autoSilence = Runnable { finish(ids = null, outcome = AlarmState.MISSED) }

    /** The latest start request: stopping with it can't swallow a request that arrived since. */
    private var lastStartId = 0
    private var alerting = false
    private var player: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private val toneLoop = object : Runnable {
        override fun run() {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1200)
            handler.postDelayed(this, 2000)
        }
    }
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_RING -> ring(intent.toRinging())
            ACTION_SNOOZE -> finish(intent.ids(), AlarmState.SNOOZED)
            ACTION_DISMISS -> finish(intent.ids(), AlarmState.DISMISSED)
            // Cancelled from the Upcoming list: its record is already CANCELLED, just silence it.
            ACTION_STOP_ONE -> finish(setOf(intent.getLongExtra(EXTRA_ID, -1L)), outcome = null)
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopAlerting()
        releaseWakeLock()
        scope.cancel()
        if (_ringing.value.isNotEmpty()) {
            // Stopped while ringing: hand these back to resync rather than leave them "ringing" forever.
            ScanLog.w("Alarm service stopped with ${_ringing.value.size} alarm(s) ringing")
            _ringing.value = emptyList()
        }
        super.onDestroy()
    }

    private fun ring(alarm: Ringing) {
        _ringing.update { list -> list.filterNot { it.id == alarm.id } + alarm }
        // First of all: a service started with startForegroundService() that doesn't call
        // startForeground() in time crashes the app.
        goForeground()
        holdWakeLock()
        startAlerting()
        handler.removeCallbacks(autoSilence)
        handler.postDelayed(autoSilence, AlarmStore.RING_GRACE.toMillis())
        // Cancelled from the Upcoming list in the very moment it fired? Then stop at once.
        val app = applicationContext
        scope.launch {
            val state = withContext(Dispatchers.IO) { AlarmStore.get(app).get(alarm.id)?.state }
            if (state != AlarmState.RINGING && _ringing.value.any { it.id == alarm.id }) {
                ScanLog.i("Alarm ${alarm.id} was cancelled as it fired; silenced")
                finish(setOf(alarm.id), outcome = null)
            }
        }
    }

    private fun goForeground() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifications.ID_RINGING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(Notifications.ID_RINGING, notification)
            }
        } catch (e: Exception) {
            ScanLog.e("Couldn't run the alarm in the foreground", e)
        }
    }

    /**
     * Stops ringing [ids] (all if null) and records [outcome] (SNOOZED, DISMISSED or MISSED; null
     * when the record is already up to date). Any other ringing alarms carry on.
     */
    private fun finish(ids: Set<Long>?, outcome: AlarmState?) {
        val done = _ringing.value.filter { ids == null || it.id in ids }
        if (done.isEmpty()) {
            stopIfIdle()
            return
        }
        val doneIds = done.map { it.id }.toSet()
        // Until the records are written, resync must still treat these as handled.
        finishing.addAll(doneIds)
        _ringing.update { list -> list.filterNot { it.id in doneIds } }
        if (_ringing.value.isEmpty()) {
            handler.removeCallbacks(autoSilence)
            stopAlerting()
        } else {
            goForeground() // the notification now lists the rest
        }
        val app = applicationContext
        scope.launch {
            try {
                if (outcome != null) withContext(Dispatchers.IO + NonCancellable) { record(app, done, outcome) }
            } finally {
                finishing.removeAll(doneIds)
            }
            stopIfIdle()
        }
    }

    private suspend fun record(app: Context, done: List<Ringing>, outcome: AlarmState) {
        val store = AlarmStore.get(app)
        val now = Instant.now()
        for (r in done) {
            val recorded = when (outcome) {
                AlarmState.SNOOZED -> store.snoozeRinging(r.id, now) != null
                AlarmState.MISSED -> store.finishRinging(r.id, AlarmState.MISSED).also { missed ->
                    if (missed) store.get(r.id)?.let { Notifications.postMissed(app, it) }
                }
                else -> store.finishRinging(r.id, AlarmState.DISMISSED)
            }
            ScanLog.i("Alarm ${r.id} ${if (recorded) outcome.name.lowercase() else "had already ended"}: ${r.title}")
        }
    }

    private fun stopIfIdle() {
        if (_ringing.value.isNotEmpty() || finishing.isNotEmpty()) return
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Ignored if a newer start request (another alarm firing right now) is on its way.
        stopSelf(lastStartId)
    }

    /** Held from the first ring until the service stops; each new ring renews its timeout. */
    private fun holdWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CalendarEventTimers:ring")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(AlarmStore.RING_GRACE.toMillis() + 60_000)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun startAlerting() {
        if (alerting) return
        alerting = true
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audio = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
            .also {
                val result = audio.requestAudioFocus(it)
                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) ScanLog.w("Audio focus not granted (result $result)")
            }
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) ScanLog.w("Alarm volume is 0: the alarm will be silent")

        player = startPlayer(attributes)
        if (player == null) {
            ScanLog.w("No alarm sound could be played; falling back to beeps")
            toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
            handler.post(toneLoop)
        }

        vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }?.also {
            it.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        }
    }

    /**
     * The default alarm sound. Its settings URI resolves to a copy kept in device-protected
     * storage, so it also plays before the first unlock after a reboot.
     */
    private fun startPlayer(attributes: AudioAttributes): MediaPlayer? {
        val sounds = listOf(Settings.System.DEFAULT_ALARM_ALERT_URI, Settings.System.DEFAULT_RINGTONE_URI, Settings.System.DEFAULT_NOTIFICATION_URI)
        for (uri in sounds) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attributes)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                return mp
            } catch (e: Exception) {
                ScanLog.w("Couldn't play $uri: ${e.message}")
                mp.release()
            }
        }
        return null
    }

    private fun stopAlerting() {
        if (!alerting) return
        alerting = false
        player?.runCatching { stop() }
        player?.release()
        player = null
        handler.removeCallbacks(toneLoop)
        toneGenerator?.release()
        toneGenerator = null
        vibrator?.cancel()
        vibrator = null
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun buildNotification(): Notification {
        val list = _ringing.value
        val ids = list.map { it.id }
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, AlarmRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title: String
        val body: String
        if (list.size <= 1) {
            val r = list.firstOrNull()
            title = AlarmText.ellipsize(r?.text ?: "Alarm")
            body = r?.whenText() ?: ""
        } else {
            title = "${list.size} alarms"
            body = list.joinToString("\n") { "${it.eventStart}  ${AlarmText.ellipsize(it.text)}" }
        }
        return NotificationCompat.Builder(this, Notifications.CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_snooze, "Snooze ${AlarmStore.SNOOZE_MINUTES} min", actionIntent(ACTION_SNOOZE, 2, ids))
            .addAction(R.drawable.ic_stat_alarm, "Dismiss", actionIntent(ACTION_DISMISS, 3, ids))
            // Since Android 14 an unlocked phone lets the user swipe this away; that means dismiss,
            // or the alarm would ring on with no way to stop it.
            .setDeleteIntent(actionIntent(ACTION_DISMISS, 4, ids))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun actionIntent(action: String, requestCode: Int, ids: List<Long>): PendingIntent = PendingIntent.getService(
        this, requestCode,
        Intent(this, AlarmRingService::class.java).setAction(action).putExtra(EXTRA_IDS, ids.toLongArray()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val ACTION_RING = "com.thomaswcode.calendareventtimers.action.RING"
        private const val ACTION_SNOOZE = "com.thomaswcode.calendareventtimers.action.SNOOZE"
        private const val ACTION_DISMISS = "com.thomaswcode.calendareventtimers.action.DISMISS"
        private const val ACTION_STOP_ONE = "com.thomaswcode.calendareventtimers.action.STOP_ONE"
        private const val EXTRA_ID = "id"
        private const val EXTRA_IDS = "ids"
        const val TEST_LABEL = "Test"

        private val _ringing = MutableStateFlow<List<Ringing>>(emptyList())

        /** Alarms ringing now, for the ring screen. */
        val ringing: StateFlow<List<Ringing>> = _ringing.asStateFlow()

        private val finishing: MutableSet<Long> = ConcurrentHashMap.newKeySet()

        /** Alarms ringing, or whose end is still being recorded: resync leaves these alone. */
        fun busyIds(): Set<Long> = _ringing.value.map { it.id }.toSet() + finishing

        fun ring(context: Context, alarm: AlarmEntity) {
            val intent = Intent(context, AlarmRingService::class.java)
                .setAction(ACTION_RING)
                .putExtra(EXTRA_ID, alarm.id)
                .putExtra("title", alarm.title)
                .putExtra("label", alarm.label)
                .putExtra("location", alarm.location)
                .putExtra("eventDate", alarm.eventDate)
                .putExtra("eventStart", alarm.eventStart)
                .putExtra("offsetMin", alarm.offsetMin)
            context.startForegroundService(intent)
        }

        /** Snoozes the alarms [ids], the ones the user was shown. */
        fun snooze(context: Context, ids: Collection<Long>) = send(context, ACTION_SNOOZE, ids)

        /** Dismisses the alarms [ids], the ones the user was shown. */
        fun dismiss(context: Context, ids: Collection<Long>) = send(context, ACTION_DISMISS, ids)

        /** Silences one ringing alarm whose record the caller has already cancelled. */
        fun stopOne(context: Context, id: Long) {
            if (_ringing.value.none { it.id == id }) return
            context.startService(Intent(context, AlarmRingService::class.java).setAction(ACTION_STOP_ONE).putExtra(EXTRA_ID, id))
        }

        private fun send(context: Context, action: String, ids: Collection<Long>) {
            context.startService(Intent(context, AlarmRingService::class.java).setAction(action).putExtra(EXTRA_IDS, ids.toLongArray()))
        }

        /** The alarms an action is for; null (older intents) means all that are ringing. */
        private fun Intent.ids(): Set<Long>? = getLongArrayExtra(EXTRA_IDS)?.toSet()

        private fun Intent.toRinging() = Ringing(
            id = getLongExtra(EXTRA_ID, -1L),
            title = getStringExtra("title") ?: "Alarm",
            label = getStringExtra("label") ?: "",
            location = getStringExtra("location"),
            eventDate = getStringExtra("eventDate") ?: "",
            eventStart = getStringExtra("eventStart") ?: "",
            offsetMin = getIntExtra("offsetMin", 0),
        )
    }
}
