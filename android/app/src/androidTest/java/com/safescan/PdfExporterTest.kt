package com.safescan

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.safescan.domain.PdfExporter
import com.safescan.core.AppResult
import com.safescan.data.ScannerMode
import com.safescan.data.Slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfExporterTest {
    private lateinit var pdfExporter: PdfExporter

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        pdfExporter = PdfExporter(context)
    }

    @Test
    fun testExportToPdf() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap1 = SampleImageProvider.getSampleBitmap(context, "sample_cnic_1.jpg")
        val bitmap2 = SampleImageProvider.getSampleBitmap(context, "sample_cnic_2.jpg")
        val bitmap3 = SampleImageProvider.getSampleBitmap(context, "sample_a4.jpg")
        
        val slots = listOf(
            Slot("1", "1", bitmap1),
            Slot("2", "2", bitmap2),
            Slot("3", "3", bitmap3)
        )
        
        val result = try {
            val fileResult = pdfExporter.exportCardsToPdf(slots, "test_export.pdf", ScannerMode.GRID)
            if (fileResult.isSuccess) {
                AppResult.Success(fileResult.getOrThrow())
            } else {
                val exception = fileResult.exceptionOrNull() ?: Exception("Unknown error")
                AppResult.Error(exception.message ?: "Failed", exception)
            }
        } catch (e: Exception) {
            AppResult.Error(e.message ?: "Failed", e)
        }
        
        assertTrue("Result should be Success", result is AppResult.Success)
        if (result is AppResult.Success) {
            val file = result.data
            assertTrue("PDF file should exist", file.exists())
            assertTrue("PDF file length should be > 0", file.length() > 0)
        }
    }
}
