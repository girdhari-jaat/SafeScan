package com.safescan.scanner

import com.safescan.domain.model.Point
import android.util.Log
import com.safescan.core.ScannerDebugLogger
import kotlin.math.sqrt

enum class DetectionState {
    NO_DOCUMENT,
    DOCUMENT_VISIBLE,
    AUTO_CAPTURING
}

class ScannerStateMachine(
    private val onDocumentDetectedStateChanged: (Boolean) -> Unit,
    private val onAutoCaptureTriggered: () -> Unit,
    private val onDetectionStateChanged: ((DetectionState) -> Unit)? = null
) {

    var detectionState: DetectionState = DetectionState.NO_DOCUMENT
        private set

    private var stableFrameCount = 0
    private var lastQuadPoints: List<Point>? = null
    private var heldQuadPoints: List<Point>? = null
    private var missingFrameCount = 0
    private val MAX_MISSING_FRAMES = 10
    private val STABLE_FRAME_THRESHOLD = 8
    private val MIN_SHARPNESS_THRESHOLD = 15.0
    private val REQUIRED_STABLE_FRAMES_FOR_OVERLAY = 4
    private val STABILITY_TOLERANCE = 80.0

    var isFocusing = false
        set(value) {
            field = value
            if (value) {
                updateState(DetectionState.AUTO_CAPTURING)
            } else {
                if (heldQuadPoints != null && missingFrameCount <= MAX_MISSING_FRAMES) {
                    updateState(DetectionState.DOCUMENT_VISIBLE)
                } else {
                    resetDetection()
                }
            }
        }

    fun getHeldPoints(): List<Point>? = heldQuadPoints

    private fun updateState(newState: DetectionState) {
        if (detectionState != newState) {
            detectionState = newState
            val isDetected = newState != DetectionState.NO_DOCUMENT
            Log.d("Detection", "State=$detectionState | isDocumentDetected=$isDetected | heldQuad=${heldQuadPoints != null} | missingFrames=$missingFrameCount")
            onDetectionStateChanged?.invoke(newState)
            onDocumentDetectedStateChanged(isDetected)
        }
    }

    fun resetDetection() {
        stableFrameCount = 0
        lastQuadPoints = null
        heldQuadPoints = null
        missingFrameCount = 0
        updateState(DetectionState.NO_DOCUMENT)
    }

    fun processFrame(points: List<Point>?, sharpness: Double, autoCaptureEnabled: Boolean) {
        // During AUTO_CAPTURING or focus, freeze state & overlay quad; do not reset or re-trigger
        if (isFocusing || detectionState == DetectionState.AUTO_CAPTURING) {
            return
        }

        var processedPoints = points

        if (processedPoints == null || processedPoints.size != 4) {
            missingFrameCount++
            if (missingFrameCount > MAX_MISSING_FRAMES) {
                resetDetection()
            }
            return
        }

        // Valid document points received
        missingFrameCount = 0

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
                stableFrameCount = (stableFrameCount - 1).coerceAtLeast(0)
            }
        } else {
            stableFrameCount = 1
        }

        lastQuadPoints = processedPoints
        heldQuadPoints = processedPoints

        // Green box & document detected state requires at least REQUIRED_STABLE_FRAMES_FOR_OVERLAY stable counts
        if (stableFrameCount >= REQUIRED_STABLE_FRAMES_FOR_OVERLAY) {
            updateState(DetectionState.DOCUMENT_VISIBLE)
        }

        ScannerDebugLogger.logStability(stableFrameCount)

        if (!autoCaptureEnabled) {
            return
        }

        val isSharp = sharpness > MIN_SHARPNESS_THRESHOLD
        val trigger = stableFrameCount >= threshold && isSharp
        ScannerDebugLogger.logAutoCap(inBox = true, sharpness = sharpness, stable = stableFrameCount, trigger = trigger)

        if (trigger) {
            isFocusing = true
            updateState(DetectionState.AUTO_CAPTURING)
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
