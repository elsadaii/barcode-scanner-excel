# VELTRIX Android

Android wrapper for the hosted VELTRIX scanner, with a native CameraX + ML Kit barcode scanning screen.

## Build
Run the GitHub Actions workflow **Build VELTRIX Android APK**. Download the `VELTRIX-Android-APK` artifact from the successful run.

The app loads the live scanner at https://elsadaii.github.io/barcode-scanner-excel/. The native scanner uses Android's default back camera selector (CameraX) and returns the decoded barcode to the web app.
