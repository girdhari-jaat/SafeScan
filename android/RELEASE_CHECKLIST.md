# Release Checklist

1. Generate Release Keystore:
```bash
keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias key0
```
When prompted, use `password` as the password (as specified in `build.gradle.kts` temporarily, or update `build.gradle.kts` with your real production password).

2. Place the `release-key.jks` in the `android/app/` directory.

3. Generate the Release Bundle (AAB):
```bash
./gradlew :app:bundleRelease
```

4. The generated AAB will be located at:
`android/app/build/outputs/bundle/release/app-release.aab`

5. Upload `app-release.aab` to Google Play Console.

## Verification
- App is 100% Offline
- No Internet permission requested
- No Firebase
- No Crashlytics
- Proguard enabled and obfuscating appropriately
- ML Kit native libraries bundled successfully
