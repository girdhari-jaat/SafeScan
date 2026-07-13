package com.safescan.domain

import android.graphics.RectF
import com.safescan.data.ScannerMode
import com.safescan.data.Slot

object ExportHelper {
    /**
     * Get front and back slots for a specific row index (0..3) in the PDF/Image grid.
     * For CARD mode, duplicate the same front (slot 0) and back (slot 1) slots 4 times.
     * For other modes (like GRID), map them sequentially.
     */
    fun getSlotsForGridRow(slots: List<Slot>, mode: ScannerMode, rowIndex: Int): Pair<Slot?, Slot?> {
        if (mode == ScannerMode.CARD) {
            val frontItem = slots.getOrNull(0)
            val backItem = slots.getOrNull(1)
            return Pair(frontItem, backItem)
        } else {
            val frontIdx = rowIndex * 2
            val backIdx = rowIndex * 2 + 1
            val frontItem = if (frontIdx < slots.size) slots[frontIdx] else null
            val backItem = if (backIdx < slots.size) slots[backIdx] else null
            return Pair(frontItem, backItem)
        }
    }

    /**
     * Helper to get page width and height dimensions based on selected size string.
     */
    fun getPageDimensions(pageSizeStr: String, referenceBitmap: android.graphics.Bitmap? = null): Pair<Int, Int> {
        return com.safescan.utils.PageConfig.getPageDimensions(pageSizeStr, referenceBitmap)
    }

    /**
     * Helper to calculate the drawing bounds of a bitmap centered inside a target PDF page size with margins.
     */
    fun calculateBitmapDrawingRects(
        bmpWidth: Int,
        bmpHeight: Int,
        pageWidth: Int,
        pageHeight: Int,
        pageSizeStr: String
    ): RectF {
        return com.safescan.utils.PageConfig.calculateBitmapDrawingRects(bmpWidth, bmpHeight, pageWidth, pageHeight, pageSizeStr)
    }
}

