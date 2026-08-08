package com.safescan.scanner

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import com.safescan.core.ScannerDebugLogger
import com.safescan.data.Slot
import com.safescan.domain.usecase.SaveDocumentUseCase
import java.io.File
import java.io.FileOutputStream

/**
 * Helper class managing high-res bitmap LRU cache, disk persistence, and thumbnail generation.
 */
class ImageCacheHelper(
    private val context: Context,
    private val saveDocumentUseCase: SaveDocumentUseCase
) {
    private val maxCacheMemoryKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(16 * 1024)
    private val bitmapSizes = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    private val highResCache = object : LruCache<String, Bitmap>(maxCacheMemoryKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            val id = System.identityHashCode(value)
            return bitmapSizes[id] ?: try {
                if (!value.isRecycled) (value.allocationByteCount / 1024).coerceAtLeast(1) else 1
            } catch (e: Exception) {
                1
            }
        }

        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            if (oldValue != null) {
                bitmapSizes.remove(System.identityHashCode(oldValue))
            }
            Log.d("ImageCacheHelper", "Disk-Backed Hybrid LRU Cache evicted high-res bitmap for key: $key")
        }
    }

    @Synchronized
    fun get(key: String): Bitmap? = highResCache.get(key)

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        val sizeKb = try {
            if (!bitmap.isRecycled) (bitmap.allocationByteCount / 1024).coerceAtLeast(1) else 1
        } catch (e: Exception) {
            1
        }
        bitmapSizes[System.identityHashCode(bitmap)] = sizeKb
        highResCache.put(key, bitmap)
    }

    @Synchronized
    fun remove(key: String): Bitmap? {
        return highResCache.remove(key)
    }

    @Synchronized
    fun evictAll() {
        highResCache.evictAll()
        bitmapSizes.clear()
    }

    fun getKeys(): Set<String> = highResCache.snapshot().keys

    fun snapshot(): Map<String, Bitmap> = highResCache.snapshot()

    suspend fun getFullResBitmap(
        slotId: String, 
        isOriginal: Boolean = false, 
        slots: List<Slot>, 
        openedDocumentId: String?
    ): Bitmap? {
        val cacheKey = if (isOriginal) "${slotId}_original" else "${slotId}_processed"
        val cached = highResCache.get(cacheKey)
        if (cached != null) {
            if (!cached.isRecycled) {
                return cached
            } else {
                highResCache.remove(cacheKey)
            }
        }

        val slot = slots.find { it.id == slotId }
        val path = if (isOriginal) slot?.originalBitmapPath else slot?.bitmapPath
        if (path != null) {
            val file = File(path)
            if (file.exists()) {
                try {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        highResCache.put(cacheKey, bitmap)
                        return bitmap
                    }
                } catch (e: Exception) {
                    Log.e("ImageCacheHelper", "Failed to load full-res bitmap from path: $path", e)
                }
            }
        }

        openedDocumentId?.let { docId ->
            try {
                val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (isOriginal) {
                        saveDocumentUseCase.loadOriginalBitmap(docId, slotId)
                    } else {
                        saveDocumentUseCase.loadPreviewBitmap(docId, slotId)
                    }
                }
                if (bitmap != null) {
                    highResCache.put(cacheKey, bitmap)
                    return bitmap
                }
            } catch (e: Exception) {
                Log.e("ImageCacheHelper", "Failed to load bitmap from saveDocumentUseCase for $docId / $slotId", e)
            }
        }

        return null
    }

    fun saveHighResToDisk(bitmap: Bitmap, slotId: String, suffix: String): String? {
        val dir = File(context.cacheDir, "temp_scans")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        cleanupDiskCacheIfNeeded(dir)
        val file = File(dir, "${slotId}_${suffix}.jpg")
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            val sizeKb = file.length() / 1024
            ScannerDebugLogger.logSaveFullImage(sizeKb)
            file.absolutePath
        } catch (e: Exception) {
            Log.e("ImageCacheHelper", "Failed to save high-res bitmap to disk", e)
            null
        }
    }

    private fun cleanupDiskCacheIfNeeded(dir: File, maxSizeBytes: Long = 50 * 1024 * 1024L) {
        try {
            val files = dir.listFiles() ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize > maxSizeBytes) {
                val sorted = files.sortedBy { it.lastModified() }
                val targetSize = (maxSizeBytes * 0.6).toLong()
                for (f in sorted) {
                    if (totalSize <= targetSize) break
                    val len = f.length()
                    if (f.delete()) {
                        totalSize -= len
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ImageCacheHelper", "Failed to cleanup disk cache", e)
        }
    }

    fun generateThumbnail(bitmap: Bitmap, maxDimension: Int = 360): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val ratio = kotlin.math.min(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
        return if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (width * ratio).toInt(),
                (height * ratio).toInt(),
                true
            )
        } else {
            bitmap
        }
    }

    @Synchronized
    fun clearAndRecycle() {
        try {
            highResCache.evictAll()
            bitmapSizes.clear()
        } catch (e: Exception) {
            Log.e("ImageCacheHelper", "Failed to clear highResCache", e)
        }
    }
}
