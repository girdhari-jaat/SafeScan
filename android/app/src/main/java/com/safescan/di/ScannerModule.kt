package com.safescan.di

import android.content.Context
import android.graphics.Bitmap
import com.safescan.scanner.DocumentScannerEngine
import com.safescan.scanner.MLScannerEngine
import com.safescan.domain.model.Point
import com.safescan.scanner.DocumentScanner
import com.safescan.scanner.TFLiteEngine
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
    fun provideTFLiteEngine(@ApplicationContext context: Context): TFLiteEngine {
        return TFLiteEngine(context)
    }

    @Provides
    @Singleton
    fun provideDocumentScanner(tfLiteEngine: TFLiteEngine, @ApplicationContext context: Context): DocumentScanner {
        return DocumentScanner(tfLiteEngine, context)
    }

    @Provides
    @Singleton
    fun provideMLScannerEngine(tfLiteEngine: TFLiteEngine): MLScannerEngine {
        return object : MLScannerEngine {
            override suspend fun detectCorners(bitmap: Bitmap): List<Point>? {
                val quad = tfLiteEngine.detectCorners(bitmap)
                return quad?.let { listOf(it.topLeft, it.topRight, it.bottomRight, it.bottomLeft) }
            }
        }
    }

    @Provides
    @Singleton
    fun provideDocumentScannerEngine(mlScannerEngine: MLScannerEngine): DocumentScannerEngine {
        return DocumentScannerEngine(mlEngine = mlScannerEngine).apply {
            engineType = com.safescan.scanner.ScannerEngineType.LOCAL_ML
        }
    }
}
