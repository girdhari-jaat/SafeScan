package com.safescan.data

import com.safescan.domain.model.Point

data class PageMetadata(
    val id: String,
    val originalFilename: String,
    val previewFilename: String,
    val filter: String = "COLOR",
    val brightness: Float = 0f,
    val contrast: Float = 1.0f,
    val sharpness: Float = 0f,
    val rotation: Int = 0,
    val recognizedText: String? = null,
    val corners: List<Point>? = null
)

data class DocumentMetadata(
    val id: String,
    val title: String,
    val createdAt: Long,
    val mode: String,
    val pages: List<PageMetadata>
)
