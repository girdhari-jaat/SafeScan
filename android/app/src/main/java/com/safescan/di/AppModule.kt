package com.safescan.di

import android.content.Context
import com.safescan.domain.PdfExporter
import com.safescan.scanner.EdgeDetectionEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // IMPROVEMENT: Provided EdgeDetectionEngine as a Singleton via Hilt
    @Provides
    @Singleton
    fun provideEdgeDetectionEngine(): EdgeDetectionEngine {
        return EdgeDetectionEngine()
    }

    // IMPROVEMENT: Provided PdfExporter as a Singleton via Hilt
    @Provides
    @Singleton
    fun providePdfExporter(@ApplicationContext context: Context): PdfExporter {
        return PdfExporter(context)
    }
}
