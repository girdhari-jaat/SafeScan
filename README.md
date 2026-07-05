<div align="center">
<img width="1200" height="475" alt="SafeScan Banner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# SafeScan

**SafeScan** ایک React + Vite + Capacitor پر مبنی Document Scanner Mobile App ہے۔  
یہ Google AI Studio میں بنائی گئی ہے اور documents کو scan کر کے PDF/JPG میں convert کرتی ہے۔

**Live Preview in AI Studio**: https://ai.studio/apps/dfe92363-fe54-4020-ab75-68a91eec7ed5

## 🚀 Features
- Auto Document Detection اور Crop
- Scan کو PDF اور JPG میں Export کریں
- Gallery سے Image Import
- Fast اور Offline ML Kit Scanning
- Android کے لیے Native Build

## 🛠️ Tech Stack
- **Frontend**: React + Vite + CSS
- **Mobile**: Capacitor 7
- **Scanner**: Google ML Kit Document Scanner
- **AI**: Gemini API

## 📦 Run Locally - Web Version

**Prerequisites:** Node.js

1. Install dependencies:
   `npm install`
2. Set the `GEMINI_API_KEY` in `.env.local` to your Gemini API key
3. Run the app:
   `npm run dev`

## 📱 Build Android APK with Capacitor

1. Web build بنائیں:
   `npm run build`

2. Capacitor Sync کریں:
   `npx cap sync android`

3. Android Studio میں Open کریں:
   `npx cap open android`

4. Android Studio سے `Build > Generate Signed Bundle / APK` کریں

## 🔑 Keywords
safe-scan, safescan, react, vite, capacitor, android-app, 
document-scanner, pdf-scanner, ocr, mlkit, google-ai-studio, cross-platform

## 📄 License
Built with Google AI Studio