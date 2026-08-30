from pathlib import Path

module_path = Path('app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java')
build_path = Path('app/build.gradle.kts')
text = module_path.read_text()

if 'CONTROLLER LIFECYCLE hook installed' in text:
    raise SystemExit('migration already applied')

text = text.replace(
    '    private static final long EARLY_REPLAY_RETRY_MS = 50L;\n',
    '    private static final long EARLY_REPLAY_RETRY_MS = 50L;\n'
    '    private static final long CONTROLLER_LIFECYCLE_DEBOUNCE_MS = 1_200L;\n'
)

text = text.replace(
    '    private volatile boolean observerRegistered;\n',
    '    private volatile boolean observerRegistered;\n'
    '    private volatile boolean controllerInvalid;\n'
    '    private volatile boolean controllerReadyObserved;\n'
    '    private volatile int controllerLifecycleHookCount;\n'
    '    private volatile long lastControllerInvalidAt;\n'
    '    private volatile long lastControllerReadyAt;\n'
)

old = '''        final int pid = Process.myPid();
        final ClassLoader cl = lp.getDefaultClassLoader();
        installEarlyKnownRfHook(cl, pid);
        installEarlyAdapterStateBridge(pid);
        commandExecutor.execute(() -> initializeRuntime(cl, pid));
'''
new = '''        final int pid = Process.myPid();
        final ClassLoader cl = lp.getDefaultClassLoader();
        // A fresh com.android.nfc process is always a fresh physical-controller proof domain.
        // Provider state may not be readable this early, so mark memory invalid now and commit the
        // epoch barrier again once the command bridge has a Context.
        markControllerInvalid("process_start_early");
        installEarlyKnownRfHook(cl, pid);
        installControllerLifecycleHooks(cl, pid);
        installEarlyAdapterStateBridge(pid);
        commandExecutor.execute(() -> initializeRuntime(cl, pid));
'''
assert old in text, 'onPackageLoaded block not found'
text = text.replace(old, new)

marker = '''    /** Register adapter OFF/ON tracking as early as the system context becomes available.
'''
insert = r'''    /**
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

'''
assert marker in text, 'adapter marker not found'
text = text.replace(marker, insert + marker)

old = '''            registerAdapterStateReceiver(app);
            refreshConfigAndProcess("startup");
'''
new = '''            registerAdapterStateReceiver(app);
            // New process == new physical controller proof domain. Do this after Context/provider
            // availability so an early package-load invalidation cannot be lost before Direct Boot
            // storage is readable.
            invalidateRfEvidenceForControllerReset("process_start");
            refreshConfigAndProcess("startup");
'''
assert old in text, 'startCommandBridge block not found'
text = text.replace(old, new)

old = '''                if (state == 3) {
                    scheduleLifecycleRecovery("adapter_state_on");
                } else if (state == 1 || state == 4) {
                    // Turning NFC off resets controller RF state even when com.android.nfc keeps
                    // the same PID. Invalidate prior RF verification so the following ON event
                    // cannot mistake stale evidence for a successful lifecycle reapply.
                    invalidateRfEvidenceForAdapterReset(state == 4 ? "adapter_turning_off" : "adapter_off");
                    finishLifecycleRecovery(lifecycleRecoveryGeneration);
                }
'''
new = '''                if (state == 3) {
                    // Broadcast is now a fallback/secondary ready signal. The primary signals are
                    // initialize()/enableInternal() hooks, but an ON broadcast still forces a
                    // closed-loop recovery when a vendor stack does not expose those methods.
                    markControllerReady("adapter_state_on");
                } else if (state == 1 || state == 4) {
                    markControllerInvalid(state == 4 ? "adapter_turning_off" : "adapter_off");
                }
'''
assert old in text, 'adapter receiver body not found'
text = text.replace(old, new)

text = text.replace(
    '    private void invalidateRfEvidenceForAdapterReset(String reason) {\n',
    '    private void invalidateRfEvidenceForControllerReset(String reason) {\n'
)

# Upgrade version + hook build because runtime hook behaviour changes.
build = build_path.read_text()
build = build.replace('versionCode = 48', 'versionCode = 49')
build = build.replace('versionName = "1.0.47"', 'versionName = "1.0.48"')
build = build.replace(
    '// Runtime protocol v7; hook build 34; diagnostic-only 1.0.47 adds high-retention correlated NCI TX/RX + Oplus SuperCard/routing/boot trace capture without changing NFC injection behaviour.\n',
    '// Runtime protocol v7; hook build 35; 1.0.48 makes real controller initialize/deinitialize + NfcService enable/disable lifecycle the primary recovery signal, with adapter broadcasts as fallback.\n'
)
build = build.replace('buildConfigField("int", "HOOK_BUILD", "34")', 'buildConfigField("int", "HOOK_BUILD", "35")')

module_path.write_text(text)
build_path.write_text(build)

# The migration is intentionally one-shot; remove itself and its workflow from the product tree.
Path('scripts/migrate_controller_lifecycle_v1048.py').unlink(missing_ok=True)
Path('.github/workflows/controller-lifecycle-v1048.yml').unlink(missing_ok=True)
