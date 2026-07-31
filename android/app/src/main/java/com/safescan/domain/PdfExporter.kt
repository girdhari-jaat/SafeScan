package com.safescan.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Environment
import com.safescan.core.ScannerDebugLogger
import com.safescan.data.ScannerMode
import com.safescan.data.Slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
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
        jpegQuality: Float = 90f,
        cardLayout: String = "2x4"
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

            val validSlots = slots.filter { 
                (it.bitmapPath != null && File(it.bitmapPath).exists()) || (it.bitmap != null && !it.bitmap!!.isRecycled) 
            }

            val qualityInt = jpegQuality.toInt().coerceIn(10, 100)

            FileOutputStream(file).use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    val writer = NativePdfWriter(bos)
                    writer.startDocument()

                // Object 1: Catalog
                val catalogObjId = writer.startObject()
                writer.write("<<\n")
                writer.write("  /Type /Catalog\n")
                writer.write("  /Pages 2 0 R\n")
                writer.write(">>\n")
                writer.endObject()

                if (mode == ScannerMode.DOCUMENT) {
                    val slotsToProcess = if (validSlots.isEmpty()) listOf(null) else validSlots
                    val numPages = slotsToProcess.size

                    // Object 2: Pages
                    // PageObjId for page i (1-based) = 2 + (i-1)*3 + 1 = 3, 6, 9, ...
                    val pageObjectIds = (1..numPages).map { i -> 2 + (i - 1) * 3 + 1 }
                    val pagesObjId = writer.startObject() // = 2
                    writer.write("<<\n")
                    writer.write("  /Type /Pages\n")
                    writer.write("  /Count $numPages\n")
                    writer.write("  /Kids [${pageObjectIds.joinToString(" ") { "$it 0 R" }}]\n")
                    writer.write(">>\n")
                    writer.endObject()

                    for ((idx, slot) in slotsToProcess.withIndex()) {
                        val rawBmp = if (slot != null) loadBitmap(slot) else null
                        val (currentWidth, currentHeight) = ExportHelper.getPageDimensions(pageSizeStr, rawBmp)

                        val isBmpLandscape = rawBmp != null && rawBmp.width > rawBmp.height
                        val finalWidth = when (pdfOrientation) {
                            "Portrait" -> minOf(currentWidth, currentHeight)
                            "Landscape" -> maxOf(currentWidth, currentHeight)
                            else -> { // Auto
                                if (isBmpLandscape) maxOf(currentWidth, currentHeight)
                                else minOf(currentWidth, currentHeight)
                            }
                        }
                        val finalHeight = when (pdfOrientation) {
                            "Portrait" -> maxOf(currentWidth, currentHeight)
                            "Landscape" -> minOf(currentWidth, currentHeight)
                            else -> { // Auto
                                if (isBmpLandscape) minOf(currentWidth, currentHeight)
                                else maxOf(currentWidth, currentHeight)
                            }
                        }

                        val targetWidth = if (pageSizeStr.equals("Original", ignoreCase = true)) {
                            rawBmp?.width ?: finalWidth
                        } else {
                            (finalWidth * (dpi / 72f)).toInt()
                        }
                        val targetHeight = if (pageSizeStr.equals("Original", ignoreCase = true)) {
                            rawBmp?.height ?: finalHeight
                        } else {
                            (finalHeight * (dpi / 72f)).toInt()
                        }

                        val (imgBytes, imgWidth, imgHeight) = if (rawBmp != null) {
                            val jpegData = compressToJpegBytes(rawBmp, qualityInt)
                            val actualW = rawBmp.width
                            val actualH = rawBmp.height
                            if (slot?.bitmapPath != null && rawBmp != slot.bitmap && !rawBmp.isRecycled) {
                                rawBmp.recycle()
                            }
                            Triple(jpegData, actualW, actualH)
                        } else {
                            val emptyBmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                            Canvas(emptyBmp).drawColor(Color.WHITE)
                            val jpegData = compressToJpegBytes(emptyBmp, qualityInt)
                            emptyBmp.recycle()
                            Triple(jpegData, targetWidth, targetHeight)
                        }

                        val dstRect = if (pageSizeStr.equals("Original", ignoreCase = true)) {
                            android.graphics.RectF(0f, 0f, finalWidth.toFloat(), finalHeight.toFloat())
                        } else {
                            ExportHelper.calculateBitmapDrawingRects(imgWidth, imgHeight, finalWidth, finalHeight, pageSizeStr)
                        }
                        val left = dstRect.left
                        val top = dstRect.top
                        val drawnW = dstRect.width()
                        val drawnH = dstRect.height()
                        val yBottom = finalHeight - top - drawnH

                        val pageObjId = 2 + idx * 3 + 1
                        val contentObjId = 2 + idx * 3 + 2
                        val imageObjId = 2 + idx * 3 + 3

                        // 1. Page Object
                        val pObj = writer.startObject() // = pageObjId
                        writer.write("<<\n")
                        writer.write("  /Type /Page\n")
                        writer.write("  /Parent 2 0 R\n")
                        writer.write("  /MediaBox [0 0 $finalWidth $finalHeight]\n")
                        writer.write("  /Resources <<\n")
                        writer.write("    /ProcSet [/PDF /Text /ImageB /ImageC /ImageI]\n")
                        writer.write("    /XObject << /Im1 $imageObjId 0 R >>\n")
                        writer.write("  >>\n")
                        writer.write("  /Contents $contentObjId 0 R\n")
                        writer.write(">>\n")
                        writer.endObject()

                        // 2. Content Stream
                        val contentStr = "q\n" +
                                "1 1 1 rg\n" +
                                String.format(java.util.Locale.US, "0 0 %.2f %.2f re\n", finalWidth.toFloat(), finalHeight.toFloat()) +
                                "f\n" +
                                String.format(java.util.Locale.US, "%.2f 0 0 %.2f %.2f %.2f cm\n", drawnW, drawnH, left, yBottom) +
                                "/Im1 Do\n" +
                                "Q\n"
                        val contentBytes = contentStr.toByteArray(Charsets.ISO_8859_1)

                        val cObj = writer.startObject() // = contentObjId
                        writer.write("<<\n")
                        writer.write("  /Length ${contentBytes.size}\n")
                        writer.write(">>\n")
                        writer.write("stream\n")
                        writer.write(contentBytes)
                        writer.write("\nendstream\n")
                        writer.endObject()

                        // 3. Image XObject
                        val iObj = writer.startObject() // = imageObjId
                        writer.write("<<\n")
                        writer.write("  /Type /XObject\n")
                        writer.write("  /Subtype /Image\n")
                        writer.write("  /Width $imgWidth\n")
                        writer.write("  /Height $imgHeight\n")
                        writer.write("  /ColorSpace /DeviceRGB\n")
                        writer.write("  /BitsPerComponent 8\n")
                        writer.write("  /Filter /DCTDecode\n")
                        writer.write("  /Length ${imgBytes.size}\n")
                        writer.write(">>\n")
                        writer.write("stream\n")
                        writer.write(imgBytes)
                        writer.write("\nendstream\n")
                        writer.endObject()
                    }
                } else {
                    // CARD or GRID mode (Single composite page)
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

                    // Render high-res composite bitmap canvas using target DPI
                    val canvasW = (finalWidth * (dpi / 72f)).toInt()
                    val canvasH = (finalHeight * (dpi / 72f)).toInt()
                    val compositeBmp = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(compositeBmp)
                    canvas.drawColor(Color.WHITE)

                    val paint = Paint().apply {
                        isFilterBitmap = true
                        isAntiAlias = true
                        isDither = true
                    }

                    val W = 2480f
                    val H = 3508f
                    canvas.scale(canvasW / W, canvasH / H)

                    if (cardLayout.equals("ID", ignoreCase = true)) {
                        // ID mode: Front 1 top, Back 1 bottom on A4 page
                        val cardW = 1400f
                        val cardH = 883f
                        val startX = (W - cardW) / 2f
                        val startY1 = 550f
                        val startY2 = 1950f

                        val frontItem = slots.getOrNull(0)
                        val backItem = slots.getOrNull(1)

                        if (frontItem != null) {
                            val frontBmp = loadBitmap(frontItem)
                            if (frontBmp != null && !frontBmp.isRecycled) {
                                val srcRect = Rect(0, 0, frontBmp.width, frontBmp.height)
                                val dstRect = android.graphics.RectF(startX, startY1, startX + cardW, startY1 + cardH)
                                canvas.drawBitmap(frontBmp, srcRect, dstRect, paint)
                                if (frontItem.bitmapPath != null && frontBmp != frontItem.bitmap && !frontBmp.isRecycled) {
                                    frontBmp.recycle()
                                }
                            }
                        }

                        if (backItem != null) {
                            val backBmp = loadBitmap(backItem)
                            if (backBmp != null && !backBmp.isRecycled) {
                                val srcRect = Rect(0, 0, backBmp.width, backBmp.height)
                                val dstRect = android.graphics.RectF(startX, startY2, startX + cardW, startY2 + cardH)
                                canvas.drawBitmap(backBmp, srcRect, dstRect, paint)
                                if (backItem.bitmapPath != null && backBmp != backItem.bitmap && !backBmp.isRecycled) {
                                    backBmp.recycle()
                                }
                            }
                        }
                    } else {
                        // "2x4" or "Grid" mode
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
                            val (frontItem, backItem) = ExportHelper.getSlotsForGridRow(slots, mode, i, cardLayout)
                            val (x, y) = positions[i]

                            if (frontItem != null) {
                                val frontBmp = loadBitmap(frontItem)
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
                                val backBmp = loadBitmap(backItem)
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
                    }

                    val jpegData = compressToJpegBytes(compositeBmp, qualityInt)
                    compositeBmp.recycle()

                    // Object 2: Pages
                    val pagesObjId = writer.startObject() // = 2
                    writer.write("<<\n")
                    writer.write("  /Type /Pages\n")
                    writer.write("  /Count 1\n")
                    writer.write("  /Kids [3 0 R]\n")
                    writer.write(">>\n")
                    writer.endObject()

                    val pageObjId = 3
                    val contentObjId = 4
                    val imageObjId = 5

                    // Page Object
                    val pObj = writer.startObject() // = 3
                    writer.write("<<\n")
                    writer.write("  /Type /Page\n")
                    writer.write("  /Parent 2 0 R\n")
                    writer.write("  /MediaBox [0 0 $finalWidth $finalHeight]\n")
                    writer.write("  /Resources <<\n")
                    writer.write("    /ProcSet [/PDF /Text /ImageB /ImageC /ImageI]\n")
                    writer.write("    /XObject << /Im1 $imageObjId 0 R >>\n")
                    writer.write("  >>\n")
                    writer.write("  /Contents $contentObjId 0 R\n")
                    writer.write(">>\n")
                    writer.endObject()

                    // Content Stream
                    val contentStr = "q\n" +
                            "1 1 1 rg\n" +
                            String.format(java.util.Locale.US, "0 0 %d %d re\n", finalWidth, finalHeight) +
                            "f\n" +
                            String.format(java.util.Locale.US, "%d 0 0 %d 0 0 cm\n", finalWidth, finalHeight) +
                            "/Im1 Do\n" +
                            "Q\n"
                    val contentBytes = contentStr.toByteArray(Charsets.ISO_8859_1)

                    val cObj = writer.startObject() // = 4
                    writer.write("<<\n")
                    writer.write("  /Length ${contentBytes.size}\n")
                    writer.write(">>\n")
                    writer.write("stream\n")
                    writer.write(contentBytes)
                    writer.write("\nendstream\n")
                    writer.endObject()

                    // Image XObject
                    val iObj = writer.startObject() // = 5
                    writer.write("<<\n")
                    writer.write("  /Type /XObject\n")
                    writer.write("  /Subtype /Image\n")
                    writer.write("  /Width $canvasW\n")
                    writer.write("  /Height $canvasH\n")
                    writer.write("  /ColorSpace /DeviceRGB\n")
                    writer.write("  /BitsPerComponent 8\n")
                    writer.write("  /Filter /DCTDecode\n")
                    writer.write("  /Length ${jpegData.size}\n")
                    writer.write(">>\n")
                    writer.write("stream\n")
                    writer.write(jpegData)
                    writer.write("\nendstream\n")
                    writer.endObject()
                }

                writer.endDocument(catalogId = catalogObjId)
                bos.flush()
            }
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
        }
    }

    private fun loadBitmap(slot: Slot): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return if (slot.bitmapPath != null && File(slot.bitmapPath).exists()) {
            try {
                BitmapFactory.decodeFile(slot.bitmapPath, options) ?: slot.bitmap
            } catch (e: Exception) {
                slot.bitmap
            }
        } else {
            slot.bitmap
        }
    }

    private fun compressToJpegBytes(
        bmp: Bitmap,
        quality: Int,
        targetWidth: Int? = null,
        targetHeight: Int? = null
    ): ByteArray {
        val scaledBmp = if (targetWidth != null && targetHeight != null && targetWidth > 0 && targetHeight > 0 && (targetWidth != bmp.width || targetHeight != bmp.height)) {
            Bitmap.createScaledBitmap(bmp, targetWidth, targetHeight, true)
        } else {
            val maxDim = maxOf(bmp.width, bmp.height)
            if (maxDim > 2560) {
                val scale = 2560f / maxDim
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            } else {
                bmp
            }
        }

        val baos = ByteArrayOutputStream()
        scaledBmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), baos)

        if (scaledBmp != bmp) scaledBmp.recycle()

        return baos.toByteArray()
    }
}
