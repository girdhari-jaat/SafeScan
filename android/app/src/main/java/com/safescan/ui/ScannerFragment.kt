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

    private var flashEnabled = false

    private enum class FragmentViewMode {
        LIBRARY,
        SCANNER
    }
    private var currentViewMode = FragmentViewMode.LIBRARY

    // On-demand permission launcher
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
                        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                        if (bitmap != null) {
                            viewModel.onCapture(bitmap, forceSkipEditor = list.size > 1)
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
                    val softwareBitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
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
                            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                            inputStream?.close()
                            if (bitmap != null) {
                                viewModel.onCapture(bitmap, isNativeScanned = true, forceSkipEditor = true)
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScannerBinding.inflate(inflater, container, false)
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
                } else if (currentViewMode == FragmentViewMode.SCANNER) {
                    updateViewMode(FragmentViewMode.LIBRARY)
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        // Default app to Library on startup
        updateViewMode(FragmentViewMode.LIBRARY)
    }

    private fun updateViewMode(mode: FragmentViewMode) {
        currentViewMode = mode
        if (mode == FragmentViewMode.LIBRARY) {
            // Hide camera-related XML views entirely
            binding.previewView.visibility = View.GONE
            binding.btnCapture.visibility = View.GONE
            binding.btnFlash.visibility = View.GONE
            binding.btnSwitchEngine.visibility = View.GONE
            binding.resultImageView.visibility = View.GONE

            // Release Camera Resources immediately
            val currentContext = context
            if (currentContext != null) {
                try {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(currentContext)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            cameraProvider.unbindAll()
                        } catch (e: Exception) {
                            Log.e("ScannerFragment", "Failed to release camera", e)
                        }
                    }, ContextCompat.getMainExecutor(currentContext))
                } catch (e: Exception) {
                    Log.e("ScannerFragment", "Error requesting camera provider", e)
                }
            }

            // Bind Compose View to LibraryScreen
            binding.composeView.apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    SafeScanTheme {
                        LibraryScreen(
                            viewModel = viewModel,
                            onStartScan = {
                                viewModel.isDocumentOpenedFromLibrary = false
                                viewModel.openedDocumentId = null
                                checkPermissionAndStartScanner()
                            },
                            onOpenDocument = { doc ->
                                viewModel.loadDocumentIntoSlots(doc)
                                checkPermissionAndStartScanner()
                            }
                        )
                    }
                }
            }
        } else {
            // Show camera-related XML views (only previewView, hide old buttons)
            if (viewModel.isDocumentOpenedFromLibrary) {
                binding.previewView.visibility = View.GONE
            } else {
                binding.previewView.visibility = View.VISIBLE
                // Start live CameraX preview
                startCamera()
            }
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
                        if (isSettingsOpen) {
                            com.safescan.ui.SettingsScreen(
                                viewModel = viewModel,
                                onBack = { viewModel.isSettingsOpen.value = false }
                            )
                        } else if (isCropping) {
                            com.safescan.ui.CropScreen(viewModel = viewModel)
                        } else if (isEditing) {
                            com.safescan.ui.EditorScreen(viewModel = viewModel)
                        } else if (viewModel.isDocumentOpenedFromLibrary) {
                            com.safescan.ui.DocumentGridView(
                                viewModel = viewModel,
                                onDismiss = {
                                    viewModel.isDocumentOpenedFromLibrary = false
                                    updateViewMode(FragmentViewMode.LIBRARY)
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
                                }
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

        binding.previewView.setOnTouchListener { _, event ->
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
            false
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
                    binding.previewView.visibility = View.INVISIBLE
                    return@addListener
                } else {
                    binding.previewView.visibility = View.VISIBLE
                }

                val mode = viewModel.currentMode.value

                // 1. Dynamic Hardware Negotiation & Mood Alignment (from gemini.md rules)
                val captureMode = when (mode) {
                    com.safescan.data.ScannerMode.CARD -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY // Fast capture, lower latency
                    com.safescan.data.ScannerMode.GRID -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    com.safescan.data.ScannerMode.DOCUMENT -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY // Standard balanced
                }

                val targetFrameRateRange = when (mode) {
                    com.safescan.data.ScannerMode.CARD -> Range(60, 60) // High frame rate for fast card detection
                    else -> Range(30, 30) // Standard frame rate for high detail documents
                }

                val previewBuilder = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                
                val preview = previewBuilder.build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(captureMode)
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()

                // Initialize ImageAnalysis for live edge detection overlay
                val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val isLiveDetectOn = viewModel.liveDetect.value
                    val isBatterySaverOn = viewModel.batterySaver.value
                    if (isLiveDetectOn && !isBatterySaverOn) {
                        try {
                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                            val width = imageProxy.width
                            val height = imageProxy.height
                            
                            liveEdgeDetectionEngine.process(imageProxy, viewModel.documentScanner) { corners ->
                                val mappedPoints = mapPointsToPreviewView(corners, width, height, rotationDegrees)
                                activity?.runOnUiThread {
                                    _binding?.overlayView?.updateCorners(mappedPoints)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ScannerFragment", "Live detection error", e)
                            imageProxy.close()
                        }
                    } else {
                        imageProxy.close()
                        activity?.runOnUiThread {
                            _binding?.overlayView?.updateCorners(null)
                        }
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()

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
        if (currentViewMode == FragmentViewMode.SCANNER && allPermissionsGranted()) {
            if (!viewModel.isDocumentOpenedFromLibrary) {
                startCamera()
            }
        }
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

    private fun takePhoto() {
        if (viewModel.usePhoneCamera.value) {
            openPhoneCamera()
            return
        }
        if (viewModel.useNativeScanner.value) {
            val maxPages = when (viewModel.currentMode.value) {
                com.safescan.data.ScannerMode.CARD -> 2
                com.safescan.data.ScannerMode.GRID -> 8
                else -> 150
            }
            openDocumentScanner(maxPages)
            return
        }

        val imageCapture = imageCapture ?: return
        val currentContext = context ?: return
        val binding = _binding ?: return

        binding.progressBar.visibility = View.VISIBLE

        // Play shutter sound if enabled
        if (viewModel.clickSound.value) {
            try {
                val sound = android.media.MediaActionSound()
                sound.play(android.media.MediaActionSound.SHUTTER_CLICK)
            } catch (e: Exception) {
                Log.e("ScannerFragment", "Failed to play shutter sound", e)
            }
        }

        // Trigger vibration/haptic feedback if enabled
        if (viewModel.vibrateOnCapture.value) {
            try {
                val vibrator = currentContext.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                if (vibrator != null && vibrator.hasVibrator()) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(100)
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
                    val rawBitmap = imageProxy.toBitmap()
                    val rotationDegrees = if (viewModel.autoRotation.value) {
                        imageProxy.imageInfo.rotationDegrees
                    } else {
                        0
                    }
                    val bitmap = if (rotationDegrees != 0) {
                        val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                        val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                        rawBitmap.recycle()
                        rotated
                    } else {
                        rawBitmap
                    }
                    viewModel.onCapture(bitmap)
                    imageProxy.close()
                }

                override fun onError(exception: ImageCaptureException) {
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
                        com.safescan.data.FlashMode.TORCH -> ImageCapture.FLASH_MODE_ON
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
                kotlinx.coroutines.flow.combine(viewModel.usePhoneCamera, viewModel.useNativeScanner) { a, b ->
                    Pair(a, b)
                }.collect {
                    if (currentViewMode == FragmentViewMode.SCANNER && !viewModel.isDocumentOpenedFromLibrary) {
                        startCamera()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            liveEdgeDetectionEngine.release()
        } catch (e: Exception) {
            Log.e("ScannerFragment", "Failed to release liveEdgeDetectionEngine", e)
        }
        _binding = null
        imageCapture = null
        cameraControl = null
        cameraInfo = null
        cameraExecutor.shutdown()
    }
}
