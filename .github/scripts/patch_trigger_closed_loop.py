from pathlib import Path

ROOT = Path('.')

def replace(path, old, new, count=1):
    p = ROOT / path
    text = p.read_text()
    if text.count(old) < count:
        raise SystemExit(f'missing pattern in {path}: {old[:120]!r}')
    text = text.replace(old, new, count)
    p.write_text(text)

# Version / protocol bump.
replace('app/build.gradle.kts', 'versionCode = 31\n        versionName = "1.0.30"', 'versionCode = 32\n        versionName = "1.0.31"')
replace('app/build.gradle.kts', '// Runtime protocol v5; generalized NFC lifecycle hook build 23 is validated with this app build.', '// Runtime protocol v6; trigger-closed-loop hook build 24 is validated with this app build.')
replace('app/build.gradle.kts', 'buildConfigField("int", "HOOK_BUILD", "23")', 'buildConfigField("int", "HOOK_BUILD", "24")')

# ConfigProvider: clear stale trigger-probe state and expose typed runtime trigger facts.
replace('app/src/main/java/com/example/nfcdoorcard/ConfigProvider.kt', 'const val STATE_SCHEMA_VERSION = 5', 'const val STATE_SCHEMA_VERSION = 6')
replace('app/src/main/java/com/example/nfcdoorcard/ConfigProvider.kt',
'''        const val KEY_REFRESH_PROBE_PID = "refresh_probe_pid"\n\n        const val KEY_RF_STATUS''',
'''        const val KEY_REFRESH_PROBE_PID = "refresh_probe_pid"\n        const val KEY_REFRESH_TRIGGER_STATUS = "refresh_trigger_status"\n        const val KEY_REFRESH_TRIGGER_TARGET = "refresh_trigger_target"\n        const val KEY_REFRESH_TRIGGER_SOURCE = "refresh_trigger_source"\n        const val KEY_REFRESH_TRIGGER_GENERATION = "refresh_trigger_generation"\n        const val KEY_REFRESH_TRIGGER_RF_CONFIRMED = "refresh_trigger_rf_confirmed"\n\n        const val KEY_RF_STATUS''')
replace('app/src/main/java/com/example/nfcdoorcard/ConfigProvider.kt',
'''            KEY_RF_PID, KEY_RF_GENERATION, KEY_RF_VERIFICATION,\n            KEY_FULL_DIAG_STAGE, KEY_FULL_DIAG_SUMMARY''',
'''            KEY_RF_PID, KEY_RF_GENERATION, KEY_RF_VERIFICATION,\n            KEY_REFRESH_TRIGGER_STATUS, KEY_REFRESH_TRIGGER_TARGET, KEY_REFRESH_TRIGGER_SOURCE,\n            KEY_REFRESH_TRIGGER_GENERATION, KEY_REFRESH_TRIGGER_RF_CONFIRMED,\n            KEY_FULL_DIAG_STAGE, KEY_FULL_DIAG_SUMMARY''')
replace('app/src/main/java/com/example/nfcdoorcard/ConfigProvider.kt',
'''            .remove(KEY_REFRESH_PROBE_PID)\n            .putInt(KEY_STATE_SCHEMA''',
'''            .remove(KEY_REFRESH_PROBE_PID)\n            .remove("refresh_probe_events")\n            .remove("refresh_probe_last")\n            .remove(KEY_REFRESH_TRIGGER_STATUS)\n            .remove(KEY_REFRESH_TRIGGER_TARGET)\n            .remove(KEY_REFRESH_TRIGGER_SOURCE)\n            .remove(KEY_REFRESH_TRIGGER_GENERATION)\n            .remove(KEY_REFRESH_TRIGGER_RF_CONFIRMED)\n            .putInt(KEY_STATE_SCHEMA''')

# Vendor controller: prefer the actual in-process vendor service object before generated Stub/proxy.
replace('app/src/main/java/com/example/nfcdoorcard/xposed/NfcProcessVendorController.java',
'''            Object vendorProxy = asInterface(VENDOR_STUB, vendor);\n            Boolean proxyAccepted = invokeBooleanMethod(vendorProxy, "enableNfcShareMode",\n                    new Class<?>[]{boolean.class}, new Object[]{enabled});\n            if (proxyAccepted != null) {\n                return new Result(proxyAccepted, proxyAccepted ? "TRIGGERED" : "SHARE_MODE",\n                        "enableNfcShareMode(" + enabled + ") via AIDL proxy", vendorDescriptor);\n            }\n''',
'''            // In com.android.nfc the returned vendor binder can be the actual local Stub/service.\n            // Prefer that real Java object first: it follows the OEM implementation directly and\n            // avoids both transaction-number coupling and generated Stub class visibility issues.\n            Object localInterface = null;\n            try { localInterface = vendor.queryLocalInterface(vendorDescriptor); } catch (Throwable ignored) { }\n            Boolean directAccepted = invokeBooleanMethod(localInterface, "enableNfcShareMode",\n                    new Class<?>[]{boolean.class}, new Object[]{enabled});\n            if (directAccepted == null) {\n                directAccepted = invokeBooleanMethod(vendor, "enableNfcShareMode",\n                        new Class<?>[]{boolean.class}, new Object[]{enabled});\n            }\n            if (directAccepted != null) {\n                return new Result(directAccepted, directAccepted ? "TRIGGERED" : "SHARE_MODE",\n                        "enableNfcShareMode(" + enabled + ") via in-process vendor service", vendorDescriptor);\n            }\n\n            Object vendorProxy = asInterface(VENDOR_STUB, vendor);\n            Boolean proxyAccepted = invokeBooleanMethod(vendorProxy, "enableNfcShareMode",\n                    new Class<?>[]{boolean.class}, new Object[]{enabled});\n            if (proxyAccepted != null) {\n                return new Result(proxyAccepted, proxyAccepted ? "TRIGGERED" : "SHARE_MODE",\n                        "enableNfcShareMode(" + enabled + ") via AIDL proxy", vendorDescriptor);\n            }\n''')

# Discovery: reject abstract/interface methods and require semantically plausible RF/refresh names.
p = ROOT / 'app/src/main/java/com/example/nfcdoorcard/xposed/discovery/HookDiscoveryEngine.java'
text = p.read_text()
text = text.replace('import java.lang.reflect.Method;\n', 'import java.lang.reflect.Method;\nimport java.lang.reflect.Modifier;\n')
text = text.replace(
'''    public List<HookTarget> discoverTriggerCandidates(ClassLoader classLoader) {\n        List<HookTarget> out = new ArrayList<>();\n        Set<String> seen = new HashSet<>();\n        for (String name : PROVEN_TRIGGER_CLASSES) inspectTriggerClass(classLoader, name, "known-family", out, seen, true);\n\n        int inspected = 0;''',
'''    public List<HookTarget> discoverKnownTriggerCandidates(ClassLoader classLoader) {\n        List<HookTarget> out = new ArrayList<>();\n        Set<String> seen = new HashSet<>();\n        for (String name : PROVEN_TRIGGER_CLASSES) inspectTriggerClass(classLoader, name, "known-family", out, seen, true);\n        return top(out);\n    }\n\n    public List<HookTarget> discoverTriggerCandidates(ClassLoader classLoader) {\n        List<HookTarget> out = new ArrayList<>(discoverKnownTriggerCandidates(classLoader));\n        Set<String> seen = new HashSet<>();\n        for (HookTarget target : out) seen.add(target.fingerprint());\n\n        int inspected = 0;''')
text = text.replace('''            for (Method m : c.getDeclaredMethods()) {\n                if (!isRfSignatureCandidate(m)) continue;\n                int score = scoreRfMethod''',
'''            for (Method m : c.getDeclaredMethods()) {\n                if (!isRfSignatureCandidate(m) || !isRfSemanticCandidate(m)) continue;\n                int score = scoreRfMethod''')
text = text.replace('''    static boolean isRfSignatureCandidate(Method m) {\n        if (m == null) return false;\n        Class<?>[] p = m.getParameterTypes();''',
'''    static boolean isRfSignatureCandidate(Method m) {\n        if (m == null) return false;\n        int modifiers = m.getModifiers();\n        if (Modifier.isAbstract(modifiers) || m.getDeclaringClass().isInterface() || m.isBridge() || m.isSynthetic()) return false;\n        Class<?>[] p = m.getParameterTypes();''')
insert_after = '''        return r == Void.TYPE || r == Boolean.TYPE || r == Boolean.class ||\n                r == Integer.TYPE || r == Integer.class ||\n                r == Long.TYPE || r == Long.class ||\n                r == Short.TYPE || r == Short.class ||\n                Number.class.isAssignableFrom(r);\n    }\n'''
if insert_after not in text:
    raise SystemExit('rf signature tail missing')
text = text.replace(insert_after, insert_after + '''\n    static boolean isRfSemanticCandidate(Method m) {\n        String n = m.getName().toLowerCase(Locale.ROOT);\n        boolean domain = n.contains("rf") || n.contains("config") || n.contains("param");\n        boolean action = n.contains("change") || n.contains("set") || n.contains("apply") ||\n                n.contains("update") || n.contains("write");\n        return domain && action;\n    }\n''', 1)
text = text.replace('''            for (Method m : c.getDeclaredMethods()) {\n                Class<?>[] p = m.getParameterTypes();\n                if (p.length != 1 || (p[0] != boolean.class && p[0] != Boolean.class)) continue;\n                int score = scoreTriggerMethod''',
'''            for (Method m : c.getDeclaredMethods()) {\n                int modifiers = m.getModifiers();\n                if (Modifier.isAbstract(modifiers) || c.isInterface() || m.isBridge() || m.isSynthetic()) continue;\n                Class<?>[] p = m.getParameterTypes();\n                if (p.length != 1 || (p[0] != boolean.class && p[0] != Boolean.class)) continue;\n                if (!isTriggerSemanticCandidate(m)) continue;\n                int score = scoreTriggerMethod''')
marker = '''    private int scoreRfMethod(Class<?> c, Method m, boolean knownFamily) {'''
if marker not in text:
    raise SystemExit('score marker missing')
text = text.replace(marker, '''    static boolean isTriggerSemanticCandidate(Method m) {\n        String n = m.getName().toLowerCase(Locale.ROOT);\n        if (n.contains("share") || n.contains("refresh") || n.contains("reload")) return true;\n        return n.contains("rf") && (n.contains("enable") || n.contains("set") || n.contains("apply") ||\n                n.contains("update") || n.contains("rotate") || n.contains("restart"));\n    }\n\n''' + marker, 1)
p.write_text(text)

# New runtime trigger binding helper.
engine = r'''package com.example.nfcdoorcard.xposed;

import com.example.nfcdoorcard.xposed.discovery.HookTarget;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/** Captures a proven in-process OEM RF-refresh service instance and safely reuses it. */
final class RefreshTriggerEngine {
    static final class Invocation {
        final boolean success;
        final String stage;
        final String detail;
        final String targetFingerprint;

        Invocation(boolean success, String stage, String detail, String targetFingerprint) {
            this.success = success;
            this.stage = stage;
            this.detail = detail;
            this.targetFingerprint = targetFingerprint == null ? "" : targetFingerprint;
        }
    }

    private volatile HookTarget target;
    private volatile Method method;
    private volatile WeakReference<Object> instance = new WeakReference<>(null);

    synchronized boolean observe(HookTarget candidate, Method candidateMethod, Object thisObject, Object result) {
        if (candidate == null || candidateMethod == null || thisObject == null || !accepted(candidateMethod, result)) return false;
        HookTarget current = target;
        Object currentInstance = instance.get();
        if (current != null && currentInstance != null && current.score > candidate.score) return false;
        if (current != null && current.fingerprint().equals(candidate.fingerprint()) && currentInstance == thisObject) return false;
        candidateMethod.setAccessible(true);
        target = candidate;
        method = candidateMethod;
        instance = new WeakReference<>(thisObject);
        return true;
    }

    Invocation invoke(boolean enabled) {
        HookTarget t = target;
        Method m = method;
        Object receiver = instance.get();
        if (t == null || m == null || receiver == null) {
            return new Invocation(false, "JAVA_TRIGGER_UNAVAILABLE", "No verified in-process refresh trigger instance", "");
        }
        try {
            Object result = m.invoke(receiver, enabled);
            boolean ok = accepted(m, result);
            return new Invocation(ok, ok ? "JAVA_TRIGGERED" : "JAVA_TRIGGER_REJECTED",
                    t.className + "#" + t.methodName + "(" + enabled + ") result=" + String.valueOf(result),
                    t.fingerprint());
        } catch (Throwable t0) {
            Throwable cause = t0.getCause() == null ? t0 : t0.getCause();
            clear();
            return new Invocation(false, "JAVA_TRIGGER_EXCEPTION",
                    cause.getClass().getName() + ": " + String.valueOf(cause.getMessage()),
                    t.fingerprint());
        }
    }

    synchronized void clear() {
        target = null;
        method = null;
        instance = new WeakReference<>(null);
    }

    HookTarget currentTarget() { return target; }

    static boolean accepted(Method method, Object result) {
        if (method == null) return false;
        Class<?> type = method.getReturnType();
        if (type == Void.TYPE) return true;
        if (result instanceof Boolean) return (Boolean) result;
        if (result instanceof Number) return ((Number) result).longValue() == 0L;
        return false;
    }
}
'''
(ROOT / 'app/src/main/java/com/example/nfcdoorcard/xposed/RefreshTriggerEngine.java').write_text(engine)

# NfcInjectionModule: install refresh probes, capture real instances, execute Java trigger first,
# correlate accepted trigger with a subsequent RF config write, and reuse the same path for lifecycle reapply.
p = ROOT / 'app/src/main/java/com/example/nfcdoorcard/xposed/NfcInjectionModule.java'
text = p.read_text()
text = text.replace('''    private static final int MAX_LEARNING_HOOKS = 4;\n''', '''    private static final int MAX_LEARNING_HOOKS = 4;\n    private static final int MAX_TRIGGER_HOOKS = 4;\n    private static final long TRIGGER_RF_WINDOW_MS = 3_000L;\n''')
text = text.replace('''    private final NfcProcessVendorController vendorController = new NfcProcessVendorController();\n''', '''    private final NfcProcessVendorController vendorController = new NfcProcessVendorController();\n    private final RefreshTriggerEngine refreshTriggerEngine = new RefreshTriggerEngine();\n''')
text = text.replace('''    private volatile long disabledFailureGeneration = Long.MIN_VALUE;\n''', '''    private volatile long disabledFailureGeneration = Long.MIN_VALUE;\n    private volatile long pendingTriggerGeneration = Long.MIN_VALUE;\n    private volatile long pendingTriggerStartedAt;\n    private volatile long confirmedTriggerGeneration = Long.MIN_VALUE;\n    private volatile String pendingTriggerTarget = "";\n''')
text = text.replace('''        List<HookTarget> installTargets = new ArrayList<>();\n        HookTarget cached = profileStore.loadValid(app, cl);''', '''        List<HookTarget> installTargets = new ArrayList<>();\n        List<HookTarget> triggerTargets = discoveryEngine.discoverKnownTriggerCandidates(cl);\n        if (triggerTargets.isEmpty()) triggerTargets = discoveryEngine.discoverTriggerCandidates(cl);\n        persistTriggerCandidates(pid, triggerTargets);\n        HookTarget cached = profileStore.loadValid(app, cl);''')
text = text.replace('''            // Trigger candidate discovery is diagnostic-only. Avoid the expensive DEX walk on\n            // the verified-profile fast path; only refresh it when RF profile discovery is needed.\n            persistTriggerCandidates(pid, discoveryEngine.discoverTriggerCandidates(cl));\n            reportStatusWithRetry''', '''            reportStatusWithRetry''')
text = text.replace('''        installedHookCount = installed;\n        if (installed == 0) {''', '''        installedHookCount = installed;\n        int triggerHookCount = 0;\n        for (int i = 0; i < Math.min(MAX_TRIGGER_HOOKS, triggerTargets.size()); i++) {\n            HookTarget target = triggerTargets.get(i);\n            try {\n                installRefreshTriggerHook(cl, pid, target);\n                triggerHookCount++;\n            } catch (Throwable t) {\n                Log.w(TAG, "REFRESH TRIGGER HOOK skipped target=" + target + " " + t.getClass().getSimpleName() + ": " + t.getMessage());\n            }\n        }\n        persistRefreshRuntime("DISCOVERED", "", "hook-probe", 0L, false);\n        Log.i(TAG, "REFRESH TRIGGER hooks=" + triggerHookCount + " candidates=" + triggerTargets.size() + " pid=" + pid);\n        if (installed == 0) {''')
# Mark correlation as soon as the verified RF owner sees a real config payload.
text = text.replace('''            SimConfig cfg = currentConfig();\n            if (cfg.diagnostics) {''', '''            SimConfig cfg = currentConfig();\n            markRfObservedForPendingTrigger(cfg, target, pid);\n            if (cfg.diagnostics) {''', 1)
# Verification string derives from the trigger/RF causal window.
text = text.replace('''    private void completeCommand(SimConfig cfg, String rfState, String uid, String source, NativeOutcome outcome, String detail) {\n        completeCommandWithVerification(cfg, rfState, uid, source, outcome, detail, "NATIVE_RESULT");\n    }''', '''    private void completeCommand(SimConfig cfg, String rfState, String uid, String source, NativeOutcome outcome, String detail) {\n        String verification = confirmedTriggerGeneration == cfg.generation ?\n                "TRIGGER_CONFIRMED_NATIVE_RESULT" : "NATIVE_RESULT";\n        completeCommandWithVerification(cfg, rfState, uid, source, outcome, detail, verification);\n        clearTriggerWindow(cfg.generation);\n    }''')
text = text.replace('''        v.put("rf_verification", "LIFECYCLE_REAPPLY_NATIVE_RESULT");''', '''        v.put("rf_verification", confirmedTriggerGeneration == cfg.generation ?\n                "LIFECYCLE_REAPPLY_TRIGGER_CONFIRMED" : "LIFECYCLE_REAPPLY_NATIVE_RESULT");''')
text = text.replace('''        writeValuesWithRetry(v, 20, 100L);\n        Log.i(TAG, "LIFECYCLE REAPPLY success generation=" + cfg.generation + " uid=" + uid +''', '''        writeValuesWithRetry(v, 20, 100L);\n        clearTriggerWindow(cfg.generation);\n        Log.i(TAG, "LIFECYCLE REAPPLY success generation=" + cfg.generation + " uid=" + uid +''')
# Route lifecycle reapply and normal command triggering through closed-loop trigger selection.
text = text.replace('''        NfcProcessVendorController.Result trigger = vendorController.setShareMode(true);''', '''        NfcProcessVendorController.Result trigger = triggerRfRefresh(cfg, true, "lifecycle:" + reason);''', 1)
text = text.replace('''        if (!cfg.active && controllerReinitRequired) {\n            NfcProcessVendorController.Result stopTrigger = vendorController.setShareMode(false);\n            String detail = stopTrigger.detail + "; appended LA_NFCID1 requires NFC process/controller restart";\n            requestControllerRestart(cfg, detail);\n            return;\n        }\n\n        NfcProcessVendorController.Result trigger = vendorController.setShareMode(cfg.active);''', '''        if (!cfg.active && controllerReinitRequired) {\n            requestControllerRestart(cfg, "Appended LA_NFCID1 requires NFC process/controller restart; direct stock delete is not assumed safe");\n            return;\n        }\n\n        NfcProcessVendorController.Result trigger = triggerRfRefresh(cfg, cfg.active, "command:" + reason);''')
# Avoid READY from a new NFC process overwriting a still-relevant command/failure summary.
text = text.replace('''        v.put("full_diag_stage", stage);\n        v.put("full_diag_summary", summary);''', '''        v.put("hook_runtime_stage", stage);\n        v.put("hook_runtime_summary", summary);\n        SimConfig cfg = readConfig();\n        boolean commandOwnsSummary = cfg.initialized && cfg.generation > 0L &&\n                cfg.commandStatus != null && !cfg.commandStatus.isEmpty() && !"SUCCESS".equals(cfg.commandStatus);\n        if (!commandOwnsSummary) {\n            v.put("full_diag_stage", stage);\n            v.put("full_diag_summary", summary);\n        }''', 1)
# Insert refresh-trigger methods before command bridge.
marker = '''    private void startCommandBridge(Application app, int pid) {'''
if marker not in text:
    raise SystemExit('command bridge marker missing')
methods = r'''    private void installRefreshTriggerHook(ClassLoader cl, int pid, HookTarget target) throws Exception {
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
        armTriggerWindow(cfg.generation, reason);
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

    private void armTriggerWindow(long generation, String source) {
        pendingTriggerGeneration = generation;
        pendingTriggerStartedAt = System.currentTimeMillis();
        confirmedTriggerGeneration = Long.MIN_VALUE;
        pendingTriggerTarget = source == null ? "" : source;
        persistRefreshRuntime("ARMED", pendingTriggerTarget, "command", generation, false);
    }

    private void markRfObservedForPendingTrigger(SimConfig cfg, HookTarget rfTarget, int pid) {
        long generation = pendingTriggerGeneration;
        if (!cfg.initialized || generation <= 0L || cfg.generation != generation) return;
        long elapsed = System.currentTimeMillis() - pendingTriggerStartedAt;
        if (elapsed < 0L || elapsed > TRIGGER_RF_WINDOW_MS) return;
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

'''
text = text.replace(marker, methods + marker, 1)
p.write_text(text)

# Discovery tests: abstract/interface filtering plus semantic precision.
p = ROOT / 'app/src/test/java/com/example/nfcdoorcard/xposed/discovery/HookDiscoveryEngineTest.java'
text = p.read_text()
text = text.replace('''        int tooMany(int a, int b, int c, int d, byte[] data) { return 0; }\n    }''', '''        int tooMany(int a, int b, int c, int d, byte[] data) { return 0; }\n        int changeRfParamsByConfig(byte[] data) { return 0; }\n        boolean sendRawFrame(byte[] data) { return true; }\n    }\n\n    private abstract static class AbstractSamples {\n        abstract int changeRfParamsByConfig(byte[] data);\n    }''')
text = text.replace('''    @Test public void rejectsAmbiguousOrUnsupportedShapes() throws Exception {''', '''    @Test public void rejectsAbstractRfMethods() throws Exception {\n        Method method = AbstractSamples.class.getDeclaredMethod("changeRfParamsByConfig", byte[].class);\n        assertFalse(HookDiscoveryEngine.isRfSignatureCandidate(method));\n    }\n\n    @Test public void semanticFilterRejectsUnrelatedByteArrayMethods() throws Exception {\n        assertTrue(HookDiscoveryEngine.isRfSemanticCandidate(m("changeRfParamsByConfig", byte[].class)));\n        assertFalse(HookDiscoveryEngine.isRfSemanticCandidate(m("sendRawFrame", byte[].class)));\n    }\n\n    @Test public void rejectsAmbiguousOrUnsupportedShapes() throws Exception {''')
p.write_text(text)

# Unit test for runtime trigger binding / result interpretation.
test = r'''package com.example.nfcdoorcard.xposed;

import com.example.nfcdoorcard.xposed.discovery.Capability;
import com.example.nfcdoorcard.xposed.discovery.HookTarget;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class RefreshTriggerEngineTest {
    private static final class SampleService {
        boolean last;
        public boolean enableNfcShareMode(boolean enabled) { last = enabled; return true; }
    }

    private static final class RejectingService {
        public boolean enableNfcShareMode(boolean enabled) { return false; }
    }

    private HookTarget target(Method m, int score) {
        return HookTarget.fromMethod(Capability.RF_REFRESH_TRIGGER, m, score, "test");
    }

    @Test public void observedInstanceCanBeReused() throws Exception {
        RefreshTriggerEngine engine = new RefreshTriggerEngine();
        SampleService service = new SampleService();
        Method method = SampleService.class.getDeclaredMethod("enableNfcShareMode", boolean.class);
        assertTrue(engine.observe(target(method, 300), method, service, true));
        RefreshTriggerEngine.Invocation result = engine.invoke(true);
        assertTrue(result.success);
        assertTrue(service.last);
        assertEquals("JAVA_TRIGGERED", result.stage);
    }

    @Test public void rejectedObservationDoesNotBind() throws Exception {
        RefreshTriggerEngine engine = new RefreshTriggerEngine();
        RejectingService service = new RejectingService();
        Method method = RejectingService.class.getDeclaredMethod("enableNfcShareMode", boolean.class);
        assertFalse(engine.observe(target(method, 300), method, service, false));
        assertFalse(engine.invoke(true).success);
    }
}
'''
(ROOT / 'app/src/test/java/com/example/nfcdoorcard/xposed/RefreshTriggerEngineTest.java').write_text(test)

print('trigger closed-loop patch applied')
