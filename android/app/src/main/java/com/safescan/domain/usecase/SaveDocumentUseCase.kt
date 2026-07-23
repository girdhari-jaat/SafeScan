package com.safescan.domain.usecase

import android.graphics.Bitmap
import com.safescan.data.DocumentMetadata
import com.safescan.data.DocumentRepository
import com.safescan.data.PageSaveData
import com.safescan.domain.model.Point
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveDocumentUseCase @Inject constructor(
    private val documentRepository: DocumentRepository
) {
    suspend fun saveDocument(
        docId: String,
        title: String,
        mode: String,
        pages: List<PageSaveData>
    ): Boolean {
        return documentRepository.saveDocument(docId, title, mode, pages)
    }

    suspend fun deleteDocument(docId: String) {
        documentRepository.deleteDocument(docId)
    }

    suspend fun renameDocument(docId: String, newTitle: String) {
        documentRepository.renameDocument(docId, newTitle)
    }

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
    ) {
        documentRepository.updatePageEdits(
            docId, pageId, filter, brightness, contrast, sharpness, saturation, rotation, corners, newPreview
        )
    }

    suspend fun saveJpgToScans(bitmap: Bitmap, quality: Int): File? {
        return documentRepository.saveJpgToScans(bitmap, quality)
    }

    suspend fun getDocuments(): List<DocumentMetadata> {
        return documentRepository.getDocuments()
    }

    suspend fun loadOriginalBitmap(docId: String, pageId: String): Bitmap? {
        return documentRepository.loadOriginalBitmap(docId, pageId)
    }

    suspend fun loadPreviewBitmap(docId: String, pageId: String): Bitmap? {
        return documentRepository.loadPreviewBitmap(docId, pageId)
    }
}
