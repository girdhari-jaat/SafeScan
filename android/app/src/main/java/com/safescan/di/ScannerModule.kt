package com.safescan.di

import android.graphics.Bitmap
import com.safescan.scanner.DocumentScannerEngine
import com.safescan.scanner.MLScannerEngine
import com.safescan.android.scanner.Point
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScannerModule {

    @Provides
    @Singleton
    fun provideMLScannerEngine(): MLScannerEngine {
        return object : MLScannerEngine {
            override suspend fun detectCorners(bitmap: Bitmap): List<Point>? {
                // Implementation left empty for now as requested
                return null
            }
        }
    }

    @Provides
    @Singleton
    fun provideDocumentScannerEngine(mlScannerEngine: MLScannerEngine): DocumentScannerEngine {
        return com.safescan.ocr.MLScannerEngine(mlEngine = mlScannerEngine).apply {
            engineType = com.safescan.scanner.ScannerEngineType.MLKIT
        }
    }
}
