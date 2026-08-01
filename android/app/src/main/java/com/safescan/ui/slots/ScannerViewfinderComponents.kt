package com.safescan.ui.slots

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safescan.data.ScannerMode
import com.safescan.scanner.CameraHardwareConfig
import com.safescan.scanner.ScannerViewModel
import com.safescan.utils.PageConfig
import kotlinx.coroutines.delay

@Composable
fun ScannerCenterInstructions(
    viewModel: ScannerViewModel
) {
    val isDocumentDetected by viewModel.isDocumentDetected.collectAsState()
    val autoCapture by viewModel.autoCapture.collectAsState()
    val currentMode by viewModel.currentMode.collectAsState()

    val slots by viewModel.slots.collectAsState()
    val selectedSlotId by viewModel.selectedSlotId.collectAsState()

    val targetSlot = remember(selectedSlotId, slots) {
        if (selectedSlotId != null) {
            slots.firstOrNull { it.id == selectedSlotId }
        } else {
            slots.firstOrNull { it.bitmap == null }
        }
    }

    var isGuideVisible by remember { mutableStateOf(true) }

    LaunchedEffect(targetSlot?.id, currentMode) {
        isGuideVisible = true
        delay(5000L)
        isGuideVisible = false
    }

    val guideText = if (isDocumentDetected) {
        if (autoCapture) "HOLD STILL... AUTO-CAPTURING" else "READY TO CAPTURE"
    } else if (isGuideVisible) {
        when (currentMode) {
            ScannerMode.CARD -> {
                targetSlot?.label ?: "All 8 Slots Captured"
            }
            ScannerMode.DOCUMENT -> "Align Document Inside Frame"
        }
    } else {
        null
    }

    if (guideText != null) {
        val guideColor = if (isDocumentDetected) Color(0xFF10B981) else Color.Yellow

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (isDocumentDetected) {
                    val pulseTransition = rememberInfiniteTransition(label = "dot_pulse")
                    val dotAlpha by pulseTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot_alpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981).copy(alpha = dotAlpha))
                    )
                }
                Text(
                    text = guideText,
                    color = guideColor,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
fun ViewfinderOverlay(
    mode: ScannerMode,
    showGrid: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isA4 = remember(context) { CameraHardwareConfig.isA4Supported(context) }
    val isCnic = remember(context) { CameraHardwareConfig.isCnicSupported(context) }

    val infiniteTransition = rememberInfiniteTransition(label = "viewfinder_pulse")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "border_pulse"
    )
    val laserYRatio by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser_y"
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val finalRatio = PageConfig.getOnscreenLayoutRatio(context, mode)

        val maxWidth = width * 0.90f
        val maxHeight = height * 0.85f

        var rectWidth = maxWidth
        var rectHeight = rectWidth / finalRatio

        if (rectHeight > maxHeight) {
            rectHeight = maxHeight
            rectWidth = rectHeight * finalRatio
        }

        if (rectWidth > 0f && rectHeight > 0f) {
            val left = (width - rectWidth) / 2f
            val top = (height - rectHeight) / 2f

            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(0f, 0f),
                size = Size(width, maxOf(0f, top))
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(0f, top + rectHeight),
                size = Size(width, maxOf(0f, height - (top + rectHeight)))
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(0f, maxOf(0f, top)),
                size = Size(left, rectHeight)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(left + rectWidth, maxOf(0f, top)),
                size = Size(width - (left + rectWidth), rectHeight)
            )

            drawRoundRect(
                color = Color.White.copy(alpha = borderAlpha),
                topLeft = Offset(left, top),
                size = Size(rectWidth, rectHeight),
                cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                style = Stroke(width = 2.5.dp.toPx())
            )

            val laserY = top + rectHeight * laserYRatio
            drawLine(
                color = Color(0xFF10B981).copy(alpha = 0.8f),
                start = Offset(left + 8.dp.toPx(), laserY),
                end = Offset(left + rectWidth - 8.dp.toPx(), laserY),
                strokeWidth = 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
            )

            if (showGrid) {
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(left + rectWidth / 3f, top),
                    end = Offset(left + rectWidth / 3f, top + rectHeight),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(left + rectWidth * 2f / 3f, top),
                    end = Offset(left + rectWidth * 2f / 3f, top + rectHeight),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(left, top + rectHeight / 3f),
                    end = Offset(left + rectWidth, top + rectHeight / 3f),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(left, top + rectHeight * 2f / 3f),
                    end = Offset(left + rectWidth, top + rectHeight * 2f / 3f),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
    }
}
