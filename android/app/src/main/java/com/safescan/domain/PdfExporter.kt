package com.safescan.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import com.safescan.data.ScannerMode
import com.safescan.data.Slot
import com.safescan.core.ScannerDebugLogger

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
            
            FileOutputStream(file).use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    val writer = NativePdfWriter(bos)
                    writer.startDocument()

                    // Register PDF Catalog and Pages Tree
                    val catalogId = writer.startObject()
                    writer.write("<< /Type /Catalog /Pages 2 0 R >>\n")
                    writer.endObject()

                    val slotsWithBitmaps = slots.filter { it.bitmap != null }
                    
                    if (mode == ScannerMode.DOCUMENT) {
                        val numPages = if (slotsWithBitmaps.isEmpty()) 1 else slotsWithBitmaps.size
                        
                        // Pages Tree definition
                        val kidsBuilder = StringBuilder("[")
                        for (i in 1..numPages) {
                            kidsBuilder.append("${3 + (i - 1) * 3} 0 R ")
                        }
                        if (numPages > 0) kidsBuilder.setLength(kidsBuilder.length - 1)
                        kidsBuilder.append("]")
                        
                        writer.startObject() // Object 2: Pages Tree
                        writer.write("<< /Type /Pages /Kids ${kidsBuilder} /Count $numPages >>\n")
                        writer.endObject()

                        if (slotsWithBitmaps.isEmpty()) {
                            // Empty placeholder page
                            val referenceBitmap = slots.firstOrNull()?.bitmap
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

                            // Write Page 1 empty placeholder
                            writer.startObject() // Object 3
                            writer.write("<<\n  /Type /Page\n  /Parent 2 0 R\n  /MediaBox [0 0 $finalWidth $finalHeight]\n>>\n")
                            writer.endObject()

                            // Content Stream (empty)
                            writer.startObject() // Object 4
                            writer.write("<< /Length 0 >>\nstream\nendstream\n")
                            writer.endObject()

                            // Image XObject (dummy empty)
                            writer.startObject() // Object 5
                            writer.write("<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Length 0 >>\nstream\nendstream\n")
                            writer.endObject()
                        } else {
                            var i = 1
                            for (slot in slotsWithBitmaps) {
                                val highResBmp = if (slot.bitmapPath != null) {
                                    try {
                                        android.graphics.BitmapFactory.decodeFile(slot.bitmapPath)
                                    } catch (e: Exception) {
                                        null
                                    }
                                } else null
                                val bmp = highResBmp ?: slot.bitmap!!
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

                                val pageObjId = 3 + (i - 1) * 3
                                val contentStreamId = 4 + (i - 1) * 3
                                val imageObjId = 5 + (i - 1) * 3

                                // 1. Compress bitmap directly as JPEG with targeted DPI and Quality
                                val targetImgWidth = if (pageSizeStr.equals("Original", ignoreCase = true)) bmp.width else (finalWidth * (dpi / 72f)).toInt()
                                val targetImgHeight = if (pageSizeStr.equals("Original", ignoreCase = true)) bmp.height else (finalHeight * (dpi / 72f)).toInt()

                                val scaledBmp = if (pageSizeStr.equals("Original", ignoreCase = true)) {
                                    bmp
                                } else {
                                    Bitmap.createScaledBitmap(bmp, targetImgWidth, targetImgHeight, true)
                                }

                                val tempOs = ByteArrayOutputStream()
                                scaledBmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality.toInt(), tempOs)
                                val jpegBytes = tempOs.toByteArray()
                                if (scaledBmp != bmp) {
                                    scaledBmp.recycle()
                                }
                                if (highResBmp != null) {
                                    highResBmp.recycle()
                                }

                                // 2. Write Page Object
                                writer.startObject() // pageObjId
                                writer.write("<<\n  /Type /Page\n  /Parent 2 0 R\n  /MediaBox [0 0 $finalWidth $finalHeight]\n  /Resources <<\n    /XObject << /ImgX1 $imageObjId 0 R >>\n  >>\n  /Contents $contentStreamId 0 R\n>>\n")
                                writer.endObject()

                                // 3. Write Content Stream
                                val dstRect = ExportHelper.calculateStretchedDrawingRect(finalWidth, finalHeight, pageSizeStr)
                                val pdfW = dstRect.width()
                                val pdfH = dstRect.height()
                                val pdfX = dstRect.left
                                val pdfY = finalHeight - dstRect.bottom

                                val ctmStr = String.format(java.util.Locale.US, "%.2f 0 0 %.2f %.2f %.2f cm", pdfW, pdfH, pdfX, pdfY)
                                val streamContent = "q\n$ctmStr\n/ImgX1 Do\nQ\n"
                                val streamBytes = streamContent.toByteArray(Charsets.US_ASCII)

                                writer.startObject() // contentStreamId
                                writer.write("<< /Length ${streamBytes.size} >>\nstream\n")
                                writer.write(streamBytes)
                                writer.write("endstream\n")
                                writer.endObject()

                                // 4. Write Image Object
                                writer.startObject() // imageObjId
                                writer.write("<<\n  /Type /XObject\n  /Subtype /Image\n  /Width $targetImgWidth\n  /Height $targetImgHeight\n  /ColorSpace /DeviceRGB\n  /BitsPerComponent 8\n  /Filter /DCTDecode\n  /Length ${jpegBytes.size}\n>>\nstream\n")
                                writer.write(jpegBytes)
                                writer.write("\nendstream\n")
                                writer.endObject()

                                i++
                            }
                        }
                    } else {
                        // CARD or GRID mode (Single-page composition)
                        writer.startObject() // Object 2: Pages Tree
                        writer.write("<< /Type /Pages /Kids [3 0 R] /Count 1 >>\n")
                        writer.endObject()

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

                        // Write Page Object
                        writer.startObject() // Object 3: Page Object
                        writer.write("<<\n  /Type /Page\n  /Parent 2 0 R\n  /MediaBox [0 0 $finalWidth $finalHeight]\n  /Resources <<\n    /XObject << /ImgX1 5 0 R >>\n  >>\n  /Contents 4 0 R\n>>\n")
                        writer.endObject()

                        // Write Content Stream
                        val ctmStr = String.format(java.util.Locale.US, "%d 0 0 %d 0 0 cm", finalWidth, finalHeight)
                        val streamContent = "q\n$ctmStr\n/ImgX1 Do\nQ\n"
                        val streamBytes = streamContent.toByteArray(Charsets.US_ASCII)

                        writer.startObject() // Object 4: Content Stream
                        writer.write("<< /Length ${streamBytes.size} >>\nstream\n")
                        writer.write(streamBytes)
                        writer.write("endstream\n")
                        writer.endObject()

                        // Composing cards on high-res canvas
                        val targetWidthPx = (finalWidth * (dpi / 72f)).toInt()
                        val targetHeightPx = (finalHeight * (dpi / 72f)).toInt()

                        val composedBmp = Bitmap.createBitmap(targetWidthPx, targetHeightPx, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(composedBmp)
                        canvas.drawColor(android.graphics.Color.WHITE)

                        // Scale from points to high-res pixels
                        canvas.scale(dpi / 72f, dpi / 72f)

                        // Scale from PWA coordinates (2480x3508) to points
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

                        val paint = Paint().apply {
                            isFilterBitmap = true
                            isAntiAlias = true
                        }

                        for (i in 0 until 4) {
                            val (frontItem, backItem) = ExportHelper.getSlotsForGridRow(slots, mode, i)
                            val (x, y) = positions[i]

                            if (frontItem?.bitmap != null) {
                                val frontHighRes = if (frontItem.bitmapPath != null) {
                                    try {
                                        android.graphics.BitmapFactory.decodeFile(frontItem.bitmapPath)
                                    } catch (e: Exception) {
                                        null
                                    }
                                } else null
                                val frontBmp = frontHighRes ?: frontItem.bitmap!!

                                val srcRect = Rect(0, 0, frontBmp.width, frontBmp.height)
                                val dstRect = android.graphics.RectF(x, y, x + cardW, y + cardH)
                                canvas.drawBitmap(frontBmp, srcRect, dstRect, paint)

                                if (frontHighRes != null) {
                                    frontHighRes.recycle()
                                }
                            }

                            if (backItem?.bitmap != null) {
                                val backHighRes = if (backItem.bitmapPath != null) {
                                    try {
                                        android.graphics.BitmapFactory.decodeFile(backItem.bitmapPath)
                                    } catch (e: Exception) {
                                        null
                                    }
                                } else null
                                val backBmp = backHighRes ?: backItem.bitmap!!

                                val srcRect = Rect(0, 0, backBmp.width, backBmp.height)
                                val dstRect = android.graphics.RectF(x + cardW + gutterX, y, x + cardW + gutterX + cardW, y + cardH)
                                canvas.drawBitmap(backBmp, srcRect, dstRect, paint)

                                if (backHighRes != null) {
                                    backHighRes.recycle()
                                }
                            }
                        }

                        val tempOs = ByteArrayOutputStream()
                        composedBmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality.toInt(), tempOs)
                        val jpegBytes = tempOs.toByteArray()
                        composedBmp.recycle()

                        // Write Image Object
                        writer.startObject() // Object 5: Image XObject
                        writer.write("<<\n  /Type /XObject\n  /Subtype /Image\n  /Width $targetWidthPx\n  /Height $targetHeightPx\n  /ColorSpace /DeviceRGB\n  /BitsPerComponent 8\n  /Filter /DCTDecode\n  /Length ${jpegBytes.size}\n>>\nstream\n")
                        writer.write(jpegBytes)
                        writer.write("\nendstream\n")
                        writer.endObject()
                    }

                    // Complete the PDF document
                    writer.endDocument(catalogId)
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
}

