# SafeScan Testing Guide

## Prerequisites
- Android Studio or Gradle CLI
- Emulator or Physical Device connected

## Running Tests
To run the automated tests locally:

```bash
./gradlew connectedAndroidTest
```

## Test Structure
- **DocumentScannerEngineTest.kt**: Validates high-performance native cropping, perspective transform, and image processing.
- **OcrEngineTest.kt**: Validates ML Kit Text Recognition with test dummy images.
- **PdfExporterTest.kt**: Tests multi-page A4 PDF generation locally.

All tests run completely offline. No external test services are required.
