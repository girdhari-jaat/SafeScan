sed -i '/fun detectEdges(bitmap: Bitmap, onResult: (List<Point>?) -> Unit) {/,/fun switchMode(mode: ScannerMode)/c\
    fun detectEdges(bitmap: Bitmap, onResult: (List<Point>?) -> Unit) {\
        if (bitmap.isRecycled) {\
            Log.e("ScannerViewModel", "detectEdges: Provided bitmap is recycled!")\
            onResult(null)\
            return\
        }\
\
        _uiState.update { it.copy(isAutoRunning = true) }\
        viewModelScope.launch(Dispatchers.IO) {\
            var points: List<Point>? = null\
            try {\
                if (!bitmap.isRecycled) {\
                    points = edgeDetectionEngine.detectEdges(bitmap)\
                    Log.d("ScannerViewModel", "detectEdges: Successfully detected corners using OpenCV")\
                }\
            } catch (e: Throwable) {\
                Log.e("ScannerViewModel", "detectEdges: OpenCV detection failed", e)\
            }\
\
            _uiState.update { it.copy(isAutoRunning = false) }\
\
            withContext(Dispatchers.Main) {\
                try {\
                    onResult(points)\
                } catch (e: Throwable) {\
                    Log.e("ScannerViewModel", "detectEdges: Callback onResult failed", e)\
                }\
            }\
        }\
    }\
\
    fun switchMode(mode: ScannerMode)' android/app/src/main/java/com/safescan/scanner/ScannerViewModel.kt
