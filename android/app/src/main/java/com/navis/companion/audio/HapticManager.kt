package com.navis.companion.audio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic feedback manager providing tactile hazard awareness for blind pedestrians.
 */
class HapticManager(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Vibrate based on alert urgency.
     * @param priority "critical", "high", "normal", or "confirmation"
     */
    fun trigger(priority: String) {
        if (vibrator == null || !vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (priority.lowercase()) {
                "critical" -> {
                    // Urgent double pulse: wait 0ms, buzz 180ms, pause 80ms, buzz 280ms
                    val timings = longArrayOf(0, 180, 80, 280)
                    val amplitudes = intArrayOf(0, 255, 0, 255)
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                }
                "high" -> {
                    // Single solid warning pulse: 220ms
                    vibrator.vibrate(VibrationEffect.createOneShot(220, 220))
                }
                "normal" -> {
                    // Gentle informational pulse: 80ms
                    vibrator.vibrate(VibrationEffect.createOneShot(80, 120))
                }
                "confirmation" -> {
                    // Quick tactile tap
                    vibrator.vibrate(VibrationEffect.createOneShot(45, 80))
                }
            }
        } else {
            @Suppress("DEPRECATION")
            when (priority.lowercase()) {
                "critical" -> vibrator.vibrate(longArrayOf(0, 180, 80, 280), -1)
                "high" -> vibrator.vibrate(220)
                else -> vibrator.vibrate(80)
            }
        }
    }
}
