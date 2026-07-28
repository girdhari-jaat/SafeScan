package com.safescan.utils

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View

object HapticFeedbackHelper {
    fun triggerHaptic(view: View? = null, context: Context? = view?.context) {
        val ctx = context ?: view?.context ?: return
        try {
            var handledByView = false
            // 1. Trigger View-level HapticFeedback respecting user settings
            view?.let { v ->
                v.isHapticFeedbackEnabled = true
                handledByView = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }

            // 2. Direct system Vibrator trigger as fallback
            if (!handledByView) {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = ctx.getSystemService(VibratorManager::class.java)
                    vibratorManager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }

                if (vibrator != null && vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val attrs = VibrationAttributes.Builder()
                            .setUsage(VibrationAttributes.USAGE_TOUCH)
                            .build()
                        val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                        vibrator.vibrate(effect, attrs)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                        vibrator.vibrate(effect)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val effect = VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
                        vibrator.vibrate(effect)
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(40)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HapticFeedbackHelper", "Failed to perform haptic feedback", e)
        }
    }
}
