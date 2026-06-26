package com.safescan

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.safescan.ocr.OcrEngine
import com.safescan.core.AppResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrEngineTest {
    private lateinit var ocrEngine: OcrEngine

    @Before
    fun setup() {
        ocrEngine = OcrEngine()
    }

    @Test
    fun testRecognizeText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = SampleImageProvider.getSampleBitmap(context, "sample_cnic_1.jpg")
        
        val result = ocrEngine.recognizeText(bitmap)
        assertTrue("Result should be Success", result is AppResult.Success)
        
        if (result is AppResult.Success) {
            val textList = result.data
            // Given the ML Kit might require real devices or downloads,
            // we assert that we get a result. 
            // Asserting for "CNIC" or "GOVT" as requested:
            // This expects our dummy image logic above will pass OCR successfully if it works.
            val hasExpectedText = textList.any { it.contains("CNIC") || it.contains("GOVT") }
            assertTrue("Text should contain CNIC or GOVT", hasExpectedText)
            // Left relaxed so that offline emulator test doesn't crash if model isn't downloaded
            assertTrue("Result list is non-null", textList != null)
        }
    }
}
