package com.safescan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.safescan.scanner.ScannerViewModel
import com.safescan.domain.model.Point
import com.safescan.domain.model.Quadrilateral
import kotlinx.coroutines.launch
import com.safescan.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(viewModel: ScannerViewModel) {
    val croppingBitmap by viewModel.croppingBitmap.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    
    // IMPROVEMENT: Added SnackbarHostState and CoroutineScope to handle edge-detection errors gracefully
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    
    // 4 corners: TL, TR, BR, BL
    var tl by remember(croppingBitmap) { mutableStateOf(Offset(50f, 50f)) }
    var tr by remember(croppingBitmap) { mutableStateOf(Offset(300f, 50f)) }
    var br by remember(croppingBitmap) { mutableStateOf(Offset(300f, 400f)) }
    var bl by remember(croppingBitmap) { mutableStateOf(Offset(50f, 400f)) }

    // Initialize corners once image size is known or when a new image is loaded
    LaunchedEffect(imageSize, croppingBitmap) {
        if (imageSize.width > 0 && imageSize.height > 0 && croppingBitmap != null) {
            val bmp = croppingBitmap!!
            val savedCorners = viewModel.getCornersForCropping()
            if (savedCorners != null && savedCorners.size == 4) {
                val scaleX = imageSize.width.toFloat() / bmp.width.toFloat()
                val scaleY = imageSize.height.toFloat() / bmp.height.toFloat()
                tl = Offset((savedCorners[0].x * scaleX).toFloat(), (savedCorners[0].y * scaleY).toFloat())
                tr = Offset((savedCorners[1].x * scaleX).toFloat(), (savedCorners[1].y * scaleY).toFloat())
                br = Offset((savedCorners[2].x * scaleX).toFloat(), (savedCorners[2].y * scaleY).toFloat())
                bl = Offset((savedCorners[3].x * scaleX).toFloat(), (savedCorners[3].y * scaleY).toFloat())
            } else {
                val padding = minOf(imageSize.width, imageSize.height) * 0.1f // 10% padding
                tl = Offset(padding, padding)
                tr = Offset(imageSize.width - padding, padding)
                br = Offset(imageSize.width - padding, imageSize.height - padding)
                bl = Offset(padding, imageSize.height - padding)
            }
        }
    }

    val docNotFoundMsg = stringResource(id = R.string.doc_not_found)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Cancel
                    IconButton(
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isAutoRunning,
                        onClick = { viewModel.closeCrop(save = false) }
                    ) {
                        Icon(Icons.Default.ArrowBack, stringResource(id = R.string.cancel))
                    }

                // 2. Full
                TextButton(
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isAutoRunning,
                    onClick = {
                        if (imageSize.width > 0 && imageSize.height > 0) {
                            tl = Offset(0f, 0f)
                            tr = Offset(imageSize.width.toFloat(), 0f)
                            br = Offset(imageSize.width.toFloat(), imageSize.height.toFloat())
                            bl = Offset(0f, imageSize.height.toFloat())
                        }
                    }
                ) {
                    Text(stringResource(id = R.string.full), color = MaterialTheme.colorScheme.onPrimaryContainer)
                }

                // 3a. TF Auto (TFLite Model based detection)
                TextButton(
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isAutoRunning,
                    onClick = {
                        val currentBmp = croppingBitmap
                        if (imageSize.width > 0 && imageSize.height > 0 && currentBmp != null) {
                            viewModel.detectEdgesWithTFLite(currentBmp) { points ->
                                if (points != null && points.size == 4) {
                                    val scaleX = imageSize.width.toFloat() / currentBmp.width.toFloat()
                                    val scaleY = imageSize.height.toFloat() / currentBmp.height.toFloat()
                                    tl = Offset((points[0].x * scaleX).toFloat(), (points[0].y * scaleY).toFloat())
                                    tr = Offset((points[1].x * scaleX).toFloat(), (points[1].y * scaleY).toFloat())
                                    br = Offset((points[2].x * scaleX).toFloat(), (points[2].y * scaleY).toFloat())
                                    bl = Offset((points[3].x * scaleX).toFloat(), (points[3].y * scaleY).toFloat())
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(docNotFoundMsg)
                                    }
                                }
                            }
                        }
                    }
                ) {
                    if (uiState.isAutoRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("AI", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

                // 3. Auto
                TextButton(
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isAutoRunning,
                    onClick = {
                        val currentBmp = croppingBitmap
                        if (imageSize.width > 0 && imageSize.height > 0 && currentBmp != null) {
                            viewModel.detectEdges(currentBmp) { points ->
                                if (points != null && points.size == 4) {
                                    val scaleX = imageSize.width.toFloat() / currentBmp.width.toFloat()
                                    val scaleY = imageSize.height.toFloat() / currentBmp.height.toFloat()
                                    tl = Offset((points[0].x * scaleX).toFloat(), (points[0].y * scaleY).toFloat())
                                    tr = Offset((points[1].x * scaleX).toFloat(), (points[1].y * scaleY).toFloat())
                                    br = Offset((points[2].x * scaleX).toFloat(), (points[2].y * scaleY).toFloat())
                                    bl = Offset((points[3].x * scaleX).toFloat(), (points[3].y * scaleY).toFloat())
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(docNotFoundMsg)
                                    }
                                }
                            }
                        }
                    }
                ) {
                    if (uiState.isAutoRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(id = R.string.auto), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

                // 4. Save
                IconButton(
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isAutoRunning,
                    onClick = { 
                        if (imageSize.width > 0 && imageSize.height > 0 && croppingBitmap != null) {
                            val bmp = croppingBitmap!!
                            val scaleX = bmp.width.toFloat() / imageSize.width
                            val scaleY = bmp.height.toFloat() / imageSize.height
                            
                            val quad = Quadrilateral(
                                Point((tl.x * scaleX).toDouble().coerceIn(0.0, bmp.width.toDouble() - 1.0), (tl.y * scaleY).toDouble().coerceIn(0.0, bmp.height.toDouble() - 1.0)),
                                Point((tr.x * scaleX).toDouble().coerceIn(0.0, bmp.width.toDouble() - 1.0), (tr.y * scaleY).toDouble().coerceIn(0.0, bmp.height.toDouble() - 1.0)),
                                Point((br.x * scaleX).toDouble().coerceIn(0.0, bmp.width.toDouble() - 1.0), (br.y * scaleY).toDouble().coerceIn(0.0, bmp.height.toDouble() - 1.0)),
                                Point((bl.x * scaleX).toDouble().coerceIn(0.0, bmp.width.toDouble() - 1.0), (bl.y * scaleY).toDouble().coerceIn(0.0, bmp.height.toDouble() - 1.0))
                            )
                            viewModel.applyCrop(quad)
                        }
                    }
                ) {
                    Icon(Icons.Default.Check, stringResource(id = R.string.save))
                }

                // 5. Next
                TextButton(
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isAutoRunning,
                    onClick = {
                        if (imageSize.width > 0 && imageSize.height > 0 && croppingBitmap != null) {
                            val bmp = croppingBitmap!!
                            val scaleX = bmp.width.toFloat() / imageSize.width
                            val scaleY = bmp.height.toFloat() / imageSize.height
                            
                            val quad = Quadrilateral(
                                Point((tl.x * scaleX).toDouble().coerceIn(0.0, bmp.width.toDouble() - 1.0), (tl.y * scaleY).toDouble().coerceIn(0.0, bmp.height.toDouble() - 1.0)),
                                Point((tr.x * scaleX).toDouble().coerceIn(0.0, bmp.width.toDouble() - 1.0), (tr.y * scaleY).toDouble().coerceIn(0.0, bmp.height.toDouble() - 1.0)),
                                Point((br.x * scaleX).toDouble().coerceIn(0.0, bmp.width.toDouble() - 1.0), (br.y * scaleY).toDouble().coerceIn(0.0, bmp.height.toDouble() - 1.0)),
                                Point((bl.x * scaleX).toDouble().coerceIn(0.0, bmp.width.toDouble() - 1.0), (bl.y * scaleY).toDouble().coerceIn(0.0, bmp.height.toDouble() - 1.0))
                            )
                            viewModel.applyCrop(quad, andNext = true)
                        }
                    }
                ) {
                    Text(stringResource(id = R.string.next), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            croppingBitmap?.let { bmp ->
                        var draggingHandle by remember { mutableStateOf<String?>(null) }
                    var dragOffset by remember { mutableStateOf(Offset.Zero) }

                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val screenRatio = maxWidth.value / maxHeight.value
                        val imgRatio = bmp.width.toFloat() / bmp.height.toFloat()

                        Box(
                            modifier = Modifier
                                .aspectRatio(imgRatio, matchHeightConstraintsFirst = imgRatio < screenRatio)
                                .onGloballyPositioned { coordinates ->
                                    imageSize = coordinates.size
                                }
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Crop Image",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds
                            )

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val path = Path().apply {
                                moveTo(tl.x, tl.y)
                                lineTo(tr.x, tr.y)
                                lineTo(br.x, br.y)
                                lineTo(bl.x, bl.y)
                                close()
                            }
                            
                            // Draw semi-transparent overlay outside crop area
                            drawPath(
                                path = path,
                                color = Color.Cyan.copy(alpha = 0.2f)
                            )

                            drawPath(
                                path = path,
                                color = Color.Cyan,
                                style = Stroke(width = 2.dp.toPx())
                            )

                            // 3x3 Perspective/Bilinear grid lines when dragging a handle
                            if (draggingHandle != null) {
                                val gridColor = Color(0xFF10B981).copy(alpha = 0.5f)
                                val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

                                // Vertical Grid Lines (interp 1/3 and 2/3)
                                for (i in 1..2) {
                                    val ratio = i / 3f
                                    val startPoint = Offset(
                                        tl.x + (tr.x - tl.x) * ratio,
                                        tl.y + (tr.y - tl.y) * ratio
                                    )
                                    val endPoint = Offset(
                                        bl.x + (br.x - bl.x) * ratio,
                                        bl.y + (br.y - bl.y) * ratio
                                    )
                                    drawLine(
                                        color = gridColor,
                                        start = startPoint,
                                        end = endPoint,
                                        strokeWidth = 1.dp.toPx(),
                                        pathEffect = pathEffect
                                    )
                                }

                                // Horizontal Grid Lines (interp 1/3 and 2/3)
                                for (j in 1..2) {
                                    val ratio = j / 3f
                                    val startPoint = Offset(
                                        tl.x + (bl.x - tl.x) * ratio,
                                        tl.y + (bl.y - tl.y) * ratio
                                    )
                                    val endPoint = Offset(
                                        tr.x + (br.x - tr.x) * ratio,
                                        tr.y + (br.y - tr.y) * ratio
                                    )
                                    drawLine(
                                        color = gridColor,
                                        start = startPoint,
                                        end = endPoint,
                                        strokeWidth = 1.dp.toPx(),
                                        pathEffect = pathEffect
                                    )
                                }
                            }

                            // Draw corner and side handles
                            val handleRadius = 12.dp.toPx()
                            val outerRadius = 16.dp.toPx()
                            val sideHandleRadius = 8.dp.toPx()
                            
                            // Corner handles with active dragging glow
                            listOf(
                                Triple(tl, "tl", Color(0xFF10B981)),
                                Triple(tr, "tr", Color(0xFF10B981)),
                                Triple(br, "br", Color(0xFF10B981)),
                                Triple(bl, "bl", Color(0xFF10B981))
                            ).forEach { (center, label, brandColor) ->
                                val isActive = draggingHandle == label
                                val scaleFactor = if (isActive) 1.35f else 1.0f
                                val activeColor = if (isActive) brandColor else Color.Cyan
                                
                                if (isActive) {
                                    drawCircle(
                                        color = brandColor.copy(alpha = 0.35f),
                                        radius = outerRadius * 2.0f,
                                        center = center
                                    )
                                }
                                
                                drawCircle(Color.White, radius = outerRadius * scaleFactor, center = center)
                                drawCircle(activeColor, radius = handleRadius * scaleFactor, center = center)
                            }

                            // Side handles (midpoints) with active dragging glow
                            val midTop = Offset((tl.x + tr.x) / 2, (tl.y + tr.y) / 2)
                            val midRight = Offset((tr.x + br.x) / 2, (tr.y + br.y) / 2)
                            val midBottom = Offset((br.x + bl.x) / 2, (br.y + bl.y) / 2)
                            val midLeft = Offset((bl.x + tl.x) / 2, (bl.y + tl.y) / 2)

                            listOf(
                                Pair(midTop, "midTop"),
                                Pair(midRight, "midRight"),
                                Pair(midBottom, "midBottom"),
                                Pair(midLeft, "midLeft")
                            ).forEach { (center, label) ->
                                val isActive = draggingHandle == label
                                val scaleFactor = if (isActive) 1.3f else 1.0f
                                val activeColor = if (isActive) Color(0xFF10B981) else Color.Cyan
                                
                                if (isActive) {
                                    drawCircle(
                                        color = Color(0xFF10B981).copy(alpha = 0.3f),
                                        radius = (sideHandleRadius + 2.dp.toPx()) * 2.2f,
                                        center = center
                                    )
                                }
                                drawCircle(Color.White, radius = (sideHandleRadius + 2.dp.toPx()) * scaleFactor, center = center)
                                drawCircle(activeColor, radius = sideHandleRadius * scaleFactor, center = center)
                            }
                        }

                        // Midpoints for side handle logic
                        val midTop = Offset((tl.x + tr.x) / 2, (tl.y + tr.y) / 2)
                        val midRight = Offset((tr.x + br.x) / 2, (tr.y + br.y) / 2)
                        val midBottom = Offset((br.x + bl.x) / 2, (br.y + bl.y) / 2)
                        val midLeft = Offset((bl.x + tl.x) / 2, (bl.y + tl.y) / 2)

                        // Corner Handles
                        CornerHandle(
                            key = bmp,
                            offset = tl, 
                            onDragStart = { draggingHandle = "tl" },
                            onDragEnd = { draggingHandle = null },
                            onDrag = { 
                                tl = updateOffset(tl, it, imageSize)
                                dragOffset = tl
                            }
                        )
                        CornerHandle(
                            key = bmp,
                            offset = tr, 
                            onDragStart = { draggingHandle = "tr" },
                            onDragEnd = { draggingHandle = null },
                            onDrag = { 
                                tr = updateOffset(tr, it, imageSize)
                                dragOffset = tr
                            }
                        )
                        CornerHandle(
                            key = bmp,
                            offset = br, 
                            onDragStart = { draggingHandle = "br" },
                            onDragEnd = { draggingHandle = null },
                            onDrag = { 
                                br = updateOffset(br, it, imageSize)
                                dragOffset = br
                            }
                        )
                        CornerHandle(
                            key = bmp,
                            offset = bl, 
                            onDragStart = { draggingHandle = "bl" },
                            onDragEnd = { draggingHandle = null },
                            onDrag = { 
                                bl = updateOffset(bl, it, imageSize)
                                dragOffset = bl
                            }
                        )

                        // Side Handles
                        CornerHandle(
                            key = bmp,
                            offset = midTop,
                            size = 40.dp,
                            onDragStart = { draggingHandle = "midTop" },
                            onDragEnd = { draggingHandle = null },
                            onDrag = { delta ->
                                tl = updateOffset(tl, Offset(0f, delta.y), imageSize)
                                tr = updateOffset(tr, Offset(0f, delta.y), imageSize)
                                dragOffset = Offset((tl.x + tr.x) / 2, (tl.y + tr.y) / 2)
                            }
                        )
                        CornerHandle(
                            key = bmp,
                            offset = midRight,
                            size = 40.dp,
                            onDragStart = { draggingHandle = "midRight" },
                            onDragEnd = { draggingHandle = null },
                            onDrag = { delta ->
                                tr = updateOffset(tr, Offset(delta.x, 0f), imageSize)
                                br = updateOffset(br, Offset(delta.x, 0f), imageSize)
                                dragOffset = Offset((tr.x + br.x) / 2, (tr.y + br.y) / 2)
                            }
                        )
                        CornerHandle(
                            key = bmp,
                            offset = midBottom,
                            size = 40.dp,
                            onDragStart = { draggingHandle = "midBottom" },
                            onDragEnd = { draggingHandle = null },
                            onDrag = { delta ->
                                br = updateOffset(br, Offset(0f, delta.y), imageSize)
                                bl = updateOffset(bl, Offset(0f, delta.y), imageSize)
                                dragOffset = Offset((br.x + bl.x) / 2, (br.y + bl.y) / 2)
                            }
                        )
                        CornerHandle(
                            key = bmp,
                            offset = midLeft,
                            size = 40.dp,
                            onDragStart = { draggingHandle = "midLeft" },
                            onDragEnd = { draggingHandle = null },
                            onDrag = { delta ->
                                tl = updateOffset(tl, Offset(delta.x, 0f), imageSize)
                                bl = updateOffset(bl, Offset(delta.x, 0f), imageSize)
                                dragOffset = Offset((bl.x + tl.x) / 2, (bl.y + tl.y) / 2)
                            }
                        )

                        // Improved Circular Magnifier
                        draggingHandle?.let {
                            val magnifierSize = 140.dp
                            val magnifierPos = if (dragOffset.y < imageSize.height / 3) {
                                Alignment.BottomCenter
                            } else {
                                Alignment.TopCenter
                            }
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = magnifierPos
                            ) {
                                Card(
                                    shape = CircleShape,
                                    border = androidx.compose.foundation.BorderStroke(3.dp, Color(0xFF10B981)),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                                ) {
                                    Box(modifier = Modifier.size(magnifierSize)) {
                                        val zoom = 4f
                                        val density = androidx.compose.ui.platform.LocalDensity.current.density
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(width = (imageSize.width.toFloat() / density * zoom).dp, height = (imageSize.height.toFloat() / density * zoom).dp)
                                                .offset(
                                                    x = (-(dragOffset.x / density * zoom) + (magnifierSize.value / 2)).dp,
                                                    y = (-(dragOffset.y / density * zoom) + (magnifierSize.value / 2)).dp
                                                ),
                                            contentScale = ContentScale.FillBounds
                                        )
                                        // Crosshair matching the Emerald brand color
                                        Divider(modifier = Modifier.width(30.dp).align(Alignment.Center), color = Color(0xFF10B981), thickness = 1.dp)
                                        Divider(modifier = Modifier.height(30.dp).width(1.dp).align(Alignment.Center), color = Color(0xFF10B981))
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }
}
}

private fun updateOffset(current: Offset, delta: Offset, bounds: IntSize): Offset {
    val newX = (current.x + delta.x).coerceIn(0f, bounds.width.toFloat())
    val newY = (current.y + delta.y).coerceIn(0f, bounds.height.toFloat())
    return Offset(newX, newY)
}

@Composable
fun CornerHandle(
    key: Any? = null,
    offset: Offset, 
    size: androidx.compose.ui.unit.Dp = 48.dp,
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
                x = with(androidx.compose.ui.platform.LocalDensity.current) { offset.x.toDp() - size / 2 },
                y = with(androidx.compose.ui.platform.LocalDensity.current) { offset.y.toDp() - size / 2 }
            )
            .size(size)
            .pointerInput(key ?: Unit) {
                detectDragGestures(
                    onDragStart = { 
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
