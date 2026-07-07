package com.safescan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    var tl by remember { mutableStateOf(Offset(50f, 50f)) }
    var tr by remember { mutableStateOf(Offset(300f, 50f)) }
    var br by remember { mutableStateOf(Offset(300f, 400f)) }
    var bl by remember { mutableStateOf(Offset(50f, 400f)) }

    // Initialize corners once image size is known
    LaunchedEffect(imageSize) {
        if (imageSize.width > 0 && imageSize.height > 0) {
            val padding = minOf(imageSize.width, imageSize.height) * 0.1f // 10% padding
            tl = Offset(padding, padding)
            tr = Offset(imageSize.width - padding, padding)
            br = Offset(imageSize.width - padding, imageSize.height - padding)
            bl = Offset(padding, imageSize.height - padding)
        }
    }

    val docNotFoundMsg = stringResource(id = R.string.doc_not_found)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.crop_document)) },
                navigationIcon = {
                    // IMPROVEMENT: Disabled cancel button when auto edge detection is running to prevent crash
                    IconButton(
                        enabled = !uiState.isAutoRunning,
                        onClick = { viewModel.closeCrop(save = false) }
                    ) {
                        Icon(Icons.Default.ArrowBack, stringResource(id = R.string.cancel))
                    }
                },
                actions = {
                    // IMPROVEMENT: Added disabled states and a CircularProgressIndicator during Edge Detection run
                    TextButton(
                        enabled = !uiState.isAutoRunning,
                        onClick = {
                            if (imageSize.width > 0 && imageSize.height > 0 && croppingBitmap != null) {
                                viewModel.detectEdges(croppingBitmap!!) { points ->
                                    if (points != null && points.size == 4) {
                                        val scaleX = imageSize.width.toFloat() / croppingBitmap!!.width.toFloat()
                                        val scaleY = imageSize.height.toFloat() / croppingBitmap!!.height.toFloat()
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
                    IconButton(
                        enabled = !uiState.isAutoRunning,
                        onClick = { 
                            if (imageSize.width > 0 && imageSize.height > 0 && croppingBitmap != null) {
                                val bmp = croppingBitmap!!
                                val scaleX = bmp.width.toFloat() / imageSize.width
                                val scaleY = bmp.height.toFloat() / imageSize.height
                                
                                // IMPROVEMENT: TASK 9 - Clamp all coordinates to image bounds to prevent IndexOutOfBounds
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
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            croppingBitmap?.let { bmp ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                        .onGloballyPositioned { coordinates ->
                            imageSize = coordinates.size
                        }
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Crop Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
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

                        // Draw corner handles
                        val handleRadius = 12.dp.toPx()
                        val outerRadius = 16.dp.toPx()
                        
                        listOf(tl, tr, br, bl).forEach { center ->
                            drawCircle(Color.White, radius = outerRadius, center = center)
                            drawCircle(Color.Cyan, radius = handleRadius, center = center)
                        }
                    }

                    // Touch handlers with Magnifier logic
                    var draggingCorner by remember { mutableStateOf<String?>(null) }
                    var dragOffset by remember { mutableStateOf(Offset.Zero) }

                    CornerHandle(
                        offset = tl, 
                        onDragStart = { draggingCorner = "tl" },
                        onDragEnd = { draggingCorner = null },
                        onDrag = { 
                            tl = updateOffset(tl, it, imageSize)
                            dragOffset = tl
                        }
                    )
                    CornerHandle(
                        offset = tr, 
                        onDragStart = { draggingCorner = "tr" },
                        onDragEnd = { draggingCorner = null },
                        onDrag = { 
                            tr = updateOffset(tr, it, imageSize)
                            dragOffset = tr
                        }
                    )
                    CornerHandle(
                        offset = br, 
                        onDragStart = { draggingCorner = "br" },
                        onDragEnd = { draggingCorner = null },
                        onDrag = { 
                            br = updateOffset(br, it, imageSize)
                            dragOffset = br
                        }
                    )
                    CornerHandle(
                        offset = bl, 
                        onDragStart = { draggingCorner = "bl" },
                        onDragEnd = { draggingCorner = null },
                        onDrag = { 
                            bl = updateOffset(bl, it, imageSize)
                            dragOffset = bl
                        }
                    )

                    // Simple Magnifier (Simulated by showing a zoomed box at top or bottom)
                    draggingCorner?.let {
                        val magnifierSize = 120.dp
                        val magnifierPos = if (dragOffset.y < imageSize.height / 2) {
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
                                shape = RoundedCornerShape(magnifierSize / 2),
                                border = androidx.compose.foundation.BorderStroke(2.dp, Color.Cyan),
                                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                            ) {
                                Box(modifier = Modifier.size(magnifierSize)) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(magnifierSize * 3) // 3x zoom
                                            .offset(
                                                x = -(dragOffset.x * (magnifierSize.value * 3 / imageSize.width)).dp + (magnifierSize / 2),
                                                y = -(dragOffset.y * (magnifierSize.value * 3 / imageSize.height)).dp + (magnifierSize / 2)
                                            ),
                                        contentScale = ContentScale.FillBounds
                                    )
                                    // Crosshair
                                    Divider(modifier = Modifier.width(20.dp).align(Alignment.Center), color = Color.Cyan, thickness = 1.dp)
                                    Divider(modifier = Modifier.height(20.dp).width(1.dp).align(Alignment.Center), color = Color.Cyan)
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
    offset: Offset, 
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDrag: (Offset) -> Unit
) {
    Box(
        modifier = Modifier
            .offset(
                x = with(androidx.compose.ui.platform.LocalDensity.current) { offset.x.toDp() - 24.dp },
                y = with(androidx.compose.ui.platform.LocalDensity.current) { offset.y.toDp() - 24.dp }
            )
            .size(48.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }
    )
}
