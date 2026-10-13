# QR Scanner

Native Android QR and barcode scanner.

## Included
- Live rear-camera scanning with CameraX
- QR and common barcode formats through Google ML Kit
- Camera permission handling with recovery button
- Flashlight toggle
- Scan an image from Gallery
- Copy, open web link, and share result
- Local scan history (up to 50 unique recent results)
- Light premium mobile-first interface
- No backend, login, or browser BarcodeDetector dependency

## Build
Open the repository in Android Studio and let Gradle sync. Then run on an Android device or use Build APK from the Android Studio build menu.

Minimum Android version: Android 6.0 (API 23).


## AdMob verification

The app includes banner, interstitial, rewarded, app-open, and native ad placements. Requests must remain gated by the Google UMP consent result; never bypass consent to force an ad. A successful Android build confirms compilation only, not live ad fill. To investigate missing ads on a device, inspect Logcat for the `QRScannerAds` tag and review UMP status, the full `LoadAdError`, connectivity, ad-unit status, and AdMob serving eligibility. Avoid repeated taps or test behavior that could create invalid ad traffic.

## Privacy and terms

- Privacy policy: [PRIVACY_POLICY.md](PRIVACY_POLICY.md)
- Terms of use: [TERMS_OF_USE.md](TERMS_OF_USE.md)
