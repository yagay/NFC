from pathlib import Path

p = Path('app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java')
s = p.read_text()

# 1) Runtime state for durable same-process exact replay and failure-loop suppression.
old = '''    private volatile EarlyRfInvocation pendingEarlyRfInvocation;\n    private volatile boolean earlyReplayWorkerScheduled;\n'''
new = '''    private volatile EarlyRfInvocation pendingEarlyRfInvocation;\n    private volatile EarlyRfInvocation lastVerifiedRfInvocation;\n    private volatile boolean earlyReplayWorkerScheduled;\n    private volatile long lifecycleFailureGeneration = Long.MIN_VALUE;\n    private volatile long lifecycleFailureControllerEpoch = Long.MIN_VALUE;\n'''
assert old in s
s = s.replace(old, new, 1)

# 2) Capture the exact OEM invocation after a native-accepted write.
old = '''                persistRestoreState();\n                clearEarlyRfInvocation(target.fingerprint());\n                if (lifecycleReapplyPending) {\n'''
new = '''                persistRestoreState();\n                captureVerifiedRfInvocation(method, chain.getThisObject(), args, target, pid);\n                clearEarlyRfInvocation(target.fingerprint());\n                if (lifecycleReapplyPending) {\n'''
assert old in s
s = s.replace(old, new, 1)

# 3) Add exact-replay helper next to early replay helpers.
anchor = '''    private synchronized void clearEarlyRfInvocation(String targetFingerprint) {\n'''
insert = r'''    private void captureVerifiedRfInvocation(Method method, Object receiver, Object[] args,
                                             HookTarget target, int pid) {
        if (method == null || args == null || target == null) return;
        EarlyRfInvocation snapshot = new EarlyRfInvocation(
                method, receiver, cloneInvocationArgs(args), target.fingerprint(),
                System.currentTimeMillis(), pid);
        lastVerifiedRfInvocation = snapshot;
        ContentValues v = baseHookState();
        SimConfig cfg = cachedConfig;
        if (cfg.initialized && cfg.generation > 0L) v.put("state_generation", cfg.generation);
        v.put("rf_replay_status", "CAPTURED");
        v.put("rf_replay_target", target.fingerprint());
        v.put("rf_replay_captured_at", snapshot.capturedAt);
        v.put("rf_replay_pid", pid);
        writeValuesWithRetry(v, 8, 75L);
        Log.i(TAG, "RF EXACT REPLAY captured target=" + target.fingerprint() + " pid=" + pid);
    }

    private boolean replayVerifiedRfInvocation(SimConfig cfg, String uidHex, long baseline) {
        EarlyRfInvocation snapshot = lastVerifiedRfInvocation;
        if (snapshot == null || snapshot.capturedPid != Process.myPid()) {
            Log.i(TAG, "RF EXACT REPLAY unavailable generation=" + cfg.generation + " pid=" + Process.myPid());
            return false;
        }
        try {
            snapshot.method.setAccessible(true);
            ContentValues v = baseHookState();
            v.put("state_generation", cfg.generation);
            v.put("rf_replay_status", "INVOKING");
            v.put("rf_replay_target", snapshot.targetFingerprint);
            v.put("rf_replay_captured_at", snapshot.capturedAt);
            v.put("rf_replay_pid", snapshot.capturedPid);
            writeValuesWithRetry(v, 8, 75L);
            Log.i(TAG, "RF EXACT REPLAY invoke target=" + snapshot.targetFingerprint +
                    " generation=" + cfg.generation + " uid=" + uidHex + " baselineSequence=" + baseline +
                    " pid=" + Process.myPid());
            snapshot.method.invoke(snapshot.receiver, cloneInvocationArgs(snapshot.args));
            return true;
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            ContentValues v = baseHookState();
            v.put("state_generation", cfg.generation);
            v.put("rf_replay_status", "INVOKE_FAILED");
            v.put("rf_replay_target", snapshot.targetFingerprint);
            v.put("rf_replay_error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
            writeValuesWithRetry(v, 8, 75L);
            Log.w(TAG, "RF EXACT REPLAY failed target=" + snapshot.targetFingerprint + " " +
                    cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return false;
        }
    }

'''
assert anchor in s
s = s.replace(anchor, insert + anchor, 1)

# 4) Make exact replay the primary final-reapply backend before share-mode triggers.
old = '''            String lastFailure = settled ? "No final RF_CONFIG_WRITE observed" : "RF startup did not become quiet before final trigger";\n            for (int attempt = 1; attempt <= LIFECYCLE_MAX_TRIGGER_ATTEMPTS && System.currentTimeMillis() < deadline; attempt++) {\n'''
new = '''            String lastFailure = settled ? "No final RF_CONFIG_WRITE observed" : "RF startup did not become quiet before final trigger";\n\n            // Primary recovery path: replay the exact native-accepted OEM RF invocation captured\n            // before the controller reset. This is especially reliable for adapter OFF -> ON where\n            // com.android.nfc stays in the same process. The reflected call re-enters this module's\n            // RF hook, so current UID rewrite, sequence proof and native result validation remain\n            // exactly the same as for a natural OEM call.\n            long replayBaseline;\n            synchronized (this) {\n                replayBaseline = rfWriteSequence;\n                finalReapplyBaselineSequence = replayBaseline;\n                finalReapplyArmed = true;\n            }\n            persistRfSequenceState("EXACT_REPLAY_ARMED", cfg.generation, replayBaseline, lastRfWriteAt);\n            if (replayVerifiedRfInvocation(cfg, uidHex, replayBaseline)) {\n                long replayRemaining = Math.max(0L, deadline - System.currentTimeMillis());\n                long replayWait = Math.min(LIFECYCLE_TRIGGER_WAIT_MS, replayRemaining);\n                if (replayWait > 0L && waitForLifecycleVerified(cfg.generation, pid, uidHex, replayWait)) {\n                    lifecycleFailureGeneration = Long.MIN_VALUE;\n                    lifecycleFailureControllerEpoch = Long.MIN_VALUE;\n                    return;\n                }\n                lastFailure = "Exact RF replay invoked but produced no FINAL verified RF_CONFIG_WRITE";\n            } else {\n                lastFailure = "Exact RF replay unavailable/failed";\n            }\n            synchronized (this) {\n                if (finalReapplyBaselineSequence == replayBaseline) finalReapplyArmed = false;\n            }\n\n            // Fallback only: some fresh processes do not yet have an exact replay template. Keep\n            // the existing share-mode/vendor trigger for those cases, but never treat its boolean\n            // return as RF success without a strictly newer accepted RF write.\n            for (int attempt = 1; attempt <= LIFECYCLE_MAX_TRIGGER_ATTEMPTS && System.currentTimeMillis() < deadline; attempt++) {\n'''
assert old in s
s = s.replace(old, new, 1)

# 5) Record failed generation+epoch so our own provider failure write doesn't restart forever.
old = '''        writeValuesWithRetry(v, 8, 75L);\n        Log.w(TAG, "LIFECYCLE RECOVERY failed generation=" + cfg.generation + " uid=" + uid + " detail=" + detail);\n    }\n\n    private void scheduleCommandRefresh(String reason) {\n'''
new = '''        lifecycleFailureGeneration = cfg.generation;\n        lifecycleFailureControllerEpoch = cfg.controllerEpoch;\n        writeValuesWithRetry(v, 8, 75L);\n        Log.w(TAG, "LIFECYCLE RECOVERY failed generation=" + cfg.generation + " uid=" + uid + " detail=" + detail);\n    }\n\n    private void scheduleCommandRefresh(String reason) {\n'''
assert old in s
s = s.replace(old, new, 1)

# 6) Suppress only self-generated provider-change retries for same physical epoch.
old = '''                if (!isLifecycleVerified(cfg.generation, pid, cfg.uid)) {\n                    Log.i(TAG, "LIFECYCLE APPLY adoption generation=" + cfg.generation + " uid=" + cfg.uid + " pid=" + pid + " reason=" + reason);\n                    scheduleLifecycleRecovery("process_" + reason);\n                }\n'''
new = '''                if (!isLifecycleVerified(cfg.generation, pid, cfg.uid)) {\n                    boolean suppressSelfRetry = "provider_change".equals(reason) &&\n                            lifecycleFailureGeneration == cfg.generation &&\n                            lifecycleFailureControllerEpoch == cfg.controllerEpoch;\n                    if (suppressSelfRetry) {\n                        Log.i(TAG, "LIFECYCLE APPLY self-retry suppressed generation=" + cfg.generation +\n                                " epoch=" + cfg.controllerEpoch + " pid=" + pid);\n                    } else {\n                        Log.i(TAG, "LIFECYCLE APPLY adoption generation=" + cfg.generation + " uid=" + cfg.uid + " pid=" + pid + " reason=" + reason);\n                        scheduleLifecycleRecovery("process_" + reason);\n                    }\n                }\n'''
assert old in s
s = s.replace(old, new, 1)

# 7) A new controller invalidation naturally clears the failure suppression by epoch, but clear
# explicitly too so diagnostics/state are unambiguous.
old = '''        if (!firstInvalid) return;\n        invalidateRfEvidenceForControllerReset(reason);\n'''
new = '''        if (!firstInvalid) return;\n        lifecycleFailureGeneration = Long.MIN_VALUE;\n        lifecycleFailureControllerEpoch = Long.MIN_VALUE;\n        invalidateRfEvidenceForControllerReset(reason);\n'''
assert old in s
s = s.replace(old, new, 1)

p.write_text(s)

# Version bump.
b = Path('app/build.gradle.kts')
t = b.read_text()
t = t.replace('versionCode = 49', 'versionCode = 50', 1)
t = t.replace('versionName = "1.0.48"', 'versionName = "1.0.49"', 1)
t = t.replace('hook build 35; 1.0.48 makes real controller initialize/deinitialize + NfcService enable/disable lifecycle the primary recovery signal, with adapter broadcasts as fallback.',
              'hook build 36; 1.0.49 replays the exact previously native-accepted RF invocation after controller READY, with share-mode only as fallback and same-epoch failure-loop suppression.', 1)
t = t.replace('buildConfigField("int", "HOOK_BUILD", "35")', 'buildConfigField("int", "HOOK_BUILD", "36")', 1)
b.write_text(t)
