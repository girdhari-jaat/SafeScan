package org.opencv.android

import android.graphics.Bitmap
import org.opencv.core.CvType
import org.opencv.core.Mat

object Utils {
    fun bitmapToMat(bmp: Bitmap, mat: Mat) {
        val width = bmp.width
        val height = bmp.height
        mat.create(height, width, CvType.CV_8UC4)
        
        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val bytes = ByteArray(width * height * 4)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = ((pixel ushr 24) and 0xFF).toByte()
            val r = ((pixel ushr 16) and 0xFF).toByte()
            val g = ((pixel ushr 8) and 0xFF).toByte()
            val b = (pixel and 0xFF).toByte()
            
            bytes[i * 4] = r
            bytes[i * 4 + 1] = g
            bytes[i * 4 + 2] = b
            bytes[i * 4 + 3] = a
        }
        mat.put(0, 0, bytes)
    }

    fun matToBitmap(mat: Mat, bmp: Bitmap) {
        val width = mat.cols()
        val height = mat.rows()
        val bytes = ByteArray(width * height * 4)
        mat.get(0, 0, bytes)
        
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = bytes[i * 4].toInt() and 0xFF
            val g = bytes[i * 4 + 1].toInt() and 0xFF
            val b = bytes[i * 4 + 2].toInt() and 0xFF
            val a = bytes[i * 4 + 3].toInt() and 0xFF
            
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
