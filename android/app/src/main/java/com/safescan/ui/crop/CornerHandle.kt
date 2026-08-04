package com.safescan.ui.crop

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

fun updateOffset(current: Offset, delta: Offset, bounds: IntSize): Offset {
    val newX = (current.x + delta.x).coerceIn(0f, bounds.width.toFloat())
    val newY = (current.y + delta.y).coerceIn(0f, bounds.height.toFloat())
    return Offset(newX, newY)
}

@Composable
fun CornerHandle(
    key: Any? = null,
    offset: Offset, 
    size: Dp = 48.dp,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDrag: (Offset) -> Unit
) {
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    
    val view = LocalView.current
    Box(
        modifier = Modifier
            .offset(
                x = with(LocalDensity.current) { offset.x.toDp() - size / 2 },
                y = with(LocalDensity.current) { offset.y.toDp() - size / 2 }
            )
            .size(size)
            .pointerInput(key ?: Unit) {
                detectDragGestures(
                    onDragStart = { 
                        com.safescan.utils.HapticFeedbackHelper.triggerHaptic(view)
                        currentOnDragStart() 
                    },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount)
                }
            }
    )
}
