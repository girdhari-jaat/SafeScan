package com.safescan.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import com.safescan.data.ScannerMode
import com.safescan.data.Slot
import java.io.File
import java.io.FileOutputStream

object PdfUtils {

    fun exportDocumentToPDF(
        context: Context,
        pages: List<Bitmap>,
        filename: String = "document.pdf",
        pageFormat: String = "A4",
        orientation: String = "portrait"
    ): File? {
        if (pages.isEmpty()) return null

        val pdfDocument = PdfDocument()
        val A4_WIDTH = 595
        val A4_HEIGHT = 842
        
        val width = if (orientation == "portrait") A4_WIDTH else A4_HEIGHT
        val height = if (orientation == "portrait") A4_HEIGHT else A4_WIDTH

        try {
            for ((index, bitmap) in pages.withIndex()) {
                val pageInfo = PdfDocument.PageInfo.Builder(width, height, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas: Canvas = page.canvas

                // Simple scaling
                val scale = Math.min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
                val scaledWidth = bitmap.width * scale
                val scaledHeight = bitmap.height * scale
                val left = (width - scaledWidth) / 2
                val top = (height - scaledHeight) / 2

                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth.toInt(), scaledHeight.toInt(), true)
                canvas.drawBitmap(scaledBitmap, left, top, null)

                pdfDocument.finishPage(page)
            }

            val file = File(context.cacheDir, filename)
            pdfDocument.writeTo(FileOutputStream(file))
            return file
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            pdfDocument.close()
        }
    }

    fun generatePDFFromCards(
        context: Context,
        slots: List<Slot>,
        filename: String = "cards.pdf",
        mode: ScannerMode
    ): File? {
        val filledSlots = slots.filter { it.bitmap != null }
        if (filledSlots.isEmpty()) return null

        val pdfDocument = PdfDocument()
        val width = 595 // A4 Width in points (72dpi) -> 210mm
        val height = 842 // A4 Height in points -> 297mm

        val mmToPt = 72f / 25.4f // 2.8346

        // Card dimensions
        val cardWidthPt = 85.6f * mmToPt
        val cardHeightPt = 53.98f * mmToPt

        try {
            val pageInfo = PdfDocument.PageInfo.Builder(width, height, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            for (i in 0 until 8) {
                val col = i % 2
                val row = i / 2

                val leftMm = 15.4f + col * (85.6f + 8.0f)
                val topMm = 22.5f + row * (54.0f + 12.0f)

                val leftPt = leftMm * mmToPt
                val topPt = topMm * mmToPt

                val bitmapToDraw = if (mode == ScannerMode.CARD) {
                    // ID card mode: repeat first 2 images
                    if (filledSlots.size > col) filledSlots[col].bitmap else null
                } else {
                    // Grid mode: draw all 8
                    if (filledSlots.size > i) filledSlots[i].bitmap else null
                }

                if (bitmapToDraw != null) {
                    val scaledBitmap = Bitmap.createScaledBitmap(
                        bitmapToDraw,
                        cardWidthPt.toInt(),
                        cardHeightPt.toInt(),
                        true
                    )
                    canvas.drawBitmap(scaledBitmap, leftPt, topPt, null)
                }
            }

            pdfDocument.finishPage(page)

            val file = File(context.cacheDir, filename)
            pdfDocument.writeTo(FileOutputStream(file))
            return file
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            pdfDocument.close()
        }
    }
}
