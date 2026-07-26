package com.safescan.scanner

import com.safescan.domain.model.Point
import android.util.Log
import com.safescan.core.ScannerDebugLogger
import kotlin.math.sqrt

enum class DetectionState {
    IDLE,          // No document detected
    DETECTED,      // Document is detected but not stable yet
    STABLE,        // Document detected and stable (green polygon showing)
    CAPTURING,     // Auto-capturing/focusing in progress
    COOLDOWN       // Cooldown period after a successful capture
}

class ScannerStateMachine(
    private val onDocumentDetectedStateChanged: (Boolean) -> Unit,
    private val onAutoCaptureTriggered: () -> Unit,
    private val onDetectionStateChanged: ((DetectionState) -> Unit)? = null
) {
    private val lock = Any()

    @Volatile
    var detectionState: DetectionState = DetectionState.IDLE
        private set

    private var stableFrameCount = 0
    private var unstableFrameCount = 0
    private var lastQuadPoints: List<Point>? = null
    private var heldQuadPoints: List<Point>? = null
    private var missingFrameCount = 0
    
    // Cooldown tracking
    private var cooldownStartTime = 0L
    private val COOLDOWN_DURATION_MS = 5500L // 5.5 seconds (5–6 second auto-capture cooldown)

    private val MAX_MISSING_FRAMES = 10
    private val STABLE_FRAME_THRESHOLD = 8
    private val MIN_SHARPNESS_THRESHOLD = 35.0
    private val REQUIRED_STABLE_FRAMES_FOR_OVERLAY = 4
    private val EMA_ALPHA = 0.6f

    var isFocusing = false
        get() = synchronized(lock) { field }
        set(value) = synchronized(lock) {
            val oldValue = field
            field = value
            if (value) {
                updateState(DetectionState.CAPTURING, "Focus started for auto-capture")
            } else {
                if (oldValue && !value) {
                    startCooldown()
                }
            }
        }

    fun getHeldPoints(): List<Point>? = synchronized(lock) {
        heldQuadPoints
    }

    private fun updateState(newState: DetectionState, reason: String) {
        if (detectionState != newState) {
            val oldState = detectionState
            detectionState = newState
            val isDetected = newState != DetectionState.IDLE && newState != DetectionState.COOLDOWN
            Log.d("Detection", "State Transition: [$oldState] ➔ [$newState] | Reason: $reason | heldQuad=${heldQuadPoints != null} | missingFrames=$missingFrameCount")
            onDetectionStateChanged?.invoke(newState)
            onDocumentDetectedStateChanged(isDetected)
        }
    }

    private fun startCooldown() {
        stableFrameCount = 0
        unstableFrameCount = 0
        lastQuadPoints = null
        heldQuadPoints = null
        missingFrameCount = 0
        cooldownStartTime = System.currentTimeMillis()
        updateState(DetectionState.COOLDOWN, "Capture completed successfully, entering auto-capture cooldown")
    }

    fun resetDetection() = synchronized(lock) {
        resetDetectionInternal("Manual or external reset request")
    }

    private fun resetDetectionInternal(reason: String) {
        if (stableFrameCount != 0 || heldQuadPoints != null || detectionState != DetectionState.IDLE) {
            Log.d("Detection", "Resetting detection state. Reason: $reason | CurrentState=$detectionState")
        }
        stableFrameCount = 0
        unstableFrameCount = 0
        lastQuadPoints = null
        heldQuadPoints = null
        missingFrameCount = 0
        updateState(DetectionState.IDLE, "Reset to IDLE")
    }

    fun processFrame(
        points: List<Point>?,
        sharpness: Double,
        autoCaptureEnabled: Boolean,
        isDeviceMotionStable: Boolean = true,
        frameWidth: Int = 0,
        frameHeight: Int = 0
    ) = synchronized(lock) {
        val now = System.currentTimeMillis()
        
        // 1. Handle Cooldown Expiry check
        if (detectionState == DetectionState.COOLDOWN) {
            val elapsed = now - cooldownStartTime
            if (elapsed >= COOLDOWN_DURATION_MS) {
                updateState(DetectionState.IDLE, "Cooldown period expired (${elapsed}ms elapsed), returning to IDLE")
            } else {
                // In cooldown, discard all frames, do not draw overlay or trigger any capture
                heldQuadPoints = null
                return
            }
        }

        // 2. Lock state during active CAPTURING / focusing
        if (isFocusing || detectionState == DetectionState.CAPTURING) {
            return
        }

        var processedPoints = points

        // 3. Edge margin validation (reject documents within 2% of screen boundaries)
        var touchesEdge = false
        if (processedPoints != null && processedPoints.size == 4 && frameWidth > 0 && frameHeight > 0) {
            val marginX = frameWidth * 0.02
            val marginY = frameHeight * 0.02
            for (p in processedPoints) {
                if (p.x < marginX || p.x > (frameWidth - marginX) || p.y < marginY || p.y > (frameHeight - marginY)) {
                    touchesEdge = true
                    break
                }
            }
        }

        if (touchesEdge) {
            Log.d("Detection", "Document rejected: Corners too close to screen edges (within 2% margins).")
            processedPoints = null
        }

        // 4. Check for missing document / invalid detection
        if (processedPoints == null || processedPoints.size != 4) {
            missingFrameCount++
            if (missingFrameCount > MAX_MISSING_FRAMES) {
                resetDetectionInternal("No document detected for $missingFrameCount consecutive frames")
            }
            return
        }

        // Valid document points received
        missingFrameCount = 0

        // 5. Apply Exponential Moving Average (EMA) corner smoothing
        if (lastQuadPoints != null) {
            processedPoints = emaCorners(lastQuadPoints!!, processedPoints)
        }

        if (processedPoints.size == 4) {
            ScannerDebugLogger.logLiveEdgePoints(
                processedPoints[0].toString(),
                processedPoints[1].toString(),
                processedPoints[2].toString(),
                processedPoints[3].toString()
            )
        }

        // 6. Stability Verification with dynamic 2-frame tolerance
        if (lastQuadPoints != null) {
            if (isStable(lastQuadPoints!!, processedPoints)) {
                unstableFrameCount = 0
                stableFrameCount++
            } else {
                unstableFrameCount++
                if (unstableFrameCount >= 2) {
                    Log.d("Detection", "Frame unstable (2 consecutive unstable frames): Resetting stability counter (was $stableFrameCount).")
                    stableFrameCount = 0
                    unstableFrameCount = 0
                } else {
                    Log.d("Detection", "Single frame unstable: Tolerating glitch (unstableCount=$unstableFrameCount, preserving stableFrameCount=$stableFrameCount).")
                }
            }
        } else {
            stableFrameCount = 1
            unstableFrameCount = 0
        }

        // 7. Clamping: clamp stable frame count to threshold to avoid infinite growth
        stableFrameCount = stableFrameCount.coerceAtMost(STABLE_FRAME_THRESHOLD)

        lastQuadPoints = processedPoints
        heldQuadPoints = processedPoints

        // 8. State Machine Transition Logic
        if (detectionState == DetectionState.IDLE) {
            updateState(DetectionState.DETECTED, "Document detected, starting stabilization counter")
        }

        if (detectionState == DetectionState.DETECTED && stableFrameCount >= REQUIRED_STABLE_FRAMES_FOR_OVERLAY) {
            updateState(DetectionState.STABLE, "Document stable (stableFrameCount=$stableFrameCount >= $REQUIRED_STABLE_FRAMES_FOR_OVERLAY), rendering overlay")
        }

        if (detectionState == DetectionState.STABLE && stableFrameCount < REQUIRED_STABLE_FRAMES_FOR_OVERLAY) {
            updateState(DetectionState.DETECTED, "Overlay lost: Stability dropped below overlay requirement ($stableFrameCount < $REQUIRED_STABLE_FRAMES_FOR_OVERLAY)")
        }

        ScannerDebugLogger.logStability(stableFrameCount, STABLE_FRAME_THRESHOLD)

        if (!autoCaptureEnabled) {
            return
        }

        // 9. Auto-Capture Trigger evaluation
        val isSharp = sharpness > MIN_SHARPNESS_THRESHOLD
        val trigger = stableFrameCount >= STABLE_FRAME_THRESHOLD && isSharp && isDeviceMotionStable
        ScannerDebugLogger.logAutoCap(inBox = true, sharpness = sharpness, stable = stableFrameCount, trigger = trigger)

        if (trigger) {
            isFocusing = true
            updateState(DetectionState.CAPTURING, "Auto-capture trigger conditions satisfied: stableCount=$stableFrameCount, sharpness=$sharpness, motionStable=$isDeviceMotionStable")
            onAutoCaptureTriggered()
        }
    }

    private fun emaCorners(old: List<Point>, new: List<Point>): List<Point> {
        val result = ArrayList<Point>(4)
        for (i in 0..3) {
            result.add(Point(
                old[i].x + EMA_ALPHA * (new[i].x - old[i].x),
                old[i].y + EMA_ALPHA * (new[i].y - old[i].y)
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
        
        // Calculate dynamic tolerance based on diagonal size of the quad
        val diagDx = p2[0].x - p2[2].x
        val diagDy = p2[0].y - p2[2].y
        val diagonal = sqrt((diagDx * diagDx + diagDy * diagDy).toDouble())
        
        // Allowed deviation is roughly 5% of the diagonal size for all 4 corners combined
        val dynamicTolerance = (diagonal * 0.05).coerceAtLeast(30.0)
        
        return totalDist < dynamicTolerance
    }
}
