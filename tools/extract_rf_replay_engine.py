from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java"
GRADLE = ROOT / "app/build.gradle.kts"


def one(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1, found {count}")
    return text.replace(old, new, 1)


def between(text, start, end, replacement, label):
    a = text.find(start)
    b = text.find(end, a + len(start)) if a >= 0 else -1
    if a < 0 or b < 0:
        raise SystemExit(f"{label}: boundaries missing")
    return text[:a] + replacement + text[b:]


def main():
    text = MODULE.read_text()
    text = one(text,
        "    private final RefreshTriggerEngine refreshTriggerEngine = new RefreshTriggerEngine();\n",
        "    private final RefreshTriggerEngine refreshTriggerEngine = new RefreshTriggerEngine();\n"
        "    private final RfReplayEngine replayEngine = new RfReplayEngine();\n",
        "add replay engine")

    text = one(text,
        "    // RF invocation snapshots back both startup bridging and controller-ready exact replay.\n"
        "    // Share-mode/Binder triggering remains compatibility fallback only.\n"
        "    private volatile RfInvocationSnapshot pendingRfInvocationSnapshot;\n"
        "    private volatile RfInvocationSnapshot lastVerifiedRfInvocation;\n"
        "    private volatile boolean earlyReplayWorkerScheduled;\n",
        "    // Startup and controller-ready replay share the same tested replay engine.\n"
        "    // Share-mode/Binder triggering remains compatibility fallback only.\n"
        "    private volatile boolean earlyReplayWorkerScheduled;\n",
        "remove module snapshot fields")

    text = one(text,
        "        RfInvocationSnapshot snapshot = lastVerifiedRfInvocation;\n"
        "        return snapshot != null && snapshot.capturedPid == Process.myPid();\n",
        "        return replayEngine.hasVerified(Process.myPid());\n",
        "use replay availability")

    text = one(text,
        "        Object[] snapshotArgs = cloneInvocationArgs(args);\n"
        "        RfInvocationSnapshot snapshot = new RfInvocationSnapshot(\n"
        "                method, receiver, snapshotArgs, target.fingerprint(), System.currentTimeMillis(), pid);\n"
        "        synchronized (this) {\n"
        "            // Keep the newest invocation from the active verified writer. During startup an OEM can\n"
        "            // emit several config writes; replaying the latest one minimizes stale side effects.\n"
        "            pendingRfInvocationSnapshot = snapshot;\n",
        "        RfReplayEngine.Snapshot snapshot = replayEngine.capturePending(\n"
        "                method, receiver, args, target.fingerprint(), System.currentTimeMillis(), pid);\n"
        "        if (snapshot == null) return;\n"
        "        synchronized (this) {\n"
        "            // Keep the newest invocation from the active verified writer. During startup an OEM can\n"
        "            // emit several config writes; replaying the latest one minimizes stale side effects.\n",
        "capture pending through engine")

    text = text.replace("RfInvocationSnapshot snapshot = null;", "RfReplayEngine.Snapshot snapshot = null;")
    text = text.replace("snapshot = pendingRfInvocationSnapshot;", "snapshot = replayEngine.pending();")
    text = text.replace("if (pendingRfInvocationSnapshot != null) {", "if (replayEngine.pending() != null) {")
    text = text.replace("clearRfInvocationSnapshot(", "replayEngine.clearPending(")

    old_early_invoke = '''                try {
                    snapshot.method.setAccessible(true);
                    synchronized (this) { recoveryWriteArmed = true; }
                    Log.i(TAG, "EARLY RF REPLAY invoke target=" + snapshot.targetFingerprint +
                            " generation=" + cfg.generation + " uid=" + uidHex + " pid=" + Process.myPid());
                    // Exactly one replay attempt. The reflected call re-enters the installed RF hook,
                    // which applies the current UID and records native/controller-epoch proof.
                    snapshot.method.invoke(snapshot.receiver, cloneInvocationArgs(snapshot.args));
                } catch (Throwable t) {
                    Throwable cause = t.getCause() == null ? t : t.getCause();
                    Log.w(TAG, "EARLY RF REPLAY failed target=" + snapshot.targetFingerprint + " " +
                            cause.getClass().getSimpleName() + ": " + cause.getMessage());
                }'''
    new_early_invoke = '''                synchronized (this) { recoveryWriteArmed = true; }
                Log.i(TAG, "EARLY RF REPLAY invoke target=" + snapshot.targetFingerprint +
                        " generation=" + cfg.generation + " uid=" + uidHex + " pid=" + Process.myPid());
                // Exactly one replay attempt. The reflected call re-enters the installed RF hook,
                // which applies the current UID and records native/controller-epoch proof.
                RfReplayEngine.ReplayResult replay = replayEngine.invoke(snapshot);
                if (!replay.invoked && replay.error != null) {
                    Log.w(TAG, "EARLY RF REPLAY failed target=" + snapshot.targetFingerprint + " " +
                            replay.error.getClass().getSimpleName() + ": " + replay.error.getMessage());
                }'''
    text = one(text, old_early_invoke, new_early_invoke, "use replay engine for early invoke")

    old_verified = '''        RfInvocationSnapshot snapshot = new RfInvocationSnapshot(
                method, receiver, cloneInvocationArgs(args), target.fingerprint(),
                System.currentTimeMillis(), pid);
        lastVerifiedRfInvocation = snapshot;'''
    new_verified = '''        RfReplayEngine.Snapshot snapshot = replayEngine.captureVerified(
                method, receiver, args, target.fingerprint(), System.currentTimeMillis(), pid);
        if (snapshot == null) return;'''
    text = one(text, old_verified, new_verified, "capture verified through engine")

    start = "    private boolean replayVerifiedRfInvocation(SimConfig cfg, String uidHex) {"
    end = "    private Application waitForApplication(long timeoutMs) {"
    replacement = '''    private boolean replayVerifiedRfInvocation(SimConfig cfg, String uidHex) {
        RfReplayEngine.Snapshot snapshot = replayEngine.verified(Process.myPid());
        if (snapshot == null) {
            Log.i(TAG, "RF REPLAY unavailable generation=" + cfg.generation + " pid=" + Process.myPid());
            return false;
        }
        ContentValues v = baseHookState();
        v.put("state_generation", cfg.generation);
        v.put("rf_replay_status", "INVOKING");
        v.put("rf_replay_target", snapshot.targetFingerprint);
        v.put("rf_replay_captured_at", snapshot.capturedAt);
        v.put("rf_replay_pid", snapshot.capturedPid);
        writeValuesWithRetry(v, 8, 75L);
        Log.i(TAG, "RF REPLAY invoke target=" + snapshot.targetFingerprint +
                " generation=" + cfg.generation + " uid=" + uidHex +
                " pid=" + Process.myPid());
        RfReplayEngine.ReplayResult replay = replayEngine.invoke(snapshot);
        if (replay.invoked) return true;

        Throwable cause = replay.error;
        ContentValues failure = baseHookState();
        failure.put("state_generation", cfg.generation);
        failure.put("rf_replay_status", "INVOKE_FAILED");
        failure.put("rf_replay_target", snapshot.targetFingerprint);
        failure.put("rf_replay_error", cause == null ? "unknown" :
                cause.getClass().getSimpleName() + ": " + cause.getMessage());
        writeValuesWithRetry(failure, 8, 75L);
        Log.w(TAG, "RF REPLAY failed target=" + snapshot.targetFingerprint + " " +
                (cause == null ? "unknown" : cause.getClass().getSimpleName() + ": " + cause.getMessage()));
        return false;
    }

'''
    text = between(text, start, end, replacement, "replace replay helpers")

    # Remove inner snapshot class; engine now owns snapshot representation and argument cloning.
    text = between(text,
        "    private static final class RfInvocationSnapshot {",
        "    private static final class NativeOutcome {",
        "",
        "remove inner snapshot class")

    MODULE.write_text(text)

    gradle = GRADLE.read_text()
    gradle = one(gradle,
        '        versionCode = 54\n        versionName = "1.0.53"\n',
        '        versionCode = 55\n        versionName = "1.0.54"\n',
        "bump version")
    gradle = one(gradle,
        '        buildConfigField("int", "HOOK_BUILD", "38")\n',
        '        buildConfigField("int", "HOOK_BUILD", "39")\n',
        "bump hook build")
    GRADLE.write_text(gradle)

if __name__ == "__main__":
    main()
