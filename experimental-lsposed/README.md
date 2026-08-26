# Experimental LSPosed backend (not built by default)

This directory is intentionally **not** part of the Gradle build in v0.1.0.

Purpose:
- detect `com.android.nfc` implementation details on a specific device;
- log class/method availability needed for compatibility work;
- keep any device-specific NFC integration isolated from the ordinary HCE app.

Safety/reliability rule for v0.1:
- no UID/NFCID spoofing;
- no vendor configuration file replacement;
- no NFC routing mutations;
- no hook that changes Google Wallet / bank HCE behavior.

Modern LSPosed metadata is pre-created under `src/main/resources/META-INF/xposed/`.
