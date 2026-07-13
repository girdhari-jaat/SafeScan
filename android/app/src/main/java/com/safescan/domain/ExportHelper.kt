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
    fun getPageDimensions(pageSizeStr: String): Pair<Int, Int> {
        val isA4 = pageSizeStr.equals("A4", ignoreCase = true)
        val pageWidth = if (isA4) 595 else 612 // Letter: 612, A4: 595
        val pageHeight = if (isA4) 842 else 792 // Letter: 792, A4: 842
        return Pair(pageWidth, pageHeight)
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
        val margin = if (pageSizeStr.equals("Original", ignoreCase = true)) 0f else 36f
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
}

