package com.safescan.domain

import android.graphics.RectF
import com.safescan.data.ScannerMode
import com.safescan.data.Slot

object ExportHelper {
    /**
     * Get front and back slots for a specific row index (0..3) in the PDF/Image grid.
     * If cardLayout is "2x4": duplicate the same front (slot 0) and back (slot 1) across all 4 rows.
     * If cardLayout is "Grid" (or other): map them sequentially across rows.
     */
    fun getSlotsForGridRow(
        slots: List<Slot>,
        mode: ScannerMode,
        rowIndex: Int,
        cardLayout: String = "2x4"
    ): Pair<Slot?, Slot?> {
        val filledCount = slots.count { it.bitmap != null || it.bitmapPath != null }
        if (mode == ScannerMode.CARD && cardLayout.equals("2x4", ignoreCase = true)) {
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

    /**
     * Calculates the drawing bounds of a bitmap stretched to perfectly match the target PDF page size
     * (respecting margins), ensuring true warp/stretch without any cropping.
     */
    fun calculateStretchedDrawingRect(
        pageWidth: Int,
        pageHeight: Int,
        pageSizeStr: String
    ): RectF {
        val margin = if (pageSizeStr.equals("Original", ignoreCase = true)) 0f else 36f
        val printableWidth = pageWidth - 2 * margin
        val printableHeight = pageHeight - 2 * margin
        return RectF(margin, margin, margin + printableWidth, margin + printableHeight)
    }
}

class NativePdfWriter(outStream: java.io.OutputStream) {
    private val bufferedOut = if (outStream is java.io.BufferedOutputStream) outStream else java.io.BufferedOutputStream(outStream, 8192)
    private var bytesWritten = 0L
    private val objectOffsets = mutableListOf<Long>()

    fun write(str: String) {
        val bytes = str.toByteArray(Charsets.ISO_8859_1)
        bufferedOut.write(bytes)
        bytesWritten += bytes.size
    }

    fun write(bytes: ByteArray) {
        bufferedOut.write(bytes)
        bytesWritten += bytes.size
    }

    fun startObject(): Int {
        val objId = objectOffsets.size + 1
        objectOffsets.add(bytesWritten)
        write("$objId 0 obj\n")
        return objId
    }

    fun endObject() {
        write("endobj\n")
    }

    fun startDocument() {
        write("%PDF-1.4\n")
        val binaryHeader = byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte())
        write(binaryHeader)
    }

    fun endDocument(catalogId: Int) {
        val xrefOffset = bytesWritten
        write("xref\n")
        write("0 ${objectOffsets.size + 1}\n")
        write("0000000000 65535 f\r\n")
        for (offset in objectOffsets) {
            write(String.format(java.util.Locale.US, "%010d 00000 n\r\n", offset))
        }
        write("trailer\n")
        write("<<\n")
        write("  /Size ${objectOffsets.size + 1}\n")
        write("  /Root $catalogId 0 R\n")
        write(">>\n")
        write("startxref\n")
        write("$xrefOffset\n")
        write("%%EOF\n")
        bufferedOut.flush()
    }
}


