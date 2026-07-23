package com.safescan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.safescan.domain.model.Point
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "DocumentRepository"
    private val baseDir: File? by lazy {
        val dir = context.getExternalFilesDir("ScannedDocuments")
        if (dir != null && !dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    /**
     * Saves a captured bitmap to /Android/data/com.safescan/files/Scans/timestamp.jpg
     */
    suspend fun saveJpgToScans(bitmap: Bitmap, quality: Int): File? = withContext(Dispatchers.IO) {
        val scansDir = context.getExternalFilesDir("Scans") ?: return@withContext null
        if (!scansDir.exists()) {
            scansDir.mkdirs()
        }
        val file = File(scansDir, "${System.currentTimeMillis()}.jpg")
        return@withContext try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            file
        } catch (e: IOException) {
            Log.e(TAG, "Error saving captured JPG to Scans", e)
            null
        }
    }

    /**
     * Retrieves all saved documents by reading metadata.json from each sub-folder.
     */
    suspend fun getDocuments(): List<DocumentMetadata> = withContext(Dispatchers.IO) {
        val root = baseDir ?: return@withContext emptyList()
        val docsList = mutableListOf<DocumentMetadata>()

        val folders = root.listFiles { file -> file.isDirectory } ?: return@withContext emptyList()
        for (folder in folders) {
            val metaFile = File(folder, "metadata.json")
            if (metaFile.exists()) {
                try {
                    val jsonStr = metaFile.readText()
                    val doc = parseDocumentMetadata(jsonStr)
                    docsList.add(doc)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading metadata.json in ${folder.name}", e)
                }
            }
        }
        return@withContext docsList.sortedByDescending { it.createdAt }
    }

    /**
     * Saves a brand new document or updates an existing one on disk.
     * Pages is a list of Page ID to Pair(OriginalBitmap, PreviewBitmap).
     */
    suspend fun saveDocument(
        docId: String,
        title: String,
        mode: String,
        pagesData: List<PageSaveData>
    ): Boolean = withContext(Dispatchers.IO) {
        val root = baseDir ?: return@withContext false
        val docFolder = File(root, docId)
        if (!docFolder.exists()) {
            docFolder.mkdirs()
        }

        val pagesDir = File(docFolder, "pages")
        if (!pagesDir.exists()) pagesDir.mkdirs()

        val previewsDir = File(docFolder, "previews")
        if (!previewsDir.exists()) previewsDir.mkdirs()

        // Load existing metadata if any to preserve page edits!
        val existingMetaFile = File(docFolder, "metadata.json")
        val existingMeta = if (existingMetaFile.exists()) {
            try {
                parseDocumentMetadata(existingMetaFile.readText())
            } catch (e: Exception) {
                null
            }
        } else null

        val pagesMetaList = mutableListOf<PageMetadata>()

        for (page in pagesData) {
            val origFile = File(pagesDir, "${page.id}.jpg")
            val prevFile = File(previewsDir, "${page.id}.jpg")

            saveBitmapToFile(page.originalBitmap, origFile)
            saveBitmapToFile(page.previewBitmap, prevFile)

            val existingPage = existingMeta?.pages?.find { it.id == page.id }

            pagesMetaList.add(
                PageMetadata(
                    id = page.id,
                    originalFilename = "pages/${page.id}.jpg",
                    previewFilename = "previews/${page.id}.jpg",
                    filter = existingPage?.filter ?: "COLOR",
                    brightness = existingPage?.brightness ?: 0f,
                    contrast = existingPage?.contrast ?: 1.0f,
                    sharpness = existingPage?.sharpness ?: 0f,
                    saturation = existingPage?.saturation ?: 0f,
                    rotation = existingPage?.rotation ?: 0,
                    recognizedText = existingPage?.recognizedText,
                    corners = page.corners ?: existingPage?.corners
                )
            )
        }

        val meta = DocumentMetadata(
            id = docId,
            title = title,
            createdAt = existingMeta?.createdAt ?: System.currentTimeMillis(),
            mode = mode,
            pages = pagesMetaList
        )

        return@withContext writeMetaFile(docFolder, meta)
    }

    /**
     * Updates page-specific edits on an existing document metadata.
     */
    suspend fun updatePageEdits(
        docId: String,
        pageId: String,
        filter: String,
        brightness: Float,
        contrast: Float,
        sharpness: Float,
        saturation: Float = 0f,
        rotation: Int,
        corners: List<Point>?,
        newPreview: Bitmap? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val root = baseDir ?: return@withContext false
        val docFolder = File(root, docId)
        val metaFile = File(docFolder, "metadata.json")
        if (!metaFile.exists()) return@withContext false

        try {
            val jsonStr = metaFile.readText()
            val doc = parseDocumentMetadata(jsonStr)
            val updatedPages = doc.pages.map { page ->
                if (page.id == pageId) {
                    if (newPreview != null) {
                        val previewsDir = File(docFolder, "previews")
                        val prevFile = File(previewsDir, "${page.id}.jpg")
                        saveBitmapToFile(newPreview, prevFile)
                    }
                    page.copy(
                        filter = filter,
                        brightness = brightness,
                        contrast = contrast,
                        sharpness = sharpness,
                        saturation = saturation,
                        rotation = rotation,
                        corners = corners
                    )
                } else page
            }
            val updatedDoc = doc.copy(pages = updatedPages)
            return@withContext writeMetaFile(docFolder, updatedDoc)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update page edits for doc: $docId, page: $pageId", e)
            return@withContext false
        }
    }

    /**
     * Updates OCR Text for a specific page.
     */
    suspend fun updatePageOcrText(docId: String, pageId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val root = baseDir ?: return@withContext false
        val docFolder = File(root, docId)
        val metaFile = File(docFolder, "metadata.json")
        if (!metaFile.exists()) return@withContext false

        try {
            val jsonStr = metaFile.readText()
            val doc = parseDocumentMetadata(jsonStr)
            val updatedPages = doc.pages.map { page ->
                if (page.id == pageId) {
                    page.copy(recognizedText = text)
                } else page
            }
            val updatedDoc = doc.copy(pages = updatedPages)
            return@withContext writeMetaFile(docFolder, updatedDoc)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update page OCR for doc: $docId, page: $pageId", e)
            return@withContext false
        }
    }

    suspend fun deleteDocument(docId: String): Boolean = withContext(Dispatchers.IO) {
        val root = baseDir ?: return@withContext false
        val docFolder = File(root, docId)
        if (docFolder.exists()) {
            return@withContext deleteRecursive(docFolder)
        }
        return@withContext false
    }

    suspend fun renameDocument(docId: String, newTitle: String): Boolean = withContext(Dispatchers.IO) {
        val root = baseDir ?: return@withContext false
        val docFolder = File(root, docId)
        val metaFile = File(docFolder, "metadata.json")
        if (!metaFile.exists()) return@withContext false

        try {
            val jsonStr = metaFile.readText()
            val doc = parseDocumentMetadata(jsonStr)
            val updatedDoc = doc.copy(title = newTitle)
            return@withContext writeMetaFile(docFolder, updatedDoc)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename document $docId", e)
            return@withContext false
        }
    }

    suspend fun loadOriginalBitmap(docId: String, pageId: String): Bitmap? = withContext(Dispatchers.IO) {
        val root = baseDir ?: return@withContext null
        val file = File(root, "$docId/pages/$pageId.jpg")
        return@withContext if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    suspend fun loadPreviewBitmap(docId: String, pageId: String): Bitmap? = withContext(Dispatchers.IO) {
        val root = baseDir ?: return@withContext null
        val file = File(root, "$docId/previews/$pageId.jpg")
        return@withContext if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    private fun saveBitmapToFile(bmp: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error saving bitmap to file ${file.name}", e)
        }
    }

    private fun deleteRecursive(fileOrDirectory: File): Boolean {
        if (fileOrDirectory.isDirectory) {
            val children = fileOrDirectory.listFiles()
            if (children != null) {
                for (child in children) {
                    deleteRecursive(child)
                }
            }
        }
        return fileOrDirectory.delete()
    }

    private fun writeMetaFile(docFolder: File, meta: DocumentMetadata): Boolean {
        val metaFile = File(docFolder, "metadata.json")
        return try {
            val json = JSONObject().apply {
                put("id", meta.id)
                put("title", meta.title)
                put("createdAt", meta.createdAt)
                put("mode", meta.mode)

                val pagesArray = JSONArray()
                for (p in meta.pages) {
                    val pageObj = JSONObject().apply {
                        put("id", p.id)
                        put("originalFilename", p.originalFilename)
                        put("previewFilename", p.previewFilename)
                        put("filter", p.filter)
                        put("brightness", p.brightness.toDouble())
                        put("contrast", p.contrast.toDouble())
                        put("sharpness", p.sharpness.toDouble())
                        put("saturation", p.saturation.toDouble())
                        put("rotation", p.rotation)
                        put("recognizedText", p.recognizedText ?: "")

                        p.corners?.let { corners ->
                            val cornersArray = JSONArray()
                            for (pt in corners) {
                                val ptObj = JSONObject().apply {
                                    put("x", pt.x)
                                    put("y", pt.y)
                                }
                                cornersArray.put(ptObj)
                            }
                            put("corners", cornersArray)
                        }
                    }
                    pagesArray.put(pageObj)
                }
                put("pages", pagesArray)
            }

            FileOutputStream(metaFile).use { out ->
                out.write(json.toString(2).toByteArray())
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write metadata.json", e)
            false
        }
    }

    private fun parseDocumentMetadata(jsonStr: String): DocumentMetadata {
        val json = JSONObject(jsonStr)
        val id = json.getString("id")
        val title = json.getString("title")
        val createdAt = json.getLong("createdAt")
        val mode = json.getString("mode")

        val pagesArray = json.getJSONArray("pages")
        val pages = mutableListOf<PageMetadata>()

        for (i in 0 until pagesArray.length()) {
            val pObj = pagesArray.getJSONObject(i)
            val pId = pObj.getString("id")
            val originalFilename = pObj.getString("originalFilename")
            val previewFilename = pObj.getString("previewFilename")
            val filter = pObj.optString("filter", "COLOR")
            val brightness = pObj.optDouble("brightness", 0.0).toFloat()
            val contrast = pObj.optDouble("contrast", 1.0).toFloat()
            val sharpness = pObj.optDouble("sharpness", 0.0).toFloat()
            val saturation = pObj.optDouble("saturation", 0.0).toFloat()
            val rotation = pObj.optInt("rotation", 0)
            val recognizedText = pObj.optString("recognizedText", "").let { if (it.isEmpty()) null else it }

            val corners = if (pObj.has("corners")) {
                val cornersArray = pObj.getJSONArray("corners")
                val ptList = mutableListOf<Point>()
                for (j in 0 until cornersArray.length()) {
                    val ptObj = cornersArray.getJSONObject(j)
                    ptList.add(Point(ptObj.getDouble("x"), ptObj.getDouble("y")))
                }
                ptList
            } else null

            pages.add(
                PageMetadata(
                    id = pId,
                    originalFilename = originalFilename,
                    previewFilename = previewFilename,
                    filter = filter,
                    brightness = brightness,
                    contrast = contrast,
                    sharpness = sharpness,
                    saturation = saturation,
                    rotation = rotation,
                    recognizedText = recognizedText,
                    corners = corners
                )
            )
        }

        return DocumentMetadata(
            id = id,
            title = title,
            createdAt = createdAt,
            mode = mode,
            pages = pages
        )
    }
}
