package com.safescan.scanner

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageCapture
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.safescan.data.ScannerMode
import com.safescan.core.DiagnosticsLogger

/**
 * CameraHardwareConfig
 *
 * Professional high-performance hardware configuration manager for CameraX.
 * Dynamically negotiates and configures camera hardware constraints (resolution,
 * frame rate, focus mode, and capture mode) based on selected scanner mode (Mood)
 * and HD quality tiers (Fast, Standard, High, and custom Megapixels).
 */
object CameraHardwareConfig {

    private var isA4SupportedCache: Boolean? = null
    private var isCnicSupportedCache: Boolean? = null

    /**
     * Check if the device's back camera supports an aspect ratio close to A4 (1.414).
     */
    fun isA4Supported(context: Context?): Boolean {
        if (context == null) return true // Default true for in-memory crop fallback
        isA4SupportedCache?.let { return it }
        val supported = checkRatioSupport(context, 1.4142f, 0.05f)
        isA4SupportedCache = supported
        return supported
    }

    /**
     * Check if the device's back camera supports an aspect ratio close to Pakistani CNIC / ID Card (1.586).
     */
    fun isCnicSupported(context: Context?): Boolean {
        if (context == null) return true // Default true for in-memory crop fallback
        isCnicSupportedCache?.let { return it }
        val supported = checkRatioSupport(context, 1.5857f, 0.05f)
        isCnicSupportedCache = supported
        return supported
    }

    private fun checkRatioSupport(context: Context, targetRatio: Float, tolerance: Float): Boolean {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return false

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
            val sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG) ?: return false

            for (size in sizes) {
                val aspect = size.width.toFloat() / size.height.toFloat() // width is always greater in camera sizes
                if (kotlin.math.abs(aspect - targetRatio) <= tolerance) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Safe fallback
        }
        return false
    }

    data class HardwareCaptureSettings(
        val captureMode: Int,
        val targetAspectRatio: Int,
        val resolutionSelector: ResolutionSelector,
        val megapixelsLabel: String,
        val targetSize: Size,
        val description: String
    )

    /**
     * Determine the target ratio in landscape (width / height) based on support and mode.
     */
    fun getTargetRatio(context: Context?, mode: ScannerMode): Float {
        return when (mode) {
            ScannerMode.DOCUMENT, ScannerMode.CARD -> {
                if (isA4Supported(context)) 1.4142f else 1.3333f // A4 (1.4142) vs 4:3 (1.3333) fallback
            }
            ScannerMode.GRID -> {
                if (isCnicSupported(context)) 1.5857f else 0.75f // Pakistani CNIC (1.5857) vs 3:4 Portrait (0.75) fallback
            }
        }
    }

    private fun getDefaultSize(targetRatio: Float, maxMegapixels: Float): Size {
        return if (kotlin.math.abs(targetRatio - 1.4142f) <= 0.05f) { // A4
            if (maxMegapixels <= 2.5f) Size(1696, 1200) // approx 2.0 MP
            else if (maxMegapixels <= 5.5f) Size(2592, 1832) // approx 4.7 MP
            else Size(3264, 2304) // approx 7.5 MP
        } else if (kotlin.math.abs(targetRatio - 1.5857f) <= 0.05f) { // ID Card / CNIC
            if (maxMegapixels <= 2.5f) Size(1920, 1200) // approx 2.3 MP
            else if (maxMegapixels <= 5.5f) Size(2880, 1800) // approx 5.1 MP
            else Size(3584, 2240) // approx 8.0 MP
        } else if (kotlin.math.abs(targetRatio - 0.75f) <= 0.05f) { // 3:4 Portrait
            if (maxMegapixels <= 2.5f) Size(1200, 1600) // approx 1.9 MP
            else if (maxMegapixels <= 5.5f) Size(1944, 2592) // approx 5.0 MP
            else Size(2448, 3264) // approx 8.0 MP
        } else { // 4:3 Fallback
            if (maxMegapixels <= 2.5f) Size(1600, 1200) // approx 1.9 MP
            else if (maxMegapixels <= 5.5f) Size(2592, 1944) // approx 5.0 MP
            else Size(3264, 2448) // approx 8.0 MP
        }
    }

    /**
     * Find best supported size closest to the Megapixel target for the selected ratio.
     */
    fun findBestSize(context: Context?, targetRatio: Float, maxMegapixels: Float, tolerance: Float = 0.05f): Size {
        if (context == null) return getDefaultSize(targetRatio, maxMegapixels)
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return getDefaultSize(targetRatio, maxMegapixels)

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return getDefaultSize(targetRatio, maxMegapixels)
            val sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG) ?: return getDefaultSize(targetRatio, maxMegapixels)

            val targetPixels = maxMegapixels * 1_000_000f
            val matchingSizes = sizes.filter { size ->
                val aspect = size.width.toFloat() / size.height.toFloat()
                kotlin.math.abs(aspect - targetRatio) <= tolerance
            }

            if (matchingSizes.isNotEmpty()) {
                return matchingSizes.minByOrNull { size ->
                    val pixels = size.width * size.height
                    kotlin.math.abs(pixels - targetPixels)
                } ?: matchingSizes[0]
            }
        } catch (e: Exception) {
            // Ignore and fallback
        }
        return getDefaultSize(targetRatio, maxMegapixels)
    }

    /**
     * Dynamically build hardware capture settings based on selected ScannerMode and hdMode (Capture Quality).
     */
    fun getCaptureSettings(context: Context?, mode: ScannerMode, hdMode: String): HardwareCaptureSettings {
        val targetRatio = getTargetRatio(context, mode)
        
        // Resolve Max Megapixels limit based on user's selected quality tier (Fast, Standard, High - capped at 8MP)
        val maxMegapixels = when (hdMode.uppercase()) {
            "FAST", "2MP" -> 2.0f
            "STANDARD", "5MP" -> 5.0f
            "8MP", "12MP", "HIGH" -> 8.0f // Capped at Max Sensor Target 8MP as requested
            else -> 5.0f
        }

        val targetSize = findBestSize(context, targetRatio, maxMegapixels)
        val label = "${String.format("%.1f", (targetSize.width * targetSize.height) / 1000000f)} MP"

        val (captureMode, desc) = when (hdMode.uppercase()) {
            "FAST", "2MP" -> Pair(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY, "Optimized for latency and ultra-fast scanning cycles.")
            "STANDARD", "5MP" -> Pair(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY, "Balanced resolution and fast post-processing speeds.")
            else -> Pair(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY, "High-fidelity resolution with maximum textual details.")
        }

        // Determine base aspect ratio strategy for CameraX
        val baseAspectRatio = if (kotlin.math.abs(targetRatio - 1.5857f) <= 0.05f) {
            AspectRatio.RATIO_16_9
        } else {
            AspectRatio.RATIO_4_3
        }

        val resolutionStrategy = ResolutionStrategy(targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)

        val resolutionSelector = ResolutionSelector.Builder()
            .setAllowedResolutionMode(ResolutionSelector.ALLOWED_RESOLUTION_MODE_ALL)
            .setAspectRatioStrategy(
                AspectRatioStrategy(
                    baseAspectRatio,
                    AspectRatioStrategy.FALLBACK_RULE_AUTO
                )
            )
            .setResolutionStrategy(resolutionStrategy)
            .build()

        DiagnosticsLogger.info("Negotiated CameraX Capture Hardware: Mode=$mode, HD=$hdMode -> Target: ${targetSize.width}x${targetSize.height} ($label), Mode=$captureMode")

        return HardwareCaptureSettings(
            captureMode = captureMode,
            targetAspectRatio = baseAspectRatio,
            resolutionSelector = resolutionSelector,
            megapixelsLabel = label,
            targetSize = targetSize,
            description = desc
        )
    }

    /**
     * Build preview resolution selector to match the target aspect ratio while maintaining smooth performance (typically 1080p).
     */
    fun getPreviewResolutionSelector(context: Context?, mode: ScannerMode): ResolutionSelector {
        val targetRatio = getTargetRatio(context, mode)
        val baseAspectRatio = if (kotlin.math.abs(targetRatio - 1.5857f) <= 0.05f) {
            AspectRatio.RATIO_16_9
        } else {
            AspectRatio.RATIO_4_3
        }

        val targetSize = if (baseAspectRatio == AspectRatio.RATIO_16_9) {
            Size(1920, 1080)
        } else {
            Size(1440, 1080)
        }

        return ResolutionSelector.Builder()
            .setAllowedResolutionMode(ResolutionSelector.ALLOWED_RESOLUTION_MODE_ALL)
            .setAspectRatioStrategy(
                AspectRatioStrategy(
                    baseAspectRatio,
                    AspectRatioStrategy.FALLBACK_RULE_AUTO
                )
            )
            .setResolutionStrategy(
                ResolutionStrategy(targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()
    }

    /**
     * Build image analysis resolution selector, optimized for real-time edge detection and frame rates (typically 720p).
     */
    fun getImageAnalysisResolutionSelector(context: Context?, mode: ScannerMode): ResolutionSelector {
        val targetRatio = getTargetRatio(context, mode)
        val baseAspectRatio = if (kotlin.math.abs(targetRatio - 1.5857f) <= 0.05f) {
            AspectRatio.RATIO_16_9
        } else {
            AspectRatio.RATIO_4_3
        }

        val targetSize = if (baseAspectRatio == AspectRatio.RATIO_16_9) {
            Size(1280, 720)
        } else {
            Size(960, 720)
        }

        return ResolutionSelector.Builder()
            .setAllowedResolutionMode(ResolutionSelector.ALLOWED_RESOLUTION_MODE_ALL)
            .setAspectRatioStrategy(
                AspectRatioStrategy(
                    baseAspectRatio,
                    AspectRatioStrategy.FALLBACK_RULE_AUTO
                )
            )
            .setResolutionStrategy(
                ResolutionStrategy(targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()
    }
}
