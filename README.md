<div align="center">
<img width="1200" height="475" alt="SafeScan Banner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# SafeScan: Professional Document & ID Scanner

**SafeScan** is a high-performance, secure mobile document scanning solution engineered for precision and efficiency. Whether you need to digitize physical documents, capture ID cards, or organize complex multi-page scans, SafeScan provides an intuitive, professional-grade toolkit right in your pocket.

**Live Preview**: [View in AI Studio](https://safescan-361361801174.asia-southeast1.run.app)

## 🌟 Key Features

### 📷 Intelligent Scanning
- **Native ML Kit Integration**: Utilizes Google ML Kit Document Scanner for fast, accurate edge detection and auto-cropping.
- **Multiple Capture Modes**: Specialized workflows for **Documents**, **ID Cards**, and **Grid-based** captures.
- **Smart Flash/Torch**: Intelligent flash management (Off, Auto, Torch) to ensure perfect lighting.
- **Real-time Guidance**: Live viewfinder grid-lines for perfect composition and alignment.

### 🎨 Advanced Editing Studio
- **Precise Crop & Rotate**: Manual or auto-detect edge adjustment, with 90-degree rotation controls.
- **Professional Filters**: Optimize scans with filters including **Original**, **Grayscale**, **Magic Color**, **B&W**, and **Photo** mode.
- **Manual Tuning**: Fine-tune **Brightness**, **Contrast**, and **Sharpness** to enhance document readability.

### ⚙️ Powerful Workflow
- **OCR (Text Recognition)**: Extract text directly from scanned documents.
- **Flexible Export**: Generate PDFs or JPG images with configurable page settings.
- **Batch Processing**: Handle multiple pages seamlessly (up to 50 pages for document mode).
- **High-Performance Architecture**: Zero-copy pixel pipeline utilizing background workers and IndexedDB for lightning-fast responsiveness.

## 🛠️ Tech Stack

- **Frontend/Mobile**: React, Vite, Capacitor 7
- **Native Core**: Kotlin, Jetpack Compose
- **AI/ML**: Google ML Kit (Document Scanner, Text Recognition)
- **Image Processing**: OpenCV, Custom Web Worker pipeline

## 🚀 Installation & Running

**Prerequisites**: Node.js

1. **Install dependencies**: `npm install`
2. **Configuration**: Set the `GEMINI_API_KEY` in `.env.local`.
3. **Run Web**: `npm run dev`

## 📱 Building Android

### Capacitor (Web-to-App)
1. `npm run build`
2. `npx cap sync android`
3. `npx cap open android`

### React Capacitor Compose
Use the dedicated CI/CD workflow defined in `.github/workflow/react_capistor.yml`.

### Native Kotlin/Compose
Use the dedicated CI/CD workflow defined in `.github/workflow/android.yml`.

## 📄 License
Built with Google AI Studio.
