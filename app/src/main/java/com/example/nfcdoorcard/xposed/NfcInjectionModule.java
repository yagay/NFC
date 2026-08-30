package com.example.nfcdoorcard.xposed;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
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
    private static final int MAX_TRIGGER_HOOKS = 4;
    private static final long TRIGGER_RF_WINDOW_MS = 3_000L;
    private static final long LIFECYCLE_TRIGGER_RF_WINDOW_MS = 5_000L;
    private static final long LIFECYCLE_NATURAL_WAIT_MS = 1_200L;
    private static final long LIFECYCLE_TRIGGER_WAIT_MS = 1_600L;
    private static final long LIFECYCLE_TOTAL_TIMEOUT_MS = 9_000L;
    private static final int LIFECYCLE_MAX_TRIGGER_ATTEMPTS = 3;

    private final ExecutorService stateSyncExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "NfcUIDSim-StateSync"));
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "NfcUIDSim-Command"));
    private final ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "NfcUIDSim-Lifecycle"));
    private final HookDiscoveryEngine discoveryEngine = new HookDiscoveryEngine();
    private final RfPayloadEngine payloadEngine = new RfPayloadEngine();
    private final HookProfileStore profileStore = new HookProfileStore();
    private final NfcProcessVendorController vendorController = new NfcProcessVendorController();
    private final RefreshTriggerEngine refreshTriggerEngine = new RefreshTriggerEngine();

    private volatile HookTarget activeTarget;
    private volatile String activeCodec = "";
    private volatile SimConfig cachedConfig = SimConfig.uninitialized();
    private volatile ContentObserver commandObserver;
    private volatile BroadcastReceiver adapterStateReceiver;
    private volatile boolean observerRegistered;
    private volatile boolean lifecycleReapplyPending;
    private volatile long lifecycleRecoveryGeneration = Long.MIN_VALUE;
    private volatile long lifecycleRecoveryStartedAt;
    private volatile String earlyHookFingerprint = "";
    private volatile long lastTriggeredGeneration = Long.MIN_VALUE;
    private volatile long completedGeneration = Long.MIN_VALUE;
    private volatile int completedPid;
    private volatile int installedHookCount;
    private volatile boolean learningMode;
    private volatile boolean disabledAfterFailure;
    private volatile String disabledFailureUid;
    private volatile long disabledFailureGeneration = Long.MIN_VALUE;
    private volatile long pendingTriggerGeneration = Long.MIN_VALUE;
    private volatile long pendingTriggerStartedAt;
    private volatile long pendingTriggerWindowMs = TRIGGER_RF_WINDOW_MS;
    private volatile long confirmedTriggerGeneration = Long.MIN_VALUE;
    private volatile String pendingTriggerTarget = "";

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
        installEarlyKnownRfHook(cl, pid);
        commandExecutor.execute(() -> initializeRuntime(cl, pid));
    }

    /**
     * Install the highest-confidence known RF writer before waiting for the NFC Application object.
     * This lets us catch the controller's natural startup RF configuration instead of always trying
     * to manufacture a second refresh after boot. Payload inspection remains the final safety gate.
     */
    private void installEarlyKnownRfHook(ClassLoader cl, int pid) {
        try {
            List<HookTarget> known = discoveryEngine.discoverKnownRfCandidates(cl);
            if (known.isEmpty()) return;
            HookTarget target = known.get(0);
            activeTarget = target;
            installRfHook(cl, pid, target);
            earlyHookFingerprint = target.fingerprint();
            installedHookCount = 1;
            Log.i(TAG, "EARLY RF HOOK installed target=" + target + " pid=" + pid);
        } catch (Throwable t) {
            Log.w(TAG, "EARLY RF HOOK unavailable " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void initializeRuntime(ClassLoader cl, int pid) {
        reportStatusWithRetry(pid, installedHookCount > 0, installedHookCount,
                installedHookCount > 0 ? "EARLY_HOOK_READY" : "INITIALIZING",
                installedHookCount > 0 ? "Known RF_CONFIG_WRITE hook installed before NFC Application startup" : "Waiting for NFC application context",
                activeTarget == null ? null : activeTarget.fingerprint());
        Application app = waitForApplication(8_000L);
        if (app == null) {
            reportStatusWithRetry(pid, installedHookCount > 0, installedHookCount, "INIT_FAILED",
                    "NFC Application context unavailable", activeTarget == null ? null : activeTarget.fingerprint());
            return;
        }

        List<HookTarget> installTargets = new ArrayList<>();
        List<HookTarget> triggerTargets = discoveryEngine.discoverKnownTriggerCandidates(cl);
        if (triggerTargets.isEmpty()) triggerTargets = discoveryEngine.discoverTriggerCandidates(cl);
        persistTriggerCandidates(pid, triggerTargets);
        HookTarget cached = profileStore.loadValid(app, cl);
        if (cached != null) {
            learningMode = false;
            activeTarget = cached;
            if (!cached.fingerprint().equals(earlyHookFingerprint)) installTargets.add(cached);
            persistProfileStatus("CACHED_VERIFIED", cached);
            Log.i(TAG, "PROFILE HIT target=" + cached + " pid=" + pid + " early=" + earlyHookFingerprint);
        } else {
            learningMode = true;
            reportStatusWithRetry(pid, installedHookCount > 0, installedHookCount, "DISCOVERING",
                    "Profile invalid/missing; learning deepest RF_CONFIG_WRITE", null);
            List<HookTarget> candidates = discoveryEngine.discoverRfCandidates(cl);
            persistDiscoveryCandidates(pid, candidates);
            if (candidates.isEmpty() && installedHookCount == 0) {
                reportStatusWithRetry(pid, false, 0, "UNSUPPORTED", "No RF_CONFIG_WRITE candidate discovered", null);
                return;
            }
            if (!candidates.isEmpty()) {
                activeTarget = candidates.get(0);
                profileStore.save(app, activeTarget, "LEARNING");
                int added = 0;
                for (HookTarget candidate : candidates) {
                    if (candidate.fingerprint().equals(earlyHookFingerprint)) continue;
                    installTargets.add(candidate);
                    if (++added >= MAX_LEARNING_HOOKS - (installedHookCount > 0 ? 1 : 0)) break;
                }
                Log.i(TAG, "LEARNING additionalInstallCount=" + installTargets.size() + " mutationOwner=" + activeTarget + " pid=" + pid);
            }
        }

        int installed = installedHookCount;
        for (HookTarget target : installTargets) {
            try {
                installRfHook(cl, pid, target);
                installed++;
            } catch (Throwable t) {
                Log.e(TAG, "HOOK CANDIDATE FAILED target=" + target + " " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            }
        }
        installedHookCount = installed;
        int triggerHookCount = 0;
        for (int i = 0; i < Math.min(MAX_TRIGGER_HOOKS, triggerTargets.size()); i++) {
            HookTarget target = triggerTargets.get(i);
            try {
                installRefreshTriggerHook(cl, pid, target);
                triggerHookCount++;
            } catch (Throwable t) {
                Log.w(TAG, "REFRESH TRIGGER HOOK skipped target=" + target + " " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        persistRefreshRuntime("DISCOVERED", "", "hook-probe", 0L, false);
        Log.i(TAG, "REFRESH TRIGGER hooks=" + triggerHookCount + " candidates=" + triggerTargets.size() + " pid=" + pid);
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
            int payloadArg = findSingleByteArrayArg(args);
            if (payloadArg < 0) return chain.proceed();
            byte[] original = (byte[]) args[payloadArg];
            int payloadScore = payloadEngine.inspectScore(original);
            if (payloadScore <= 0) return chain.proceed();

            HookTarget owner = activeTarget;
            if (owner != null && !owner.fingerprint().equals(target.fingerprint())) {
                SimConfig passiveCfg = currentConfig();
                if (passiveCfg.diagnostics) {
                    Log.i(TAG, "RFPROBE PASSIVE target=" + target.fingerprint() + " owner=" + owner.fingerprint() +
                            " payloadScore=" + payloadScore + " pid=" + pid);
                }
                return chain.proceed();
            }

            SimConfig cfg = currentConfig();
            if (!cfg.initialized) return chain.proceed();

            // A natural RF write in a new NFC process is the best lifecycle recovery trigger. Mark
            // it as a lifecycle reapply before mutating so a successful native result updates only
            // observed RF evidence; the original user APPLY command history remains untouched.
            boolean persistedApplyFromOldProcess = cfg.active && cfg.generation > 0L &&
                    cfg.handledGeneration == cfg.generation && "SUCCESS".equals(cfg.commandStatus) &&
                    cfg.commandPid > 0 && cfg.commandPid != pid;
            if (persistedApplyFromOldProcess && !isLifecycleVerified(cfg.generation, pid, cfg.uid)) {
                synchronized (this) {
                    lifecycleReapplyPending = true;
                    lifecycleRecoveryGeneration = cfg.generation;
                    if (lifecycleRecoveryStartedAt == 0L) lifecycleRecoveryStartedAt = System.currentTimeMillis();
                }
            }

            markRfObservedForPendingTrigger(cfg, target, pid);
            if (cfg.diagnostics) {
                String caller = compactCallStack(24);
                Log.i(TAG, "RFPROBE ACTIVE target=" + target.fingerprint() + " targetScore=" + target.score +
                        " payloadScore=" + payloadScore + " pid=" + pid + " generation=" + cfg.generation + " stack=" + caller);
                persistRfCaller(pid, caller);
            }

            if (!cfg.active) {
                if ("STOP".equals(cfg.commandAction) && !isGenerationCompleted(cfg.generation, pid)) {
                    byte[] stock = reversibleStockPayload;
                    if (stock != null && target.fingerprint().equals(reversibleTargetFingerprint)) {
                        Object[] stockArgs = args.clone();
                        stockArgs[payloadArg] = stock.clone();
                        Object result = chain.proceed(stockArgs);
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
                    " reason=" + rewritten.reason + " pid=" + pid + " generation=" + cfg.generation + " uid=" + uidHex +
                    " lifecycle=" + lifecycleReapplyPending);

            Object[] rewrittenArgs = args.clone();
            rewrittenArgs[payloadArg] = rewritten.data;
            Object result = chain.proceed(rewrittenArgs);
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
                if (lifecycleReapplyPending) {
                    completeLifecycleReapply(cfg, uidHex, rewritten.codecId, outcome, target);
                    finishLifecycleRecovery(cfg.generation);
                } else if (!isGenerationCompleted(cfg.generation, pid)) {
                    completeCommand(cfg, "RF_UID_APPLIED", uidHex, rewritten.codecId, outcome,
                            "UID applied by verified target " + target.className + "#" + target.methodName +
                                    "; stopMode=" + restoreMode);
                }
            } else if (lifecycleReapplyPending) {
                publishLifecycleFailure(cfg, uidHex, "Native rejected lifecycle RF payload from " + rewritten.codecId, outcome);
                finishLifecycleRecovery(cfg.generation);
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

    private void installRefreshTriggerHook(ClassLoader cl, int pid, HookTarget target) throws Exception {
        Method method = target.resolve(cl);
        hook(method).intercept(chain -> {
            Object receiver = chain.getThisObject();
            Object result = chain.proceed();
            if (refreshTriggerEngine.observe(target, method, receiver, result)) {
                persistRefreshRuntime("VERIFIED_INSTANCE", target.fingerprint(), "java-observed", 0L, false);
                Log.i(TAG, "REFRESH TRIGGER VERIFIED target=" + target.fingerprint() + " pid=" + pid);
            }
            return result;
        });
    }

    private NfcProcessVendorController.Result triggerRfRefresh(SimConfig cfg, boolean enabled, String reason) {
        return triggerRfRefresh(cfg, enabled, reason, TRIGGER_RF_WINDOW_MS);
    }

    private NfcProcessVendorController.Result triggerRfRefresh(SimConfig cfg, boolean enabled, String reason, long windowMs) {
        armTriggerWindow(cfg.generation, reason, windowMs);
        RefreshTriggerEngine.Invocation javaTrigger = refreshTriggerEngine.invoke(enabled);
        if (javaTrigger.success) {
            pendingTriggerTarget = javaTrigger.targetFingerprint;
            persistRefreshRuntime("TRIGGERED", javaTrigger.targetFingerprint, "java-verified", cfg.generation, false);
            Log.i(TAG, "REFRESH TRIGGER java success generation=" + cfg.generation + " target=" + javaTrigger.targetFingerprint);
            return new NfcProcessVendorController.Result(true, "JAVA_TRIGGERED", javaTrigger.detail, null);
        }

        NfcProcessVendorController.Result fallback = vendorController.setShareMode(enabled);
        String fallbackTarget = "vendor-controller:" + fallback.stage;
        pendingTriggerTarget = fallbackTarget;
        persistRefreshRuntime(fallback.success ? "TRIGGERED" : "TRIGGER_FAILED", fallbackTarget,
                "vendor-fallback", cfg.generation, false);
        if (!fallback.success) clearTriggerWindow(cfg.generation);
        return fallback;
    }

    private void armTriggerWindow(long generation, String source, long windowMs) {
        pendingTriggerGeneration = generation;
        pendingTriggerStartedAt = System.currentTimeMillis();
        pendingTriggerWindowMs = Math.max(TRIGGER_RF_WINDOW_MS, windowMs);
        confirmedTriggerGeneration = Long.MIN_VALUE;
        pendingTriggerTarget = source == null ? "" : source;
        persistRefreshRuntime("ARMED", pendingTriggerTarget, "command", generation, false);
    }

    private void markRfObservedForPendingTrigger(SimConfig cfg, HookTarget rfTarget, int pid) {
        long generation = pendingTriggerGeneration;
        if (!cfg.initialized || generation <= 0L || cfg.generation != generation) return;
        long elapsed = System.currentTimeMillis() - pendingTriggerStartedAt;
        if (elapsed < 0L || elapsed > pendingTriggerWindowMs) return;
        if (confirmedTriggerGeneration == generation) return;
        confirmedTriggerGeneration = generation;
        persistRefreshRuntime("RF_WRITE_OBSERVED", pendingTriggerTarget, "causal-window", generation, true);
        Log.i(TAG, "REFRESH TRIGGER RF confirmed generation=" + generation + " elapsedMs=" + elapsed +
                " trigger=" + pendingTriggerTarget + " rfTarget=" + rfTarget.fingerprint() + " pid=" + pid);
    }

    private void clearTriggerWindow(long generation) {
        if (pendingTriggerGeneration != generation) return;
        pendingTriggerGeneration = Long.MIN_VALUE;
        pendingTriggerStartedAt = 0L;
        pendingTriggerWindowMs = TRIGGER_RF_WINDOW_MS;
        pendingTriggerTarget = "";
    }

    private void persistRefreshRuntime(String status, String target, String source, long generation, boolean rfConfirmed) {
        ContentValues v = baseHookState();
        if (generation > 0L) v.put("state_generation", generation);
        v.put("refresh_trigger_status", status == null ? "" : status);
        v.put("refresh_trigger_target", target == null ? "" : target);
        v.put("refresh_trigger_source", source == null ? "" : source);
        v.put("refresh_trigger_generation", generation);
        v.put("refresh_trigger_rf_confirmed", rfConfirmed);
        writeValuesWithRetry(v, 8, 75L);
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
            registerAdapterStateReceiver(app);
            refreshConfigAndProcess("startup");
        } catch (Throwable t) {
            writeSimpleCommandState("OBSERVER_FAILED", "Cannot register ConfigProvider observer: " + t.getMessage(), 0L, "", false);
        }
    }

    private void registerAdapterStateReceiver(Application app) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null || !"android.nfc.action.ADAPTER_STATE_CHANGED".equals(intent.getAction())) return;
                int state = intent.getIntExtra("android.nfc.extra.ADAPTER_STATE", -1);
                if (state == 3) {
                    scheduleLifecycleRecovery("adapter_state_on");
                } else if (state == 1 || state == 4) {
                    finishLifecycleRecovery(lifecycleRecoveryGeneration);
                }
            }
        };
        IntentFilter filter = new IntentFilter("android.nfc.action.ADAPTER_STATE_CHANGED");
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(receiver, filter);
        adapterStateReceiver = receiver;
        Log.i(TAG, "ADAPTER STATE receiver registered pid=" + Process.myPid());
    }

    private void scheduleLifecycleRecovery(String reason) {
        lifecycleExecutor.execute(() -> runLifecycleRecovery(reason));
    }

    /**
     * Closed-loop lifecycle APPLY recovery. A trigger returning true is never treated as success;
     * only a rewritten RF_CONFIG_WRITE accepted by native code can move observed state to ACTIVE.
     */
    private void runLifecycleRecovery(String reason) {
        SimConfig cfg = readConfig();
        int pid = Process.myPid();
        if (!cfg.initialized || !cfg.active || cfg.uid == null || cfg.generation <= 0L ||
                cfg.handledGeneration != cfg.generation || !"SUCCESS".equals(cfg.commandStatus)) return;
        String uidHex = normalizeUid(cfg.uid);
        if (uidHex.length() != 8) return;
        if (isLifecycleVerified(cfg.generation, pid, uidHex)) {
            finishLifecycleRecovery(cfg.generation);
            return;
        }

        synchronized (this) {
            if (lifecycleReapplyPending && lifecycleRecoveryGeneration == cfg.generation) return;
            lifecycleReapplyPending = true;
            lifecycleRecoveryGeneration = cfg.generation;
            lifecycleRecoveryStartedAt = System.currentTimeMillis();
        }

        persistRefreshRuntime("LIFECYCLE_WAITING_NATURAL_RF", "", reason, cfg.generation, false);
        Log.i(TAG, "LIFECYCLE RECOVERY start reason=" + reason + " generation=" + cfg.generation +
                " uid=" + uidHex + " pid=" + pid);

        try {
            // Give the NFC stack a short chance to perform its own startup RF configuration. The
            // early hook will mutate that current OEM payload if it occurs.
            if (waitForLifecycleVerified(cfg.generation, pid, uidHex, LIFECYCLE_NATURAL_WAIT_MS)) return;

            long deadline = lifecycleRecoveryStartedAt + LIFECYCLE_TOTAL_TIMEOUT_MS;
            String lastFailure = "No RF_CONFIG_WRITE observed";
            for (int attempt = 1; attempt <= LIFECYCLE_MAX_TRIGGER_ATTEMPTS && System.currentTimeMillis() < deadline; attempt++) {
                SimConfig latest = readConfig();
                if (!latest.initialized || !latest.active || latest.generation != cfg.generation) {
                    lastFailure = "Desired simulation changed during lifecycle recovery";
                    break;
                }

                NfcProcessVendorController.Result trigger = triggerRfRefresh(
                        latest, true, "lifecycle:" + reason + ":attempt-" + attempt, LIFECYCLE_TRIGGER_RF_WINDOW_MS);
                lastFailure = trigger.stage + ": " + trigger.detail;
                Log.i(TAG, "LIFECYCLE RECOVERY trigger attempt=" + attempt + " stage=" + trigger.stage +
                        " success=" + trigger.success + " generation=" + cfg.generation + " pid=" + pid);

                long remaining = Math.max(0L, deadline - System.currentTimeMillis());
                long wait = Math.min(LIFECYCLE_TRIGGER_WAIT_MS, remaining);
                if (wait > 0L && waitForLifecycleVerified(cfg.generation, pid, uidHex, wait)) return;

                // A vendor fallback invocation itself passes through the hooked Java method and can
                // populate RefreshTriggerEngine. The next iteration therefore prefers the verified
                // in-process instance automatically instead of repeatedly using Binder fallback.
                if (confirmedTriggerGeneration == cfg.generation &&
                        waitForLifecycleVerified(cfg.generation, pid, uidHex, Math.min(500L, remaining))) return;
            }

            if (!isLifecycleVerified(cfg.generation, pid, uidHex)) {
                publishLifecycleFailure(cfg, uidHex,
                        "No verified RF_CONFIG_WRITE in new NFC process within lifecycle recovery window; lastTrigger=" + lastFailure,
                        NativeOutcome.notInvoked());
            }
        } finally {
            if (!isLifecycleVerified(cfg.generation, pid, uidHex)) finishLifecycleRecovery(cfg.generation);
        }
    }

    private boolean waitForLifecycleVerified(long generation, int pid, String uid, long timeoutMs) {
        long end = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() < end) {
            if (isLifecycleVerified(generation, pid, uid)) return true;
            sleep(100L);
        }
        return isLifecycleVerified(generation, pid, uid);
    }

    private boolean isLifecycleVerified(long generation, int pid, String uid) {
        Context ctx = currentContext();
        if (ctx == null) return false;
        long rfGeneration = Long.MIN_VALUE;
        int rfPid = 0;
        boolean accepted = false;
        String effective = "", confidence = "", rfUid = "";
        try (Cursor c = ctx.getContentResolver().query(CONFIG_URI, null, null, null, null)) {
            if (c == null) return false;
            while (c.moveToNext()) {
                String key = c.getString(0), value = c.getString(1);
                if ("rf_generation".equals(key)) rfGeneration = parseLong(value, Long.MIN_VALUE);
                else if ("rf_pid".equals(key)) rfPid = (int) parseLong(value, 0L);
                else if ("rf_accepted".equals(key)) accepted = Boolean.parseBoolean(value);
                else if ("effective_state".equals(key)) effective = value == null ? "" : value;
                else if ("verification_confidence".equals(key)) confidence = value == null ? "" : value;
                else if ("rf_uid".equals(key)) rfUid = normalizeUid(value);
            }
        } catch (Throwable ignored) { return false; }
        return rfGeneration == generation && rfPid == pid && accepted && "ACTIVE".equals(effective) &&
                "VERIFIED".equals(confidence) && normalizeUid(uid).equals(rfUid);
    }

    private synchronized void finishLifecycleRecovery(long generation) {
        if (generation != Long.MIN_VALUE && lifecycleRecoveryGeneration != Long.MIN_VALUE &&
                lifecycleRecoveryGeneration != generation) return;
        lifecycleReapplyPending = false;
        lifecycleRecoveryGeneration = Long.MIN_VALUE;
        lifecycleRecoveryStartedAt = 0L;
        if (generation > 0L) clearTriggerWindow(generation);
    }

    private void publishLifecycleFailure(SimConfig cfg, String uid, String detail, NativeOutcome outcome) {
        persistRefreshRuntime("LIFECYCLE_FAILED", pendingTriggerTarget, "lifecycle-recovery", cfg.generation, false);
        ContentValues v = baseHookState();
        v.put("state_generation", cfg.generation);
        v.put("rf_status", "RF_LIFECYCLE_REAPPLY_FAILED");
        v.put("rf_uid", uid == null ? "" : uid);
        v.put("rf_source", "lifecycle-recovery");
        v.put("rf_result", "");
        v.put("rf_native_result", outcome == null ? "" : outcome.rawValue);
        v.put("rf_native_result_type", outcome == null ? "not-invoked" : outcome.resultType);
        v.put("rf_accepted", false);
        v.put("rf_error", detail == null ? "" : detail);
        v.put("rf_pid", Process.myPid());
        v.put("rf_generation", cfg.generation);
        v.put("rf_verification", "LIFECYCLE_REAPPLY_FAILED");
        v.put("full_diag_stage", "LIFECYCLE_REAPPLY_FAILED");
        v.put("full_diag_summary", detail == null ? "" : detail);
        writeValuesWithRetry(v, 8, 75L);
        Log.w(TAG, "LIFECYCLE RECOVERY failed generation=" + cfg.generation + " uid=" + uid + " detail=" + detail);
    }

    private void scheduleCommandRefresh(String reason) {
        if (observerRegistered) commandExecutor.execute(() -> refreshConfigAndProcess(reason));
    }

    private void refreshConfigAndProcess(String reason) {
        SimConfig cfg = readConfig();
        if (!cfg.initialized) return;
        cachedConfig = cfg;
        int pid = Process.myPid();

        // Provider is the durable command authority. A new com.android.nfc process must not replay
        // an already handled generation just because this Java instance lost its in-memory cache.
        boolean persistedTerminal = cfg.generation > 0L && cfg.handledGeneration == cfg.generation &&
                ("SUCCESS".equals(cfg.commandStatus) || "FAILED".equals(cfg.commandStatus));
        if (persistedTerminal) {
            completedGeneration = cfg.generation;
            completedPid = pid;
            lastTriggeredGeneration = cfg.generation;
            if ("SUCCESS".equals(cfg.commandStatus) && cfg.active) {
                if (!isLifecycleVerified(cfg.generation, pid, cfg.uid)) {
                    Log.i(TAG, "LIFECYCLE APPLY adoption generation=" + cfg.generation + " uid=" + cfg.uid + " pid=" + pid + " reason=" + reason);
                    scheduleLifecycleRecovery("process_" + reason);
                }
            } else {
                Log.i(TAG, "COMMAND terminal adopted without replay status=" + cfg.commandStatus +
                        " action=" + cfg.commandAction + " generation=" + cfg.generation + " pid=" + pid);
            }
            return;
        }

        if ("RESTART_REQUIRED".equals(cfg.commandStatus) &&
                cfg.consumedGeneration == cfg.generation && cfg.handledGeneration != cfg.generation) {
            return;
        }
        if (isGenerationCompleted(cfg.generation, pid) || cfg.generation == lastTriggeredGeneration) return;
        lastTriggeredGeneration = cfg.generation;

        String action = cfg.active ? "APPLY" : "STOP";
        writeSimpleCommandState("RUNNING", "Executing " + action + " inside com.android.nfc", cfg.generation, action, false);
        Log.i(TAG, "COMMAND " + action + " reason=" + reason + " generation=" + cfg.generation + " uid=" + cfg.uid + " pid=" + pid +
                " restoreMode=" + restoreMode);

        if (!cfg.active && controllerReinitRequired) {
            requestControllerRestart(cfg, "Appended LA_NFCID1 requires NFC process/controller restart; direct stock delete is not assumed safe");
            return;
        }

        NfcProcessVendorController.Result trigger = triggerRfRefresh(cfg, cfg.active, "command:" + reason);
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
        SimConfig cfg = readConfig();
        if (cfg.initialized) cachedConfig = cfg;
        return cfg.initialized ? cfg : cachedConfig;
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
        v.put("hook_runtime_stage", stage);
        v.put("hook_runtime_summary", summary);
        SimConfig cfg = readConfig();
        boolean commandOwnsSummary = cfg.initialized && cfg.generation > 0L &&
                cfg.commandStatus != null && !cfg.commandStatus.isEmpty() && !"SUCCESS".equals(cfg.commandStatus);
        if (!commandOwnsSummary) {
            v.put("full_diag_stage", stage);
            v.put("full_diag_summary", summary);
        }
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
        String verification = confirmedTriggerGeneration == cfg.generation ?
                "TRIGGER_CONFIRMED_NATIVE_RESULT" : "NATIVE_RESULT";
        completeCommandWithVerification(cfg, rfState, uid, source, outcome, detail, verification);
        clearTriggerWindow(cfg.generation);
    }

    private void completeLifecycleReapply(SimConfig cfg, String uid, String source,
                                          NativeOutcome outcome, HookTarget target) {
        ContentValues v = baseHookState();
        v.put("state_generation", cfg.generation);
        v.put("rf_status", "RF_UID_APPLIED");
        v.put("rf_uid", uid);
        v.put("rf_source", "lifecycle-reapply|" + (source == null ? "" : source));
        v.put("rf_result", outcome.rawValue);
        v.put("rf_native_result", outcome.rawValue);
        v.put("rf_native_result_type", outcome.resultType);
        v.put("rf_accepted", outcome.accepted);
        v.put("rf_error", "");
        v.put("rf_pid", Process.myPid());
        v.put("rf_generation", cfg.generation);
        v.put("rf_verification", confirmedTriggerGeneration == cfg.generation ?
                "LIFECYCLE_REAPPLY_TRIGGER_CONFIRMED" : "LIFECYCLE_REAPPLY_NATIVE_RESULT");
        v.put("operation_state", "IDLE");
        v.put("effective_state", "ACTIVE");
        v.put("verification_confidence", "VERIFIED");
        v.put("full_diag_stage", "LIFECYCLE_REAPPLY_SUCCESS");
        v.put("full_diag_summary", "Saved UID automatically reapplied after NFC process/controller lifecycle via " +
                target.className + "#" + target.methodName);
        writeValuesWithRetry(v, 20, 100L);
        clearTriggerWindow(cfg.generation);
        Log.i(TAG, "LIFECYCLE REAPPLY success generation=" + cfg.generation + " uid=" + uid +
                " target=" + target.fingerprint() + " pid=" + Process.myPid());
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
        v.put("command_consumed_generation", cfg.generation);
        v.put("command_handled_generation", cfg.generation);
        v.put("command_action", cfg.active ? "APPLY" : "STOP");
        v.put("command_status", "SUCCESS");
        v.put("command_detail", detail == null ? "" : detail);
        v.put("command_pid", pid);
        v.put("full_diag_stage", rfState);
        v.put("full_diag_summary", detail == null ? "" : detail);
        writeValuesWithRetry(v, 20, 100L);
    }

    private void requestControllerRestart(SimConfig cfg, String detail) {
        int pid = Process.myPid();
        ContentValues v = baseHookState();
        v.put("state_generation", cfg.generation);
        v.put("command_consumed_generation", cfg.generation);
        v.put("command_action", "STOP");
        v.put("command_status", "RESTART_REQUIRED");
        v.put("command_detail", detail == null ? "Controller restart required" : detail);
        v.put("command_pid", pid);
        v.put("rf_status", "RF_CONTROLLER_RESTART_REQUIRED");
        v.put("rf_uid", "");
        v.put("rf_source", activeCodec == null ? "" : activeCodec);
        v.put("rf_result", "");
        v.put("rf_native_result", "");
        v.put("rf_native_result_type", "not-invoked");
        v.put("rf_accepted", false);
        v.put("rf_error", "");
        v.put("rf_pid", pid);
        v.put("rf_generation", cfg.generation);
        v.put("rf_verification", "LIFECYCLE_PENDING");
        v.put("operation_state", "RESETTING_CONTROLLER");
        v.put("effective_state", "UNKNOWN");
        v.put("verification_confidence", "LIFECYCLE_PENDING");
        v.put("full_diag_stage", "RF_CONTROLLER_RESTART_REQUIRED");
        v.put("full_diag_summary", detail == null ? "Controller restart required" : detail);
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
        v.put("command_consumed_generation", cfg.generation);
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
            v.put("command_consumed_generation", generation);
            v.put("operation_state", "APPLY".equals(action) ? "APPLYING" : "STOPPING");
            v.put("verification_confidence", "PENDING");
        }
        if (handled) {
            v.put("command_consumed_generation", generation);
            v.put("command_handled_generation", generation);
        }
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
                Context ctx = currentContext();
                if (ctx != null) {
                    try { ctx.getContentResolver().insert(CONFIG_URI, copy); return; }
                    catch (Throwable e) { Log.w(TAG, "status write attempt " + (i + 1) + " failed: " + e.getMessage()); }
                }
                sleep(delayMs);
            }
        });
    }

    private SimConfig readConfig() {
        Context ctx = currentContext();
        if (ctx == null) return SimConfig.uninitialized();
        boolean active = false, diagnostics = false;
        String uid = null, action = "", status = "";
        long generation = 0L, consumed = Long.MIN_VALUE, handled = Long.MIN_VALUE;
        int commandPid = 0;
        try (Cursor c = ctx.getContentResolver().query(CONFIG_URI, null, null, null, null)) {
            if (c == null) return SimConfig.uninitialized();
            while (c.moveToNext()) {
                String key = c.getString(0), value = c.getString(1);
                if ("simulation_enabled".equals(key)) active = Boolean.parseBoolean(value);
                else if ("uid".equals(key)) uid = value;
                else if ("diagnostic_logging_enabled".equals(key)) diagnostics = Boolean.parseBoolean(value);
                else if ("command_generation".equals(key)) generation = parseLong(value, 0L);
                else if ("command_consumed_generation".equals(key)) consumed = parseLong(value, Long.MIN_VALUE);
                else if ("command_handled_generation".equals(key)) handled = parseLong(value, Long.MIN_VALUE);
                else if ("command_action".equals(key)) action = value == null ? "" : value;
                else if ("command_status".equals(key)) status = value == null ? "" : value;
                else if ("command_pid".equals(key)) commandPid = (int) parseLong(value, 0L);
            }
            if (action.isEmpty()) action = active ? "APPLY" : "STOP";
            return new SimConfig(true, active, uid, diagnostics, generation, consumed, handled, action, status, commandPid);
        } catch (Throwable t) {
            return SimConfig.uninitialized();
        }
    }

    private static Context currentContext() {
        Application app = currentApplication();
        if (app != null) return app;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method current = at.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            if (thread == null) return null;
            Method systemContext = at.getDeclaredMethod("getSystemContext");
            systemContext.setAccessible(true);
            Object ctx = systemContext.invoke(thread);
            return ctx instanceof Context ? (Context) ctx : null;
        } catch (Throwable ignored) { return null; }
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

    private static int findSingleByteArrayArg(Object[] args) {
        if (args == null || args.length == 0) return -1;
        int index = -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof byte[]) {
                if (index >= 0) return -1;
                index = i;
            }
        }
        return index;
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
        final long generation, consumedGeneration, handledGeneration;
        final int commandPid;
        SimConfig(boolean initialized, boolean active, String uid, boolean diagnostics, long generation,
                  long consumedGeneration, long handledGeneration, String commandAction, String commandStatus, int commandPid) {
            this.initialized = initialized; this.active = active; this.uid = uid; this.diagnostics = diagnostics;
            this.generation = generation; this.consumedGeneration = consumedGeneration; this.handledGeneration = handledGeneration;
            this.commandAction = commandAction; this.commandStatus = commandStatus; this.commandPid = commandPid;
        }
        static SimConfig uninitialized() { return new SimConfig(false, false, null, false, 0L, Long.MIN_VALUE, Long.MIN_VALUE, "", "", 0); }
    }
}
