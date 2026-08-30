from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java"
VENDOR = ROOT / "app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcProcessVendorController.java"
GRADLE = ROOT / "app/build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)


def remove_between(text: str, start: str, end: str, label: str) -> str:
    a = text.find(start)
    b = text.find(end, a + len(start)) if a >= 0 else -1
    if a < 0 or b < 0:
        raise SystemExit(f"{label}: boundaries not found")
    return text[:a] + text[b:]


def main():
    text = MODULE.read_text()

    # 1.0.46 settling/final-sequence machinery was built around the later-disproved assumption
    # that recovery only needed a sufficiently late share-mode-triggered RF write. 1.0.49 proved
    # the reliable primitive is exact invocation replay after a real controller READY edge.
    text = replace_once(text,
        "    private static final long LIFECYCLE_MIN_SETTLE_MS = 1_200L;\n"
        "    private static final long LIFECYCLE_RF_QUIET_MS = 700L;\n"
        "    private static final long LIFECYCLE_SETTLE_MAX_WAIT_MS = 3_500L;\n",
        "    private static final long LIFECYCLE_REPLAY_DELAY_MS = 600L;\n",
        "replace settling constants")

    text = replace_once(text,
        "    private volatile long rfWriteSequence;\n"
        "    private volatile long lastRfWriteAt;\n"
        "    private volatile boolean finalReapplyArmed;\n"
        "    private volatile long finalReapplyBaselineSequence;\n"
        "    private volatile long finalVerifiedSequence;\n",
        "    // Only an explicitly armed recovery write may publish lifecycle VERIFIED state.\n"
        "    // Natural startup writes are still rewritten, but the controller-ready replay remains\n"
        "    // the deterministic proof point that fixed OFF -> ON on the target device.\n"
        "    private volatile boolean recoveryWriteArmed;\n",
        "replace final sequence fields")

    # Use one snapshot type for both startup bridge and verified exact replay.
    text = text.replace("EarlyRfInvocation", "RfInvocationSnapshot")

    # Remove the 1.0.46 per-write sequence observation block from the RF hot path.
    start = "            // Every active-owner RF write participates in lifecycle settling."
    end = "            // A natural RF write in a new NFC process is the best lifecycle recovery trigger."
    text = remove_between(text, start, end, "remove hot-path settling observation")

    old_accept = '''                if (lifecycleReapplyPending) {
                    if (isFinalReapplyWrite(cfg.generation, observedRfSequence)) {
                        completeLifecycleReapply(cfg, uidHex, rewritten.codecId, outcome, target, observedRfSequence);
                        finishLifecycleRecovery(cfg.generation);
                    } else {
                        recordLifecycleProvisional(cfg, uidHex, rewritten.codecId, outcome, target, observedRfSequence);
                    }
                } else if (!isGenerationCompleted(cfg.generation, pid)) {'''
    new_accept = '''                if (lifecycleReapplyPending) {
                    if (recoveryWriteArmed) {
                        completeLifecycleReapply(cfg, uidHex, rewritten.codecId, outcome, target);
                        finishLifecycleRecovery(cfg.generation);
                    } else if (cfg.diagnostics) {
                        Log.i(TAG, "LIFECYCLE natural RF write accepted before replay generation=" +
                                cfg.generation + " uid=" + uidHex + " target=" + target.fingerprint());
                    }
                } else if (!isGenerationCompleted(cfg.generation, pid)) {'''
    text = replace_once(text, old_accept, new_accept, "replace lifecycle accepted gate")

    # Controller invalidation no longer maintains historical final-sequence state.
    text = replace_once(text,
        "            finalReapplyArmed = false;\n"
        "            finalReapplyBaselineSequence = rfWriteSequence;\n"
        "            finalVerifiedSequence = 0L;\n",
        "            recoveryWriteArmed = false;\n",
        "simplify invalidation state")

    # Replace the entire lifecycle worker with controller-ready debounce -> exact replay -> fallback.
    start = "    private void runLifecycleRecovery(String reason) {"
    end = "    private boolean waitForRfSettled(long generation, long timeoutMs) {"
    a = text.find(start)
    b = text.find(end, a)
    if a < 0 or b < 0:
        raise SystemExit("runLifecycleRecovery block not found")
    replacement = '''    private void runLifecycleRecovery(String reason) {
        SimConfig cfg = readConfig();
        int pid = Process.myPid();
        if (!cfg.initialized || !cfg.active || cfg.uid == null || cfg.generation <= 0L ||
                cfg.handledGeneration != cfg.generation || !"SUCCESS".equals(cfg.commandStatus)) return;
        String uidHex = normalizeUid(cfg.uid);
        if (uidHex.length() != 8 && uidHex.length() != 14 && uidHex.length() != 20) return;
        if (isLifecycleVerified(cfg.generation, pid, uidHex)) {
            finishLifecycleRecovery(cfg.generation);
            return;
        }

        synchronized (this) {
            if (lifecycleWorkerRunning && lifecycleWorkerGeneration == cfg.generation) return;
            lifecycleWorkerRunning = true;
            lifecycleWorkerGeneration = cfg.generation;
            lifecycleReapplyPending = true;
            lifecycleRecoveryGeneration = cfg.generation;
            lifecycleRecoveryStartedAt = System.currentTimeMillis();
            recoveryWriteArmed = false;
        }

        persistRefreshRuntime("LIFECYCLE_REPLAY_WAIT", "", reason, cfg.generation, false);
        Log.i(TAG, "LIFECYCLE RECOVERY start reason=" + reason + " generation=" + cfg.generation +
                " uid=" + uidHex + " pid=" + pid);

        try {
            // Small fixed controller-ready debounce replaces the old RF quiet-period/final-sequence
            // state machine. Correctness comes from a fresh native-accepted proof in this controller
            // epoch, not from timing guesses or share-mode return values.
            sleep(LIFECYCLE_REPLAY_DELAY_MS);
            SimConfig latest = readConfig();
            if (!latest.initialized || !latest.active || latest.generation != cfg.generation) return;
            if (isLifecycleVerified(cfg.generation, pid, uidHex)) return;

            String lastFailure;
            synchronized (this) { recoveryWriteArmed = true; }
            boolean replayed = replayVerifiedRfInvocation(latest, uidHex);
            if (replayed && waitForLifecycleVerified(cfg.generation, pid, uidHex, LIFECYCLE_TRIGGER_WAIT_MS)) {
                lifecycleFailureGeneration = Long.MIN_VALUE;
                lifecycleFailureControllerEpoch = Long.MIN_VALUE;
                return;
            }
            synchronized (this) { recoveryWriteArmed = false; }
            lastFailure = replayed ?
                    "Exact RF replay invoked but no fresh native proof was observed" :
                    "Exact RF replay unavailable/failed";

            // Compatibility fallback for fresh processes that do not yet have a verified replay
            // template. A trigger is only useful if it causes a real RF writer invocation; its
            // boolean return never counts as success by itself.
            long deadline = System.currentTimeMillis() + LIFECYCLE_TOTAL_TIMEOUT_MS;
            for (int attempt = 1; attempt <= LIFECYCLE_MAX_TRIGGER_ATTEMPTS &&
                    System.currentTimeMillis() < deadline; attempt++) {
                latest = readConfig();
                if (!latest.initialized || !latest.active || latest.generation != cfg.generation) break;
                synchronized (this) { recoveryWriteArmed = true; }
                NfcProcessVendorController.Result trigger = triggerRfRefresh(
                        latest, true, "lifecycle-fallback:" + reason + ":attempt-" + attempt,
                        LIFECYCLE_TRIGGER_RF_WINDOW_MS);
                lastFailure = trigger.stage + ": " + trigger.detail;
                long remaining = Math.max(0L, deadline - System.currentTimeMillis());
                long wait = Math.min(LIFECYCLE_TRIGGER_WAIT_MS, remaining);
                if (wait > 0L && waitForLifecycleVerified(cfg.generation, pid, uidHex, wait)) return;
                synchronized (this) { recoveryWriteArmed = false; }
            }

            if (!isLifecycleVerified(cfg.generation, pid, uidHex)) {
                publishLifecycleFailure(cfg, uidHex,
                        "No verified RF_CONFIG_WRITE after controller READY; lastRecovery=" + lastFailure,
                        NativeOutcome.notInvoked());
            }
        } finally {
            synchronized (this) { recoveryWriteArmed = false; }
            if (!isLifecycleVerified(cfg.generation, pid, uidHex)) finishLifecycleRecovery(cfg.generation);
        }
    }

'''
    text = text[:a] + replacement + text[b:]

    # Remove waitForRfSettled entirely.
    text = remove_between(text,
        "    private boolean waitForRfSettled(long generation, long timeoutMs) {",
        "    private boolean waitForLifecycleVerified(long generation, int pid, String uid, long timeoutMs) {",
        "remove waitForRfSettled")

    text = replace_once(text,
        "        finalReapplyArmed = false;\n"
        "        finalReapplyBaselineSequence = rfWriteSequence;\n",
        "        recoveryWriteArmed = false;\n",
        "simplify finishLifecycleRecovery")

    # Arm the startup replay itself so a successful early replay can finish lifecycle verification
    # without falling through a second recovery worker.
    old_early_invoke = '''                try {
                    snapshot.method.setAccessible(true);
                    Log.i(TAG, "EARLY RF REPLAY invoke target=" + snapshot.targetFingerprint +
                            " generation=" + cfg.generation + " uid=" + uidHex + " pid=" + Process.myPid());
                    // Exactly one replay attempt. The reflected call re-enters the installed RF hook,
                    // which applies the current UID and records native/controller-epoch proof.
                    snapshot.method.invoke(snapshot.receiver, cloneInvocationArgs(snapshot.args));
                } catch (Throwable t) {'''
    new_early_invoke = '''                try {
                    snapshot.method.setAccessible(true);
                    synchronized (this) { recoveryWriteArmed = true; }
                    Log.i(TAG, "EARLY RF REPLAY invoke target=" + snapshot.targetFingerprint +
                            " generation=" + cfg.generation + " uid=" + uidHex + " pid=" + Process.myPid());
                    // Exactly one replay attempt. The reflected call re-enters the installed RF hook,
                    // which applies the current UID and records native/controller-epoch proof.
                    snapshot.method.invoke(snapshot.receiver, cloneInvocationArgs(snapshot.args));
                } catch (Throwable t) {'''
    text = replace_once(text, old_early_invoke, new_early_invoke, "arm startup replay")
    text = replace_once(text,
        "                if (waitForLifecycleVerified(cfg.generation, Process.myPid(), uidHex, LIFECYCLE_NATURAL_WAIT_MS)) {\n",
        "                if (waitForLifecycleVerified(cfg.generation, Process.myPid(), uidHex, LIFECYCLE_NATURAL_WAIT_MS)) {\n"
        "                    synchronized (this) { recoveryWriteArmed = false; }\n",
        "disarm successful startup replay")
    text = replace_once(text,
        "                clearEarlyRfInvocation(snapshot.targetFingerprint);\n                shouldFallback = true;\n",
        "                synchronized (this) { recoveryWriteArmed = false; }\n"
        "                clearEarlyRfInvocation(snapshot.targetFingerprint);\n"
        "                shouldFallback = true;\n",
        "disarm failed startup replay")

    # Exact replay no longer needs historical sequence baselines.
    text = replace_once(text,
        "    private boolean replayVerifiedRfInvocation(SimConfig cfg, String uidHex, long baseline) {",
        "    private boolean replayVerifiedRfInvocation(SimConfig cfg, String uidHex) {",
        "simplify replay signature")
    text = replace_once(text,
        "                    \" generation=\" + cfg.generation + \" uid=\" + uidHex + \" baselineSequence=\" + baseline +\n"
        "                    \" pid=\" + Process.myPid());",
        "                    \" generation=\" + cfg.generation + \" uid=\" + uidHex +\n"
        "                    \" pid=\" + Process.myPid());",
        "simplify replay log")

    # Replace lifecycle completion and remove all 1.0.46 provisional/final-sequence helpers.
    start = "    private void completeLifecycleReapply(SimConfig cfg, String uid, String source,"
    end = "    private void completeControllerReinit(SimConfig cfg, String detail) {"
    a = text.find(start)
    b = text.find(end, a)
    if a < 0 or b < 0:
        raise SystemExit("lifecycle completion helper range not found")
    replacement = '''    private void completeLifecycleReapply(SimConfig cfg, String uid, String source,
                                          NativeOutcome outcome, HookTarget target) {
        ContentValues v = baseHookState();
        v.put("state_generation", cfg.generation);
        v.put("rf_status", "RF_UID_APPLIED");
        v.put("rf_uid", uid);
        v.put("rf_source", "lifecycle-replay|" + (source == null ? "" : source));
        v.put("rf_result", outcome.rawValue);
        v.put("rf_native_result", outcome.rawValue);
        v.put("rf_native_result_type", outcome.resultType);
        v.put("rf_accepted", outcome.accepted);
        v.put("rf_error", "");
        v.put("rf_pid", Process.myPid());
        v.put("rf_generation", cfg.generation);
        v.put("rf_controller_epoch", cfg.controllerEpoch);
        v.put("rf_verification", "LIFECYCLE_REPLAY_NATIVE_RESULT");
        v.put("operation_state", "IDLE");
        v.put("effective_state", "ACTIVE");
        v.put("verification_confidence", "VERIFIED");
        v.put("full_diag_stage", "LIFECYCLE_REPLAY_SUCCESS");
        v.put("full_diag_summary", "Saved UID replayed after controller READY via " +
                target.className + "#" + target.methodName);
        writeValuesWithRetry(v, 20, 100L);
        clearTriggerWindow(cfg.generation);
        Log.i(TAG, "LIFECYCLE REPLAY success generation=" + cfg.generation + " uid=" + uid +
                " target=" + target.fingerprint() + " pid=" + Process.myPid());
    }

'''
    text = text[:a] + replacement + text[b:]

    # Updated comment: startup replay and verified replay now share one snapshot representation.
    text = replace_once(text,
        "    // Cold-start bridge: if the first natural OEM RF write arrives before ConfigProvider is\n"
        "    // readable, capture that exact in-process invocation and replay it once durable desired\n"
        "    // state becomes available. This avoids depending on share-mode/Binder triggers for recovery.\n",
        "    // RF invocation snapshots back both startup bridging and controller-ready exact replay.\n"
        "    // Share-mode/Binder triggering remains compatibility fallback only.\n",
        "update snapshot comment")

    # Rename exact replay telemetry wording; keep fields because they are useful diagnostics.
    text = text.replace("RF EXACT REPLAY", "RF REPLAY")

    MODULE.write_text(text)

    # Delete unused experimental reflected controller reinitializer. Production STOP uses the
    # process-restart barrier and lifecycle recovery uses exact replay instead.
    vendor = VENDOR.read_text()
    vendor = remove_between(vendor,
        "    /** Experimental lifecycle capability; normal STOP currently uses process restart. */",
        "    private Result setShareModeOnce(boolean enabled) {",
        "remove unused reinitializeController")
    VENDOR.write_text(vendor)

    gradle = GRADLE.read_text()
    gradle = replace_once(gradle, '        versionCode = 50\n        versionName = "1.0.49"\n',
                          '        versionCode = 51\n        versionName = "1.0.50"\n', 'bump version')
    gradle = replace_once(gradle,
        '        // Runtime protocol v7; hook build 36; 1.0.49 replays the exact previously native-accepted RF invocation after controller READY, with share-mode only as fallback and same-epoch failure-loop suppression.\n',
        '        // Runtime protocol v7; hook build 37; 1.0.50 removes obsolete RF settling/final-sequence machinery and keeps controller-ready exact replay as the primary recovery path, with share-mode only as compatibility fallback.\n',
        'update build comment')
    gradle = replace_once(gradle, '        buildConfigField("int", "HOOK_BUILD", "36")\n',
                          '        buildConfigField("int", "HOOK_BUILD", "37")\n', 'bump hook build')
    GRADLE.write_text(gradle)

    # Guard that historical settling symbols are truly gone.
    final = MODULE.read_text()
    retired = [
        "RfSettlingPolicy", "finalReapplyArmed", "finalReapplyBaselineSequence",
        "finalVerifiedSequence", "waitForRfSettled", "recordLifecycleProvisional",
        "persistRfSequenceState", "isFinalReapplyWrite", "rfWriteSequence", "lastRfWriteAt"
    ]
    leftovers = [name for name in retired if name in final]
    if leftovers:
        raise SystemExit("retired lifecycle symbols remain: " + ", ".join(leftovers))

    print("Historical NFC recovery patch cleanup applied successfully")


if __name__ == "__main__":
    main()
