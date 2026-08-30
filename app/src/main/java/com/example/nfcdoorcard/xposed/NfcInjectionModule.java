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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Capability-oriented LSPosed NFC execution engine.
 *
 * The UI publishes desired state to ConfigProvider. Inside com.android.nfc this module:
 *  1) discovers RF_CONFIG_WRITE by structural capability instead of one permanent name,
 *  2) hooks the best production target,
 *  3) classifies each byte[] payload independently,
 *  4) rewrites only a recognized/safe RF representation,
 *  5) treats native result=0 as runtime verification and persists the verified profile.
 */
public class NfcInjectionModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";
    private static final int HOOK_BUILD = BuildConfig.HOOK_BUILD;
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings");

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
        reportStatusWithRetry(pid, false, 0, "DISCOVERING", "Discovering RF_CONFIG_WRITE capability", null);

        List<HookTarget> candidates = discoveryEngine.discoverRfCandidates(cl);
        persistDiscoveryCandidates(pid, candidates);
        if (candidates.isEmpty()) {
            Log.e(TAG, "RF_CONFIG_WRITE UNSUPPORTED pid=" + pid);
            reportStatusWithRetry(pid, false, 0, "UNSUPPORTED", "No RF_CONFIG_WRITE candidate discovered", null);
            return;
        }

        HookTarget target = candidates.get(0);
        activeTarget = target;
        try {
            Method method = target.resolve(cl);
            hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length != 1 || !(args[0] instanceof byte[])) return chain.proceed();

                SimConfig cfg = currentConfig();
                if (!cfg.active) {
                    Object result = chain.proceed();
                    if (cfg.initialized && "STOP".equals(cfg.commandAction) && nativeOk(result)
                            && !isGenerationCompleted(cfg.generation, pid)) {
                        Log.i(TAG, "STOCK RF ACCEPTED target=" + target.fingerprint() + " pid=" + pid +
                                " generation=" + cfg.generation + " result=" + result);
                        markTargetVerified(target);
                        completeCommand(cfg, "RF_STOCK_RESTORED", "", target.fingerprint(),
                                String.valueOf(result), "Stock RF config accepted by verified RF_CONFIG_WRITE target");
                    }
                    return result;
                }

                if (cfg.uid == null) return chain.proceed();
                String uidHex = normalizeUid(cfg.uid);

                if (disabledAfterFailure) {
                    boolean sameFailedAttempt = disabledFailureGeneration == cfg.generation
                            && disabledFailureUid != null
                            && disabledFailureUid.equals(uidHex);
                    if (sameFailedAttempt) return chain.proceed();
                    disabledAfterFailure = false;
                    disabledFailureUid = null;
                    disabledFailureGeneration = Long.MIN_VALUE;
                    Log.i(TAG, "NFCID1 RETRY unlocked generation=" + cfg.generation + " uid=" + uidHex + " pid=" + pid);
                }

                if (cfg.diagnostics) {
                    String caller = compactCallStack(24);
                    Log.i(TAG, "RFPROBE target=" + target.fingerprint() + " pid=" + pid +
                            " generation=" + cfg.generation + " uid=" + uidHex + " stack=" + caller);
                    persistRfCaller(pid, caller);
                }

                if (uidHex.length() != 8) {
                    failCommand(cfg, "UID_INVALID", uidHex, "UID must be 4 bytes", "");
                    return chain.proceed();
                }

                RewriteResult rewritten = payloadEngine.rewrite((byte[]) args[0], hexToBytes(uidHex));
                if (!rewritten.changed) {
                    writeRfProgress(cfg, "WAITING", uidHex, rewritten.reason, "", rewritten.codecId);
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
                    markTargetVerified(target);
                    Log.i(TAG, "NFCID1 ACCEPTED target=" + target.fingerprint() + " codec=" + rewritten.codecId +
                            " pid=" + pid + " generation=" + cfg.generation + " uid=" + uidHex + " result=" + result);
                    completeCommand(cfg, "RF_UID_APPLIED", uidHex, rewritten.codecId,
                            String.valueOf(result), "UID applied by verified RF_CONFIG_WRITE target using " + rewritten.codecId);
                } else {
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

            reportStatusWithRetry(pid, true, 1, "READY",
                    "RF_CONFIG_WRITE target ready score=" + target.score + " source=" + target.source,
                    target.fingerprint());
            Log.i(TAG, "PROD HOOK READY build=" + HOOK_BUILD + " target=" + target + " pid=" + pid);
            startCommandBridge(pid);
        } catch (Throwable t) {
            Log.e(TAG, "PROD HOOK FAILED build=" + HOOK_BUILD + " target=" + target + " pid=" + pid + " " +
                    t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            reportStatusWithRetry(pid, false, 0, "HOOK_FAILED",
                    t.getClass().getSimpleName() + ": " + t.getMessage(), target.fingerprint());
        }
    }

    private void startCommandBridge(int pid) {
        commandExecutor.execute(() -> {
            for (int attempt = 0; attempt < 60 && !observerRegistered; attempt++) {
                Application app = currentApplication();
                if (app != null) {
                    try {
                        if (activeTarget != null) profileStore.save(app, activeTarget, "DISCOVERED");
                        ContentObserver observer = new ContentObserver(null) {
                            @Override public void onChange(boolean selfChange) { scheduleCommandRefresh("provider_change"); }
                            @Override public void onChange(boolean selfChange, Uri uri) { scheduleCommandRefresh("provider_change"); }
                        };
                        app.getContentResolver().registerContentObserver(CONFIG_URI, true, observer);
                        commandObserver = observer;
                        observerRegistered = true;
                        Log.i(TAG, "COMMAND BRIDGE ready pid=" + pid);
                        refreshConfigAndProcess("startup");
                        return;
                    } catch (Throwable t) {
                        Log.w(TAG, "command observer attempt=" + (attempt + 1) + " failed: " + t.getMessage());
                    }
                }
                sleep(150L);
            }
            Log.e(TAG, "COMMAND BRIDGE unavailable pid=" + pid);
            writeSimpleCommandState("OBSERVER_FAILED", "Cannot register ConfigProvider observer", 0L, "", false);
        });
    }

    private void scheduleCommandRefresh(String reason) {
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
            v.put("rf_hook_signature", t.parameterSignature + "->" + t.returnType);
            v.put("rf_hook_score", t.score);
            v.put("rf_hook_source", t.source);
            v.put("rf_hook_fingerprint", t.fingerprint());
            v.put("profile_status", "DISCOVERED");
        }
        writeValuesWithRetry(v, 20, 100L);
    }

    private void markTargetVerified(HookTarget target) {
        Application app = currentApplication();
        if (app != null) profileStore.save(app, target, "VERIFIED");
        ContentValues v = new ContentValues();
        v.put("profile_status", "VERIFIED");
        v.put("rf_hook_fingerprint", target.fingerprint());
        writeValuesWithRetry(v, 8, 75L);
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
