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
            // 1. Trigger View-level performHapticFeedback with flags to bypass view/global restrictions
            view?.let { v ->
                v.isHapticFeedbackEnabled = true
                val flags = HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    v.performHapticFeedback(HapticFeedbackConstants.CONFIRM, flags)
                } else {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, flags)
                }
            }

            // 2. Direct system Vibrator trigger as guaranteed fallback/booster for Android 12, 13, 14, 15, 16
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
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
                    try {
                        if (vibrator.areAllEffectsSupported(VibrationEffect.EFFECT_CLICK) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES) {
                            val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                            vibrator.vibrate(effect, attrs)
                        } else {
                            val effect = VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
                            vibrator.vibrate(effect, attrs)
                        }
                    } catch (e: Exception) {
                        val effect = VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
                        vibrator.vibrate(effect)
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                        vibrator.vibrate(effect)
                    } catch (e: Exception) {
                        val effect = VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
                        vibrator.vibrate(effect)
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(35)
                }
            }
        } catch (e: Exception) {
            Log.e("HapticFeedbackHelper", "Failed to perform haptic feedback", e)
        }
    }
}
