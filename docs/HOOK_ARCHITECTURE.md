# NFC Hook Architecture

## Goal

Keep the app maintainable across OxygenOS/NFC stack upgrades without tying production behavior to one permanent vendor class or method name.

The Hook runtime is split into four independent concerns:

1. **Command protocol** — app publishes desired simulation state and a generation number.
2. **Hook discovery** — finds methods capable of `RF_CONFIG_WRITE` by structural scoring.
3. **Payload codecs** — classify and safely rewrite the actual `byte[]` seen at runtime.
4. **Runtime verification/profile** — native result `0` verifies a target and caches it for the exact system/NFC build.

The app process keeps its responsibilities separate as well:

- `NfcAppScreen` renders Compose state and forwards user intent.
- `SimulationCoordinator` owns APPLY/STOP orchestration and timeouts.
- `DiagnosticExporter` builds support bundles.
- `HookConfigStore` and `HookStateWriter` isolate Provider reads and writes.
- `SimulationResultPolicy` decides whether an RF result belongs to the active request.

## Production startup

`NfcInjectionModule` runs only in `com.android.nfc`.

### Fast path

A cached hook profile is reused only when all of these are true:

- `profile_status` is verified;
- `Build.FINGERPRINT` matches;
- `com.android.nfc` versionCode matches;
- class/method/parameter/return signature still resolves.

Only that one method is hooked.

### OTA / invalid-profile path

When the profile is missing or invalid:

1. `HookDiscoveryEngine` checks known NFC families as fast hints.
2. If needed it enumerates NFC-related Dex classes.
3. Methods are scored by stable structural traits: one `byte[]` argument, non-void return, NFC/NXP/RF/config naming signals, numeric native-style result, etc.
4. A known high-confidence family installs one hook. Unknown OTA targets install at most four temporary learning hooks.
5. Learning hooks do **nothing** to unrelated `byte[]` calls. `RfPayloadEngine.inspectScore()` must first recognize an RF payload.
6. The candidate whose rewritten payload returns native result `0` becomes the new `VERIFIED` profile.

This keeps normal operation cheap while making OTA recovery automatic and bounded.

## Stable capability names

Do not build new features around vendor class names. Use capability names:

- `RF_CONFIG_WRITE` — method that accepts the RF configuration payload and returns native success/failure.
- `RF_REFRESH_TRIGGER` — action that causes the OEM stack to refresh RF configuration.

Vendor class names are discovery hints/profile data, not architecture.

## Payload handling

Hook discovery and payload parsing must stay separate.

`RfPayloadEngine` chooses a codec for every invocation:

- `OplusTextConfigCodec` — textual `OPLUS_CONF_EXTN` wrapper.
- `RawNciCodec` — raw NCI `CORE_SET_CONFIG (20 02 ...)`.

Rules:

1. Prefer replacing an existing `LA_NFCID1 (33 04)` value in place.
2. Append a new `33 04 <UID>` only when structural boundaries are safe.
3. Oplus text config may use the historically proven boundary-only append fallback, but only inside an explicit `OPLUS_CONF_EXTN` block with a valid bounded `20 02` frame.
4. A Java-side rewrite is never final proof. Native result `0` is authoritative.

## Command generations

Every APPLY/STOP request has a monotonically changing generation.

A terminal RF result belongs to one generation, NFC PID, and controller epoch. Old asynchronous writes or results from a replaced controller must never be interpreted as the result of a newer request.

Important states:

- `command_generation`
- `command_handled_generation`
- `rf_generation`
- `command_pid`
- `rf_pid`
- `controller_epoch`
- `rf_controller_epoch`

Lifecycle recovery can complete just before the exact RF callback is observed. In that narrow case the coordinator enters `WAIT_FOR_REPLAY` for at most 1.2 seconds. Success still requires the matching generation, PID, epoch, recognized RF payload, and native result `0`; the grace period only accommodates callback ordering.

## Hook profile fields

The provider records enough information to diagnose an OTA without decompiling the NFC APK first:

- `profile_status`
- `profile_system_fingerprint`
- `profile_nfc_version`
- `rf_hook_class`
- `rf_hook_method`
- `rf_hook_param_signature`
- `rf_hook_return_type`
- `rf_hook_score`
- `rf_hook_source`
- `rf_hook_fingerprint`
- `rf_hook_candidates`
- `rf_hook_candidate_count`

The full diagnostic export already includes Provider state, so these fields are captured automatically.

## OTA maintenance procedure

After a system upgrade:

1. Install the current app build and restart NFC/phone so LSPosed loads the module.
2. Open the app and enable logs if deeper probing is needed.
3. Run one APPLY and one STOP.
4. Export diagnostics.
5. Check `profile_status` and `rf_hook_candidates` first.

Expected outcomes:

- **Profile hit:** `CACHED_VERIFIED`, one hook installed.
- **Automatic relearn:** `LEARNING/DISCOVERED` becomes `VERIFIED` after native result `0`.
- **No candidate:** expand `HookDiscoveryEngine` scoring/class enumeration, not vendor-specific UI code.
- **Candidate found but payload unknown:** add/adjust a `RfPayloadCodec`, not the discovery engine.

This separation is the main maintenance rule: **hook location changes belong to discovery; payload format changes belong to codecs.**

## Safety / regression rule

Never mark simulation successful from Binder/trigger success alone.

Production success requires generation-bound RF confirmation with native result `0`. STOP success similarly requires stock RF confirmation from a recognized RF payload path.

Keep Hook installation, lifecycle recovery, and early RF replay together unless device evidence supports a new boundary. Their ordering is correctness-critical, so reducing file size alone is not sufficient justification for a split.
