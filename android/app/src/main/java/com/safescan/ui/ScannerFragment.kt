package com.safescan.ui

import android.annotation.SuppressLint
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.safescan.databinding.FragmentScannerBinding
import com.safescan.scanner.ScannerEngineType
import com.safescan.scanner.ScannerViewModel
import com.safescan.domain.model.Point
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.safescan.ui.SlotsScreen
import com.safescan.ui.theme.SafeScanTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class ScannerFragment : Fragment() {

    private var _binding: FragmentScannerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ScannerViewModel by viewModels()
    private val liveEdgeDetectionEngine by lazy { com.safescan.scanner.LiveEdgeDetectionEngine() }

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var flashEnabled = false

    private enum class FragmentViewMode {
        LIBRARY,
        WIZARD,
        SCANNER
    }
    private var currentViewMode = FragmentViewMode.LIBRARY
    private var isTargetLocked = false
    private var lastBackPressedTime = 0L

    override fun onPause() {
        super.onPause()
        cameraProvider?.unbindAll() // Stop live camera
        imageAnalysis?.setAnalyzer(cameraExecutor, null) // Stop live analysis
    }

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            updateViewMode(FragmentViewMode.SCANNER)
            Toast.makeText(context, "Camera permission is required to scan documents.", Toast.LENGTH_LONG).show()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris: List<android.net.Uri>? ->
        uris?.let { list ->
            if (list.isEmpty()) return@let
            
            viewLifecycleOwner.lifecycleScope.launch {
                var successCount = 0
                for (uri in list) {
                    try {
                        val inputStream = requireContext().contentResolver.openInputStream(uri)
                        var importedBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                        if (importedBitmap != null) {
                            // Always apply EXIF orientation correction so images display upright
                            val exifRotation = getExifRotation(requireContext(), uri)
                            if (exifRotation != 0) {
                                val matrix = android.graphics.Matrix().apply { postRotate(exifRotation.toFloat()) }
                                val rotated = android.graphics.Bitmap.createBitmap(importedBitmap, 0, 0, importedBitmap.width, importedBitmap.height, matrix, true)
                                importedBitmap.recycle()
                                importedBitmap = rotated
                            }
                            // If Auto-Rotation is enabled, apply intelligent mode-based aspect ratio correction
                            if (viewModel.autoRotation.value) {
                                importedBitmap = autoRotateForMode(importedBitmap, viewModel.currentMode.value)
                            }
                            viewModel.onCapture(importedBitmap, forceSkipEditor = list.size > 1)
                            successCount++
                        }
                    } catch (e: Exception) {
                        Log.e("ScannerFragment", "Error reading imported image", e)
                    }
                }
                if (successCount > 0) {
                    Toast.makeText(context, "$successCount images imported successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to import images", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var photoUri: android.net.Uri? = null

    private val takePictureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            photoUri?.let { uri ->
                try {
                    val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        android.graphics.ImageDecoder.decodeBitmap(
                            android.graphics.ImageDecoder.createSource(requireContext().contentResolver, uri)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        android.provider.MediaStore.Images.Media.getBitmap(requireContext().contentResolver, uri)
                    }
                    // It returns a hardware bitmap on P+. We might need to copy it to a software bitmap to process it.
                    var softwareBitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                    
                    // Always apply EXIF orientation correction so images display upright
                    val exifRotation = getExifRotation(requireContext(), uri)
                    if (exifRotation != 0) {
                        val matrix = android.graphics.Matrix().apply { postRotate(exifRotation.toFloat()) }
                        val rotated = android.graphics.Bitmap.createBitmap(softwareBitmap, 0, 0, softwareBitmap.width, softwareBitmap.height, matrix, true)
                        softwareBitmap.recycle()
                        softwareBitmap = rotated
                    }
                    // If Auto-Rotation is enabled, apply intelligent mode-based aspect ratio correction
                    if (viewModel.autoRotation.value) {
                        softwareBitmap = autoRotateForMode(softwareBitmap, viewModel.currentMode.value)
                    }
                    
                    viewModel.onCapture(softwareBitmap, isNativeScanned = true)
                } catch (e: Exception) {
                    Log.e("ScannerFragment", "Failed to process captured image", e)
                }
            }
        }
    }

    private val systemDocumentScannerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pages?.let { pages ->
                viewLifecycleOwner.lifecycleScope.launch {
                    for (page in pages) {
                        try {
                            val uri = page.imageUri
                            val inputStream = requireContext().contentResolver.openInputStream(uri)
                            var scanBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                            inputStream?.close()
                            if (scanBitmap != null) {
                                // Always apply EXIF orientation correction so images display upright
                                val exifRotation = getExifRotation(requireContext(), uri)
                                if (exifRotation != 0) {
                                    val matrix = android.graphics.Matrix().apply { postRotate(exifRotation.toFloat()) }
                                    val rotated = android.graphics.Bitmap.createBitmap(scanBitmap, 0, 0, scanBitmap.width, scanBitmap.height, matrix, true)
                                    scanBitmap.recycle()
                                    scanBitmap = rotated
                                }
                                // If Auto-Rotation is enabled, apply intelligent mode-based aspect ratio correction
                                if (viewModel.autoRotation.value) {
                                    scanBitmap = autoRotateForMode(scanBitmap, viewModel.currentMode.value)
                                }
                                viewModel.onCapture(scanBitmap, isNativeScanned = true, forceSkipEditor = true)
                            }
                        } catch (e: Exception) {
                            Log.e("ScannerFragment", "Error processing scanned image", e)
                        }
                    }
                }
            }
        }
    }

    private val ocrScannerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val scannedText = data?.getStringExtra("label")
                ?: data?.getStringExtra("text")
                ?: data?.getStringExtra("com.google.android.gms.actions.extra.TEXT")
                ?: data?.getStringExtra("com.google.android.gms.actions.extra.OCR_TEXT")
                ?: data?.getStringExtra("OCR_TEXT")
                ?: ""
            
            if (scannedText.isNotEmpty()) {
                viewModel.recognizedText.value = scannedText
                Toast.makeText(context, "Text captured successfully!", Toast.LENGTH_SHORT).show()
                Toast.makeText(context, "No text detected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val qrCodeScannerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val scannedQr = data?.getStringExtra("android.provider.extra.SCAN_QR_CODE_RESULT")
                ?: data?.getStringExtra("SCAN_RESULT")
                ?: data?.data?.toString()
                ?: ""
            
            if (scannedQr.isNotEmpty()) {
                viewModel.recognizedText.value = scannedQr
                Toast.makeText(context, "QR Scanned: $scannedQr", Toast.LENGTH_LONG).show()
                Toast.makeText(context, "No QR data found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openDocumentScanner(maxPages: Int) {
        try {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(maxPages)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
                
            val scanner = GmsDocumentScanning.getClient(options)
            scanner.getStartScanIntent(requireActivity())
                .addOnSuccessListener { intentSender ->
                    systemDocumentScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
                .addOnFailureListener { e ->
                    Log.e("ScannerFragment", "Failed to start system document scanner", e)
                    Toast.makeText(context, "System document scanner not available.", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Log.e("ScannerFragment", "Failed to start system document scanner", e)
            Toast.makeText(context, "System document scanner not available.", Toast.LENGTH_SHORT).show()
        }
    }

    fun openOcrScanner() {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = android.net.Uri.parse("googleapp://lens")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW)
                fallbackIntent.data = android.net.Uri.parse("https://lens.google.com/")
                startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), "Install Google Lens", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openQrScanner() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val intent = Intent("android.provider.action.SCAN_QR_CODE")
                qrCodeScannerLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e("ScannerFragment", "Failed to start system QR scanner", e)
                Toast.makeText(context, "System QR scanner not available.", Toast.LENGTH_SHORT).show()
            }
            Toast.makeText(requireContext(), "Requires Android 14+", Toast.LENGTH_SHORT).show()
        }
    }

    private var shutterSound: android.media.MediaActionSound? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScannerBinding.inflate(inflater, container, false)
        shutterSound = android.media.MediaActionSound()
        shutterSound?.load(android.media.MediaActionSound.SHUTTER_CLICK)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupObservers()
        setupListeners()

        // Handle physical device back presses gracefully
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val isEditing = viewModel.isEditing.value
                val isCropping = viewModel.isCropping.value
                val isSettingsOpen = viewModel.isSettingsOpen.value
                val isGridViewVisible = viewModel.isGridViewVisible.value
                if (isGridViewVisible) {
                    viewModel.isGridViewVisible.value = false
                } else if (isSettingsOpen) {
                    viewModel.isSettingsOpen.value = false
                } else if (isCropping) {
                    viewModel.isCropping.value = false
                } else if (isEditing) {
                    viewModel.isEditing.value = false
                } else if (currentViewMode == FragmentViewMode.WIZARD) {
                    updateViewMode(FragmentViewMode.LIBRARY)
                } else if (currentViewMode == FragmentViewMode.SCANNER) {
                    updateViewMode(FragmentViewMode.LIBRARY)
                } else if (currentViewMode == FragmentViewMode.LIBRARY) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastBackPressedTime < 2000) {
                        isEnabled = false
                        requireActivity().finish()
                    } else {
                        lastBackPressedTime = currentTime
                        Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        // Default app to Library on startup
        updateViewMode(FragmentViewMode.LIBRARY)
    }

    private fun updateViewMode(mode: FragmentViewMode) {
        currentViewMode = mode
        updateCameraState()
        if (mode == FragmentViewMode.LIBRARY) {
            // Hide camera-related XML views entirely
            binding.btnCapture.visibility = View.GONE
            binding.btnFlash.visibility = View.GONE
            binding.btnSwitchEngine.visibility = View.GONE
            binding.resultImageView.visibility = View.GONE
            binding.overlayView.visibility = View.GONE
            binding.overlayView.updateCorners(null)

            // Bind Compose View to LibraryScreen
            binding.composeView.apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    SafeScanTheme {
                        LibraryScreen(
                            viewModel = viewModel,
                            onStartScan = {
                                viewModel.isDocumentOpenedFromLibrary.value = false
                                viewModel.openedDocumentId = null
                                if (viewModel.wizardDontShowAgain.value) {
                                    checkPermissionAndStartScanner()
                                } else {
                                    updateViewMode(FragmentViewMode.WIZARD)
                                }
                            },
                            onOpenDocument = { doc ->
                                viewModel.loadDocumentIntoSlots(doc)
                                checkPermissionAndStartScanner()
                            }
                        )
                    }
                }
            }
        } else if (mode == FragmentViewMode.WIZARD) {
            // Hide camera-related XML views entirely
            binding.btnCapture.visibility = View.GONE
            binding.btnFlash.visibility = View.GONE
            binding.btnSwitchEngine.visibility = View.GONE
            binding.resultImageView.visibility = View.GONE
            binding.overlayView.visibility = View.GONE
            binding.overlayView.updateCorners(null)

            // Bind Compose View to WizardScreen
            binding.composeView.apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    SafeScanTheme {
                        com.safescan.ui.WizardScreen(
                            viewModel = viewModel,
                            onStartScan = {
                                checkPermissionAndStartScanner()
                            },
                            onClose = {
                                updateViewMode(FragmentViewMode.LIBRARY)
                            }
                        )
                    }
                }
            }
        } else {
            // Show camera-related XML views (only previewView, hide old buttons)
            binding.btnCapture.visibility = View.GONE
            binding.btnFlash.visibility = View.GONE
            binding.btnSwitchEngine.visibility = View.GONE
            
            // Bind Compose View to scanner/editor layout overlays
            binding.composeView.apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    SafeScanTheme {
                        val isEditing by viewModel.isEditing.collectAsState()
                        val isCropping by viewModel.isCropping.collectAsState()
                        val isSettingsOpen by viewModel.isSettingsOpen.collectAsState()
                        val isDocOpenFromLib by viewModel.isDocumentOpenedFromLibrary.collectAsState()

                        val showOverlay = !isSettingsOpen && !isCropping && !isEditing && !isDocOpenFromLib
                        androidx.compose.runtime.LaunchedEffect(showOverlay) {
                            binding.overlayView.visibility = if (showOverlay) View.VISIBLE else View.GONE
                            if (!showOverlay) {
                                binding.overlayView.updateCorners(null)
                            }
                        }

                        if (isSettingsOpen) {
                            com.safescan.ui.SettingsScreen(
                                viewModel = viewModel,
                                onBack = { viewModel.isSettingsOpen.value = false }
                            )
                        } else if (isCropping) {
                            com.safescan.ui.CropScreen(viewModel = viewModel)
                        } else if (isEditing) {
                            com.safescan.ui.EditorScreen(viewModel = viewModel)
                        } else if (isDocOpenFromLib) {
                            com.safescan.ui.DocumentGridView(
                                viewModel = viewModel,
                                onDismiss = {
                                    viewModel.isDocumentOpenedFromLibrary.value = false
                                    updateViewMode(FragmentViewMode.LIBRARY)
                                },
                                onScanPage = {
                                    viewModel.isDocumentOpenedFromLibrary.value = false
                                    viewModel.selectedSlotId.value = null
                                }
                            )
                        } else {
                            SlotsScreen(
                                viewModel = viewModel,
                                onCaptureClick = { takePhoto() },
                                onClose = { updateViewMode(FragmentViewMode.LIBRARY) },
                                onFlashToggle = { toggleFlash() },
                                onGalleryClick = { pickImageLauncher.launch("image/*") },
                                onSlotClick = { slotId ->
                                    viewModel.onSlotClick(slotId)
                                    // return to preview view and result image gone
                                    binding.resultImageView.visibility = View.GONE
                                    binding.previewView.visibility = View.VISIBLE
                                },
                                onSlotLongClick = { slotId ->
                                    viewModel.openEditor(slotId)
                                },
                                onWizardClick = { updateViewMode(FragmentViewMode.WIZARD) }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionAndStartScanner() {
        val permissionsToRequest = mutableListOf(android.Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
            permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            updateViewMode(FragmentViewMode.SCANNER)
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun setupListeners() {
        binding.btnCapture.setOnClickListener {
            takePhoto()
        }

        binding.btnFlash.setOnClickListener {
            toggleFlash()
        }

        binding.btnSwitchEngine.setOnClickListener {
            val current = viewModel.uiState.value.currentEngine
            val next = if (current == ScannerEngineType.OPENCV) {
                ScannerEngineType.LOCAL_ML
            } else {
                ScannerEngineType.OPENCV
            }
            viewModel.toggleEngine(next)
            context?.let { ctx ->
                Toast.makeText(ctx, "Engine set to: $next", Toast.LENGTH_SHORT).show()
            }
        }

        val scaleGestureDetector = android.view.ScaleGestureDetector(requireContext(), object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                val cameraInfoObj = cameraInfo ?: return false
                val cameraControlObj = cameraControl ?: return false
                val currentZoomRatio = cameraInfoObj.zoomState.value?.zoomRatio ?: 1f
                val delta = detector.scaleFactor
                val targetZoomRatio = (currentZoomRatio * delta).coerceIn(1f, 10f)
                cameraControlObj.setZoomRatio(targetZoomRatio)
                return true
            }
        })

        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (scaleGestureDetector.isInProgress) {
                return@setOnTouchListener true
            }
            if (event.action == MotionEvent.ACTION_DOWN) {
                val factory = binding.previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                
                val doubleFocus = viewModel.doubleFocusEnabled.value
                val action = if (doubleFocus) {
                    val centerPoint = factory.createPoint(binding.previewView.width / 2f, binding.previewView.height / 2f)
                    FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                        .addPoint(centerPoint, FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                } else {
                    FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                }
                
                cameraControl?.startFocusAndMetering(action)
                if (doubleFocus) {
                    Toast.makeText(requireContext(), "Dual-Point Focus Lock Active", Toast.LENGTH_SHORT).show()
                }
                return@setOnTouchListener true
            }
            true
        }
    }

    private fun mapPointsToPreviewView(
        points: List<Point>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        rotationDegrees: Int
    ): List<android.graphics.PointF> {
        val binding = _binding ?: return emptyList()
        val viewWidth = binding.previewView.width.toFloat()
        val viewHeight = binding.previewView.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) return emptyList()

        val rotatedWidth: Float
        val rotatedHeight: Float
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            rotatedWidth = bitmapHeight.toFloat()
            rotatedHeight = bitmapWidth.toFloat()
        } else {
            rotatedWidth = bitmapWidth.toFloat()
            rotatedHeight = bitmapHeight.toFloat()
        }

        val frameRatio = rotatedWidth / rotatedHeight
        val viewRatio = viewWidth / viewHeight

        val scale: Float
        val dx: Float
        val dy: Float

        if (frameRatio > viewRatio) {
            // The scaled frame is wider than the view, so height fits exactly, and left/right are cropped
            scale = viewHeight / rotatedHeight
            dx = (rotatedWidth * scale - viewWidth) / 2f
            dy = 0f
        } else {
            // The scaled frame is taller than the view, so width fits exactly, and top/bottom are cropped
            scale = viewWidth / rotatedWidth
            dx = 0f
            dy = (rotatedHeight * scale - viewHeight) / 2f
        }

        return points.map { pt ->
            // 1. First, normalize coordinates relative to the bitmap (0.0 to 1.0)
            val normX = pt.x.toFloat() / bitmapWidth
            val normY = pt.y.toFloat() / bitmapHeight

            // 2. Rotate the normalized coordinates if the sensor is rotated (usually 90 or 270 on Android)
            val rotatedX: Float
            val rotatedY: Float
            when (rotationDegrees) {
                90 -> {
                    rotatedX = 1f - normY
                    rotatedY = normX
                }
                180 -> {
                    rotatedX = 1f - normX
                    rotatedY = 1f - normY
                }
                270 -> {
                    rotatedX = normY
                    rotatedY = 1f - normX
                }
                else -> {
                    rotatedX = normX
                    rotatedY = normY
                }
            }

            // 3. Map to screen coordinate system with crop-offsets
            val screenX = (rotatedX * rotatedWidth * scale) - dx
            val screenY = (rotatedY * rotatedHeight * scale) - dy

            android.graphics.PointF(screenX, screenY)
        }
    }

    private fun getOverlayHoleRect(pw: Float, ph: Float): android.graphics.RectF {
        val mode = viewModel.currentMode.value
        val finalRatio = com.safescan.utils.PageConfig.getOnscreenLayoutRatio(requireContext(), mode)

        val maxWidth = pw * 0.90f
        val maxHeight = ph * 0.85f

        var rectWidth = maxWidth
        var rectHeight = rectWidth / finalRatio

        if (rectHeight > maxHeight) {
            rectHeight = maxHeight
            rectWidth = rectHeight * finalRatio
        }

        val rectLeft = (pw - rectWidth) / 2f
        val rectTop = (ph - rectHeight) / 2f
        return android.graphics.RectF(rectLeft, rectTop, rectLeft + rectWidth, rectTop + rectHeight)
    }

    private fun getExifRotation(context: android.content.Context, uri: android.net.Uri): Int {
        var rotation = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exifInterface = android.media.ExifInterface(inputStream)
                val orientation = exifInterface.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
                rotation = when (orientation) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ScannerFragment", "Failed to read EXIF rotation for uri: $uri", e)
        }
        return rotation
    }

    private fun autoRotateForMode(bitmap: android.graphics.Bitmap, mode: com.safescan.data.ScannerMode): android.graphics.Bitmap {
        val isLandscape = bitmap.width > bitmap.height
        val needsRotation = when (mode) {
            com.safescan.data.ScannerMode.DOCUMENT -> isLandscape // DOCUMENT should be portrait
            com.safescan.data.ScannerMode.CARD, com.safescan.data.ScannerMode.GRID -> !isLandscape // CARD/GRID should be landscape
        }
        return if (needsRotation) {
            com.safescan.core.ScannerDebugLogger.logAutoRotation(90f)
            val matrix = android.graphics.Matrix().apply { postRotate(90f) }
            val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            rotated
        } else {
            com.safescan.core.ScannerDebugLogger.logAutoRotation(0f)
            bitmap
        }
    }

    private fun updateCameraState() {
        if (!isAdded) return
        val currentContext = context ?: return
        val binding = _binding ?: return
        
        val isEditing = viewModel.isEditing.value
        val isCropping = viewModel.isCropping.value
        val isSettingsOpen = viewModel.isSettingsOpen.value
        val isDocOpenFromLib = viewModel.isDocumentOpenedFromLibrary.value
        val isGridViewVisible = viewModel.isGridViewVisible.value
        val isScannerMode = currentViewMode == FragmentViewMode.SCANNER
        val usePhoneCam = viewModel.usePhoneCamera.value
        val useNativeScan = viewModel.useNativeScanner.value
        
        val shouldCameraBeOn = isScannerMode && 
                               !isDocOpenFromLib &&
                               !isGridViewVisible && 
                               !isEditing && 
                               !isCropping && 
                               !isSettingsOpen && 
                               !usePhoneCam && 
                               !useNativeScan &&
                               allPermissionsGranted()
                               
        if (shouldCameraBeOn) {
            startCamera()
        } else {
            binding.previewView.visibility = View.GONE
            try {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(currentContext)
                cameraProviderFuture.addListener({
                    try {
                        this@ScannerFragment.cameraProvider = cameraProviderFuture.get()
                        this@ScannerFragment.cameraProvider?.unbindAll()
                        liveEdgeDetectionEngine.release()
                    } catch (e: Exception) {
                        Log.e("ScannerFragment", "Failed to unbind camera in updateCameraState", e)
                    }
                }, ContextCompat.getMainExecutor(currentContext))
            } catch (e: Exception) {
                Log.e("ScannerFragment", "Error getting camera provider in updateCameraState", e)
            }
        }
    }

    private fun startCamera() {
        if (!isAdded) return
        val currentContext = context ?: return
        if (ContextCompat.checkSelfPermission(currentContext, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(currentContext)

        cameraProviderFuture.addListener({
            try {
                val fragmentContext = context ?: return@addListener
                val binding = _binding ?: return@addListener
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                if (viewModel.useNativeScanner.value || viewModel.usePhoneCamera.value) {
                    cameraProvider.unbindAll()
                        liveEdgeDetectionEngine.release()
                    binding.previewView.visibility = View.INVISIBLE
                    return@addListener
                } else {
                    binding.previewView.visibility = View.VISIBLE
                }

                val mode = viewModel.currentMode.value
                val hdModeStr = viewModel.hdMode.value
                val currentRatio = com.safescan.scanner.CameraHardwareConfig.getTargetRatio(currentContext, mode)
                binding.overlayView.setAspectRatio(currentRatio)

                // 1. Dynamic Hardware Negotiation & Mood Alignment (configured in CameraHardwareConfig)
                val captureSettings = com.safescan.scanner.CameraHardwareConfig.getCaptureSettings(currentContext, mode, hdModeStr)
                com.safescan.core.ScannerDebugLogger.logCameraX("${captureSettings.targetSize.width}x${captureSettings.targetSize.height} (${captureSettings.megapixelsLabel})", mode.name)
                val previewSelector = com.safescan.scanner.CameraHardwareConfig.getPreviewResolutionSelector(currentContext, mode)
                val analysisSelector = com.safescan.scanner.CameraHardwareConfig.getImageAnalysisResolutionSelector(currentContext, mode)

                val previewBuilder = Preview.Builder()
                    .setResolutionSelector(previewSelector)
                
                val preview = previewBuilder.build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(captureSettings.captureMode)
                    .setResolutionSelector(captureSettings.resolutionSelector)
                    .build()

                // Initialize ImageAnalysis for live edge detection overlay
                this@ScannerFragment.imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(analysisSelector)
                    .build()

                this@ScannerFragment.imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
                    val isLiveDetectOn = viewModel.liveDetect.value
                    val isBatterySaverOn = viewModel.batterySaver.value
                    val isOverlayActive = !viewModel.isEditing.value && !viewModel.isCropping.value && !viewModel.isSettingsOpen.value && !viewModel.isDocumentOpenedFromLibrary.value && !viewModel.isGridViewVisible.value
                    if (isLiveDetectOn && !isBatterySaverOn && isOverlayActive) {
                        try {
                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                            val width = imageProxy.width
                            val height = imageProxy.height
                            _binding?.let { bindingObj ->
                                val pw = bindingObj.previewView.width.toFloat()
                                val ph = bindingObj.previewView.height.toFloat()
                                val holeRect = getOverlayHoleRect(pw, ph)
                                if (pw > 0 && ph > 0 && !holeRect.isEmpty) {
                                    val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                                    val bw = if (isRotated) height.toFloat() else width.toFloat()
                                    val bh = if (isRotated) width.toFloat() else height.toFloat()
                                    
                                    val ratioW = pw / bw
                                    val ratioH = ph / bh
                                    val scale = if (ratioW > ratioH) ratioW else ratioH
                                    val scaledBw = bw * scale
                                    val scaledBh = bh * scale
                                    
                                    val leftOffset = (scaledBw - pw) / 2f
                                    val topOffset = (scaledBh - ph) / 2f
                                    
                                    val cropLeft = ((holeRect.left + leftOffset) / scale).toInt()
                                    val cropTop = ((holeRect.top + topOffset) / scale).toInt()
                                    val cropRight = ((holeRect.right + leftOffset) / scale).toInt()
                                    val cropBottom = ((holeRect.bottom + topOffset) / scale).toInt()
                                    
                                    val safeLeft = cropLeft.coerceIn(0, bw.toInt())
                                    val safeTop = cropTop.coerceIn(0, bh.toInt())
                                    val safeRight = cropRight.coerceIn(0, bw.toInt())
                                    val safeBottom = cropBottom.coerceIn(0, bh.toInt())
                                    
                                    val rect = if (isRotated) {
                                        android.graphics.Rect(safeTop, safeLeft, safeBottom, safeRight)
                                    } else {
                                        android.graphics.Rect(safeLeft, safeTop, safeRight, safeBottom)
                                    }
                                    imageProxy.setCropRect(rect)
                                }
                            }
                            
                            liveEdgeDetectionEngine.process(imageProxy, viewModel.documentScanner, viewModel.uiState.value.currentEngine) { corners, sharpness ->
                                val mappedPoints = if (corners != null && corners.isNotEmpty()) {
                                    mapPointsToPreviewView(corners, width, height, rotationDegrees)
                                } else {
                                    null
                                }
                                activity?.runOnUiThread {
                                    val bindingObj = _binding
                                    bindingObj?.overlayView?.visibility = View.VISIBLE
                                    bindingObj?.overlayView?.updateCorners(mappedPoints)
                                    if (corners != null && corners.isNotEmpty()) {
                                        if (!isTargetLocked) {
                                            isTargetLocked = true
                                            if (viewModel.vibrateOnCapture.value) {
                                                bindingObj?.root?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                            }
                                        }
                                    } else {
                                        isTargetLocked = false
                                    }
                                }
                                viewModel.onDocumentDetected(corners, sharpness)
                            }
                        } catch (e: Exception) {
                            Log.e("ScannerFragment", "Live detection error", e)
                            imageProxy.close()
                        }
                    } else {
                        imageProxy.close()
                        isTargetLocked = false
                        viewModel.onDocumentDetected(null, 0.0)
                        activity?.runOnUiThread {
                            _binding?.overlayView?.visibility = View.GONE
                            _binding?.overlayView?.updateCorners(null)
                        }
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                        liveEdgeDetectionEngine.release()

                val camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis
                )

                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo

                // Enable torch according to current saved preference
                cameraControl?.enableTorch(viewModel.flashOn.value)

            } catch (exc: Exception) {
                Log.e("ScannerFragment", "CameraX initialization or binding failed", exc)
                context?.let { ctx ->
                    Toast.makeText(ctx, "Failed to initialize camera: ${exc.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }

        }, ContextCompat.getMainExecutor(context ?: return))
    }

    override fun onResume() {
        super.onResume()
        updateCameraState()
    }

    private fun allPermissionsGranted(): Boolean {
        val currentContext = context ?: return false
        return ContextCompat.checkSelfPermission(
            currentContext, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun openPhoneCamera() {
        val context = requireContext()
        val photoFile = java.io.File(context.cacheDir, "temp_camera_${System.currentTimeMillis()}.jpg")
        photoUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile
        )
        takePictureLauncher.launch(photoUri)
    }

    private fun focusAndTakePhoto(isAutoCapture: Boolean = false) {
        if (viewModel.isFocusing) return
        viewModel.isFocusing = true

        val binding = _binding
        if (binding == null) {
            viewModel.isFocusing = false
            takePhoto()
            return
        }
        
        val previewView = binding.previewView
        val mappedCorners = binding.overlayView.getCorners()
        
        var centerX = previewView.width / 2f
        var centerY = previewView.height / 2f
        
        if (mappedCorners != null && mappedCorners.size == 4) {
            var sumX = 0f
            var sumY = 0f
            for (p in mappedCorners) {
                sumX += p.x
                sumY += p.y
            }
            centerX = sumX / 4f
            centerY = sumY / 4f
        }

        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(centerX, centerY)
        // Relaxed settings for Auto Capture
        val flags = if (isAutoCapture) androidx.camera.core.FocusMeteringAction.FLAG_AF else androidx.camera.core.FocusMeteringAction.FLAG_AF or androidx.camera.core.FocusMeteringAction.FLAG_AE
        val action = androidx.camera.core.FocusMeteringAction.Builder(
            point, 
            flags
        ).build()
        
        val future = cameraControl?.startFocusAndMetering(action)
        if (future == null) {
            viewModel.isFocusing = false
            takePhoto()
            return
        }

        future.addListener({
            try {
                val result = future.get()
                if (result.isFocusSuccessful) {
                    Log.d("ScannerFragment", "Focus locked and successful. Triggering photo capture.")
                    takePhoto()
                } else {
                    Log.d("ScannerFragment", "Focus lock failed. Resetting isFocusing.")
                    cameraControl?.cancelFocusAndMetering()
                    viewModel.isFocusing = false
                }
            } catch (e: Exception) {
                Log.e("ScannerFragment", "Focus metering listener exception", e)
                cameraControl?.cancelFocusAndMetering()
                viewModel.isFocusing = false
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        if (viewModel.usePhoneCamera.value) {
            viewModel.isFocusing = false
            openPhoneCamera()
            return
        }
        if (viewModel.useNativeScanner.value) {
            viewModel.isFocusing = false
            val maxPages = when (viewModel.currentMode.value) {
                com.safescan.data.ScannerMode.CARD -> 2
                com.safescan.data.ScannerMode.GRID -> 8
                else -> 150
            }
            openDocumentScanner(maxPages)
            return
        }

        val imageCapture = imageCapture ?: run {
            viewModel.isFocusing = false
            return
        }
        val currentContext = context ?: run {
            viewModel.isFocusing = false
            return
        }
        val binding = _binding ?: run {
            viewModel.isFocusing = false
            return
        }

        binding.progressBar.visibility = View.VISIBLE

        // Play shutter sound if enabled
        if (viewModel.clickSound.value) {
            try {
                shutterSound?.play(android.media.MediaActionSound.SHUTTER_CLICK)
            } catch (e: Exception) {
                Log.e("ScannerFragment", "Failed to play shutter sound", e)
            }
        }

        // Trigger vibration/haptic feedback if enabled
        if (viewModel.vibrateOnCapture.value) {
            try {
                // Use performHapticFeedback for modern devices
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                
                // Also use Vibrator for redundancy
                val vibrator = currentContext.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (vibrator != null && vibrator.hasVibrator()) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
                    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(50)
                    }
                }
            } catch (e: Exception) {
                Log.e("ScannerFragment", "Failed to vibrate on capture", e)
            }
        }

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(currentContext),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    // FIX: FINAL LEAK
                    try {
                        cameraControl?.cancelFocusAndMetering()
                        val rawBitmap = imageProxy.toBitmap()
                        // ALWAYS rotate the raw camera sensor bitmap by imageProxy.imageInfo.rotationDegrees
                        // so it displays in standard upright portrait/landscape orientation matching the screen preview.
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        com.safescan.core.ScannerDebugLogger.logCameraRotation(rotationDegrees)
                        val bitmap = if (rotationDegrees != 0) {
                            val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                            val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                            rawBitmap.recycle()
                            rotated
                        } else {
                            rawBitmap
                        }

                        // Precisely crop the high-resolution photo to match the visual cutout frame before processing
                        val binding = _binding
                        var finalBitmap = if (binding != null) {
                            val pw = binding.previewView.width.toFloat()
                            val ph = binding.previewView.height.toFloat()
                            val holeRect = getOverlayHoleRect(pw, ph)
                            if (pw > 0f && ph > 0f && !holeRect.isEmpty) {
                                com.safescan.core.ScannerDebugLogger.logCrop(holeRect.left, holeRect.top, holeRect.width(), holeRect.height())
                                val bw = bitmap.width.toFloat()
                                val bh = bitmap.height.toFloat()
                                
                                val scale = maxOf(pw / bw, ph / bh)
                                val scaledBw = bw * scale
                                val scaledBh = bh * scale
                                
                                val leftOffset = (scaledBw - pw) / 2f
                                val topOffset = (scaledBh - ph) / 2f
                                
                                val cropLeft = ((holeRect.left + leftOffset) / scale).toInt().coerceIn(0, bitmap.width)
                                val cropTop = ((holeRect.top + topOffset) / scale).toInt().coerceIn(0, bitmap.height)
                                val cropRight = ((holeRect.right + leftOffset) / scale).toInt().coerceIn(0, bitmap.width)
                                val cropBottom = ((holeRect.bottom + topOffset) / scale).toInt().coerceIn(0, bitmap.height)
                                
                                val cropWidth = (cropRight - cropLeft).coerceAtLeast(100)
                                val cropHeight = (cropBottom - cropTop).coerceAtLeast(100)
                                
                                val safeWidth = cropWidth.coerceAtMost(bitmap.width - cropLeft)
                                val safeHeight = cropHeight.coerceAtMost(bitmap.height - cropTop)
                                
                                val cropped = Bitmap.createBitmap(bitmap, cropLeft, cropTop, safeWidth, safeHeight)
                                com.safescan.core.ScannerDebugLogger.logCropRoiSize(cropped.width, cropped.height)
                                bitmap.recycle()
                                cropped
                            } else {
                                bitmap
                            }
                        } else {
                            bitmap
                        }

                        // If Auto-Rotation is enabled, apply intelligent mode-based aspect ratio/layout correction
                        // (We always auto-rotate CARD and GRID captures so portrait overlays are transformed to landscape instantly)
                        if (viewModel.autoRotation.value || viewModel.currentMode.value == com.safescan.data.ScannerMode.CARD || viewModel.currentMode.value == com.safescan.data.ScannerMode.GRID) {
                            finalBitmap = autoRotateForMode(finalBitmap, viewModel.currentMode.value)
                        }

                        viewModel.onCapture(finalBitmap)
                        viewModel.isFocusing = false
                    } finally {
                        imageProxy.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    cameraControl?.cancelFocusAndMetering()
                    viewModel.isFocusing = false
                    Log.e("ScannerFragment", "Photo capture failed: ${exception.message}", exception)
                    _binding?.progressBar?.visibility = View.GONE
                    context?.let { ctx ->
                        Toast.makeText(ctx, "Capture failed: ${exception.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun toggleFlash() {
        viewModel.cycleFlashMode()
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val binding = _binding ?: return@collect
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    
                    state.scannedBitmap?.let { bitmap ->
                        binding.resultImageView.visibility = View.VISIBLE
                        binding.resultImageView.setImageBitmap(bitmap)
                        binding.previewView.visibility = View.INVISIBLE
                    } ?: run {
                        if (currentViewMode == FragmentViewMode.SCANNER) {
                            binding.resultImageView.visibility = View.GONE
                            binding.previewView.visibility = View.VISIBLE
                        }
                    }

                    state.errorMessage?.let { msg ->
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Observe flash/torch state to update physical camera on-the-fly without restart
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flashMode.collect { mode ->
                    val torchOn = mode == com.safescan.data.FlashMode.TORCH
                    flashEnabled = torchOn
                    
                    try {
                        cameraControl?.enableTorch(torchOn)
                    } catch (e: Exception) {
                        Log.e("ScannerFragment", "Failed to update torch", e)
                    }
                    imageCapture?.flashMode = when (mode) {
                        com.safescan.data.FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                        com.safescan.data.FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                        com.safescan.data.FlashMode.TORCH -> ImageCapture.FLASH_MODE_OFF // Torch uses cameraControl
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                    _binding?.btnFlash?.alpha = if (mode != com.safescan.data.FlashMode.OFF) 1.0f else 0.5f
                }
            }
        }

        // Observe liveDetect state to clear corners overlay when disabled
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.liveDetect.collect { enabled ->
                    if (!enabled) {
                        _binding?.overlayView?.updateCorners(null)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.autoCaptureEvent.collect {
                    if (currentViewMode == FragmentViewMode.SCANNER && !viewModel.isEditing.value && !viewModel.isCropping.value) {
                        focusAndTakePhoto(isAutoCapture = true)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(
                    viewModel.isEditing,
                    viewModel.isCropping,
                    viewModel.isSettingsOpen,
                    viewModel.isDocumentOpenedFromLibrary,
                    viewModel.isGridViewVisible,
                    viewModel.usePhoneCamera,
                    viewModel.useNativeScanner
                ) { _ ->
                    // Trigger state update when any of these change
                }.collect {
                    updateCameraState()
                }
            }
        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        // FIX: FINAL LEAK
        try {
            context?.let { ctx ->
                val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(ctx)
                if (cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbindAll()
                } else {
                    cameraProviderFuture.addListener({
                        try {
                            cameraProviderFuture.get().unbindAll()
                        } catch (e: Exception) {
                            Log.e("ScannerFragment", "Failed to unbind camera in onDestroyView listener", e)
                        }
                    }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
                }
            }
        } catch (e: Exception) {
            Log.e("ScannerFragment", "Failed to unbind camera in onDestroyView", e)
        }
        try {
            liveEdgeDetectionEngine.release()
        } catch (e: Exception) {
            Log.e("ScannerFragment", "Failed to release liveEdgeDetectionEngine", e)
        }
        _binding = null
        shutterSound?.release()
        shutterSound = null
    }
}
