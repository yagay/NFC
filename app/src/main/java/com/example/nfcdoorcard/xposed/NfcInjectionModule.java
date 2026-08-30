package com.example.nfcdoorcard.xposed;

import android.app.Application;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import com.example.nfcdoorcard.BuildConfig;
import com.example.nfcdoorcard.xposed.discovery.HookDiscoveryEngine;
import com.example.nfcdoorcard.xposed.discovery.HookTarget;
import com.example.nfcdoorcard.xposed.payload.RewriteResult;
import com.example.nfcdoorcard.xposed.payload.RfPayloadEngine;
import com.example.nfcdoorcard.xposed.profile.HookProfileStore;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Capability-based NFC RF override engine with controller lifecycle restoration. */
public class NfcInjectionModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";
    private static final int HOOK_BUILD = BuildConfig.HOOK_BUILD;
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings");
    private static final int MAX_LEARNING_HOOKS = 4;

    private final ExecutorService stateSyncExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "NfcUIDSim-StateSync"));
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "NfcUIDSim-Command"));
    private final HookDiscoveryEngine discoveryEngine = new HookDiscoveryEngine();
    private final RfPayloadEngine payloadEngine = new RfPayloadEngine();
    private final HookProfileStore profileStore = new HookProfileStore();
    private final NfcProcessVendorController vendorController = new NfcProcessVendorController();

    private volatile HookTarget activeTarget;
    private volatile String activeCodec = "";
    private volatile SimConfig cachedConfig = SimConfig.uninitialized();
    private volatile ContentObserver commandObserver;
    private volatile boolean observerRegistered;
    private volatile long lastTriggeredGeneration = Long.MIN_VALUE;
    private volatile long completedGeneration = Long.MIN_VALUE;
    private volatile int completedPid;
    private volatile int installedHookCount;
    private volatile boolean learningMode;
    private volatile boolean disabledAfterFailure;
    private volatile String disabledFailureUid;
    private volatile long disabledFailureGeneration = Long.MIN_VALUE;

    // Restoration state. If APPLY replaced an existing LA_NFCID1, its exact pre-APPLY
    // payload is reversible. If APPLY appended LA_NFCID1 to a payload where 0x33 was absent,
    // CORE_SET_CONFIG replay cannot delete it; the NFC controller must be reinitialized.
    private volatile byte[] reversibleStockPayload;
    private volatile String reversibleTargetFingerprint;
    private volatile long reversibleCapturedGeneration = Long.MIN_VALUE;
    private volatile boolean controllerReinitRequired;
    private volatile String restoreMode = "NONE";

    @Override public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        Log.i(TAG, "PROD MODULE loaded build=" + HOOK_BUILD + " process=" + param.getProcessName());
    }

    @Override public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
        super.onPackageLoaded(lp);
        if (!"com.android.nfc".equals(lp.getPackageName())) return;
        final int pid = Process.myPid();
        final ClassLoader cl = lp.getDefaultClassLoader();
        commandExecutor.execute(() -> initializeRuntime(cl, pid));
    }

    private void initializeRuntime(ClassLoader cl, int pid) {
        reportStatusWithRetry(pid, false, 0, "INITIALIZING", "Waiting for NFC application context", null);
        Application app = waitForApplication(8_000L);
        if (app == null) {
            reportStatusWithRetry(pid, false, 0, "INIT_FAILED", "NFC Application context unavailable", null);
            return;
        }

        persistTriggerCandidates(pid, discoveryEngine.discoverTriggerCandidates(cl));
        List<HookTarget> installTargets = new ArrayList<>();
        HookTarget cached = profileStore.loadValid(app, cl);
        if (cached != null) {
            learningMode = false;
            installTargets.add(cached);
            activeTarget = cached;
            persistProfileStatus("CACHED_VERIFIED", cached);
            Log.i(TAG, "PROFILE HIT target=" + cached + " pid=" + pid);
        } else {
            learningMode = true;
            reportStatusWithRetry(pid, false, 0, "DISCOVERING", "Profile invalid/missing; learning deepest RF_CONFIG_WRITE", null);
            List<HookTarget> candidates = discoveryEngine.discoverRfCandidates(cl);
            persistDiscoveryCandidates(pid, candidates);
            if (candidates.isEmpty()) {
                reportStatusWithRetry(pid, false, 0, "UNSUPPORTED", "No RF_CONFIG_WRITE candidate discovered", null);
                return;
            }
            int hookCount = Math.min(MAX_LEARNING_HOOKS, candidates.size());
            installTargets.addAll(candidates.subList(0, hookCount));
            // The highest static/runtime-family score owns mutation during learning. Lower
            // wrapper candidates are observation-only so they cannot pollute the payload
            // before the deeper Native target receives it.
            activeTarget = installTargets.get(0);
            profileStore.save(app, activeTarget, "LEARNING");
            Log.i(TAG, "LEARNING installCount=" + hookCount + " mutationOwner=" + activeTarget + " pid=" + pid);
        }

        int installed = 0;
        for (HookTarget target : installTargets) {
            try {
                installRfHook(cl, pid, target);
                installed++;
            } catch (Throwable t) {
                Log.e(TAG, "HOOK CANDIDATE FAILED target=" + target + " " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            }
        }
        installedHookCount = installed;
        if (installed == 0) {
            reportStatusWithRetry(pid, false, 0, "HOOK_FAILED", "All RF_CONFIG_WRITE candidates failed to install", null);
            return;
        }

        reportStatusWithRetry(pid, true, installed, "READY",
                learningMode ? "RF_CONFIG_WRITE learning mode; highest-score target owns mutation" : "RF_CONFIG_WRITE verified profile ready",
                activeTarget == null ? null : activeTarget.fingerprint());
        startCommandBridge(app, pid);
    }

    private void installRfHook(ClassLoader cl, int pid, HookTarget target) throws Exception {
        Method method = target.resolve(cl);
        hook(method).intercept(chain -> {
            Object[] args = chain.getArgs().toArray();
            if (args.length != 1 || !(args[0] instanceof byte[])) return chain.proceed();
            byte[] original = (byte[]) args[0];
            int payloadScore = payloadEngine.inspectScore(original);
            if (payloadScore <= 0) return chain.proceed();

            HookTarget owner = activeTarget;
            if (owner != null && !owner.fingerprint().equals(target.fingerprint())) {
                if (currentConfig().diagnostics) {
                    Log.i(TAG, "RFPROBE PASSIVE target=" + target.fingerprint() + " owner=" + owner.fingerprint() +
                            " payloadScore=" + payloadScore + " pid=" + pid);
                }
                return chain.proceed();
            }

            SimConfig cfg = currentConfig();
            if (cfg.diagnostics) {
                String caller = compactCallStack(24);
                Log.i(TAG, "RFPROBE ACTIVE target=" + target.fingerprint() + " targetScore=" + target.score +
                        " payloadScore=" + payloadScore + " pid=" + pid + " generation=" + cfg.generation + " stack=" + caller);
                persistRfCaller(pid, caller);
            }

            if (!cfg.active) {
                // Reversible case only: APPLY replaced an existing 0x33, so replaying the
                // exact pre-APPLY payload really writes the previous LA_NFCID1 value back.
                if (cfg.initialized && "STOP".equals(cfg.commandAction) && !isGenerationCompleted(cfg.generation, pid)) {
                    byte[] stock = reversibleStockPayload;
                    if (stock != null && target.fingerprint().equals(reversibleTargetFingerprint)) {
                        Object result = chain.proceed(new Object[]{stock.clone()});
                        if (nativeOk(result)) {
                            clearRestoreState();
                            completeCommand(cfg, "RF_STOCK_RESTORED", "", "explicit-stock-nfcid1|" + target.fingerprint(),
                                    String.valueOf(result), "Original LA_NFCID1 explicitly restored by verified RF_CONFIG_WRITE target");
                        } else {
                            failCommand(cfg, "RF_STOCK_FAILED", "", "Native rejected explicit stock LA_NFCID1 restore", String.valueOf(result));
                        }
                        return result;
                    }
                }
                // APPENDED_LA_NFCID1 cannot be undone by replaying a payload that omits 0x33.
                // The command thread performs a controller close/init lifecycle instead.
                return chain.proceed();
            }

            if (cfg.uid == null) return chain.proceed();
            String uidHex = normalizeUid(cfg.uid);
            if (uidHex.length() != 8) {
                failCommand(cfg, "UID_INVALID", uidHex, "UID must be 4 bytes", "");
                return chain.proceed();
            }

            if (disabledAfterFailure) {
                boolean same = disabledFailureGeneration == cfg.generation && uidHex.equals(disabledFailureUid);
                if (same) return chain.proceed();
                disabledAfterFailure = false;
                disabledFailureUid = null;
                disabledFailureGeneration = Long.MIN_VALUE;
            }

            RewriteResult rewritten = payloadEngine.rewrite(original, hexToBytes(uidHex));
            if (!rewritten.changed) {
                writeRfProgress(cfg, "WAITING", uidHex, target.methodName + ": " + rewritten.reason, "", rewritten.codecId);
                return chain.proceed();
            }

            activeCodec = rewritten.codecId;
            writeRfProgress(cfg, "APPLYING", uidHex, rewritten.reason, "pending", rewritten.codecId);
            Log.i(TAG, "NFCID1 APPLY target=" + target.fingerprint() + " codec=" + rewritten.codecId +
                    " reason=" + rewritten.reason + " pid=" + pid + " generation=" + cfg.generation + " uid=" + uidHex);

            Object result = chain.proceed(new Object[]{rewritten.data});
            if (nativeOk(result)) {
                disabledAfterFailure = false;
                disabledFailureUid = null;
                disabledFailureGeneration = Long.MIN_VALUE;

                boolean replacedExisting = rewritten.reason != null && rewritten.reason.contains("REPLACED_EXISTING_LA_NFCID1");
                synchronized (this) {
                    if (learningMode) {
                        markTargetVerified(target);
                        learningMode = false;
                    }
                    if (replacedExisting) {
                        reversibleStockPayload = original.clone();
                        reversibleTargetFingerprint = target.fingerprint();
                        reversibleCapturedGeneration = cfg.generation;
                        controllerReinitRequired = false;
                        restoreMode = "EXPLICIT_LA_NFCID1";
                    } else {
                        reversibleStockPayload = null;
                        reversibleTargetFingerprint = null;
                        reversibleCapturedGeneration = Long.MIN_VALUE;
                        controllerReinitRequired = true;
                        restoreMode = "CONTROLLER_REINIT";
                    }
                }
                persistRestoreState();
                if (!isGenerationCompleted(cfg.generation, pid)) {
                    completeCommand(cfg, "RF_UID_APPLIED", uidHex, rewritten.codecId, String.valueOf(result),
                            "UID applied by verified target " + target.className + "#" + target.methodName +
                                    "; stopMode=" + restoreMode);
                }
            } else if (!isGenerationCompleted(cfg.generation, pid)) {
                disabledAfterFailure = true;
                disabledFailureUid = uidHex;
                disabledFailureGeneration = cfg.generation;
                failCommand(cfg, "RF_UID_FAILED", uidHex,
                        "native rejected payload from " + rewritten.codecId + "; retry allowed on next generation",
                        String.valueOf(result));
            }
            return result;
        });
    }

    private void startCommandBridge(Application app, int pid) {
        try {
            ContentObserver observer = new ContentObserver(null) {
                @Override public void onChange(boolean selfChange) { scheduleCommandRefresh("provider_change"); }
                @Override public void onChange(boolean selfChange, Uri uri) { scheduleCommandRefresh("provider_change"); }
            };
            app.getContentResolver().registerContentObserver(CONFIG_URI, true, observer);
            commandObserver = observer;
            observerRegistered = true;
            refreshConfigAndProcess("startup");
        } catch (Throwable t) {
            writeSimpleCommandState("OBSERVER_FAILED", "Cannot register ConfigProvider observer: " + t.getMessage(), 0L, "", false);
        }
    }

    private void scheduleCommandRefresh(String reason) {
        if (observerRegistered) commandExecutor.execute(() -> refreshConfigAndProcess(reason));
    }

    private void refreshConfigAndProcess(String reason) {
        SimConfig cfg = readConfig();
        if (!cfg.initialized) return;
        cachedConfig = cfg;
        int pid = Process.myPid();
        if (isGenerationCompleted(cfg.generation, pid) || cfg.generation == lastTriggeredGeneration) return;
        lastTriggeredGeneration = cfg.generation;

        String action = cfg.active ? "APPLY" : "STOP";
        writeSimpleCommandState("RUNNING", "Executing " + action + " inside com.android.nfc", cfg.generation, action, false);
        Log.i(TAG, "COMMAND " + action + " reason=" + reason + " generation=" + cfg.generation + " uid=" + cfg.uid + " pid=" + pid +
                " restoreMode=" + restoreMode);

        if (!cfg.active && controllerReinitRequired) {
            // First tell the OEM stack that share mode is off. Its callback is allowed to run,
            // but it is not considered restoration proof. Then force an actual controller
            // close/init so the previously appended LA_NFCID1 disappears from controller state.
            NfcProcessVendorController.Result stopTrigger = vendorController.setShareMode(false);
            writeRfProgress(cfg, "RESETTING_CONTROLLER", "",
                    stopTrigger.detail + "; reinitializing NFC controller because LA_NFCID1 was appended", "", "controller-lifecycle");
            NfcProcessVendorController.Result reset = vendorController.reinitializeController();
            if (reset.success) {
                clearRestoreState();
                completeControllerReinit(cfg, reset.detail);
            } else {
                failCommand(cfg, "RF_CONTROLLER_RESET_FAILED", "", reset.stage + ": " + reset.detail, "");
            }
            return;
        }

        NfcProcessVendorController.Result trigger = vendorController.setShareMode(cfg.active);
        if (isGenerationCompleted(cfg.generation, pid)) return;
        if (trigger.success) {
            writeSimpleCommandState("TRIGGERED", trigger.detail + "; waiting for RF confirmation", cfg.generation, action, false);
        } else {
            writeSimpleCommandState("TRIGGER_FAILED", trigger.stage + ": " + trigger.detail, cfg.generation, action, false);
        }
    }

    private synchronized void clearRestoreState() {
        reversibleStockPayload = null;
        reversibleTargetFingerprint = null;
        reversibleCapturedGeneration = Long.MIN_VALUE;
        controllerReinitRequired = false;
        restoreMode = "NONE";
        persistRestoreState();
    }

    private void persistRestoreState() {
        ContentValues v = new ContentValues();
        v.put("rf_restore_mode", restoreMode);
        v.put("rf_controller_reinit_required", controllerReinitRequired);
        v.put("stock_snapshot_available", reversibleStockPayload != null);
        v.put("stock_snapshot_generation", reversibleCapturedGeneration == Long.MIN_VALUE ? 0L : reversibleCapturedGeneration);
        v.put("stock_snapshot_target", reversibleTargetFingerprint == null ? "" : reversibleTargetFingerprint);
        writeValuesWithRetry(v, 8, 75L);
    }

    private boolean isGenerationCompleted(long generation, int pid) {
        return completedPid == pid && completedGeneration == generation;
    }

    private SimConfig currentConfig() {
        SimConfig cfg = cachedConfig;
        if (cfg.initialized) return cfg;
        cfg = readConfig();
        if (cfg.initialized) cachedConfig = cfg;
        return cfg;
    }

    private Application waitForApplication(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        Application app;
        while ((app = currentApplication()) == null && System.currentTimeMillis() < end) sleep(100L);
        return app;
    }

    private String compactCallStack(int maxFrames) {
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String c = e.getClassName();
            if (c.equals(Thread.class.getName()) || c.equals(NfcInjectionModule.class.getName()) || c.startsWith("java.lang.Thread")) continue;
            if (kept++ > 0) sb.append(" <- ");
            sb.append(c).append('#').append(e.getMethodName()).append(':').append(e.getLineNumber());
            if (kept >= maxFrames) break;
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    private void persistRfCaller(int pid, String caller) {
        ContentValues v = new ContentValues();
        v.put("rf_caller", caller.length() > 3500 ? caller.substring(0, 3500) : caller);
        v.put("rf_caller_pid", pid);
        writeValuesWithRetry(v, 8, 100L);
    }

    private void persistDiscoveryCandidates(int pid, List<HookTarget> candidates) {
        ContentValues v = new ContentValues();
        v.put("rf_hook_candidates", candidateReport(candidates, 6000));
        v.put("rf_hook_candidate_count", candidates.size());
        v.put("rf_hook_discovery_pid", pid);
        v.put("profile_status", "LEARNING");
        if (!candidates.isEmpty()) putTarget(v, candidates.get(0));
        writeValuesWithRetry(v, 20, 100L);
    }

    private void persistTriggerCandidates(int pid, List<HookTarget> candidates) {
        ContentValues v = new ContentValues();
        v.put("refresh_probe_candidates", candidateReport(candidates, 6000));
        v.put("refresh_probe_count", candidates.size());
        v.put("refresh_probe_pid", pid);
        writeValuesWithRetry(v, 20, 100L);
    }

    private String candidateReport(List<HookTarget> candidates, int max) {
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) report.append(" | ");
            report.append('#').append(i + 1).append(' ').append(candidates.get(i));
        }
        String text = report.toString();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private void putTarget(ContentValues v, HookTarget t) {
        v.put("rf_hook_class", t.className);
        v.put("rf_hook_method", t.methodName);
        v.put("rf_hook_param_signature", t.parameterSignature);
        v.put("rf_hook_return_type", t.returnType);
        v.put("rf_hook_signature", t.parameterSignature + "->" + t.returnType);
        v.put("rf_hook_score", t.score);
        v.put("rf_hook_source", t.source);
        v.put("rf_hook_fingerprint", t.fingerprint());
    }

    private void persistProfileStatus(String status, HookTarget target) {
        ContentValues v = new ContentValues();
        v.put("profile_status", status);
        v.put("profile_hook_build", HOOK_BUILD);
        v.put("profile_schema", 3);
        if (target != null) putTarget(v, target);
        writeValuesWithRetry(v, 8, 75L);
    }

    private void markTargetVerified(HookTarget target) {
        activeTarget = target;
        Application app = currentApplication();
        if (app != null) profileStore.save(app, target, "VERIFIED");
        persistProfileStatus("VERIFIED", target);
    }

    private void reportStatusWithRetry(int pid, boolean ready, int count, String stage, String summary, String targetId) {
        ContentValues v = new ContentValues();
        v.put("hook_build", HOOK_BUILD);
        v.put("scope_ok", true);
        v.put("scope_process", "com.android.nfc");
        v.put("scope_pid", pid);
        v.put("hook_installed", ready);
        v.put("hook_class", "NfcInjectionModule");
        v.put("hook_count", count);
        v.put("hook_pid", pid);
        v.put("full_diag_stage", stage);
        v.put("full_diag_summary", summary);
        if (targetId != null) v.put("rf_hook_fingerprint", targetId);
        writeValuesWithRetry(v, 20, 150L);
    }

    private void writeRfProgress(SimConfig cfg, String state, String uid, String detail, String result, String codecId) {
        ContentValues v = baseHookState();
        v.put("rf_status", state);
        v.put("rf_uid", uid == null ? "" : uid);
        v.put("rf_source", codecId == null ? "" : codecId);
        v.put("rf_result", result == null ? "" : result);
        v.put("rf_error", state.contains("FAILED") ? detail : "");
        v.put("rf_pid", Process.myPid());
        v.put("rf_generation", cfg.generation);
        v.put("rf_verification", state.equals("APPLYING") ? "CONFIG_WRITE_PENDING" : "LIFECYCLE_PENDING");
        v.put("full_diag_stage", state);
        v.put("full_diag_summary", detail == null ? "" : detail);
        writeValuesWithRetry(v, 20, 100L);
    }

    private void completeCommand(SimConfig cfg, String rfState, String uid, String source, String result, String detail) {
        completeCommandWithVerification(cfg, rfState, uid, source, result, detail, "NATIVE_RESULT");
    }

    private void completeControllerReinit(SimConfig cfg, String detail) {
        completeCommandWithVerification(cfg, "RF_STOCK_RESTORED_BY_RESTART", "", "controller-reinit", "",
                detail + "; appended LA_NFCID1 cleared by NFC controller close/init", "PROCESS_RESTART");
    }

    private void completeCommandWithVerification(SimConfig cfg, String rfState, String uid, String source,
                                                 String result, String detail, String verification) {
        int pid = Process.myPid();
        completedGeneration = cfg.generation;
        completedPid = pid;
        ContentValues v = baseHookState();
        v.put("rf_status", rfState);
        v.put("rf_uid", uid == null ? "" : uid);
        v.put("rf_source", source == null ? "" : source);
        v.put("rf_result", result == null ? "" : result);
        v.put("rf_error", "");
        v.put("rf_pid", pid);
        v.put("rf_generation", cfg.generation);
        v.put("rf_verification", verification);
        v.put("command_handled_generation", cfg.generation);
        v.put("command_action", cfg.active ? "APPLY" : "STOP");
        v.put("command_status", "SUCCESS");
        v.put("command_detail", detail == null ? "" : detail);
        v.put("command_pid", pid);
        v.put("full_diag_stage", rfState);
        v.put("full_diag_summary", detail == null ? "" : detail);
        writeValuesWithRetry(v, 20, 100L);
    }

    private void failCommand(SimConfig cfg, String rfState, String uid, String detail, String result) {
        int pid = Process.myPid();
        completedGeneration = cfg.generation;
        completedPid = pid;
        ContentValues v = baseHookState();
        v.put("rf_status", rfState);
        v.put("rf_uid", uid == null ? "" : uid);
        v.put("rf_source", activeCodec == null ? "" : activeCodec);
        v.put("rf_result", result == null ? "" : result);
        v.put("rf_error", detail == null ? "" : detail);
        v.put("rf_pid", pid);
        v.put("rf_generation", cfg.generation);
        v.put("rf_verification", "FAILED");
        v.put("command_handled_generation", cfg.generation);
        v.put("command_action", cfg.active ? "APPLY" : "STOP");
        v.put("command_status", "FAILED");
        v.put("command_detail", detail == null ? "" : detail);
        v.put("command_pid", pid);
        v.put("full_diag_stage", rfState);
        v.put("full_diag_summary", detail == null ? "" : detail);
        writeValuesWithRetry(v, 20, 100L);
    }

    private ContentValues baseHookState() {
        int pid = Process.myPid();
        ContentValues v = new ContentValues();
        v.put("hook_build", HOOK_BUILD);
        v.put("scope_ok", true);
        v.put("scope_process", "com.android.nfc");
        v.put("scope_pid", pid);
        v.put("hook_installed", installedHookCount > 0);
        v.put("hook_class", "NfcInjectionModule");
        v.put("hook_count", installedHookCount);
        v.put("hook_pid", pid);
        if (activeTarget != null) putTarget(v, activeTarget);
        v.put("rf_restore_mode", restoreMode);
        v.put("rf_controller_reinit_required", controllerReinitRequired);
        v.put("stock_snapshot_available", reversibleStockPayload != null);
        v.put("stock_snapshot_generation", reversibleCapturedGeneration == Long.MIN_VALUE ? 0L : reversibleCapturedGeneration);
        v.put("stock_snapshot_target", reversibleTargetFingerprint == null ? "" : reversibleTargetFingerprint);
        return v;
    }

    private void writeSimpleCommandState(String status, String detail, long generation, String action, boolean handled) {
        ContentValues v = baseHookState();
        v.put("command_status", status);
        v.put("command_detail", detail == null ? "" : detail);
        v.put("command_pid", Process.myPid());
        v.put("command_action", action == null ? "" : action);
        if (handled) v.put("command_handled_generation", generation);
        writeValuesWithRetry(v, 20, 100L);
    }

    private void writeValuesWithRetry(ContentValues values, int attempts, long delayMs) {
        final ContentValues copy = new ContentValues(values);
        stateSyncExecutor.execute(() -> {
            for (int i = 0; i < attempts; i++) {
                Application app = currentApplication();
                if (app != null) {
                    try { app.getContentResolver().insert(CONFIG_URI, copy); return; }
                    catch (Throwable e) { Log.w(TAG, "status write attempt " + (i + 1) + " failed: " + e.getMessage()); }
                }
                sleep(delayMs);
            }
        });
    }

    private SimConfig readConfig() {
        Application app = currentApplication();
        if (app == null) return SimConfig.uninitialized();
        boolean active = false, diagnostics = false;
        String uid = null, action = "", status = "";
        long generation = 0L, handled = Long.MIN_VALUE;
        int commandPid = 0;
        try (Cursor c = app.getContentResolver().query(CONFIG_URI, null, null, null, null)) {
            if (c == null) return SimConfig.uninitialized();
            while (c.moveToNext()) {
                String key = c.getString(0), value = c.getString(1);
                if ("simulation_enabled".equals(key)) active = Boolean.parseBoolean(value);
                else if ("uid".equals(key)) uid = value;
                else if ("diagnostic_logging_enabled".equals(key)) diagnostics = Boolean.parseBoolean(value);
                else if ("command_generation".equals(key)) generation = parseLong(value, 0L);
                else if ("command_handled_generation".equals(key)) handled = parseLong(value, Long.MIN_VALUE);
                else if ("command_action".equals(key)) action = value == null ? "" : value;
                else if ("command_status".equals(key)) status = value == null ? "" : value;
                else if ("command_pid".equals(key)) commandPid = (int) parseLong(value, 0L);
            }
            if (action.isEmpty()) action = active ? "APPLY" : "STOP";
            return new SimConfig(true, active, uid, diagnostics, generation, handled, action, status, commandPid);
        } catch (Throwable t) {
            return SimConfig.uninitialized();
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            return (Application) m.invoke(null);
        } catch (Throwable ignored) { return null; }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name); t.setDaemon(true); return t;
    }
    private static String normalizeUid(String uid) {
        return uid == null ? "" : uid.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
    }
    private static boolean nativeOk(Object result) {
        return result instanceof Number && ((Number) result).intValue() == 0;
    }
    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Throwable ignored) { return fallback; }
    }
    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final class SimConfig {
        final boolean initialized, active, diagnostics;
        final String uid, commandAction, commandStatus;
        final long generation, handledGeneration;
        final int commandPid;
        SimConfig(boolean initialized, boolean active, String uid, boolean diagnostics, long generation,
                  long handledGeneration, String commandAction, String commandStatus, int commandPid) {
            this.initialized = initialized; this.active = active; this.uid = uid; this.diagnostics = diagnostics;
            this.generation = generation; this.handledGeneration = handledGeneration; this.commandAction = commandAction;
            this.commandStatus = commandStatus; this.commandPid = commandPid;
        }
        static SimConfig uninitialized() { return new SimConfig(false, false, null, false, 0L, Long.MIN_VALUE, "", "", 0); }
    }
}
