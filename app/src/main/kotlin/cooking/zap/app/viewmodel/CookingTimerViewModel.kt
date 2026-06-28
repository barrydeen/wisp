package cooking.zap.app.viewmodel

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.UUID

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

    val hasActiveTimers: Boolean get() = _timers.value.any { !it.isFinished }

    val nextFinishing: CookingTimer?
        get() = _timers.value.filter { !it.isFinished }.minByOrNull { it.remainingSeconds }

    init {
        ensureNotificationChannel()
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(1_000)
                val current = _timers.value
                if (current.none { !it.isFinished }) continue
                _timers.value = current.map { timer ->
                    if (timer.isFinished) return@map timer
                    val remaining = timer.remainingSeconds - 1
                    if (remaining <= 0) {
                        onTimerFinished(timer)
                        timer.copy(remainingSeconds = 0, isFinished = true)
                    } else {
                        timer.copy(remainingSeconds = remaining)
                    }
                }
            }
        }
    }

    fun addTimer(label: String, totalMinutes: Int) {
        if (totalMinutes <= 0) return
        val seconds = totalMinutes * 60
        val displayLabel = label.trim().ifBlank { "${totalMinutes}m timer" }
        _timers.value = _timers.value + CookingTimer(
            label = displayLabel,
            totalSeconds = seconds,
            remainingSeconds = seconds
        )
    }

    fun removeTimer(id: String) {
        _timers.value = _timers.value.filter { it.id != id }
    }

    fun resetTimer(id: String) {
        _timers.value = _timers.value.map { t ->
            if (t.id == id) t.copy(remainingSeconds = t.totalSeconds, isFinished = false) else t
        }
    }

    fun clearFinished() {
        _timers.value = _timers.value.filter { !it.isFinished }
    }

    private fun onTimerFinished(timer: CookingTimer) {
        vibrate()
        fireNotification(timer.label)
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = app.getSystemService(VibratorManager::class.java)
                mgr?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 400), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vib = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vib?.vibrate(longArrayOf(0, 200, 100, 200, 100, 400), -1)
            }
        } catch (_: Exception) {}
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = app.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Cooking Timers",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    enableVibration(true)
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun fireNotification(label: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val mgr = app.getSystemService(NotificationManager::class.java) ?: return
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Timer done!")
                .setContentText("$label is ready")
                .setAutoCancel(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification().apply {
                icon = android.R.drawable.ic_lock_idle_alarm
                tickerText = "$label is ready"
            }
        }
        mgr.notify(label.hashCode(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "cooking_timers"
    }
}
