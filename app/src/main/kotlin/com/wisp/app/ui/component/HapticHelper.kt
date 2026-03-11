package com.wisp.app.ui.component

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object HapticHelper {
    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun blip() {
        val v = vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(30, 80))
    }

    fun pulse() {
        val v = vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(80, 150))
    }

    fun zapBuzz() {
        val v = vibrator ?: return
        v.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 60, 40, 100),
                intArrayOf(0, 200, 0, 255),
                -1
            )
        )
    }
}
