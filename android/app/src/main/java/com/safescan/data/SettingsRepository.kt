package com.safescan.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object PreferencesKeys {
        val SCANNER_MODE = stringPreferencesKey("scanner_mode")
        val AUTO_CROP = booleanPreferencesKey("auto_crop")
        val FLASH_ON = booleanPreferencesKey("flash_on")
        val DPI = floatPreferencesKey("dpi")
        val JPEG_QUALITY = floatPreferencesKey("jpeg_quality")
        val PDF_FILENAME = stringPreferencesKey("pdf_filename")
        val PAGE_SIZE = stringPreferencesKey("page_size")
        val DOUBLE_FOCUS = booleanPreferencesKey("double_focus")
        val SAVE_JPG = booleanPreferencesKey("save_jpg")
        val AUTO_PDF = booleanPreferencesKey("auto_pdf")
        val BATCH_SCAN = booleanPreferencesKey("batch_scan")
        val SHOW_GRID = booleanPreferencesKey("show_grid")
        val CLICK_SOUND = booleanPreferencesKey("click_sound")
        val AUTO_ORIENTATION = booleanPreferencesKey("auto_orientation")
        val SHADOW_REMOVE = booleanPreferencesKey("shadow_remove")
        val AUTO_ROTATION = booleanPreferencesKey("auto_rotation")
        val DEFAULT_FILTER = stringPreferencesKey("default_filter")
        val UI_LANGUAGE = stringPreferencesKey("ui_language")
        val VIBRATE_ON_CAPTURE = booleanPreferencesKey("vibrate_on_capture")
        val SAVE_TO_GALLERY = booleanPreferencesKey("save_to_gallery")
        val LIVE_DETECT = booleanPreferencesKey("live_detect")
        val BATTERY_SAVER = booleanPreferencesKey("battery_saver")
        val USE_PHONE_CAMERA = booleanPreferencesKey("use_phone_camera")
        val USE_NATIVE_SCANNER = booleanPreferencesKey("use_native_scanner")
        val HD_MODE = stringPreferencesKey("hd_mode")
    }

    private val safeData: Flow<Preferences> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    val scannerModeFlow: Flow<ScannerMode> = safeData
        .map { preferences ->
            val modeName = preferences[PreferencesKeys.SCANNER_MODE] ?: ScannerMode.CARD.name
            try {
                ScannerMode.valueOf(modeName)
            } catch (e: IllegalArgumentException) {
                ScannerMode.CARD
            }
        }

    val autoCropFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.AUTO_CROP] ?: true }

    val flashOnFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.FLASH_ON] ?: false }

    val dpiFlow: Flow<Float> = safeData
        .map { preferences -> preferences[PreferencesKeys.DPI] ?: 300f }

    val jpegQualityFlow: Flow<Float> = safeData
        .map { preferences -> preferences[PreferencesKeys.JPEG_QUALITY] ?: 80f }

    val pdfFilenameFlow: Flow<String> = safeData
        .map { preferences -> preferences[PreferencesKeys.PDF_FILENAME] ?: "Scan_Document" }

    val pageSizeFlow: Flow<String> = safeData
        .map { preferences -> preferences[PreferencesKeys.PAGE_SIZE] ?: "A4" }

    val doubleFocusFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.DOUBLE_FOCUS] ?: false }

    val saveJpgFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.SAVE_JPG] ?: true }

    val autoPdfFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.AUTO_PDF] ?: true }

    val batchScanFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.BATCH_SCAN] ?: true }

    val showGridFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.SHOW_GRID] ?: true }

    val clickSoundFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.CLICK_SOUND] ?: true }

    val autoOrientationFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.AUTO_ORIENTATION] ?: false }

    val shadowRemoveFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.SHADOW_REMOVE] ?: false }

    val autoRotationFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.AUTO_ROTATION] ?: false }

    val defaultFilterFlow: Flow<String> = safeData
        .map { preferences -> preferences[PreferencesKeys.DEFAULT_FILTER] ?: "original" }

    val uiLanguageFlow: Flow<String> = safeData
        .map { preferences -> preferences[PreferencesKeys.UI_LANGUAGE] ?: "en" }

    val vibrateOnCaptureFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.VIBRATE_ON_CAPTURE] ?: true }

    val saveToGalleryFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.SAVE_TO_GALLERY] ?: false }

    val liveDetectFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.LIVE_DETECT] ?: true }

    val batterySaverFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.BATTERY_SAVER] ?: false }

    val usePhoneCameraFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.USE_PHONE_CAMERA] ?: false }

    val useNativeScannerFlow: Flow<Boolean> = safeData
        .map { preferences -> preferences[PreferencesKeys.USE_NATIVE_SCANNER] ?: false }

    val hdModeFlow: Flow<String> = safeData
        .map { preferences -> preferences[PreferencesKeys.HD_MODE] ?: "Standard" }

    suspend fun setScannerMode(mode: ScannerMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SCANNER_MODE] = mode.name
        }
    }

    suspend fun setAutoCrop(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_CROP] = enabled
        }
    }

    suspend fun setFlashOn(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FLASH_ON] = enabled
        }
    }

    suspend fun setDpi(dpi: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DPI] = dpi
        }
    }

    suspend fun setJpegQuality(quality: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.JPEG_QUALITY] = quality
        }
    }

    suspend fun setPdfFilename(filename: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PDF_FILENAME] = filename
        }
    }

    suspend fun setPageSize(pageSize: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PAGE_SIZE] = pageSize
        }
    }

    suspend fun setDoubleFocus(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DOUBLE_FOCUS] = enabled
        }
    }

    suspend fun setSaveJpg(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SAVE_JPG] = enabled
        }
    }

    suspend fun setAutoPdf(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_PDF] = enabled
        }
    }

    suspend fun setBatchScan(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BATCH_SCAN] = enabled
        }
    }

    suspend fun setShowGrid(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_GRID] = enabled
        }
    }

    suspend fun setClickSound(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CLICK_SOUND] = enabled
        }
    }

    suspend fun setAutoOrientation(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_ORIENTATION] = enabled
        }
    }

    suspend fun setShadowRemove(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHADOW_REMOVE] = enabled
        }
    }

    suspend fun setAutoRotation(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_ROTATION] = enabled
        }
    }

    suspend fun setDefaultFilter(filter: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_FILTER] = filter
        }
    }

    suspend fun setUiLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.UI_LANGUAGE] = language
        }
    }

    suspend fun setVibrateOnCapture(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.VIBRATE_ON_CAPTURE] = enabled
        }
    }

    suspend fun setSaveToGallery(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SAVE_TO_GALLERY] = enabled
        }
    }

    suspend fun setLiveDetect(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LIVE_DETECT] = enabled
        }
    }

    suspend fun setBatterySaver(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BATTERY_SAVER] = enabled
        }
    }

    suspend fun setUsePhoneCamera(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_PHONE_CAMERA] = enabled
        }
    }

    suspend fun setUseNativeScanner(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USE_NATIVE_SCANNER] = enabled
        }
    }

    suspend fun setHdMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HD_MODE] = mode
        }
    }
}
