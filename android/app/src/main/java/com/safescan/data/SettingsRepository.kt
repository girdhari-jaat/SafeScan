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
    }

    val scannerModeFlow: Flow<ScannerMode> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val modeName = preferences[PreferencesKeys.SCANNER_MODE] ?: ScannerMode.CARD.name
            ScannerMode.valueOf(modeName)
        }

    val autoCropFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[PreferencesKeys.AUTO_CROP] ?: true }

    val flashOnFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[PreferencesKeys.FLASH_ON] ?: false }

    val dpiFlow: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[PreferencesKeys.DPI] ?: 300f }

    val jpegQualityFlow: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[PreferencesKeys.JPEG_QUALITY] ?: 80f }

    val pdfFilenameFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[PreferencesKeys.PDF_FILENAME] ?: "Scan_Document" }

    val pageSizeFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[PreferencesKeys.PAGE_SIZE] ?: "A4" }

    val doubleFocusFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[PreferencesKeys.DOUBLE_FOCUS] ?: false }

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
}
