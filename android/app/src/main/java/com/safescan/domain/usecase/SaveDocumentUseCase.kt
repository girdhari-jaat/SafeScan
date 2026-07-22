package com.safescan.domain.usecase

import android.graphics.Bitmap
import com.safescan.data.DocumentMetadata
import com.safescan.data.DocumentPage
import com.safescan.data.DocumentRepository
import com.safescan.domain.model.Point
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveDocumentUseCase @Inject constructor(
    private val documentRepository: DocumentRepository
) {
    fun saveDocument(
        docId: String,
        title: String,
        mode: String,
        pages: List<Pair<Bitmap, Bitmap>>
    ): DocumentMetadata? {
        return documentRepository.saveDocument(docId, title, mode, pages)
    }

    fun deleteDocument(docId: String) {
        documentRepository.deleteDocument(docId)
    }

    fun renameDocument(docId: String, newTitle: String) {
        documentRepository.renameDocument(docId, newTitle)
    }

    fun updatePageEdits(
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

    fun saveJpgToScans(bitmap: Bitmap, quality: Int): File? {
        return documentRepository.saveJpgToScans(bitmap, quality)
    }

    fun getDocuments(): List<DocumentMetadata> {
        return documentRepository.getDocuments()
    }

    fun loadOriginalBitmap(docId: String, pageId: String): Bitmap? {
        return documentRepository.loadOriginalBitmap(docId, pageId)
    }

    fun loadPreviewBitmap(docId: String, pageId: String): Bitmap? {
        return documentRepository.loadPreviewBitmap(docId, pageId)
    }
}
