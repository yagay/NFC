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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Capability-oriented, self-learning LSPosed NFC execution engine.
 *
 * Fast path: reuse one VERIFIED target when the system fingerprint/NFC package build and
 * method signature still match. OTA path: discover and temporarily hook a bounded set of
 * high-scoring RF_CONFIG_WRITE candidates. Only calls carrying a recognized RF payload
 * are touched. The candidate that accepts a rewritten payload with native result=0 is
 * persisted as the next VERIFIED profile.
 */
public class NfcInjectionModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";
    private static final int HOOK_BUILD = BuildConfig.HOOK_BUILD;
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings");
    private static final int MAX_LEARNING_HOOKS = 4;

    private final ExecutorService stateSyncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NfcUIDSim-StateSync");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NfcUIDSim-Command");
        t.setDaemon(true);
        return t;
    });

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
    private volatile boolean disabledAfterFailure;
    private volatile String disabledFailureUid;
    private volatile long disabledFailureGeneration = Long.MIN_VALUE;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        Log.i(TAG, "PROD MODULE loaded build=" + HOOK_BUILD + " process=" + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
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
            installTargets.add(cached);
            activeTarget = cached;
            persistProfileStatus("CACHED_VERIFIED", cached);
            Log.i(TAG, "PROFILE HIT target=" + cached + " pid=" + pid);
        } else {
            reportStatusWithRetry(pid, false, 0, "DISCOVERING", "Profile invalid/missing; discovering RF_CONFIG_WRITE", null);
            List<HookTarget> candidates = discoveryEngine.discoverRfCandidates(cl);
            persistDiscoveryCandidates(pid, candidates);
            if (candidates.isEmpty()) {
                Log.e(TAG, "RF_CONFIG_WRITE UNSUPPORTED pid=" + pid);
                reportStatusWithRetry(pid, false, 0, "UNSUPPORTED", "No RF_CONFIG_WRITE candidate discovered", null);
                return;
            }

            int hookCount = candidates.get(0).source.equals("known-family")
                    ? 1 : Math.min(MAX_LEARNING_HOOKS, candidates.size());
            installTargets.addAll(candidates.subList(0, hookCount));
            activeTarget = installTargets.get(0);
            profileStore.save(app, activeTarget, hookCount == 1 ? "DISCOVERED" : "LEARNING");
            Log.i(TAG, "DISCOVERY installCount=" + hookCount + " top=" + activeTarget + " pid=" + pid);
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

        if (installed == 0) {
            reportStatusWithRetry(pid, false, 0, "HOOK_FAILED", "All RF_CONFIG_WRITE candidates failed to install", null);
            return;
        }

        reportStatusWithRetry(pid, true, installed, "READY",
                installed == 1 ? "RF_CONFIG_WRITE profile ready" : "RF_CONFIG_WRITE learning mode hooks=" + installed,
                activeTarget == null ? null : activeTarget.fingerprint());
        Log.i(TAG, "PROD HOOK READY build=" + HOOK_BUILD + " hooks=" + installed + " pid=" + pid);
        startCommandBridge(app, pid);
    }

    private void installRfHook(ClassLoader cl, int pid, HookTarget target) throws Exception {
        Method method = target.resolve(cl);
        hook(method).intercept(chain -> {
            Object[] args = chain.getArgs().toArray();
            if (args.length != 1 || !(args[0] instanceof byte[])) return chain.proceed();
            byte[] original = (byte[]) args[0];

            // This is the safety gate for learning hooks: unrelated byte[] methods remain
            // observationally identical and cannot complete STOP or mutate APPLY state.
            int payloadScore = payloadEngine.inspectScore(original);
            if (payloadScore <= 0) return chain.proceed();

            SimConfig cfg = currentConfig();
            if (cfg.diagnostics) {
                String caller = compactCallStack(24);
                Log.i(TAG, "RFPROBE target=" + target.fingerprint() + " targetScore=" + target.score +
                        " payloadScore=" + payloadScore + " pid=" + pid + " generation=" + cfg.generation + " stack=" + caller);
                persistRfCaller(pid, caller);
            }

            if (!cfg.active) {
                Object result = chain.proceed();
                if (cfg.initialized && "STOP".equals(cfg.commandAction) && nativeOk(result)
                        && !isGenerationCompleted(cfg.generation, pid)) {
                    activeTarget = target;
                    markTargetVerified(target);
                    Log.i(TAG, "STOCK RF ACCEPTED target=" + target.fingerprint() + " pid=" + pid +
                            " generation=" + cfg.generation + " result=" + result);
                    completeCommand(cfg, "RF_STOCK_RESTORED", "", target.fingerprint(),
                            String.valueOf(result), "Stock RF accepted by verified RF_CONFIG_WRITE target");
                }
                return result;
            }

            if (cfg.uid == null) return chain.proceed();
            String uidHex = normalizeUid(cfg.uid);
            if (uidHex.length() != 8) {
                failCommand(cfg, "UID_INVALID", uidHex, "UID must be 4 bytes", "");
                return chain.proceed();
            }

            if (disabledAfterFailure) {
                boolean sameFailedAttempt = disabledFailureGeneration == cfg.generation
                        && disabledFailureUid != null && disabledFailureUid.equals(uidHex);
                if (sameFailedAttempt) return chain.proceed();
                disabledAfterFailure = false;
                disabledFailureUid = null;
                disabledFailureGeneration = Long.MIN_VALUE;
                Log.i(TAG, "NFCID1 RETRY unlocked generation=" + cfg.generation + " uid=" + uidHex + " pid=" + pid);
            }

            RewriteResult rewritten = payloadEngine.rewrite(original, hexToBytes(uidHex));
            if (!rewritten.changed) {
                writeRfProgress(cfg, "WAITING", uidHex,
                        target.methodName + ": " + rewritten.reason, "", rewritten.codecId);
                return chain.proceed();
            }

            activeCodec = rewritten.codecId;
            Log.i(TAG, "NFCID1 APPLY target=" + target.fingerprint() + " codec=" + rewritten.codecId +
                    " reason=" + rewritten.reason + " pid=" + pid + " generation=" + cfg.generation +
                    " uid=" + uidHex + " payload=" + rewritten.oldPayloadLength + "->" + rewritten.newPayloadLength +
                    " params=" + rewritten.oldParamCount + "->" + rewritten.newParamCount);
            writeRfProgress(cfg, "APPLYING", uidHex, rewritten.reason, "pending", rewritten.codecId);

            Object result = chain.proceed(new Object[]{rewritten.data});
            if (nativeOk(result)) {
                disabledAfterFailure = false;
                disabledFailureUid = null;
                disabledFailureGeneration = Long.MIN_VALUE;
                activeTarget = target;
                markTargetVerified(target);
                if (!isGenerationCompleted(cfg.generation, pid)) {
                    Log.i(TAG, "NFCID1 ACCEPTED target=" + target.fingerprint() + " codec=" + rewritten.codecId +
                            " pid=" + pid + " generation=" + cfg.generation + " uid=" + uidHex + " result=" + result);
                    completeCommand(cfg, "RF_UID_APPLIED", uidHex, rewritten.codecId,
                            String.valueOf(result), "UID applied by verified target " + target.className + "#" + target.methodName);
                }
            } else if (!isGenerationCompleted(cfg.generation, pid)) {
                disabledAfterFailure = true;
                disabledFailureUid = uidHex;
                disabledFailureGeneration = cfg.generation;
                Log.e(TAG, "NFCID1 FAILED target=" + target.fingerprint() + " codec=" + rewritten.codecId +
                        " pid=" + pid + " generation=" + cfg.generation + " uid=" + uidHex + " result=" + result);
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
            Log.i(TAG, "COMMAND BRIDGE ready pid=" + pid);
            refreshConfigAndProcess("startup");
        } catch (Throwable t) {
            Log.e(TAG, "COMMAND BRIDGE unavailable pid=" + pid + " " + t.getMessage());
            writeSimpleCommandState("OBSERVER_FAILED", "Cannot register ConfigProvider observer: " + t.getMessage(), 0L, "", false);
        }
    }

    private void scheduleCommandRefresh(String reason) {
        if (!observerRegistered) return;
        commandExecutor.execute(() -> refreshConfigAndProcess(reason));
    }

    private void refreshConfigAndProcess(String reason) {
        SimConfig cfg = readConfig();
        if (!cfg.initialized) return;
        cachedConfig = cfg;

        int pid = Process.myPid();
        if (isGenerationCompleted(cfg.generation, pid)) return;
        if (cfg.generation == lastTriggeredGeneration) return;
        lastTriggeredGeneration = cfg.generation;

        String action = cfg.active ? "APPLY" : "STOP";
        Log.i(TAG, "COMMAND " + action + " reason=" + reason + " generation=" + cfg.generation + " uid=" + cfg.uid + " pid=" + pid);
        writeSimpleCommandState("RUNNING", "Executing " + action + " inside com.android.nfc", cfg.generation, action, false);

        NfcProcessVendorController.Result trigger = vendorController.setShareMode(cfg.active);
        if (isGenerationCompleted(cfg.generation, pid)) {
            Log.i(TAG, "COMMAND terminal RF result already recorded generation=" + cfg.generation + " pid=" + pid);
            return;
        }

        if (trigger.success) {
            writeSimpleCommandState("TRIGGERED", trigger.detail + "; waiting for RF confirmation",
                    cfg.generation, action, false);
        } else {
            Log.e(TAG, "COMMAND trigger failed generation=" + cfg.generation + " stage=" + trigger.stage + " detail=" + trigger.detail);
            writeSimpleCommandState("TRIGGER_FAILED", trigger.stage + ": " + trigger.detail,
                    cfg.generation, action, false);
        }
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
            if (c.equals(Thread.class.getName()) || c.equals(NfcInjectionModule.class.getName())) continue;
            if (c.startsWith("java.lang.Thread")) continue;
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
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) report.append(" | ");
            report.append('#').append(i + 1).append(' ').append(candidates.get(i));
        }
        String text = report.toString();
        if (text.length() > 6000) text = text.substring(0, 6000);
        v.put("rf_hook_candidates", text);
        v.put("rf_hook_candidate_count", candidates.size());
        v.put("rf_hook_discovery_pid", pid);
        if (!candidates.isEmpty()) {
            HookTarget t = candidates.get(0);
            v.put("rf_hook_class", t.className);
            v.put("rf_hook_method", t.methodName);
            v.put("rf_hook_param_signature", t.parameterSignature);
            v.put("rf_hook_return_type", t.returnType);
            v.put("rf_hook_signature", t.parameterSignature + "->" + t.returnType);
            v.put("rf_hook_score", t.score);
            v.put("rf_hook_source", t.source);
            v.put("rf_hook_fingerprint", t.fingerprint());
            v.put("profile_status", "DISCOVERED");
        }
        writeValuesWithRetry(v, 20, 100L);
    }

    private void persistProfileStatus(String status, HookTarget target) {
        ContentValues v = new ContentValues();
        v.put("profile_status", status);
        if (target != null) v.put("rf_hook_fingerprint", target.fingerprint());
        writeValuesWithRetry(v, 8, 75L);
    }

    private void markTargetVerified(HookTarget target) {
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
        v.put("rf_error", state.endsWith("FAILED") || state.equals("UID_INVALID") ? detail : "");
        v.put("rf_pid", Process.myPid());
        v.put("rf_generation", cfg.generation);
        v.put("full_diag_stage", state);
        v.put("full_diag_summary", detail == null ? "" : detail);
        writeValuesWithRetry(v, 20, 100L);
    }

    private void completeCommand(SimConfig cfg, String rfState, String uid, String source, String result, String detail) {
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
        v.put("hook_installed", true);
        v.put("hook_class", "NfcInjectionModule");
        v.put("hook_count", 1);
        v.put("hook_pid", pid);
        if (activeTarget != null) v.put("rf_hook_fingerprint", activeTarget.fingerprint());
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
                    try {
                        app.getContentResolver().insert(CONFIG_URI, copy);
                        return;
                    } catch (Throwable e) {
                        Log.w(TAG, "status write attempt " + (i + 1) + " failed: " + e.getMessage());
                    }
                }
                sleep(delayMs);
            }
            Log.w(TAG, "status write gave up after " + attempts + " attempts");
        });
    }

    private SimConfig readConfig() {
        Application app = currentApplication();
        if (app == null) return SimConfig.uninitialized();

        boolean active = false;
        String uid = null;
        boolean diagnostics = false;
        long generation = 0L;
        long handledGeneration = Long.MIN_VALUE;
        String commandAction = "";
        String commandStatus = "";
        int commandPid = 0;

        try (Cursor c = app.getContentResolver().query(CONFIG_URI, null, null, null, null)) {
            if (c == null) return SimConfig.uninitialized();
            while (c.moveToNext()) {
                String key = c.getString(0);
                String value = c.getString(1);
                if ("simulation_enabled".equals(key)) active = Boolean.parseBoolean(value);
                else if ("uid".equals(key)) uid = value;
                else if ("diagnostic_logging_enabled".equals(key)) diagnostics = Boolean.parseBoolean(value);
                else if ("command_generation".equals(key)) generation = parseLong(value, 0L);
                else if ("command_handled_generation".equals(key)) handledGeneration = parseLong(value, Long.MIN_VALUE);
                else if ("command_action".equals(key)) commandAction = value == null ? "" : value;
                else if ("command_status".equals(key)) commandStatus = value == null ? "" : value;
                else if ("command_pid".equals(key)) commandPid = (int) parseLong(value, 0L);
            }
            if (commandAction.isEmpty()) commandAction = active ? "APPLY" : "STOP";
            return new SimConfig(true, active, uid, diagnostics, generation, handledGeneration,
                    commandAction, commandStatus, commandPid);
        } catch (Throwable t) {
            Log.w(TAG, "config read failed: " + t.getMessage());
            return SimConfig.uninitialized();
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            return (Application) m.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String normalizeUid(String uid) {
        return uid == null ? "" : uid.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
    }

    private static boolean nativeOk(Object result) {
        return result instanceof Number && ((Number) result).intValue() == 0;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); }
        catch (Throwable ignored) { return fallback; }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final class SimConfig {
        final boolean initialized;
        final boolean active;
        final String uid;
        final boolean diagnostics;
        final long generation;
        final long handledGeneration;
        final String commandAction;
        final String commandStatus;
        final int commandPid;

        SimConfig(boolean initialized, boolean active, String uid, boolean diagnostics,
                  long generation, long handledGeneration, String commandAction,
                  String commandStatus, int commandPid) {
            this.initialized = initialized;
            this.active = active;
            this.uid = uid;
            this.diagnostics = diagnostics;
            this.generation = generation;
            this.handledGeneration = handledGeneration;
            this.commandAction = commandAction;
            this.commandStatus = commandStatus;
            this.commandPid = commandPid;
        }

        static SimConfig uninitialized() {
            return new SimConfig(false, false, null, false, 0L, Long.MIN_VALUE, "", "", 0);
        }
    }
}
