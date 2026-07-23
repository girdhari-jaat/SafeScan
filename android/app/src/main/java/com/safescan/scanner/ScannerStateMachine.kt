package com.safescan.scanner

import com.safescan.domain.model.Point
import com.safescan.core.ScannerDebugLogger
import kotlin.math.sqrt

class ScannerStateMachine(
    private val onDocumentDetectedStateChanged: (Boolean) -> Unit,
    private val onAutoCaptureTriggered: () -> Unit
) {

    private var stableFrameCount = 0
    private var lastQuadPoints: List<Point>? = null
    private val STABLE_FRAME_THRESHOLD = 3
    private val STABILITY_TOLERANCE = 120.0

    var isFocusing = false

    fun processFrame(points: List<Point>?, sharpness: Double, autoCaptureEnabled: Boolean) {
        if (isFocusing) {
            return
        }

        var processedPoints = points

        if (processedPoints == null || processedPoints.size != 4) {
            stableFrameCount = 0
            lastQuadPoints = null
            onDocumentDetectedStateChanged(false)
            return
        }

        // Smoothing: Average with last frame
        if (lastQuadPoints != null) {
            processedPoints = averageCorners(lastQuadPoints!!, processedPoints!!)
        }

        if (processedPoints.size == 4) {
            ScannerDebugLogger.logLiveEdgePoints(
                processedPoints[0].toString(),
                processedPoints[1].toString(),
                processedPoints[2].toString(),
                processedPoints[3].toString()
            )
        }

        // Stability Check with a graceful decay and high tolerance for hand tremor
        val threshold = STABLE_FRAME_THRESHOLD
        if (lastQuadPoints != null) {
            if (isStable(lastQuadPoints!!, processedPoints)) {
                stableFrameCount++
            } else {
                // If there's a slight tremor or minor lighting shift, don't hard-reset to 1.
                // Gracefully decrement or hold the stableFrameCount to prevent user frustration.
                stableFrameCount = (stableFrameCount - 1).coerceAtLeast(1)
            }
        } else {
            stableFrameCount = 1
        }

        lastQuadPoints = processedPoints

        // Green box & document detected state requires at least 2 stable counts
        onDocumentDetectedStateChanged(stableFrameCount >= 2)

        ScannerDebugLogger.logStability(stableFrameCount)

        if (!autoCaptureEnabled) {
            return
        }

        val isSharp = sharpness > 10.0 || sharpness == 0.0
        val trigger = stableFrameCount >= threshold && isSharp
        ScannerDebugLogger.logAutoCap(inBox = true, sharpness = sharpness, stable = stableFrameCount, trigger = trigger)

        if (trigger) {
            stableFrameCount = 0
            lastQuadPoints = null
            onDocumentDetectedStateChanged(false)
            onAutoCaptureTriggered()
        }
    }

    private fun averageCorners(p1: List<Point>, p2: List<Point>): List<Point> {
        val result = mutableListOf<Point>()
        for (i in 0..3) {
            result.add(Point(
                (p1[i].x + p2[i].x) / 2f,
                (p1[i].y + p2[i].y) / 2f
            ))
        }
        return result
    }

    private fun isStable(p1: List<Point>, p2: List<Point>): Boolean {
        var totalDist = 0.0
        for (i in 0..3) {
            val dx = p1[i].x - p2[i].x
            val dy = p1[i].y - p2[i].y
            totalDist += sqrt((dx * dx + dy * dy).toDouble())
        }
        return totalDist < STABILITY_TOLERANCE
    }
}
