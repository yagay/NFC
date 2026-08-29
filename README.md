# NFC Expert Pro (v7)

A specialized NFC simulation and diagnostic tool for Android devices, optimized for OxygenOS 16 / Android 15+.

## Overview

This project leverages the **LibXposed** framework to perform low-level NFC hardware abstraction layer (HAL) hijacking. It is designed to solve the common issue where system-level NFC resets or power management events interfere with UID simulation on modern Android devices.

## Key Features

- **Real-time Module Status**: Visual indicator in the UI showing whether the Xposed module is successfully injected and active.
- **Hardware-Level UID Simulation**: Directly hooks `NativeNfcManager` (NXP/ST/Standard variants) to force target UID, SAK, and ATQA values.
- **State Enforcement**: Intercepts system events (screen state, wallet switches, routing updates) to prevent the system from "backstabbing" and resetting the simulated UID.
- **Comprehensive Multi-Level Diagnostics**:
    - **HIJACK**: Real-time logs from the Xposed hook.
    - **LSPosed**: Framework-level loading and error logs.
    - **KernelSU**: Root execution and system-level event logs.
- **One-Click NFC Toggle**: Automatically restarts the NFC service to apply changes using root privileges.

## Project Structure

- `MainActivity.kt`: Modern Compose-based UI with integrated diagnostic console and card management.
- `XposedEntry.kt`: Core LibXposed implementation using the latest API 102.
- `ConfigProvider.kt`: Secure IPC mechanism for passing simulation parameters from the UI to the NFC process.
- `AppLogger.kt`: Internal diagnostic buffer for tracking App-side events.

## Installation & Setup

1. **Prerequisites**: 
    - A rooted device with **KernelSU** or **Magisk**.
    - **LSPosed (Dexposed/Mod)** installed and working.
2. **Build**: Build the APK and install it on your device.
3. **Activation**:
    - Open LSPosed Manager.
    - Enable the "NFC" module.
    - **Crucial**: Ensure the scope includes "System Framework" and the **NFC Service** (usually `com.android.nfc` or `com.oplus.nfc`).
    - Reboot the device or toggle NFC in the app.
4. **Verification**: Check if the "Module Active" indicator at the top of the App is green.

## Development

- **Language**: 100% Kotlin
- **UI**: Jetpack Compose (Material 3)
- **Xposed API**: LibXposed (Service 102)
- **Minimum SDK**: 31 (Android 12)
- **Compile SDK**: 34

## License

Personal Research Project. Use responsibly for legal NFC diagnostics only.
