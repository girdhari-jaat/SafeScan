package com.safescan.android.scanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class Quadrilateral(
    val topLeft: Point,
    val topRight: Point,
    val bottomRight: Point,
    val bottomLeft: Point
)

class OpenCVScanner {

    /**
     * Finds the largest quadrilateral in the given Bitmap using Canny edge detection.
     */
    fun findDocumentQuadrilateral(bitmap: Bitmap): Quadrilateral? {
        val src = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        var largestQuad: MatOfPoint2f? = null

        try {
            Utils.bitmapToMat(bitmap, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 75.0, 200.0)

            Imgproc.findContours(
                edges,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            contours.sortByDescending { Imgproc.contourArea(it) }

            for (contour in contours) {
                val contour2f = MatOfPoint2f(*contour.toArray())
                val peri = Imgproc.arcLength(contour2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

                if (approx.total() == 4L) {
                    largestQuad = approx
                    contour2f.release()
                    break
                }
                contour2f.release()
                approx.release()
            }

            if (largestQuad != null) {
                val points = largestQuad.toArray()
                val orderedPoints = orderPoints(points)
                return Quadrilateral(
                    orderedPoints[0],
                    orderedPoints[1],
                    orderedPoints[2],
                    orderedPoints[3]
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            src.release()
            gray.release()
            blurred.release()
            edges.release()
            hierarchy.release()
            largestQuad?.release()
            for (contour in contours) {
                contour.release()
            }
        }

        return null
    }

    /**
     * Applies perspective transform to crop the document.
     */
    fun cropDocument(bitmap: Bitmap, quad: Quadrilateral): Bitmap {
        val src = Mat()
        var srcPoints: MatOfPoint2f? = null
        var dstPoints: MatOfPoint2f? = null
        var perspectiveTransform: Mat? = null
        val dst = Mat()

        try {
            Utils.bitmapToMat(bitmap, src)

            val tl = quad.topLeft
            val tr = quad.topRight
            val br = quad.bottomRight
            val bl = quad.bottomLeft

            val widthA = sqrt((br.x - bl.x).pow(2) + (br.y - bl.y).pow(2))
            val widthB = sqrt((tr.x - tl.x).pow(2) + (tr.y - tl.y).pow(2))
            val maxWidth = max(widthA, widthB).toInt().coerceAtLeast(1)

            val heightA = sqrt((tr.x - br.x).pow(2) + (tr.y - br.y).pow(2))
            val heightB = sqrt((tl.x - bl.x).pow(2) + (tl.y - bl.y).pow(2))
            val maxHeight = max(heightA, heightB).toInt().coerceAtLeast(1)

            srcPoints = MatOfPoint2f(tl, tr, br, bl)
            dstPoints = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(maxWidth.toDouble() - 1, 0.0),
                Point(maxWidth.toDouble() - 1, maxHeight.toDouble() - 1),
                Point(0.0, maxHeight.toDouble() - 1)
            )

            perspectiveTransform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            Imgproc.warpPerspective(src, dst, perspectiveTransform, Size(maxWidth.toDouble(), maxHeight.toDouble()))

            val croppedBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(dst, croppedBitmap)
            return croppedBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return bitmap
        } finally {
            src.release()
            srcPoints?.release()
            dstPoints?.release()
            perspectiveTransform?.release()
            dst.release()
        }
    }

    private fun orderPoints(pts: Array<Point>): Array<Point> {
        val ordered = Array(4) { Point() }
        
        // Sum of x and y (top-left has smallest sum, bottom-right has largest sum)
        val sums = pts.map { it.x + it.y }
        ordered[0] = pts[sums.indexOf(sums.minOrNull()!!)]
        ordered[2] = pts[sums.indexOf(sums.maxOrNull()!!)]
        
        // Difference of y and x (top-right has smallest diff, bottom-left has largest diff)
        val diffs = pts.map { it.y - it.x }
        ordered[1] = pts[diffs.indexOf(diffs.minOrNull()!!)]
        ordered[3] = pts[diffs.indexOf(diffs.maxOrNull()!!)]

        return ordered
    }
}
