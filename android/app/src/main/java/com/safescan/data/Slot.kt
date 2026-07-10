package com.safescan.data

import android.graphics.Bitmap
import com.safescan.domain.model.Point

data class Slot(
    val id: String,
    val label: String,
    val bitmap: Bitmap? = null,
    val originalBitmap: Bitmap? = null,
    val corners: List<Point>? = null,
    val bitmapPath: String? = null,
    val originalBitmapPath: String? = null
)

