package com.safescan.di

import android.content.Context
import android.graphics.Bitmap
import com.safescan.scanner.DocumentScannerEngine
import com.safescan.scanner.MLScannerEngine
import com.safescan.domain.model.Point
import com.safescan.scanner.DocumentScanner
import com.safescan.scanner.ml.LocalMLEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScannerModule {
    @Provides
    @Singleton
    fun provideLocalMLEngine(@ApplicationContext context: Context): LocalMLEngine {
        return LocalMLEngine(context)
    }

    @Provides
    @Singleton
    fun provideDocumentScanner(localMLEngine: LocalMLEngine): DocumentScanner {
        return DocumentScanner(localMLEngine)
    }

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
        return DocumentScannerEngine(mlEngine = mlScannerEngine).apply {
            engineType = com.safescan.scanner.ScannerEngineType.OPENCV
        }
    }
}
