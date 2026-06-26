package com.safescan.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.safescan.data.ScannerMode
import com.safescan.data.Slot

class PdfExporter(private val context: Context) {

    // IMPROVEMENT: Changed return type to Result<File> instead of throwing or returning nullable File to avoid crashes
    suspend fun exportCardsToPdf(slots: List<Slot>, filename: String, mode: ScannerMode): Result<File> = withContext(Dispatchers.IO) {
        val documentDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: return@withContext Result.failure(IllegalStateException("Cannot access external files directory"))

        if (!documentDir.exists()) {
            documentDir.mkdirs()
        }

        val safeFilename = if (filename.endsWith(".pdf", ignoreCase = true)) filename else "$filename.pdf"
        val file = File(documentDir, safeFilename)

        val pdfDocument = PdfDocument()

        try {
            // A4 in points (1/72 inch) is 595 x 842
            val pageWidth = 595
            val pageHeight = 842

            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            // PWA logic uses a 2480x3508 canvas internally
            val W = 2480f
            val H = 3508f
            
            // Scale canvas so we can use exact same coordinates
            canvas.scale(pageWidth / W, pageHeight / H)

            val paint = Paint().apply {
                isFilterBitmap = true
                isAntiAlias = true
            }

            // EXACT values from PWA
            val cardW = 1011f
            val cardH = 638f
            val gutterX = 120f
            val gridWidth = (cardW * 2) + gutterX
            val startX = (W - gridWidth) / 2f
            
            val gutterY = 100f
            val gridHeight = (cardH * 4) + (gutterY * 3)
            val startY = (H - gridHeight) / 2f

            val positions = mutableListOf<Pair<Float, Float>>()
            for (r in 0 until 4) {
                positions.add(Pair(startX, startY + (r * (cardH + gutterY))))
            }

            // Fill slots. In CARD mode, length is 2. In GRID mode, length is 8.
            for (i in 0 until 4) {
                val frontIdx = i * 2
                val backIdx = i * 2 + 1

                val frontItem = if (frontIdx < slots.size) slots[frontIdx] else null
                val backItem = if (backIdx < slots.size) slots[backIdx] else null

                val (x, y) = positions[i]

                if (frontItem?.bitmap != null) {
                    val srcRect = Rect(0, 0, frontItem.bitmap.width, frontItem.bitmap.height)
                    val dstRect = android.graphics.RectF(x, y, x + cardW, y + cardH)
                    canvas.drawBitmap(frontItem.bitmap, srcRect, dstRect, paint)
                }

                if (backItem?.bitmap != null) {
                    val srcRect = Rect(0, 0, backItem.bitmap.width, backItem.bitmap.height)
                    val dstRect = android.graphics.RectF(x + cardW + gutterX, y, x + cardW + gutterX + cardW, y + cardH)
                    canvas.drawBitmap(backItem.bitmap, srcRect, dstRect, paint)
                }
            }

            pdfDocument.finishPage(page)

            FileOutputStream(file).use { outStream ->
                pdfDocument.writeTo(outStream)
            }

            Result.success(file)
        } catch (e: Exception) {
            if (file.exists()) {
                file.delete()
            }
            Result.failure(e)
        } finally {
            pdfDocument.close()
        }
    }
}
