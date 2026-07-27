package com.safescan.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.Environment
import com.safescan.core.ScannerDebugLogger
import com.safescan.data.ScannerMode
import com.safescan.data.Slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class PdfExporter(private val context: Context) {

    suspend fun exportCardsToPdf(
        slots: List<Slot>,
        filename: String,
        mode: ScannerMode,
        pageSizeStr: String = "A4",
        pdfOrientation: String = "Auto",
        dpi: Float = 300f,
        jpegQuality: Float = 90f
    ): Result<File> = withContext(Dispatchers.IO) {
        ScannerDebugLogger.logEnter("PdfExporter.exportCardsToPdf")
        val documentDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: run {
                ScannerDebugLogger.logError("PDF", "Cannot access external files directory")
                ScannerDebugLogger.logExit("PdfExporter.exportCardsToPdf")
                return@withContext Result.failure(IllegalStateException("Cannot access external files directory"))
            }

        if (!documentDir.exists()) {
            documentDir.mkdirs()
        }

        val safeFilename = if (filename.endsWith(".pdf", ignoreCase = true)) filename else "$filename.pdf"
        val file = File(documentDir, safeFilename)

        try {
            ScannerDebugLogger.logPdfAssemble(slots.size, pageSizeStr)
            
            val pdfDocument = PdfDocument()

            val validSlots = slots.filter { 
                (it.bitmapPath != null && File(it.bitmapPath).exists()) || (it.bitmap != null && !it.bitmap!!.isRecycled) 
            }

            val paint = Paint().apply {
                isFilterBitmap = true
                isAntiAlias = true
                isDither = true
            }

            if (mode == ScannerMode.DOCUMENT) {
                if (validSlots.isEmpty()) {
                    // Empty placeholder page
                    val referenceBitmap = slots.firstOrNull()?.bitmap
                    val (pageWidth, pageHeight) = ExportHelper.getPageDimensions(pageSizeStr, referenceBitmap)
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    page.canvas.drawColor(android.graphics.Color.WHITE)
                    pdfDocument.finishPage(page)
                } else {
                    var pageIndex = 1
                    for (slot in validSlots) {
                        val bmp = loadAndPrepareBitmap(slot, jpegQuality) ?: continue
                        val (currentWidth, currentHeight) = ExportHelper.getPageDimensions(pageSizeStr, bmp)

                        val finalWidth = when (pdfOrientation) {
                            "Portrait" -> minOf(currentWidth, currentHeight)
                            "Landscape" -> maxOf(currentWidth, currentHeight)
                            else -> { // Auto
                                if (bmp.width > bmp.height) maxOf(currentWidth, currentHeight)
                                else minOf(currentWidth, currentHeight)
                            }
                        }
                        val finalHeight = when (pdfOrientation) {
                            "Portrait" -> maxOf(currentWidth, currentHeight)
                            "Landscape" -> minOf(currentWidth, currentHeight)
                            else -> { // Auto
                                if (bmp.width > bmp.height) minOf(currentWidth, currentHeight)
                                else maxOf(currentWidth, currentHeight)
                            }
                        }

                        val pageInfo = PdfDocument.PageInfo.Builder(finalWidth, finalHeight, pageIndex).create()
                        val page = pdfDocument.startPage(pageInfo)
                        val canvas = page.canvas

                        // Fill page background white
                        canvas.drawColor(android.graphics.Color.WHITE)

                        // Calculate destination drawing rect
                        val dstRect = ExportHelper.calculateStretchedDrawingRect(finalWidth, finalHeight, pageSizeStr)
                        val srcRect = Rect(0, 0, bmp.width, bmp.height)

                        canvas.drawBitmap(bmp, srcRect, dstRect, paint)

                        pdfDocument.finishPage(page)
                        pageIndex++

                        if (slot.bitmapPath != null && bmp != slot.bitmap && !bmp.isRecycled) {
                            bmp.recycle()
                        }
                    }
                }
            } else {
                // CARD or GRID mode (Single-page composition)
                val referenceBitmap = slots.firstOrNull { it.bitmap != null }?.bitmap
                val (pageWidth, pageHeight) = ExportHelper.getPageDimensions(pageSizeStr, referenceBitmap)
                val finalWidth = when (pdfOrientation) {
                    "Portrait" -> minOf(pageWidth, pageHeight)
                    "Landscape" -> maxOf(pageWidth, pageHeight)
                    else -> pageWidth
                }
                val finalHeight = when (pdfOrientation) {
                    "Portrait" -> maxOf(pageWidth, pageHeight)
                    "Landscape" -> minOf(pageWidth, pageHeight)
                    else -> pageHeight
                }

                val pageInfo = PdfDocument.PageInfo.Builder(finalWidth, finalHeight, 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                canvas.drawColor(android.graphics.Color.WHITE)

                // Scale from PWA standard coordinates (2480x3508) to PDF points
                val W = 2480f
                val H = 3508f
                canvas.scale(finalWidth / W, finalHeight / H)

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

                for (i in 0 until 4) {
                    val (frontItem, backItem) = ExportHelper.getSlotsForGridRow(slots, mode, i)
                    val (x, y) = positions[i]

                    if (frontItem != null) {
                        val frontBmp = loadAndPrepareBitmap(frontItem, jpegQuality)
                        if (frontBmp != null && !frontBmp.isRecycled) {
                            val srcRect = Rect(0, 0, frontBmp.width, frontBmp.height)
                            val dstRect = android.graphics.RectF(x, y, x + cardW, y + cardH)
                            canvas.drawBitmap(frontBmp, srcRect, dstRect, paint)
                            if (frontItem.bitmapPath != null && frontBmp != frontItem.bitmap && !frontBmp.isRecycled) {
                                frontBmp.recycle()
                            }
                        }
                    }

                    if (backItem != null) {
                        val backBmp = loadAndPrepareBitmap(backItem, jpegQuality)
                        if (backBmp != null && !backBmp.isRecycled) {
                            val srcRect = Rect(0, 0, backBmp.width, backBmp.height)
                            val dstRect = android.graphics.RectF(x + cardW + gutterX, y, x + cardW + gutterX + cardW, y + cardH)
                            canvas.drawBitmap(backBmp, srcRect, dstRect, paint)
                            if (backItem.bitmapPath != null && backBmp != backItem.bitmap && !backBmp.isRecycled) {
                                backBmp.recycle()
                            }
                        }
                    }
                }

                pdfDocument.finishPage(page)
            }

            FileOutputStream(file).use { fos ->
                pdfDocument.writeTo(fos)
            }
            pdfDocument.close()

            val sizeMb = file.length().toDouble() / (1024.0 * 1024.0)
            ScannerDebugLogger.logPdfSuccess(file.absolutePath, sizeMb)
            ScannerDebugLogger.logExit("PdfExporter.exportCardsToPdf")
            Result.success(file)
        } catch (e: Exception) {
            ScannerDebugLogger.logError("PDF", "Failed to export PDF", e)
            if (file.exists()) {
                file.delete()
            }
            ScannerDebugLogger.logExit("PdfExporter.exportCardsToPdf")
            Result.failure(e)
        }
    }

    private fun loadAndPrepareBitmap(slot: Slot, jpegQuality: Float): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBmp = if (slot.bitmapPath != null && File(slot.bitmapPath).exists()) {
            try {
                BitmapFactory.decodeFile(slot.bitmapPath, options)
            } catch (e: Exception) {
                slot.bitmap
            }
        } else {
            slot.bitmap
        } ?: return null

        if (rawBmp.isRecycled) return null

        val qualityInt = jpegQuality.toInt().coerceIn(10, 100)
        val baos = ByteArrayOutputStream()
        rawBmp.compress(Bitmap.CompressFormat.JPEG, qualityInt, baos)
        val bytes = baos.toByteArray()
        val compressedBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (rawBmp != slot.bitmap && rawBmp != compressedBmp && !rawBmp.isRecycled) {
            rawBmp.recycle()
        }
        return compressedBmp
    }
}
