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
            // Trigger candidate discovery is diagnostic-only. Avoid the expensive DEX walk on
            // the verified-profile fast path; only refresh it when RF profile discovery is needed.
            persistTriggerCandidates(pid, discoveryEngine.discoverTriggerCandidates(cl));
            reportStatusWithRetry(pid, false, 0, "DISCOVERING", "Profile invalid/missing; learning deepest RF_CONFIG_WRITE", null);
            List<HookTarget> candidates = discoveryEngine.discoverRfCandidates(cl);
            persistDiscoveryCandidates(pid, candidates);
            if (candidates.isEmpty()) {
                reportStatusWithRetry(pid, false, 0, "UNSUPPORTED", "No RF_CONFIG_WRITE candidate discovered", null);
                return;
            }
            int hookCount = Math.min(MAX_LEARNING_HOOKS, candidates.size());
            installTargets.addAll(candidates.subList(0, hookCount));
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
                if (cfg.initialized && "STOP".equals(cfg.commandAction) && !isGenerationCompleted(cfg.generation, pid)) {
                    byte[] stock = reversibleStockPayload;
                    if (stock != null && target.fingerprint().equals(reversibleTargetFingerprint)) {
                        Object result = chain.proceed(new Object[]{stock.clone()});
                        NativeOutcome outcome = interpretNativeResult(method, result);
                        if (outcome.accepted) {
                            clearRestoreState();
                            completeCommand(cfg, "RF_STOCK_RESTORED", "", "explicit-stock-nfcid1|" + target.fingerprint(),
                                    outcome, "Original LA_NFCID1 explicitly restored by verified RF_CONFIG_WRITE target");
                        } else {
                            failCommand(cfg, "RF_STOCK_FAILED", "", "Native rejected explicit stock LA_NFCID1 restore", outcome);
                        }
                        return result;
                    }
                }
                return chain.proceed();
            }

            if (cfg.uid == null) return chain.proceed();
            String uidHex = normalizeUid(cfg.uid);
            if (uidHex.length() != 8) {
                failCommand(cfg, "UID_INVALID", uidHex, "UID must be 4 bytes", NativeOutcome.notInvoked());
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
                writeRfProgress(cfg, "WAITING", uidHex, target.methodName + ": " + rewritten.reason, rewritten.codecId);
                return chain.proceed();
            }

            activeCodec = rewritten.codecId;
            writeRfProgress(cfg, "APPLYING", uidHex, rewritten.reason, rewritten.codecId);
            Log.i(TAG, "NFCID1 APPLY target=" + target.fingerprint() + " codec=" + rewritten.codecId +
                    " reason=" + rewritten.reason + " pid=" + pid + " generation=" + cfg.generation + " uid=" + uidHex);

            Object result = chain.proceed(new Object[]{rewritten.data});
            NativeOutcome outcome = interpretNativeResult(method, result);
            if (outcome.accepted) {
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
                    completeCommand(cfg, "RF_UID_APPLIED", uidHex, rewritten.codecId, outcome,
                            "UID applied by verified target " + target.className + "#" + target.methodName +
                                    "; stopMode=" + restoreMode);
                }
            } else if (!isGenerationCompleted(cfg.generation, pid)) {
                disabledAfterFailure = true;
                disabledFailureUid = uidHex;
                disabledFailureGeneration = cfg.generation;
                failCommand(cfg, "RF_UID_FAILED", uidHex,
                        "native rejected payload from " + rewritten.codecId + "; retry allowed on next generation", outcome);
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
            NfcProcessVendorController.Result stopTrigger = vendorController.setShareMode(false);
            writeRfProgress(cfg, "STOPPING", "",
                    stopTrigger.detail + "; appended LA_NFCID1 requires NFC process/controller restart", "controller-lifecycle");
            // OxygenOS 16 rejects raw INfcAdapter disable/enable Binder calls from UID 1027 when
            // generated attribution/package identity is absent. Use the proven process lifecycle reset.
            failCommand(cfg, "RF_CONTROLLER_RESTART_REQUIRED", "",
                    "CONTROLLER_RESTART_REQUIRED: appended LA_NFCID1 cannot be deleted in-place; restart com.android.nfc to reinitialize controller",
                    NativeOutcome.notInvoked());
            return;
        }

        NfcProcessVendorController.Result trigger = vendorController.setShareMode(cfg.active);
        if (isGenerationCompleted(cfg.generation, pid)) return;
        if (trigger.success) {
            writeSimpleCommandState("TRIGGERED", trigger.detail + "; waiting for RF confirmation", cfg.generation, action, false);
        } else {
            writeSimpleCommandState("TRIGGER_FAILED", trigger.stage + ": " + trigger.detail, cfg.generation, action, false);
            writeSemanticState("FAILED", "UNKNOWN", "TRIGGER_FAILED", false, null);
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
        SimConfig cfg = cachedConfig;
        if (cfg.initialized && cfg.generation > 0L) v.put("state_generation", cfg.generation);
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
        v.put("runtime_pid", pid);
        v.put("full_diag_stage", stage);
        v.put("full_diag_summary", summary);
        if (targetId != null) v.put("rf_hook_fingerprint", targetId);
        writeValuesWithRetry(v, 20, 150L);
    }

    private void writeRfProgress(SimConfig cfg, String state, String uid, String detail, String codecId) {
        ContentValues v = baseHookState();
        v.put("state_generation", cfg.generation);
        v.put("rf_status", state);
        v.put("rf_uid", uid == null ? "" : uid);
        v.put("rf_source", codecId == null ? "" : codecId);
        v.put("rf_result", "");
        v.put("rf_native_result", "");
        v.put("rf_native_result_type", "");
        v.put("rf_accepted", false);
        v.put("rf_error", state.contains("FAILED") ? detail : "");
        v.put("rf_pid", Process.myPid());
        v.put("rf_generation", cfg.generation);
        v.put("rf_verification", state.equals("APPLYING") ? "CONFIG_WRITE_PENDING" : "LIFECYCLE_PENDING");
        v.put("operation_state", operationForRfState(state));
        v.put("effective_state", "UNKNOWN");
        v.put("verification_confidence", state.equals("APPLYING") ? "CONFIG_PENDING" : "LIFECYCLE_PENDING");
        v.put("full_diag_stage", state);
        v.put("full_diag_summary", detail == null ? "" : detail);
        writeValuesWithRetry(v, 20, 100L);
    }

    private void completeCommand(SimConfig cfg, String rfState, String uid, String source, NativeOutcome outcome, String detail) {
        completeCommandWithVerification(cfg, rfState, uid, source, outcome, detail, "NATIVE_RESULT");
    }

    private void completeControllerReinit(SimConfig cfg, String detail) {
        completeCommandWithVerification(cfg, "RF_STOCK_RESTORED_BY_RESTART", "", "controller-reinit",
                NativeOutcome.lifecycleAccepted("controller-reinit"),
                detail + "; appended LA_NFCID1 cleared by NFC controller close/init", "PROCESS_RESTART");
    }

    private void completeCommandWithVerification(SimConfig cfg, String rfState, String uid, String source,
                                                 NativeOutcome outcome, String detail, String verification) {
        int pid = Process.myPid();
        completedGeneration = cfg.generation;
        completedPid = pid;
        ContentValues v = baseHookState();
        v.put("state_generation", cfg.generation);
        v.put("rf_status", rfState);
        v.put("rf_uid", uid == null ? "" : uid);
        v.put("rf_source", source == null ? "" : source);
        v.put("rf_result", outcome.accepted ? "0" : "");
        v.put("rf_native_result", outcome.rawValue);
        v.put("rf_native_result_type", outcome.resultType);
        v.put("rf_accepted", outcome.accepted);
        v.put("rf_error", "");
        v.put("rf_pid", pid);
        v.put("rf_generation", cfg.generation);
        v.put("rf_verification", verification);
        v.put("operation_state", "IDLE");
        v.put("effective_state", cfg.active ? "ACTIVE" : "STOCK");
        v.put("verification_confidence", "VERIFIED");
        v.put("command_handled_generation", cfg.generation);
        v.put("command_action", cfg.active ? "APPLY" : "STOP");
        v.put("command_status", "SUCCESS");
        v.put("command_detail", detail == null ? "" : detail);
        v.put("command_pid", pid);
        v.put("full_diag_stage", rfState);
        v.put("full_diag_summary", detail == null ? "" : detail);
        writeValuesWithRetry(v, 20, 100L);
    }

    private void failCommand(SimConfig cfg, String rfState, String uid, String detail, NativeOutcome outcome) {
        int pid = Process.myPid();
        completedGeneration = cfg.generation;
        completedPid = pid;
        ContentValues v = baseHookState();
        v.put("state_generation", cfg.generation);
        v.put("rf_status", rfState);
        v.put("rf_uid", uid == null ? "" : uid);
        v.put("rf_source", activeCodec == null ? "" : activeCodec);
        v.put("rf_result", "");
        v.put("rf_native_result", outcome.rawValue);
        v.put("rf_native_result_type", outcome.resultType);
        v.put("rf_accepted", false);
        v.put("rf_error", detail == null ? "" : detail);
        v.put("rf_pid", pid);
        v.put("rf_generation", cfg.generation);
        v.put("rf_verification", "FAILED");
        v.put("operation_state", "FAILED");
        v.put("effective_state", "UNKNOWN");
        v.put("verification_confidence", "FAILED");
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
        v.put("runtime_pid", pid);
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
        if (generation > 0L) v.put("state_generation", generation);
        v.put("command_status", status);
        v.put("command_detail", detail == null ? "" : detail);
        v.put("command_pid", Process.myPid());
        v.put("command_action", action == null ? "" : action);
        if ("RUNNING".equals(status) || "TRIGGERED".equals(status)) {
            v.put("operation_state", "APPLY".equals(action) ? "APPLYING" : "STOPPING");
            v.put("verification_confidence", "PENDING");
        }
        if (handled) v.put("command_handled_generation", generation);
        writeValuesWithRetry(v, 20, 100L);
    }

    private void writeSemanticState(String operation, String effective, String confidence,
                                    boolean accepted, NativeOutcome outcome) {
        ContentValues v = baseHookState();
        SimConfig cfg = cachedConfig;
        if (cfg.initialized && cfg.generation > 0L) v.put("state_generation", cfg.generation);
        v.put("operation_state", operation);
        v.put("effective_state", effective);
        v.put("verification_confidence", confidence);
        v.put("rf_accepted", accepted);
        if (outcome != null) {
            v.put("rf_native_result", outcome.rawValue);
            v.put("rf_native_result_type", outcome.resultType);
        }
        writeValuesWithRetry(v, 8, 75L);
    }

    private static String operationForRfState(String state) {
        if ("APPLYING".equals(state) || "WAITING".equals(state)) return "APPLYING";
        if ("STOPPING".equals(state)) return "STOPPING";
        if (state != null && state.contains("FAILED")) return "FAILED";
        return "IDLE";
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

    private static NativeOutcome interpretNativeResult(Method method, Object result) {
        Class<?> type = method == null ? null : method.getReturnType();
        if (type == Void.TYPE) return new NativeOutcome(true, "null", "void");
        if (type == Boolean.TYPE || type == Boolean.class) {
            boolean ok = Boolean.TRUE.equals(result);
            return new NativeOutcome(ok, String.valueOf(result), "boolean");
        }
        if (result instanceof Number) {
            boolean ok = ((Number) result).intValue() == 0;
            return new NativeOutcome(ok, String.valueOf(result), type == null ? result.getClass().getName() : type.getName());
        }
        return new NativeOutcome(false, String.valueOf(result), type == null ? "unknown" : type.getName());
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

    private static final class NativeOutcome {
        final boolean accepted;
        final String rawValue;
        final String resultType;

        NativeOutcome(boolean accepted, String rawValue, String resultType) {
            this.accepted = accepted;
            this.rawValue = rawValue == null ? "" : rawValue;
            this.resultType = resultType == null ? "unknown" : resultType;
        }

        static NativeOutcome notInvoked() { return new NativeOutcome(false, "", "not-invoked"); }
        static NativeOutcome lifecycleAccepted(String source) { return new NativeOutcome(true, source, "lifecycle"); }
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
