package cooking.zap.app.viewmodel

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class CookingTimer(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val totalSeconds: Int,
    val remainingSeconds: Int,
    val isFinished: Boolean = false
)

class CookingTimerViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _timers = MutableStateFlow<List<CookingTimer>>(emptyList())
    val timers: StateFlow<List<CookingTimer>> = _timers

    // Non-null when a timer just finished — drives the completion overlay.
    private val _completionEvent = MutableStateFlow<CookingTimer?>(null)
    val completionEvent: StateFlow<CookingTimer?> = _completionEvent

    // Whether the sheet should request POST_NOTIFICATIONS on first open.
    val needsNotificationPermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

    val hasActiveTimers: Boolean get() = _timers.value.any { !it.isFinished }

    val nextFinishing: CookingTimer?
        get() = _timers.value.filter { !it.isFinished }.minByOrNull { it.remainingSeconds }

    init {
        ensureNotificationChannel()
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(1_000)
                // Snapshot finished IDs before the tick so we can detect what's new.
                val alreadyFinishedIds = _timers.value
                    .filter { it.isFinished }.map { it.id }.toSet()
                // Atomic update — prevents races with concurrent add/remove calls.
                _timers.update { current ->
                    if (current.none { !it.isFinished }) return@update current
                    current.map { timer ->
                        if (timer.isFinished) return@map timer
                        val remaining = (timer.remainingSeconds - 1).coerceAtLeast(0)
                        if (remaining == 0) timer.copy(remainingSeconds = 0, isFinished = true)
                        else timer.copy(remainingSeconds = remaining)
                    }
                }
                val newlyFinished = _timers.value
                    .filter { it.isFinished && it.id !in alreadyFinishedIds }
                if (newlyFinished.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        newlyFinished.forEach { onTimerFinished(it) }
                    }
                }
            }
        }
    }

    fun addTimer(label: String, totalMinutes: Int) {
        if (totalMinutes <= 0) return
        val seconds = totalMinutes * 60
        val displayLabel = label.trim().ifBlank { "${totalMinutes}m timer" }
        _timers.update { it + CookingTimer(label = displayLabel, totalSeconds = seconds, remainingSeconds = seconds) }
    }

    fun removeTimer(id: String) {
        _timers.update { it.filter { t -> t.id != id } }
        if (_completionEvent.value?.id == id) stopAlertAndDismiss()
    }

    fun resetTimer(id: String) {
        _timers.update { it.map { t -> if (t.id == id) t.copy(remainingSeconds = t.totalSeconds, isFinished = false) else t } }
    }

    fun clearFinished() {
        _timers.update { it.filter { t -> !t.isFinished } }
    }

    fun dismissCompletion() {
        stopAlertAndDismiss()
    }

    private fun stopAlertAndDismiss() {
        try {
            activeRingtone?.stop()
        } catch (_: Exception) {}
        activeRingtone = null
        _completionEvent.value = null
    }

    private fun onTimerFinished(timer: CookingTimer) {
        _completionEvent.value = timer
        vibrate()
        playAlarm()
        fireNotification(timer.label)
        // Auto-dismiss overlay and stop sound after 60 seconds if not manually dismissed.
        viewModelScope.launch {
            delay(ALERT_DURATION_MS)
            if (_completionEvent.value?.id == timer.id) stopAlertAndDismiss()
        }
    }

    private fun playAlarm() {
        try {
            activeRingtone?.stop()
            val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            val ringtone = RingtoneManager.getRingtone(app, uri) ?: return

            // Route through the alarm stream at full alarm volume.
            val alarmAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.audioAttributes = alarmAttrs

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.isLooping = true
                ringtone.volume = 1.0f
            }

            activeRingtone = ringtone
            ringtone.play()

            // Pre-API-28: Ringtone.isLooping is unavailable; replay the ringtone every
            // RINGTONE_REPLAY_INTERVAL_MS until the alert is dismissed or times out.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                viewModelScope.launch(Dispatchers.Main) {
                    val endAt = System.currentTimeMillis() + ALERT_DURATION_MS
                    while (System.currentTimeMillis() < endAt && activeRingtone === ringtone) {
                        delay(RINGTONE_REPLAY_INTERVAL_MS)
                        if (activeRingtone === ringtone) {
                            try { ringtone.stop() } catch (_: Exception) {}
                            try { ringtone.play() } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private var activeRingtone: android.media.Ringtone? = null

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = app.getSystemService(VibratorManager::class.java)
                mgr?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 150, 80, 150, 80, 350), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vib = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vib?.vibrate(longArrayOf(0, 150, 80, 150, 80, 350), -1)
            }
        } catch (_: Exception) {}
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = app.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Cooking Timers", NotificationManager.IMPORTANCE_HIGH).apply {
                        enableVibration(true)
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    private fun fireNotification(label: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val mgr = app.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Use a global counter for the ID so concurrent timers don't overwrite each other.
            mgr.notify(notifIdCounter.getAndIncrement(), Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Timer done!")
                .setContentText("$label is ready")
                .setAutoCancel(true)
                .build())
        }
    }

    companion object {
        private const val CHANNEL_ID = "cooking_timers"
        private const val ALERT_DURATION_MS = 60_000L
        private const val RINGTONE_REPLAY_INTERVAL_MS = 10_000L
        private val notifIdCounter = AtomicInteger(1_000)
    }
}
