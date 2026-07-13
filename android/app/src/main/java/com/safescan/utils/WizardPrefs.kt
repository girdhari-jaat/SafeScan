package com.safescan.utils

import android.content.Context
import android.content.SharedPreferences

class WizardPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("safescan_wizard_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_SCAN_TYPE = "scan_type"
        const val KEY_PAGE_SIZE = "page_size"
        const val KEY_IMAGE_QUALITY = "image_quality"
        const val KEY_WARP = "warp"
        const val KEY_ROTATION = "rotation"
        const val KEY_FILTER = "filter"
        const val KEY_FLASH = "flash"
        const val KEY_FOCUS_MODE = "focus_mode"
        const val KEY_LIVE_EDGE = "live_edge"
        const val KEY_AUTO_CAPTURE = "auto_capture"
        const val KEY_AUTO_CROP = "auto_crop"
        const val KEY_AUTO_SHADOW = "auto_shadow"
        const val KEY_MANUAL_CROP = "manual_crop"
        const val KEY_BATCH_MODE = "batch_mode"
        const val KEY_DONT_SHOW_AGAIN = "dont_show_again"
    }

    var scanType: String
        get() = prefs.getString(KEY_SCAN_TYPE, "Document") ?: "Document"
        set(value) = prefs.edit().putString(KEY_SCAN_TYPE, value).apply()

    var pageSize: String
        get() = prefs.getString(KEY_PAGE_SIZE, "A4") ?: "A4"
        set(value) = prefs.edit().putString(KEY_PAGE_SIZE, value).apply()

    var imageQuality: String
        get() = prefs.getString(KEY_IMAGE_QUALITY, "Fast") ?: "Fast"
        set(value) = prefs.edit().putString(KEY_IMAGE_QUALITY, value).apply()

    var warp: String
        get() = prefs.getString(KEY_WARP, "Perspective") ?: "Perspective"
        set(value) = prefs.edit().putString(KEY_WARP, value).apply()

    var rotation: String
        get() = prefs.getString(KEY_ROTATION, "Auto") ?: "Auto"
        set(value) = prefs.edit().putString(KEY_ROTATION, value).apply()

    var filter: String
        get() = prefs.getString(KEY_FILTER, "Original") ?: "Original"
        set(value) = prefs.edit().putString(KEY_FILTER, value).apply()

    var flash: String
        get() = prefs.getString(KEY_FLASH, "Auto") ?: "Auto"
        set(value) = prefs.edit().putString(KEY_FLASH, value).apply()

    var focusMode: String
        get() = prefs.getString(KEY_FOCUS_MODE, "Continuous") ?: "Continuous"
        set(value) = prefs.edit().putString(KEY_FOCUS_MODE, value).apply()

    var liveEdge: Boolean
        get() = prefs.getBoolean(KEY_LIVE_EDGE, false)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_EDGE, value).apply()

    var autoCapture: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPTURE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CAPTURE, value).apply()

    var autoCrop: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CROP, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CROP, value).apply()

    var autoShadow: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SHADOW, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SHADOW, value).apply()

    var manualCrop: Boolean
        get() = prefs.getBoolean(KEY_MANUAL_CROP, false)
        set(value) = prefs.edit().putBoolean(KEY_MANUAL_CROP, value).apply()

    var batchMode: Boolean
        get() = prefs.getBoolean(KEY_BATCH_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_BATCH_MODE, value).apply()

    var dontShowAgain: Boolean
        get() = prefs.getBoolean(KEY_DONT_SHOW_AGAIN, false)
        set(value) = prefs.edit().putBoolean(KEY_DONT_SHOW_AGAIN, value).apply()
}
