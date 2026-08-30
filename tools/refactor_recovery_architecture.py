from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java"
GRADLE = ROOT / "app/build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    a = text.find(start)
    b = text.find(end, a + len(start)) if a >= 0 else -1
    if a < 0 or b < 0:
        raise SystemExit(f"{label}: boundaries not found")
    return text[:a] + replacement + text[b:]


def main():
    text = MODULE.read_text()

    text = replace_once(text,
        "    private volatile HookTarget activeTarget;\n",
        "    private volatile HookTarget activeTarget;\n"
        "    private volatile ClassLoader nfcClassLoader;\n"
        "    private volatile boolean fullTriggerDiscoveryDone;\n",
        "add lazy discovery state")

    text = replace_once(text,
        "        final ClassLoader cl = lp.getDefaultClassLoader();\n",
        "        final ClassLoader cl = lp.getDefaultClassLoader();\n"
        "        nfcClassLoader = cl;\n",
        "remember classloader")

    # Startup only installs cheap known trigger hooks. Full dex trigger discovery is compatibility
    # fallback and is deferred until a trigger is actually needed.
    text = replace_once(text,
        "        List<HookTarget> triggerTargets = discoveryEngine.discoverKnownTriggerCandidates(cl);\n"
        "        if (triggerTargets.isEmpty()) triggerTargets = discoveryEngine.discoverTriggerCandidates(cl);\n",
        "        List<HookTarget> triggerTargets = discoveryEngine.discoverKnownTriggerCandidates(cl);\n",
        "make trigger discovery lazy")

    # Insert lazy full discovery before triggerRfRefresh.
    anchor = "    private NfcProcessVendorController.Result triggerRfRefresh(SimConfig cfg, boolean enabled, String reason) {\n"
    helper = '''    private synchronized void ensureFullTriggerDiscovery() {
        if (fullTriggerDiscoveryDone) return;
        fullTriggerDiscoveryDone = true;
        ClassLoader cl = nfcClassLoader;
        if (cl == null) return;
        try {
            List<HookTarget> candidates = discoveryEngine.discoverTriggerCandidates(cl);
            persistTriggerCandidates(Process.myPid(), candidates);
            int installed = 0;
            for (int i = 0; i < Math.min(MAX_TRIGGER_HOOKS, candidates.size()); i++) {
                try {
                    installRefreshTriggerHook(cl, Process.myPid(), candidates.get(i));
                    installed++;
                } catch (Throwable t) {
                    Log.w(TAG, "LAZY REFRESH TRIGGER hook skipped target=" + candidates.get(i) + " " +
                            t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
            Log.i(TAG, "LAZY REFRESH TRIGGER discovery installed=" + installed +
                    " candidates=" + candidates.size() + " pid=" + Process.myPid());
        } catch (Throwable t) {
            Log.w(TAG, "LAZY REFRESH TRIGGER discovery failed " + t.getClass().getSimpleName() +
                    ": " + t.getMessage());
        }
    }

    private boolean hasVerifiedReplayForCurrentProcess() {
        RfInvocationSnapshot snapshot = lastVerifiedRfInvocation;
        return snapshot != null && snapshot.capturedPid == Process.myPid();
    }

'''
    if anchor not in text:
        raise SystemExit("trigger anchor missing")
    text = text.replace(anchor, helper + anchor, 1)

    # Only pay for full trigger discovery when the cheap known Java trigger cannot be invoked.
    text = replace_once(text,
        "        RefreshTriggerEngine.Invocation javaTrigger = refreshTriggerEngine.invoke(enabled);\n"
        "        if (javaTrigger.success) {",
        "        RefreshTriggerEngine.Invocation javaTrigger = refreshTriggerEngine.invoke(enabled);\n"
        "        if (!javaTrigger.success && !fullTriggerDiscoveryDone) {\n"
        "            ensureFullTriggerDiscovery();\n"
        "            javaTrigger = refreshTriggerEngine.invoke(enabled);\n"
        "        }\n"
        "        if (javaTrigger.success) {",
        "lazy fallback discovery invocation")

    # Replace lifecycle recovery with state-machine-driven decisions. Timing and side effects remain
    # here, but policy is now pure/testable.
    start = "    private void runLifecycleRecovery(String reason) {"
    end = "    private boolean waitForLifecycleVerified(long generation, int pid, String uid, long timeoutMs) {"
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

        boolean replayAttempted = false;
        int fallbackAttempts = 0;
        String lastFailure = "No recovery action executed";
        long deadline = System.currentTimeMillis() + LIFECYCLE_TOTAL_TIMEOUT_MS;
        try {
            sleep(LIFECYCLE_REPLAY_DELAY_MS);
            while (System.currentTimeMillis() < deadline) {
                SimConfig latest = readConfig();
                boolean commandSucceeded = latest.initialized && latest.active &&
                        latest.generation == cfg.generation && latest.handledGeneration == latest.generation &&
                        "SUCCESS".equals(latest.commandStatus);
                boolean verified = commandSucceeded && isLifecycleVerified(cfg.generation, pid, uidHex);
                RecoveryStateMachine.Action action = RecoveryStateMachine.next(
                        new RecoveryStateMachine.Snapshot(
                                latest.initialized && latest.active,
                                commandSucceeded,
                                verified,
                                hasVerifiedReplayForCurrentProcess(),
                                replayAttempted,
                                fallbackAttempts,
                                LIFECYCLE_MAX_TRIGGER_ATTEMPTS));

                if (action == RecoveryStateMachine.Action.NONE) return;
                if (action == RecoveryStateMachine.Action.MARK_FAILED) break;

                synchronized (this) { recoveryWriteArmed = true; }
                if (action == RecoveryStateMachine.Action.EXACT_REPLAY) {
                    replayAttempted = true;
                    boolean replayed = replayVerifiedRfInvocation(latest, uidHex);
                    lastFailure = replayed ?
                            "Exact RF replay invoked but no fresh native proof was observed" :
                            "Exact RF replay unavailable/failed";
                    if (replayed && waitForLifecycleVerified(
                            cfg.generation, pid, uidHex, LIFECYCLE_TRIGGER_WAIT_MS)) {
                        lifecycleFailureGeneration = Long.MIN_VALUE;
                        lifecycleFailureControllerEpoch = Long.MIN_VALUE;
                        return;
                    }
                } else {
                    fallbackAttempts++;
                    NfcProcessVendorController.Result trigger = triggerRfRefresh(
                            latest, true,
                            "lifecycle-fallback:" + reason + ":attempt-" + fallbackAttempts,
                            LIFECYCLE_TRIGGER_RF_WINDOW_MS);
                    lastFailure = trigger.stage + ": " + trigger.detail;
                    long remaining = Math.max(0L, deadline - System.currentTimeMillis());
                    long wait = Math.min(LIFECYCLE_TRIGGER_WAIT_MS, remaining);
                    if (wait > 0L && waitForLifecycleVerified(cfg.generation, pid, uidHex, wait)) return;
                }
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
    text = replace_between(text, start, end, replacement, "replace lifecycle recovery")

    MODULE.write_text(text)

    gradle = GRADLE.read_text()
    gradle = replace_once(gradle,
        '        versionCode = 51\n        versionName = "1.0.50"\n',
        '        versionCode = 52\n        versionName = "1.0.51"\n',
        "bump version")
    gradle = replace_once(gradle,
        '        // Runtime protocol v7; hook build 37; 1.0.50 removes obsolete RF settling/final-sequence machinery and keeps controller-ready exact replay as the primary recovery path, with share-mode only as compatibility fallback.\n'
        '        // Application ID, source namespace, Provider authority and LSPosed entry all use com.yagay.nfcdoorcard.\n'
        '        buildConfigField("int", "HOOK_BUILD", "37")\n',
        '        // Runtime protocol v7; hook build 38; 1.0.51 extracts pure recovery policy, defers full trigger discovery until fallback is needed, and keeps exact replay as the controller-ready primary path.\n'
        '        // Application ID, source namespace, Provider authority and LSPosed entry all use com.yagay.nfcdoorcard.\n'
        '        buildConfigField("int", "HOOK_BUILD", "38")\n',
        "bump hook build")
    GRADLE.write_text(gradle)

if __name__ == "__main__":
    main()
