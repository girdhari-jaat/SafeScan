package com.safescan.ui

import android.annotation.SuppressLint
import android.content.Intent
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
    private val liveEdgeDetectionEngine = com.safescan.scanner.LiveEdgeDetectionEngine()

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
        } else {
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
                            viewModel.onCapture(bitmap)
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

    private val systemDocumentScannerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val uris = ArrayList<android.net.Uri>()
            
            // Check clipData first (multi-select)
            val clipData = data?.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i).uri?.let { uris.add(it) }
                }
            } else {
                // Check direct data
                data?.data?.let { uris.add(it) }
                // Also check parcelable array list of extra "android.provider.extra.SCAN_RESULT"
                try {
                    val list = data?.getParcelableArrayListExtra<android.net.Uri>("android.provider.extra.SCAN_RESULT")
                    if (list != null) {
                        for (uri in list) {
                            if (!uris.contains(uri)) {
                                uris.add(uri)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ScannerFragment", "Error reading extra SCAN_RESULT", e)
                }
            }

            if (uris.isNotEmpty()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    for (uri in uris) {
                        try {
                            val inputStream = requireContext().contentResolver.openInputStream(uri)
                            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                            inputStream?.close()
                            if (bitmap != null) {
                                viewModel.onCapture(bitmap, true)
                            }
                        } catch (e: Exception) {
                            Log.e("ScannerFragment", "Error reading scanned image Uri: $uri", e)
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
            } else {
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
            } else {
                Toast.makeText(context, "No QR data found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openDocumentScanner(maxPages: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val intent = Intent("android.provider.action.SCAN_DOCUMENT")
                intent.putExtra("android.provider.extra.MAX_DOCUMENTS", maxPages)
                systemDocumentScannerLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e("ScannerFragment", "Failed to start system document scanner", e)
                Toast.makeText(context, "System document scanner not available.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Requires Android 13+", Toast.LENGTH_SHORT).show()
        }
    }

    fun openOcrScanner() {
        val intent = Intent("com.google.android.gms.actions.OCR_CAPTURE")
        try {
            ocrScannerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Install Google Lens", Toast.LENGTH_SHORT).show()
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
        } else {
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
                } else {
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
            binding.previewView.visibility = View.VISIBLE
            binding.btnCapture.visibility = View.GONE
            binding.btnFlash.visibility = View.GONE
            binding.btnSwitchEngine.visibility = View.GONE
            
            // Start live CameraX preview
            startCamera()

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
                        } else {
                            if (viewModel.isDocumentOpenedFromLibrary) {
                                Box(
                                    modifier = Modifier.fillMaxSize()
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
    }

    private fun checkPermissionAndStartScanner() {
        val permissionsToRequest = mutableListOf(android.Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
            permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
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
            val next = if (current == ScannerEngineType.MLKIT) {
                ScannerEngineType.LOCAL_ML
            } else {
                ScannerEngineType.MLKIT
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
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                } else {
                    FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
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
        points: List<com.safescan.android.scanner.Point>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        rotationDegrees: Int
    ): List<android.graphics.PointF> {
        val binding = _binding ?: return emptyList()
        val viewWidth = binding.previewView.width.toFloat()
        val viewHeight = binding.previewView.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) return emptyList()

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

            // 3. Map normalized rotated coordinates to the PreviewView screen coordinates
            android.graphics.PointF(rotatedX * viewWidth, rotatedY * viewHeight)
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
                
                val preview = previewBuilder.build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(captureMode)
                    .setFlashMode(if (viewModel.flashOn.value) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                    .build()

                // Initialize ImageAnalysis for live edge detection overlay
                val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val isLiveDetectOn = viewModel.liveDetect.value
                    val isBatterySaverOn = viewModel.batterySaver.value
                    if (isLiveDetectOn && !isBatterySaverOn) {
                        try {
                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                            val width = imageProxy.width
                            val height = imageProxy.height
                            
                            liveEdgeDetectionEngine.process(imageProxy) { corners ->
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
            startCamera()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        val currentContext = context ?: return false
        return ContextCompat.checkSelfPermission(
            currentContext, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun takePhoto() {
        if (viewModel.useNativeScanner.value || viewModel.usePhoneCamera.value) {
            openDocumentScanner(20)
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
        viewModel.toggleFlash(!viewModel.flashOn.value)
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
                viewModel.flashOn.collect { enabled ->
                    flashEnabled = enabled
                    try {
                        cameraControl?.enableTorch(enabled)
                    } catch (e: Exception) {
                        Log.e("ScannerFragment", "Failed to update torch", e)
                    }
                    imageCapture?.flashMode = if (enabled) {
                        ImageCapture.FLASH_MODE_ON
                    } else {
                        ImageCapture.FLASH_MODE_OFF
                    }
                    _binding?.btnFlash?.alpha = if (enabled) 1.0f else 0.5f
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

        // Observe isEditing state to handle returning to Library when editor closes for a library-opened document
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isEditing.collect { isEditing ->
                    if (!isEditing && viewModel.isDocumentOpenedFromLibrary) {
                        viewModel.isDocumentOpenedFromLibrary = false
                        updateViewMode(FragmentViewMode.LIBRARY)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        imageCapture = null
        cameraControl = null
        cameraInfo = null
        cameraExecutor.shutdown()
    }
}
