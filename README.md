# NFC Door Card

Android 12–17 (API 31–37) NFC diagnostics + conservative HCE prototype.

## v0.1 features

- NFC tag/card inspection using Android Reader Mode.
- Shows UID, technology list, NFC-A ATQA and SAK when available.
- Classifies ISO-DEP, MIFARE Classic and non-ISO-DEP NFC-A tags.
- Minimal `HostApduService` demo AID (`F0010203040506`) to verify HCE registration.
- Root availability check (`su -c id -u`).
- LSPosed device-backend folder reserved separately; it does not mutate NFC system state.

## Why low-level UID emulation is not enabled

Android HCE is intended for ISO-DEP/APDU services and does not provide an application API for choosing a fixed NFC-A UID. MIFARE Classic also uses a protocol/crypto path that ordinary Android HCE does not reproduce. Device-specific NFC controller changes can crash the NFC service or interfere with payment routing, so they should only be considered after collecting target-device diagnostics.

## Build

Open the project in Android Studio with Android 17 / API 37 SDK installed, then run the `app` configuration. The project uses AGP 9.2.0 + JDK 17.

A GitHub Actions workflow is included at `.github/workflows/build-apk.yml`; pushing the project to GitHub will build and upload a debug APK artifact automatically.

CLI, if a Gradle wrapper is added by Android Studio:

```bash
./gradlew assembleDebug
```

## Next diagnostic data to collect on the phone

1. Read the physical card with this app and save UID / Tech / ATQA / SAK.
2. `adb shell dumpsys nfc`
3. List NFC vendor files (names only):
   `adb shell su -c 'find /vendor/etc /odm/etc /product/etc -maxdepth 2 -iname "*nfc*" -o -iname "libnfc*" 2>/dev/null'`
4. Relevant crash/log lines while toggling NFC:
   `adb shell logcat -b all -d | grep -iE "nfc|NfcService|libnfc|sn100|st21|pn5"`

Do not replace vendor NFC files from another ROM/device.
