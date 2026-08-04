package com.safescan.ui.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun CropMagnifierLens(
    dragOffset: Offset,
    imageSize: IntSize,
    imageBitmap: ImageBitmap,
    tl: Offset,
    tr: Offset,
    br: Offset,
    bl: Offset
) {
    val magnifierSize = 150.dp
    val magnifierPos = if (dragOffset.y < imageSize.height / 3) {
        Alignment.BottomCenter
    } else {
        Alignment.TopCenter
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = magnifierPos
    ) {
        Card(
            shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(3.dp, Color(0xFF10B981)),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(magnifierSize)
                    .clip(CircleShape)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasW = size.width
                    val canvasH = size.height
                    val centerX = canvasW / 2f
                    val centerY = canvasH / 2f
                    val zoom = 3.5f

                    clipPath(Path().apply { addOval(Rect(0f, 0f, canvasW, canvasH)) }) {
                        // 1. Dark background for off-document boundary area
                        drawRect(Color(0xFF18181B))

                        // 2. Draw scaled image bitmap positioned exactly at dragOffset
                        if (imageSize.width > 0 && imageSize.height > 0) {
                            val imgLeft = centerX - dragOffset.x * zoom
                            val imgTop = centerY - dragOffset.y * zoom
                            val imgWidth = imageSize.width.toFloat() * zoom
                            val imgHeight = imageSize.height.toFloat() * zoom

                            drawImage(
                                image = imageBitmap,
                                dstOffset = IntOffset(imgLeft.roundToInt(), imgTop.roundToInt()),
                                dstSize = IntSize(imgWidth.roundToInt(), imgHeight.roundToInt())
                            )
                        }

                        // 3. Helper to map crop points to magnifier coordinates
                        fun mapToMag(p: Offset): Offset {
                            return Offset(
                                centerX + (p.x - dragOffset.x) * zoom,
                                centerY + (p.y - dragOffset.y) * zoom
                            )
                        }

                        // 4. Overlay crop region polygon inside magnifier lens
                        val magTl = mapToMag(tl)
                        val magTr = mapToMag(tr)
                        val magBr = mapToMag(br)
                        val magBl = mapToMag(bl)

                        val cropPath = Path().apply {
                            moveTo(magTl.x, magTl.y)
                            lineTo(magTr.x, magTr.y)
                            lineTo(magBr.x, magBr.y)
                            lineTo(magBl.x, magBl.y)
                            close()
                        }

                        drawPath(
                            path = cropPath,
                            color = Color.Cyan.copy(alpha = 0.2f)
                        )
                        drawPath(
                            path = cropPath,
                            color = Color.Cyan,
                            style = Stroke(width = 2.dp.toPx())
                        )

                        // 5. Center Target Crosshair (Emerald brand color)
                        val crosshairColor = Color(0xFF10B981)
                        val lineLen = 18.dp.toPx()
                        
                        drawLine(
                            color = crosshairColor,
                            start = Offset(centerX - lineLen, centerY),
                            end = Offset(centerX + lineLen, centerY),
                            strokeWidth = 1.5.dp.toPx()
                        )
                        drawLine(
                            color = crosshairColor,
                            start = Offset(centerX, centerY - lineLen),
                            end = Offset(centerX, centerY + lineLen),
                            strokeWidth = 1.5.dp.toPx()
                        )
                        
                        // Center handle target dot
                        drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(centerX, centerY))
                        drawCircle(crosshairColor, radius = 3.dp.toPx(), center = Offset(centerX, centerY))
                    }
                }
            }
        }
    }
}
