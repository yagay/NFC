# NFC Door Card

Android 12–17 (API 31–37) NFC diagnostics + conservative HCE prototype.

## Current features

- NFC tag/card inspection using Android Reader Mode.
- Shows UID, technology list, NFC-A ATQA and SAK when available.
- Distinguishes MIFARE Classic, ISO-DEP, NFC-A, NFC-B, NFC-F/FeliCa and NFC-V/ISO 15693.
- Minimal `HostApduService` demo AID (`F0010203040506`) with logcat diagnostics.
- Root availability check through a single hardened `RootShell` implementation.
- Modern libxposed API/service 102 integration.
- Static LSPosed scope for `com.android.nfc`.
- `NativeNfcManager.doInitialize()` diagnostic hook only; no controller configuration writes are performed.
- Device-protected UID test configuration storage exposed through a read-only provider for future authorized diagnostics integration.

## UID test request semantics

The app can save a target UID as a **test request** and restart the NFC service to exercise the diagnostic lifecycle. This does **not** mean the NFC controller UID has been changed. The current build intentionally does not invoke vendor-specific controller configuration methods.

## Why low-level UID emulation is not enabled

Android HCE is intended for ISO-DEP/APDU services and does not provide an application API for choosing a fixed NFC-A UID. MIFARE Classic also uses a protocol/crypto path that ordinary Android HCE does not reproduce. Device-specific NFC controller changes can crash the NFC service or interfere with payment routing, so they should only be considered after collecting target-device diagnostics and confirming the exact vendor API semantics.

## Build

Use Android 17 / API 37 SDK and JDK 17. The project currently uses AGP 9.2.1 and Gradle 9.4.1.

GitHub Actions workflow: `.github/workflows/build-apk.yml`

```bash
gradle --no-daemon :app:assembleDebug
```

## Useful device diagnostics

1. Read the physical card with this app and record UID / Tech / ATQA / SAK.
2. `adb shell dumpsys nfc`
3. List NFC vendor files (names only):
   `adb shell su -c 'find /vendor/etc /odm/etc /product/etc -maxdepth 2 -iname "*nfc*" -o -iname "libnfc*" 2>/dev/null'`
4. Relevant NFC logs:
   `adb shell logcat -b all -d | grep -iE "nfc|NfcService|NfcDoorHCE|NfcUIDSim|libnfc|sn100|st21|pn5"`

Do not replace vendor NFC files from another ROM/device.
