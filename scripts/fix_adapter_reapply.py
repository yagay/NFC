from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
p = ROOT / 'app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java'
s = p.read_text()

old_receiver = '''                if (state == 3) {
                    scheduleLifecycleRecovery("adapter_state_on");
                } else if (state == 1 || state == 4) {
                    finishLifecycleRecovery(lifecycleRecoveryGeneration);
                }
'''
new_receiver = '''                if (state == 3) {
                    scheduleLifecycleRecovery("adapter_state_on");
                } else if (state == 1 || state == 4) {
                    // Turning NFC off resets controller RF state even when com.android.nfc keeps
                    // the same PID. Invalidate prior RF verification so the following ON event
                    // cannot mistake stale evidence for a successful lifecycle reapply.
                    invalidateRfEvidenceForAdapterReset(state == 4 ? "adapter_turning_off" : "adapter_off");
                    finishLifecycleRecovery(lifecycleRecoveryGeneration);
                }
'''
if old_receiver not in s:
    raise SystemExit('adapter receiver block not found')
s = s.replace(old_receiver, new_receiver)

old_uid_guard = '''        String uidHex = normalizeUid(cfg.uid);
        if (uidHex.length() != 8) return;
'''
new_uid_guard = '''        String uidHex = normalizeUid(cfg.uid);
        if (uidHex.length() != 8 && uidHex.length() != 14 && uidHex.length() != 20) return;
'''
if old_uid_guard not in s:
    raise SystemExit('lifecycle uid guard not found')
s = s.replace(old_uid_guard, new_uid_guard)

anchor = '''    private void scheduleLifecycleRecovery(String reason) {
        lifecycleExecutor.execute(() -> runLifecycleRecovery(reason));
    }
'''
insert = '''    private void scheduleLifecycleRecovery(String reason) {
        lifecycleExecutor.execute(() -> runLifecycleRecovery(reason));
    }

    /**
     * NFC adapter OFF / TURNING_OFF resets the controller RF configuration but may leave the
     * com.android.nfc Java process alive. PID-based verification therefore cannot prove that the
     * previously applied NFCID1 is still present. Preserve the durable desired APPLY command and
     * selected UID, but explicitly invalidate only observed RF evidence. The next adapter ON event
     * will then run the closed-loop lifecycle recovery and require a fresh native-accepted RF write.
     */
    private void invalidateRfEvidenceForAdapterReset(String reason) {
        SimConfig cfg = readConfig();
        if (!cfg.initialized || !cfg.active || cfg.generation <= 0L) return;
        ContentValues v = baseHookState();
        v.put("state_generation", cfg.generation);
        v.put("operation_state", "LIFECYCLE_REAPPLY");
        v.put("effective_state", "UNKNOWN");
        v.put("verification_confidence", "LIFECYCLE_PENDING");
        v.put("rf_accepted", false);
        v.put("rf_status", "RF_INVALIDATED_BY_ADAPTER_RESET");
        v.put("rf_uid", "");
        v.put("rf_source", reason == null ? "adapter-reset" : reason);
        v.put("rf_result", "");
        v.put("rf_native_result", "");
        v.put("rf_native_result_type", "lifecycle");
        v.put("rf_error", "");
        v.put("rf_pid", 0);
        v.put("rf_generation", cfg.generation);
        v.put("rf_verification", "ADAPTER_RESET_INVALIDATED");
        v.put("full_diag_stage", "ADAPTER_RESET_WAITING_REAPPLY");
        v.put("full_diag_summary", "NFC adapter reset invalidated prior RF evidence; waiting for fresh lifecycle reapply");
        persistRefreshRuntime("LIFECYCLE_INVALIDATED", "", reason, cfg.generation, false);
        writeValuesWithRetry(v, 8, 75L);
        synchronized (this) {
            lifecycleReapplyPending = false;
            lifecycleRecoveryGeneration = Long.MIN_VALUE;
            lifecycleRecoveryStartedAt = 0L;
        }
        clearTriggerWindow(cfg.generation);
        Log.i(TAG, "ADAPTER RESET invalidated RF evidence reason=" + reason +
                " generation=" + cfg.generation + " uid=" + cfg.uid + " pid=" + Process.myPid());
    }
'''
if anchor not in s:
    raise SystemExit('scheduleLifecycleRecovery anchor not found')
s = s.replace(anchor, insert)
p.write_text(s)

# Bump app + hook versions because NFC-process lifecycle behavior changed.
g = ROOT / 'app/build.gradle.kts'
t = g.read_text()
t = t.replace('versionCode = 41', 'versionCode = 42')
t = t.replace('versionName = "1.0.40"', 'versionName = "1.0.41"')
t = t.replace('hook build 28; safe 4/7/10-byte NFCID1 and vendor-neutral NCI discovery.',
              'hook build 29; adapter OFF/ON RF invalidation + automatic lifecycle reapply; safe 4/7/10-byte NFCID1 and vendor-neutral NCI discovery.')
t = t.replace('buildConfigField("int", "HOOK_BUILD", "28")', 'buildConfigField("int", "HOOK_BUILD", "29")')
g.write_text(t)
