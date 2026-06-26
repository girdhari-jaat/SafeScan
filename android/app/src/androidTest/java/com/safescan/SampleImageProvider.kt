package com.safescan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

object SampleImageProvider {
    fun getSampleBitmap(context: Context, fileName: String): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 60f
            isAntiAlias = true
        }
        canvas.drawText("CNIC GOVT SAMPLE TEXT", 100f, 100f, paint)
        return bitmap
    }
}
