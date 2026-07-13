package com.safescan.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.safescan.data.ScannerMode

/**
 * PageConfig
 *
 * Centralized configuration manager for page sizes, orientations, aspect ratios,
 * and dimension calculations. Handles A4, Letter, Legal, Auto, and Original sizes.
 */
object PageConfig {
    const val SIZE_A4 = "A4"
    const val SIZE_LETTER = "Letter"
    const val SIZE_LEGAL = "Legal"
    const val SIZE_AUTO = "Auto"
    const val SIZE_ORIGINAL = "Original"

    val ALL_PAGE_SIZES = listOf(SIZE_A4, SIZE_LETTER, SIZE_LEGAL, SIZE_AUTO, SIZE_ORIGINAL)

    /**
     * Determines target dimension (Width, Height) in PDF points (72 DPI) based on page size.
       If bitmap is provided, auto and original are derived from it dynamically.
     */
    fun getPageDimensions(pageSizeStr: String, referenceBitmap: Bitmap? = null): Pair<Int, Int> {
        return when (pageSizeStr.uppercase()) {
            "A4" -> Pair(595, 842)
            "LETTER" -> Pair(612, 792)
            "LEGAL" -> Pair(612, 1008)
            "ORIGINAL" -> {
                if (referenceBitmap != null) {
                    Pair(referenceBitmap.width, referenceBitmap.height)
                } else {
                    Pair(595, 842) // A4 fallback
                }
            }
            "AUTO" -> {
                if (referenceBitmap != null) {
                    val ratio = referenceBitmap.height.toFloat() / referenceBitmap.width.toFloat()
                    when {
                        ratio > 1.55f -> Pair(612, 1008) // Legal
                        ratio > 1.35f -> Pair(595, 842)  // A4
                        else -> Pair(612, 792)           // Letter
                    }
                } else {
                    Pair(595, 842) // A4 fallback
                }
            }
            else -> Pair(595, 842)
        }
    }

    /**
     * Calculates drawing rectangle inside target canvas dimensions with professional margins.
     */
    fun calculateBitmapDrawingRects(
        bmpWidth: Int,
        bmpHeight: Int,
        pageWidth: Int,
        pageHeight: Int,
        pageSizeStr: String
    ): RectF {
        val margin = if (pageSizeStr.equals(SIZE_ORIGINAL, ignoreCase = true)) 0f else 36f
        val printableWidth = pageWidth - 2 * margin
        val printableHeight = pageHeight - 2 * margin

        val scaleX = printableWidth / bmpWidth
        val scaleY = printableHeight / bmpHeight
        val scale = minOf(scaleX, scaleY)

        val drawnWidth = bmpWidth * scale
        val drawnHeight = bmpHeight * scale

        val left = margin + (printableWidth - drawnWidth) / 2f
        val top = margin + (printableHeight - drawnHeight) / 2f

        return RectF(left, top, left + drawnWidth, top + drawnHeight)
    }

    /**
     * Obtains the onscreen layout ratio (width/height) based on the camera scanning mode.
     */
    fun getOnscreenLayoutRatio(context: Context?, mode: ScannerMode): Float {
        val baseRatio = com.safescan.scanner.CameraHardwareConfig.getTargetRatio(context, mode)
        return when (mode) {
            ScannerMode.CARD, ScannerMode.GRID -> {
                1f / 1.586f // Card portrait layout
            }
            ScannerMode.DOCUMENT -> {
                if (baseRatio > 1.0f) 1f / baseRatio else baseRatio // A4 portrait layout
            }
        }
    }
}
