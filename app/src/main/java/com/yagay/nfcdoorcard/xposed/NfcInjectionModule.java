package com.yagay.nfcdoorcard.xposed;

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

import com.yagay.nfcdoorcard.BuildConfig;
import com.yagay.nfcdoorcard.xposed.discovery.HookDiscoveryEngine;
import com.yagay.nfcdoorcard.xposed.discovery.HookTarget;
import com.yagay.nfcdoorcard.xposed.payload.RewriteResult;
import com.yagay.nfcdoorcard.xposed.payload.RfPayloadEngine;
import com.yagay.nfcdoorcard.xposed.profile.HookProfileStore;

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
    private static final Uri CONFIG_URI = Uri.parse("content://com.yagay.nfcdoorcard.config/settings");
    private static final int MAX_LEARNING_HOOKS = 4;
    private static final int MAX_TRIGGER_HOOKS = 4;
    private static final long TRIGGER_RF_WINDOW_MS = 3_000L;
    private static final long LIFECYCLE_TRIGGER_RF_WINDOW_MS = 5_000L;
    private static final long LIFECYCLE_NATURAL_WAIT_MS = 1_200L;
    private static final long LIFECYCLE_TRIGGER_WAIT_MS = 1_600L;
    private static final long LIFECYCLE_TOTAL_TIMEOUT_MS = 9_000L;
    private static final long LIFECYCLE_REPLAY_DELAY_MS = 600L;
    private static final int LIFECYCLE_MAX_TRIGGER_ATTEMPTS = 3;
    private static final long EARLY_CONFIG_WAIT_MS = 200L;
    private static final long EARLY_CONFIG_RETRY_MS = 20L;
    private static final long EARLY_REPLAY_TIMEOUT_MS = 8_000L;
    private static final long EARLY_REPLAY_RETRY_MS = 50L;
    private static final long CONTROLLER_LIFECYCLE_DEBOUNCE_MS = 1_200L;

    private final ExecutorService stateSyncExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "NfcUIDSim-StateSync"));
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "NfcUIDSim-Command"));
    private final ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "NfcUIDSim-Lifecycle"));
    private final ExecutorService earlyReplayExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "NfcUIDSim-EarlyReplay"));
    private final HookDiscoveryEngine discoveryEngine = new HookDiscoveryEngine();
    private final RfPayloadEngine payloadEngine = new RfPayloadEngine();
    private final HookProfileStore profileStore = new HookProfileStore();
    private final NfcProcessVendorController vendorController = new NfcProcessVendorController();
    private final RefreshTriggerEngine refreshTriggerEngine = new RefreshTriggerEngine();
    private final RfReplayEngine replayEngine = new RfReplayEngine();

    private volatile HookTarget activeTarget;
    private volatile ClassLoader nfcClassLoader;
    private volatile boolean fullTriggerDiscoveryDone;
    private volatile String activeCodec = "";
    private volatile SimConfig cachedConfig = SimConfig.uninitialized();
    private volatile ContentObserver commandObserver;
    private volatile BroadcastReceiver adapterStateReceiver;
    private volatile boolean observerRegistered;
    private volatile boolean controllerInvalid;
    private volatile boolean controllerReadyObserved;
    private volatile int controllerLifecycleHookCount;
    private volatile long lastControllerInvalidAt;
    private volatile long lastControllerReadyAt;
    private volatile boolean lifecycleReapplyPending;
    private volatile long lifecycleRecoveryGeneration = Long.MIN_VALUE;
    private volatile long lifecycleRecoveryStartedAt;
    private volatile boolean lifecycleWorkerRunning;
    private volatile long lifecycleWorkerGeneration = Long.MIN_VALUE;
    // Only an explicitly armed recovery write may publish lifecycle VERIFIED state.
    // Natural startup writes are still rewritten, while controller-ready exact replay is
    // the deterministic proof point for OFF -> ON recovery.
    private volatile boolean recoveryWriteArmed;
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

    // Startup and controller-ready replay share the same tested replay engine.
    // Share-mode/Binder triggering remains compatibility fallback only.
    private volatile boolean earlyReplayWorkerScheduled;
    private volatile long lifecycleFailureGeneration = Long.MIN_VALUE;
    private volatile long lifecycleFailureControllerEpoch = Long.MIN_VALUE;

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
        nfcClassLoader = cl;
        // A fresh com.android.nfc process is always a fresh physical-controller proof domain.
        // Provider state may not be readable this early, so mark memory invalid now and commit the
        // epoch barrier again once the command bridge has a Context.
        markControllerInvalid("process_start_early");
        installEarlyKnownRfHook(cl, pid);
        installControllerLifecycleHooks(cl, pid);
        installEarlyAdapterStateBridge(pid);
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

    /**
     * Observe real NFC controller/service lifecycle methods in addition to adapter broadcasts.
     * We never alter return values or arguments here: these hooks are read-only lifecycle signals.
     * This covers controller resets where OxygenOS/NXP reinitializes NFC without a reliable public
     * ACTION_ADAPTER_STATE_CHANGED OFF/ON pair.
     */
    private void installControllerLifecycleHooks(ClassLoader cl, int pid) {
        String[] classes = {
                "com.android.nfc.dhimpl.NativeNfcManager",
                "com.android.nfc.dhimpl.NxpNativeNfcManager",
                "com.android.nfc.NfcService"
        };
        int installed = 0;
        for (String className : classes) {
            try {
                Class<?> cls = Class.forName(className, false, cl);
                for (Method method : cls.getDeclaredMethods()) {
                    String name = method.getName();
                    boolean invalidator = "deinitialize".equals(name) || "disableInternal".equals(name);
                    boolean ready = "initialize".equals(name) || "enableInternal".equals(name);
                    if (!invalidator && !ready) continue;
                    try {
                        method.setAccessible(true);
                        final String signal = className + "#" + name + methodSignature(method);
                        hook(method).intercept(chain -> {
                            if (invalidator) markControllerInvalid("hook:" + signal);
                            Object result = chain.proceed();
                            if (ready && controllerCallSucceeded(method, result)) {
                                markControllerReady("hook:" + signal);
                            }
                            return result;
                        });
                        installed++;
                        Log.i(TAG, "CONTROLLER LIFECYCLE hook installed signal=" + signal + " pid=" + pid);
                    } catch (Throwable hookError) {
                        Log.w(TAG, "CONTROLLER LIFECYCLE hook skipped " + className + "#" + name + " " +
                                hookError.getClass().getSimpleName() + ": " + hookError.getMessage());
                    }
                }
            } catch (Throwable ignored) {
                // Capability not present on this NFC stack. Other known classes/broadcast fallback
                // remain available, so absence is not treated as a module failure.
            }
        }
        controllerLifecycleHookCount = installed;
        persistControllerLifecycle("HOOKS_READY", "discovery", "count=" + installed);
        Log.i(TAG, "CONTROLLER LIFECYCLE hooks=" + installed + " pid=" + pid);
    }

    private static String methodSignature(Method method) {
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(params[i].getSimpleName());
        }
        return sb.append(")->").append(method.getReturnType().getSimpleName()).toString();
    }

    private static boolean controllerCallSucceeded(Method method, Object result) {
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class) return true;
        if (returnType == boolean.class || returnType == Boolean.class) {
            return Boolean.TRUE.equals(result);
        }
        if (result instanceof Number) {
            // Native NFC wrappers conventionally use 0 as success; positive values are also
            // accepted here for Java service methods that return an enabled/state token.
            return ((Number) result).longValue() >= 0L;
        }
        return result != null;
    }

    private void markControllerInvalid(String reason) {
        long now = System.currentTimeMillis();
        boolean firstInvalid;
        synchronized (this) {
            firstInvalid = !controllerInvalid;
            controllerInvalid = true;
            controllerReadyObserved = false;
            if (firstInvalid) lastControllerInvalidAt = now;
        }
        if (!firstInvalid) return;
        lifecycleFailureGeneration = Long.MIN_VALUE;
        lifecycleFailureControllerEpoch = Long.MIN_VALUE;
        invalidateRfEvidenceForControllerReset(reason);
        persistControllerLifecycle("INVALID", reason, "pid=" + Process.myPid());
        finishLifecycleRecovery(lifecycleRecoveryGeneration);
        Log.i(TAG, "CONTROLLER INVALID reason=" + reason + " pid=" + Process.myPid());
    }

    private void markControllerReady(String reason) {
        long now = System.currentTimeMillis();
        boolean wasInvalid;
        synchronized (this) {
            // initialize() and enableInternal() can be nested in one enable transaction. Treat the
            // pair as one physical ready edge rather than advancing the epoch twice.
            if (lastControllerReadyAt > 0L && now - lastControllerReadyAt < CONTROLLER_LIFECYCLE_DEBOUNCE_MS) {
                return;
            }
            wasInvalid = controllerInvalid;
            controllerInvalid = false;
            controllerReadyObserved = true;
            lastControllerReadyAt = now;
        }
        // Some OEM paths expose only a ready/initialize edge. Conservatively invalidate old proof
        // before recovery when no matching disable/deinitialize edge was observed.
        if (!wasInvalid) invalidateRfEvidenceForControllerReset("implicit_invalid_before_" + reason);
        persistControllerLifecycle("READY", reason,
                "pid=" + Process.myPid() + ";wasInvalid=" + wasInvalid);
        Log.i(TAG, "CONTROLLER READY reason=" + reason + " pid=" + Process.myPid() +
                " wasInvalid=" + wasInvalid);
        scheduleLifecycleRecovery("controller_ready:" + reason);
    }

    private void persistControllerLifecycle(String status, String source, String detail) {
        ContentValues v = baseHookState();
        SimConfig cfg = cachedConfig;
        if (cfg.initialized && cfg.generation > 0L) v.put("state_generation", cfg.generation);
        v.put("controller_lifecycle_status", status == null ? "" : status);
        v.put("controller_lifecycle_source", source == null ? "" : source);
        v.put("controller_lifecycle_detail", detail == null ? "" : detail);
        v.put("controller_lifecycle_hook_count", controllerLifecycleHookCount);
        v.put("controller_lifecycle_invalid", controllerInvalid);
        v.put("controller_lifecycle_ready_observed", controllerReadyObserved);
        v.put("controller_lifecycle_last_invalid_at", lastControllerInvalidAt);
        v.put("controller_lifecycle_last_ready_at", lastControllerReadyAt);
        writeValuesWithRetry(v, 8, 75L);
    }

    /** Register adapter OFF/ON tracking as early as the system context becomes available.
     * This is deliberately independent of waitForApplication(): controller power transitions can
     * happen before the NFC Application object is published, and missing OFF would leave stale RF
     * evidence valid for the following ON cycle.
     */
    private void installEarlyAdapterStateBridge(int pid) {
        lifecycleExecutor.execute(() -> {
            long end = System.currentTimeMillis() + 2_000L;
            while (adapterStateReceiver == null && System.currentTimeMillis() < end) {
                Context ctx = currentContext();
                if (ctx != null) {
                    registerAdapterStateReceiver(ctx);
                    if (adapterStateReceiver != null) {
                        Log.i(TAG, "EARLY ADAPTER STATE bridge ready pid=" + pid);
                        return;
                    }
                }
                sleep(25L);
            }
            if (adapterStateReceiver == null) {
                Log.w(TAG, "EARLY ADAPTER STATE bridge unavailable pid=" + pid);
            }
        });
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
            if (!cfg.initialized) {
                // The first controller RF configuration can race Provider availability during a
                // cold com.android.nfc startup. Spend only a small bounded budget here so the NFC
                // service is never stalled for seconds. If config is still unavailable, let the
                // OEM call proceed unchanged but retain this exact verified RF writer invocation
                // for one in-process replay as soon as durable desired state becomes readable.
                cfg = awaitEarlyConfig();
                if (!cfg.initialized) {
                    captureRfInvocationSnapshot(method, chain.getThisObject(), args, payloadArg, target, pid);
                    return chain.proceed();
                }
            }

            // A natural RF write in a new NFC process is the best lifecycle recovery trigger. Mark
            // it as a lifecycle reapply before mutating so a successful native result updates only
            // observed RF evidence; the original user APPLY command history remains untouched.
            // Any terminal desired APPLY whose RF proof is no longer fresh is a lifecycle
            // reapply, even when com.android.nfc kept the same PID. This is crucial for adapter
            // OFF -> ON: the controller resets but the Java service process may survive. The
            // controller epoch makes stale proof detectable, and this natural OEM RF write is the
            // preferred place to reapply instead of manufacturing a share-mode refresh.
            boolean terminalDesiredApply = cfg.active && cfg.generation > 0L &&
                    cfg.handledGeneration == cfg.generation && "SUCCESS".equals(cfg.commandStatus);
            if (terminalDesiredApply && !isLifecycleVerified(cfg.generation, pid, cfg.uid)) {
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
            if (uidHex.length() != 8 && uidHex.length() != 14 && uidHex.length() != 20) {
                failCommand(cfg, "UID_INVALID", uidHex, "UID must be 4, 7 or 10 bytes", NativeOutcome.notInvoked());
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
            persistRewriteDiagnostics(rewritten);
            Log.i(TAG, "RF_REWRITE codec=" + rewritten.codecId + " reason=" + rewritten.reason +
                    " oldPayload=" + rewritten.oldPayloadLength + " newPayload=" + rewritten.newPayloadLength +
                    " oldCount=" + rewritten.oldParamCount + " newCount=" + rewritten.newParamCount +
                    " originalBytes=" + original.length + " rewrittenBytes=" + rewritten.data.length);
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

                boolean reversibleExisting = rewritten.reason != null &&
                        (rewritten.reason.contains("REPLACED_EXISTING_LA_NFCID1") ||
                                rewritten.reason.contains("RESIZED_EXISTING_LA_NFCID1"));
                synchronized (this) {
                    if (learningMode) {
                        markTargetVerified(target);
                        learningMode = false;
                    }
                    if (reversibleExisting) {
                        // Both same-length replacement and structurally verified resize mutate an
                        // existing LA_NFCID1 parameter. The exact original payload is therefore a
                        // safe inverse, including stock 33 00 -> 33 04/07/0A transitions.
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
                captureVerifiedRfInvocation(method, chain.getThisObject(), args, target, pid);
                replayEngine.clearPending(target.fingerprint());
                if (lifecycleReapplyPending) {
                    if (recoveryWriteArmed) {
                        completeLifecycleReapply(cfg, uidHex, rewritten.codecId, outcome, target);
                        finishLifecycleRecovery(cfg.generation);
                    } else if (cfg.diagnostics) {
                        Log.i(TAG, "LIFECYCLE natural RF write accepted before replay generation=" +
                                cfg.generation + " uid=" + uidHex + " target=" + target.fingerprint());
                    }
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

    private synchronized void ensureFullTriggerDiscovery() {
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
        return replayEngine.hasVerified(Process.myPid());
    }

    private NfcProcessVendorController.Result triggerRfRefresh(SimConfig cfg, boolean enabled, String reason) {
        return triggerRfRefresh(cfg, enabled, reason, TRIGGER_RF_WINDOW_MS);
    }

    private NfcProcessVendorController.Result triggerRfRefresh(SimConfig cfg, boolean enabled, String reason, long windowMs) {
        armTriggerWindow(cfg.generation, reason, windowMs);
        RefreshTriggerEngine.Invocation javaTrigger = refreshTriggerEngine.invoke(enabled);
        if (!javaTrigger.success && !fullTriggerDiscoveryDone) {
            ensureFullTriggerDiscovery();
            javaTrigger = refreshTriggerEngine.invoke(enabled);
        }
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
            // New process == new physical controller proof domain. Do this after Context/provider
            // availability so an early package-load invalidation cannot be lost before Direct Boot
            // storage is readable.
            invalidateRfEvidenceForControllerReset("process_start");
            refreshConfigAndProcess("startup");
        } catch (Throwable t) {
            writeSimpleCommandState("OBSERVER_FAILED", "Cannot register ConfigProvider observer: " + t.getMessage(), 0L, "", false);
        }
    }

    private synchronized void registerAdapterStateReceiver(Context app) {
        if (adapterStateReceiver != null) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null || !"android.nfc.action.ADAPTER_STATE_CHANGED".equals(intent.getAction())) return;
                int state = intent.getIntExtra("android.nfc.extra.ADAPTER_STATE", -1);
                if (state == 3) {
                    // Broadcast is now a fallback/secondary ready signal. The primary signals are
                    // initialize()/enableInternal() hooks, but an ON broadcast still forces a
                    // closed-loop recovery when a vendor stack does not expose those methods.
                    markControllerReady("adapter_state_on");
                } else if (state == 1 || state == 4) {
                    markControllerInvalid(state == 4 ? "adapter_turning_off" : "adapter_off");
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
     * NFC adapter OFF / TURNING_OFF resets the controller RF configuration but may leave the
     * com.android.nfc Java process alive. PID-based verification therefore cannot prove that the
     * previously applied NFCID1 is still present. Preserve the durable desired APPLY command and
     * selected UID, but explicitly invalidate only observed RF evidence. The next adapter ON event
     * will then run the closed-loop lifecycle recovery and require a fresh native-accepted RF write.
     */
    private void invalidateRfEvidenceForControllerReset(String reason) {
        SimConfig cfg = readConfig();
        if (!cfg.initialized || !cfg.active || cfg.generation <= 0L) return;
        long nextEpoch = Math.max(cfg.controllerEpoch + 1L, System.currentTimeMillis());
        ContentValues v = baseHookState();
        v.put("state_generation", cfg.generation);
        // controller_epoch is lifecycle metadata, not terminal command/RF evidence. ConfigProvider
        // intentionally permits it to advance after a successful APPLY. Existing RF evidence is
        // then stale because rf_controller_epoch no longer matches.
        v.put("controller_epoch", nextEpoch);
        // Epoch invalidation is a correctness barrier, not diagnostic telemetry. Commit it
        // synchronously so an immediately following ADAPTER_STATE_ON or OEM RF write cannot read
        // the previous epoch. Normal status/log writes remain asynchronous.
        if (!writeValuesSynchronously(v, 5, 25L)) {
            Log.w(TAG, "CONTROLLER EPOCH synchronous write failed reason=" + reason +
                    " generation=" + cfg.generation + " nextEpoch=" + nextEpoch);
        }
        cachedConfig = cfg.withControllerEpoch(nextEpoch);
        persistRefreshRuntime("LIFECYCLE_INVALIDATED", "", reason, cfg.generation, false);
        synchronized (this) {
            lifecycleReapplyPending = false;
            lifecycleRecoveryGeneration = Long.MIN_VALUE;
            lifecycleRecoveryStartedAt = 0L;
            lifecycleWorkerRunning = false;
            lifecycleWorkerGeneration = Long.MIN_VALUE;
            recoveryWriteArmed = false;
        }
        clearTriggerWindow(cfg.generation);
        Log.i(TAG, "CONTROLLER EPOCH advanced reason=" + reason + " generation=" + cfg.generation +
                " oldEpoch=" + cfg.controllerEpoch + " newEpoch=" + nextEpoch + " uid=" + cfg.uid +
                " pid=" + Process.myPid());
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
        long controllerEpoch = 0L, rfControllerEpoch = Long.MIN_VALUE;
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
                else if ("controller_epoch".equals(key)) controllerEpoch = parseLong(value, 0L);
                else if ("rf_controller_epoch".equals(key)) rfControllerEpoch = parseLong(value, Long.MIN_VALUE);
            }
        } catch (Throwable ignored) { return false; }
        return rfGeneration == generation && rfPid == pid && accepted && controllerEpoch > 0L &&
                rfControllerEpoch == controllerEpoch && "ACTIVE".equals(effective) &&
                "VERIFIED".equals(confidence) && normalizeUid(uid).equals(rfUid);
    }

    private synchronized void finishLifecycleRecovery(long generation) {
        if (generation != Long.MIN_VALUE && lifecycleRecoveryGeneration != Long.MIN_VALUE &&
                lifecycleRecoveryGeneration != generation) return;
        lifecycleReapplyPending = false;
        lifecycleRecoveryGeneration = Long.MIN_VALUE;
        lifecycleRecoveryStartedAt = 0L;
        lifecycleWorkerRunning = false;
        lifecycleWorkerGeneration = Long.MIN_VALUE;
        recoveryWriteArmed = false;
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
        v.put("rf_controller_epoch", cfg.controllerEpoch);
        v.put("rf_verification", "LIFECYCLE_REAPPLY_FAILED");
        v.put("full_diag_stage", "LIFECYCLE_REAPPLY_FAILED");
        v.put("full_diag_summary", detail == null ? "" : detail);
        lifecycleFailureGeneration = cfg.generation;
        lifecycleFailureControllerEpoch = cfg.controllerEpoch;
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
                    boolean suppressSelfRetry = "provider_change".equals(reason) &&
                            lifecycleFailureGeneration == cfg.generation &&
                            lifecycleFailureControllerEpoch == cfg.controllerEpoch;
                    if (suppressSelfRetry) {
                        Log.i(TAG, "LIFECYCLE APPLY self-retry suppressed generation=" + cfg.generation +
                                " epoch=" + cfg.controllerEpoch + " pid=" + pid);
                    } else {
                        Log.i(TAG, "LIFECYCLE APPLY adoption generation=" + cfg.generation + " uid=" + cfg.uid + " pid=" + pid + " reason=" + reason);
                        scheduleLifecycleRecovery("process_" + reason);
                    }
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

    private SimConfig awaitEarlyConfig() {
        long end = System.currentTimeMillis() + EARLY_CONFIG_WAIT_MS;
        SimConfig cfg;
        do {
            cfg = readConfig();
            if (cfg.initialized) {
                cachedConfig = cfg;
                return cfg;
            }
            if (System.currentTimeMillis() >= end) break;
            sleep(EARLY_CONFIG_RETRY_MS);
        } while (true);
        return SimConfig.uninitialized();
    }

    private void captureRfInvocationSnapshot(Method method, Object receiver, Object[] args, int payloadArg,
                                          HookTarget target, int pid) {
        if (method == null || args == null || payloadArg < 0 || target == null) return;
        RfReplayEngine.Snapshot snapshot = replayEngine.capturePending(
                method, receiver, args, target.fingerprint(), System.currentTimeMillis(), pid);
        if (snapshot == null) return;
        synchronized (this) {
            // Keep the newest invocation from the active verified writer. During startup an OEM can
            // emit several config writes; replaying the latest one minimizes stale side effects.
            if (earlyReplayWorkerScheduled) return;
            earlyReplayWorkerScheduled = true;
        }
        Log.i(TAG, "EARLY RF REPLAY captured target=" + target.fingerprint() + " pid=" + pid +
                " payloadArg=" + payloadArg);
        earlyReplayExecutor.execute(this::runEarlyRfReplayWorker);
    }

    private void runEarlyRfReplayWorker() {
        long deadline = System.currentTimeMillis() + EARLY_REPLAY_TIMEOUT_MS;
        RfReplayEngine.Snapshot snapshot = null;
        boolean shouldFallback = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                snapshot = replayEngine.pending();
                if (snapshot == null) return;
                if (snapshot.capturedPid != Process.myPid()) {
                    replayEngine.clearPending(snapshot.targetFingerprint);
                    return;
                }
                SimConfig cfg = readConfig();
                if (!cfg.initialized) {
                    sleep(EARLY_REPLAY_RETRY_MS);
                    continue;
                }
                cachedConfig = cfg;
                // Early replay is lifecycle recovery, not a second command executor. Only replay a
                // durable successful APPLY whose RF proof is stale. Pending/failed generations are
                // left to the normal command bridge so replay cannot silently change command semantics.
                boolean terminalDesiredApply = cfg.active && cfg.uid != null && cfg.generation > 0L &&
                        cfg.handledGeneration == cfg.generation && "SUCCESS".equals(cfg.commandStatus);
                if (!terminalDesiredApply) {
                    replayEngine.clearPending(snapshot.targetFingerprint);
                    return;
                }
                String uidHex = normalizeUid(cfg.uid);
                if (uidHex.length() != 8 && uidHex.length() != 14 && uidHex.length() != 20) {
                    replayEngine.clearPending(snapshot.targetFingerprint);
                    return;
                }
                if (isLifecycleVerified(cfg.generation, Process.myPid(), uidHex)) {
                    replayEngine.clearPending(snapshot.targetFingerprint);
                    return;
                }
                synchronized (this) { recoveryWriteArmed = true; }
                Log.i(TAG, "EARLY RF REPLAY invoke target=" + snapshot.targetFingerprint +
                        " generation=" + cfg.generation + " uid=" + uidHex + " pid=" + Process.myPid());
                // Exactly one replay attempt. The reflected call re-enters the installed RF hook,
                // which applies the current UID and records native/controller-epoch proof.
                RfReplayEngine.ReplayResult replay = replayEngine.invoke(snapshot);
                if (!replay.invoked && replay.error != null) {
                    Log.w(TAG, "EARLY RF REPLAY failed target=" + snapshot.targetFingerprint + " " +
                            replay.error.getClass().getSimpleName() + ": " + replay.error.getMessage());
                }
                if (waitForLifecycleVerified(cfg.generation, Process.myPid(), uidHex, LIFECYCLE_NATURAL_WAIT_MS)) {
                    replayEngine.clearPending(snapshot.targetFingerprint);
                    return;
                }
                replayEngine.clearPending(snapshot.targetFingerprint);
                shouldFallback = true;
                break;
            }
            if (replayEngine.pending() != null) {
                snapshot = replayEngine.pending();
                Log.w(TAG, "EARLY RF REPLAY config timeout target=" + snapshot.targetFingerprint +
                        " ageMs=" + (System.currentTimeMillis() - snapshot.capturedAt));
                replayEngine.clearPending(snapshot.targetFingerprint);
            }
        } finally {
            synchronized (this) {
                earlyReplayWorkerScheduled = false;
                recoveryWriteArmed = false;
            }
        }
        // Never monopolize the lifecycle executor. If the exact OEM invocation could not produce
        // fresh native proof, fall back to the existing natural-wait -> Java trigger -> vendor path.
        if (shouldFallback) scheduleLifecycleRecovery("early_rf_replay_fallback");
    }

    private void captureVerifiedRfInvocation(Method method, Object receiver, Object[] args,
                                             HookTarget target, int pid) {
        if (method == null || args == null || target == null) return;
        RfReplayEngine.Snapshot snapshot = replayEngine.captureVerified(
                method, receiver, args, target.fingerprint(), System.currentTimeMillis(), pid);
        if (snapshot == null) return;
        ContentValues v = baseHookState();
        SimConfig cfg = cachedConfig;
        if (cfg.initialized && cfg.generation > 0L) v.put("state_generation", cfg.generation);
        v.put("rf_replay_status", "CAPTURED");
        v.put("rf_replay_target", target.fingerprint());
        v.put("rf_replay_captured_at", snapshot.capturedAt);
        v.put("rf_replay_pid", pid);
        writeValuesWithRetry(v, 8, 75L);
        Log.i(TAG, "RF REPLAY captured target=" + target.fingerprint() + " pid=" + pid);
    }

    private boolean replayVerifiedRfInvocation(SimConfig cfg, String uidHex) {
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

    private void persistRewriteDiagnostics(RewriteResult rewritten) {
        if (rewritten == null) return;
        ContentValues v = new ContentValues();
        v.put("rf_rewrite_reason", rewritten.reason == null ? "" : rewritten.reason);
        v.put("rf_rewrite_codec", rewritten.codecId == null ? "" : rewritten.codecId);
        v.put("rf_rewrite_old_payload_len", rewritten.oldPayloadLength);
        v.put("rf_rewrite_new_payload_len", rewritten.newPayloadLength);
        v.put("rf_rewrite_old_param_count", rewritten.oldParamCount);
        v.put("rf_rewrite_new_param_count", rewritten.newParamCount);
        writeValuesWithRetry(v, 8, 75L);
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
        v.put("rf_controller_epoch", cfg.controllerEpoch);
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
        v.put("rf_controller_epoch", cfg.controllerEpoch);
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
        // controller_epoch is authoritative lifecycle metadata. Never copy it from cachedConfig
        // into generic asynchronous status writes, because a late status write must not roll back
        // a newer adapter-reset epoch. Lifecycle/RF paths write epochs explicitly.
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

    private boolean writeValuesSynchronously(ContentValues values, int attempts, long delayMs) {
        ContentValues copy = new ContentValues(values);
        for (int i = 0; i < attempts; i++) {
            Context ctx = currentContext();
            if (ctx != null) {
                try {
                    ctx.getContentResolver().insert(CONFIG_URI, copy);
                    return true;
                } catch (Throwable e) {
                    Log.w(TAG, "sync state write attempt " + (i + 1) + " failed: " + e.getMessage());
                }
            }
            if (i + 1 < attempts) sleep(delayMs);
        }
        return false;
    }

    private SimConfig readConfig() {
        Context ctx = currentContext();
        if (ctx == null) return SimConfig.uninitialized();
        boolean active = false, diagnostics = false;
        String uid = null, action = "", status = "";
        long generation = 0L, consumed = Long.MIN_VALUE, handled = Long.MIN_VALUE, controllerEpoch = 0L;
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
                else if ("controller_epoch".equals(key)) controllerEpoch = parseLong(value, 0L);
            }
            if (action.isEmpty()) action = active ? "APPLY" : "STOP";
            // Schema migration or first install may not have an epoch yet. Seed it without
            // declaring RF success; subsequent verified writes will record this epoch.
            if (controllerEpoch <= 0L) controllerEpoch = 1L;
            return new SimConfig(true, active, uid, diagnostics, generation, consumed, handled, action, status, commandPid, controllerEpoch);
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
        final long generation, consumedGeneration, handledGeneration, controllerEpoch;
        final int commandPid;
        SimConfig(boolean initialized, boolean active, String uid, boolean diagnostics, long generation,
                  long consumedGeneration, long handledGeneration, String commandAction, String commandStatus, int commandPid, long controllerEpoch) {
            this.initialized = initialized; this.active = active; this.uid = uid; this.diagnostics = diagnostics;
            this.generation = generation; this.consumedGeneration = consumedGeneration; this.handledGeneration = handledGeneration;
            this.commandAction = commandAction; this.commandStatus = commandStatus; this.commandPid = commandPid;
            this.controllerEpoch = controllerEpoch;
        }
        SimConfig withControllerEpoch(long epoch) {
            return new SimConfig(initialized, active, uid, diagnostics, generation, consumedGeneration, handledGeneration,
                    commandAction, commandStatus, commandPid, epoch);
        }
        static SimConfig uninitialized() { return new SimConfig(false, false, null, false, 0L, Long.MIN_VALUE, Long.MIN_VALUE, "", "", 0, 0L); }
    }
}
