<div align="center">
<img width="1200" height="475" alt="SafeScan Banner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# SafeScan

**SafeScan** is a Document Scanner Mobile App built with React + Vite + Capacitor.  
It was created in Google AI Studio and converts documents into PDF/JPG.

**Live Preview in AI Studio**: https://safescan-361361801174.asia-southeast1.run.app

## 🚀 Features
- Auto Document Detection and Crop
- Export Scans to PDF and JPG
- Import Images from Gallery
- Fast and Offline ML Kit Scanning

## 🛠️ Tech Stack
- **Frontend**: React + Vite + CSS
- **Mobile**: Capacitor 7
- **Scanner**: Google ML Kit Document Scanner
- **AI**: Gemini API

## 📦 Run Locally - Web Version

**Prerequisites:** Node.js

1. Install dependencies:
   npm install
2. Set the `GEMINI_API_KEY` in `.env.local` to your Gemini API key
3. Run the app:
   npm run dev

## 📱 Build Android APK with Capacitor

1. Create Web build:
   npm run build
2. Sync for Capacitor:
   npx cap sync android
3. Open in Android Studio:
   npx cap open android
4. In Android Studio go to `Build > Generate Signed Bundle / APK`
   
On Github run workflow
.github/workflow/react_capistor.yml

## 📱 Build Android Kotlin APK

On Github run workflow
.github/workflow/android.yml



## 🔑 Keywords
safe-scan, safescan, react, vite, capacitor, android-app, 
document-scanner, pdf-scanner, ocr, mlkit, google-ai-studio, cross-platform

## 📄 License
Built with Google AI Studio