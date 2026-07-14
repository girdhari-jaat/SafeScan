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
import com.safescan.core.ScannerDebugLogger

class PdfExporter(private val context: Context) {

    // IMPROVEMENT: Changed return type to Result<File> instead of throwing or returning nullable File to avoid crashes and support multi-page dynamic size PDF documents
    suspend fun exportCardsToPdf(slots: List<Slot>, filename: String, mode: ScannerMode, pageSizeStr: String = "A4", pdfOrientation: String = "Auto"): Result<File> = withContext(Dispatchers.IO) {
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

        val pdfDocument = PdfDocument()

        try {
            ScannerDebugLogger.logPdfAssemble(slots.size, pageSizeStr)
            val referenceBitmap = slots.firstOrNull { it.bitmap != null }?.bitmap
            // Use ExportHelper for page size dimensions
            val (pageWidth, pageHeight) = ExportHelper.getPageDimensions(pageSizeStr, referenceBitmap)

            val paint = Paint().apply {
                isFilterBitmap = true
                isAntiAlias = true
            }

            if (mode == ScannerMode.DOCUMENT) {
                // DOCUMENT MODE: multi-page PDF document (one full-page image per page)
                var pageNum = 1
                for (slot in slots) {
                    val bmp = slot.bitmap ?: continue
                    
                    val (currentWidth, currentHeight) = ExportHelper.getPageDimensions(pageSizeStr, bmp)

                    val finalWidth = when (pdfOrientation) {
                        "Portrait" -> minOf(currentWidth, currentHeight)
                        "Landscape" -> maxOf(currentWidth, currentHeight)
                        else -> { // Auto: dynamically match the individual bitmap's orientation
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

                    val pageInfo = PdfDocument.PageInfo.Builder(finalWidth, finalHeight, pageNum++).create()
                    val page = pdfDocument.startPage(pageInfo)
                    val canvas = page.canvas

                    val srcRect = Rect(0, 0, bmp.width, bmp.height)
                    val dstRect = ExportHelper.calculateBitmapDrawingRects(
                        bmp.width,
                        bmp.height,
                        finalWidth,
                        finalHeight,
                        pageSizeStr
                    )
                    canvas.drawBitmap(bmp, srcRect, dstRect, paint)

                    pdfDocument.finishPage(page)
                }

                
                // If no scanned pages, generate a blank placeholder page to avoid empty PDF error
                if (pageNum == 1) {
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
                    pdfDocument.finishPage(page)
                }
            } else {
                // CARD & GRID MODE: Fill slots together on a single A4/Letter page as front/back pairs
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

                // PWA logic uses a 2480x3508 canvas internally
                val W = 2480f
                val H = 3508f
                
                // Scale canvas so we can use exact same coordinates
                canvas.scale(pageWidth / W, pageHeight / H)

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

                // Fill slots using ExportHelper. In CARD mode, the front and back images are repeated 4 times on the A4 page.
                for (i in 0 until 4) {
                    val (frontItem, backItem) = ExportHelper.getSlotsForGridRow(slots, mode, i)

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
            }

            FileOutputStream(file).use { outStream ->
                pdfDocument.writeTo(outStream)
            }

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
        } finally {
            pdfDocument.close()
        }
    }
}
