package com.safescan.domain.usecase

import android.content.Context
import com.safescan.data.DocumentPage
import com.safescan.data.ScannerMode
import com.safescan.data.Slot
import com.safescan.domain.PdfExporter
import com.safescan.utils.PageConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportPdfUseCase @Inject constructor(
    private val pdfExporter: PdfExporter
) {
    suspend fun execute(
        context: Context,
        pages: List<DocumentPage>,
        pageConfig: PageConfig,
        outputFile: File
    ) {
        pdfExporter.exportToPdf(context, pages, pageConfig, outputFile)
    }

    suspend fun exportCardsToPdf(
        slots: List<Slot>,
        filename: String,
        mode: ScannerMode,
        pageSizeStr: String = "A4",
        pdfOrientation: String = "Auto",
        dpi: Float = 300f,
        jpegQuality: Float = 90f
    ): Result<File> {
        return pdfExporter.exportCardsToPdf(
            slots = slots,
            filename = filename,
            mode = mode,
            pageSizeStr = pageSizeStr,
            pdfOrientation = pdfOrientation,
            dpi = dpi,
            jpegQuality = jpegQuality
        )
    }
}
