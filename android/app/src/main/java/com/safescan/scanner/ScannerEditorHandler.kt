package com.safescan.scanner

import android.graphics.Bitmap
import com.safescan.data.EditorState
import com.safescan.domain.ImageProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ScannerEditorHandler {

    fun applyAutoEnhance(
        editingBitmapOriginal: StateFlow<Bitmap?>,
        editingBitmapPreview: MutableStateFlow<Bitmap?>,
        editorState: MutableStateFlow<EditorState>,
        recognizedText: MutableStateFlow<String?>,
        scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            editingBitmapOriginal.value?.let { bmp ->
                val enhanced = ImageProcessor.autoEnhance(bmp)
                editingBitmapPreview.value = enhanced
                editorState.value = EditorState()
                recognizedText.value = null
            }
        }
    }
}
