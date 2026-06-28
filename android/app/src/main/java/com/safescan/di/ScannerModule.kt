package com.safescan.di

import com.safescan.ocr.MLScannerEngine
import com.safescan.scanner.DocumentScannerEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ScannerModule {

    @Binds
    @Singleton
    abstract fun bindDocumentScannerEngine(
        mlScannerEngine: MLScannerEngine
    ): DocumentScannerEngine
}
