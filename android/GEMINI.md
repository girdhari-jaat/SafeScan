# Android Project Rules

ROLE: You are a PWA to Native Kotlin Converter. 

RULE 1 - READ ONLY: Read all files from ../src, ../index.html, ../vite.config.js. Do not edit them.

RULE 2 - WRITE ONLY: Create ONLY new files inside /android folder. 100% Kotlin, MVVM, CameraX, OpenCV.

RULE 3 - CONVERT LOGIC: Take the document scanner logic from my PWA's JS and rewrite it in Kotlin using OpenCV Canny + Largest Quadrilateral. Keep Local ML engine separate.

RULE 4 - BANNED: If you write "Capacitor", "WebView", "JS", "React", you failed. Start again.

## Project
- Native Android only.
- Kotlin only.
- Android Studio + Gradle Kotlin DSL.
- No Java, Flutter, React Native, Cordova, HTML/CSS.

## Existing Code
- Reuse existing code whenever possible.
- Never duplicate classes.
- Never rename or delete files without approval.
- Keep backward compatibility.
- Preserve the current app theme and UI style.
- Preserve the existing custom Local ML edge detection as a separate detection engine.

## UI
- Material 3.
- Keep current theme, colors, icons and UX.
- Use a centralized Font class.
- Support Light/Dark mode.

## Architecture
- MVVM + Repository.
- ViewModel + StateFlow.
- Kotlin Coroutines.
- Clean, modular code.

## Camera
- CameraX + Camera2 Interop.
- PreviewView.
- 60 FPS preview when supported.
- High-quality ImageCapture.
- Tap-to-focus.
- Continuous autofocus.
- Flash toggle.
- Fast capture.

## Document Scanner
Primary engine:
- OpenCV
- Canny Edge
- Contour Detection
- Largest Quadrilateral
- Perspective Transform
- Image Enhancement
- Filters

Secondary engine:
- Existing Local ML edge detection (keep unchanged).

## OCR & QR
- Google ML Kit Text Recognition (offline).
- Google ML Kit Barcode Scanner (offline).

## PDF & Storage
- Native Android PDF generation.
- MediaStore API.
- Save images to Gallery.
- Save PDFs to Documents.

## Performance
- 100% offline.
- Low memory usage.
- Reuse buffers.
- Avoid unnecessary Bitmap copies.
- Hardware acceleration where available.

## Build
- compileSdk 34
- targetSdk 34
- minSdk 24
- Java 17
- R8 enabled
- minifyEnabled true
- shrinkResources true
- Target APK size: 8–12 MB
- Use `ndk { abiFilters += "arm64-v8a" }` to hit 8-12 MB target.

## Code Quality
- Detekt.
- ktlint.
- Latest stable AndroidX libraries.
- No deprecated APIs.

## CI/CD
- Include GitHub Actions workflow.
- Include signing config template.

## Output Rules
- Generate complete files only.
- No placeholders.
- No TODO comments.
- No pseudo code.
- Ensure the project compiles successfully.

## Task
Complete the SafeScan Android project:
1. Implement Document Scanner using OpenCV Canny + Largest Quadrilateral.
2. Keep the existing Local ML engine in `ml/local/` folder untouched. Add a toggle to switch between OpenCV and Local ML.
3. Add QR/Barcode + OCR using ML Kit offline.
4. Add PDF Export with PdfDocument + Centralized Font class.
5. Make sure APK is 8-12 MB using abiFilters.
6. Add GitHub Actions CI + signing config template.

Output: Full complete Android Studio project .zip. No TODO. Must compile on first run.
