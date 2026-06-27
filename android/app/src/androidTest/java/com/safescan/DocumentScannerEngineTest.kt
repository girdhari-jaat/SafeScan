package com.safescan

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.safescan.scanner.DocumentScannerEngine
import com.safescan.core.AppResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentScannerEngineTest {
    private lateinit var engine: DocumentScannerEngine

    @Before
    fun setup() {
        engine = DocumentScannerEngine()
    }

    @Test
    fun testScanDocument() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = SampleImageProvider.getSampleBitmap(context, "sample_a4.jpg")
        
        val result = engine.scanDocument(bitmap)
        assertTrue(result is AppResult.Success)
        
        if (result is AppResult.Success) {
            assertNotNull("Processed bitmap should not be null", result.data)
            assertTrue("Processed bitmap width > 0", result.data.width > 0)
        }
    }
}
