package com.safescan.scanner

import androidx.camera.core.ImageProxy
import com.safescan.android.scanner.Point
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class LiveEdgeDetectionEngine {
    fun process(imageProxy: ImageProxy, onResult: (List<Point>) -> Unit) {
        val bitmap = imageProxy.toBitmap()
        
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        
        val edges = Mat()
        Imgproc.Canny(gray, edges, 75.0, 200.0)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        contours.sortByDescending { Imgproc.contourArea(it) }

        var foundCorners: List<Point>? = null
        for (contour in contours) {
            val contour2f = MatOfPoint2f(*contour.toArray())
            val approx = MatOfPoint2f()
            val peri = Imgproc.arcLength(contour2f, true)
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

            if (approx.total() == 4L) {
                val points = approx.toArray().toList()
                val sorted = points.sortedBy { it.y }
                val top = sorted.take(2).sortedBy { it.x }
                val bottom = sorted.drop(2).sortedBy { it.x }
                foundCorners = listOf(
                    Point(top[0].x, top[0].y),
                    Point(top[1].x, top[1].y),
                    Point(bottom[1].x, bottom[1].y),
                    Point(bottom[0].x, bottom[0].y)
                )
                break
            }
        }
        
        if (foundCorners != null) {
            onResult(foundCorners)
        }
        
        imageProxy.close()
    }
}
