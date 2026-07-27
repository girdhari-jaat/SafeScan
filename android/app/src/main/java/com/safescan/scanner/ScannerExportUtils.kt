package com.safescan.scanner

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

object ScannerExportUtils {

    fun saveImageToGallery(context: Context, bitmap: Bitmap, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "SafeScan_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/SafeScan")
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Image saved to DCIM/SafeScan", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("ScannerExportUtils", "Failed to save image", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun savePdfToPublicDocuments(context: Context, sourceFile: File, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val fileName = sourceFile.name
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val relativePath = Environment.DIRECTORY_DOCUMENTS + "/SafeScan/"
                val collection = MediaStore.Files.getContentUri("external")

                var existingUri: android.net.Uri? = null
                try {
                    val projection = arrayOf(MediaStore.MediaColumns._ID)
                    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND (${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?)"
                    val selectionArgs = arrayOf(
                        fileName,
                        relativePath,
                        Environment.DIRECTORY_DOCUMENTS + "/SafeScan"
                    )
                    resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                            val id = cursor.getLong(idIndex)
                            existingUri = android.content.ContentUris.withAppendedId(collection, id)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ScannerExportUtils", "Error querying MediaStore for existing file", e)
                }

                val targetUri: android.net.Uri? = existingUri ?: run {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    }
                    resolver.insert(collection, contentValues)
                }

                if (targetUri != null) {
                    try {
                        resolver.openOutputStream(targetUri, "rwt")?.use { out ->
                            FileInputStream(sourceFile).use { input ->
                                input.copyTo(out)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "PDF Saved", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("ScannerExportUtils", "Failed to save PDF", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Failed to save PDF", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to save PDF", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                try {
                    val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "SafeScan")
                    if (!publicDir.exists()) {
                        publicDir.mkdirs()
                    }
                    val targetFile = File(publicDir, fileName)
                    FileInputStream(sourceFile).use { input ->
                        java.io.FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "PDF Saved", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("ScannerExportUtils", "Failed to save PDF", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to save PDF", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
