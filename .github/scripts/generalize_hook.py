from pathlib import Path

root = Path('.')

def replace(path, old, new, count=None):
    p = root / path
    s = p.read_text()
    n = s.count(old)
    if n == 0:
        raise SystemExit(f'pattern not found in {path}: {old[:120]!r}')
    if count is not None and n != count:
        raise SystemExit(f'expected {count} matches in {path}, got {n}: {old[:120]!r}')
    p.write_text(s.replace(old, new))

# Version / hook protocol bump.
replace('app/build.gradle.kts', 'versionCode = 29', 'versionCode = 30', 1)
replace('app/build.gradle.kts', 'versionName = "1.0.29"', 'versionName = "1.0.30"', 1)
replace('app/build.gradle.kts', 'buildConfigField("int", "HOOK_BUILD", "22")', 'buildConfigField("int", "HOOK_BUILD", "23")', 1)

# Replace discovery with capability-oriented signature support: one byte[] among up to 4 args,
# including void/boolean/numeric return families. Runtime payload validation still decides ownership.
discovery = r'''package com.example.nfcdoorcard.xposed.discovery;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Capability-oriented hook discovery for RF writes and refresh triggers. */
public final class HookDiscoveryEngine {
    private static final int MAX_CLASSES = 1800;
    private static final int MAX_RESULTS = 12;
    private static final int MAX_RF_PARAMS = 4;

    private static final String[] PROVEN_RF_CLASSES = new String[] {
            "com.android.nfc.dhimpl.NxpNativeNfcManager",
            "com.android.nfc.nxp.NxpNfcService$NxpNfcAdapterService",
            "com.android.nfc.nxp.NxpNfcService"
    };

    private static final String[] PROVEN_TRIGGER_CLASSES = new String[] {
            "com.android.nfc.VendorNfcService$VendorNfcAdapterService",
            "com.android.nfc.nxp.NxpNfcService$NxpNfcAdapterService"
    };

    public HookTarget discoverRfConfigWrite(ClassLoader classLoader) {
        List<HookTarget> ranked = discoverRfCandidates(classLoader);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    public List<HookTarget> discoverRfCandidates(ClassLoader classLoader) {
        List<HookTarget> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : PROVEN_RF_CLASSES) inspectRfClass(classLoader, name, "known-family", out, seen, true);

        int inspected = 0;
        for (String name : enumerateClassNames(classLoader)) {
            if (inspected >= MAX_CLASSES) break;
            if (!looksNfcRelated(name)) continue;
            inspected++;
            inspectRfClass(classLoader, name, "dex-scan", out, seen, false);
        }
        return top(out);
    }

    public List<HookTarget> discoverTriggerCandidates(ClassLoader classLoader) {
        List<HookTarget> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : PROVEN_TRIGGER_CLASSES) inspectTriggerClass(classLoader, name, "known-family", out, seen, true);

        int inspected = 0;
        for (String name : enumerateClassNames(classLoader)) {
            if (inspected >= MAX_CLASSES) break;
            if (!looksNfcRelated(name)) continue;
            inspected++;
            inspectTriggerClass(classLoader, name, "dex-scan", out, seen, false);
        }
        return top(out);
    }

    private List<HookTarget> top(List<HookTarget> out) {
        out.sort(Comparator.comparingInt((HookTarget t) -> t.score).reversed());
        if (out.size() > MAX_RESULTS) return new ArrayList<>(out.subList(0, MAX_RESULTS));
        return out;
    }

    private void inspectRfClass(ClassLoader cl, String className, String source,
                                List<HookTarget> out, Set<String> seen, boolean knownFamily) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!isRfSignatureCandidate(m)) continue;
                int score = scoreRfMethod(c, m, knownFamily);
                if (score < 40) continue;
                HookTarget target = HookTarget.fromMethod(Capability.RF_CONFIG_WRITE, m, score, source);
                if (seen.add(target.fingerprint())) out.add(target);
            }
        } catch (Throwable ignored) { }
    }

    /** Package-visible for unit tests. Runtime payload inspection is the final safety gate. */
    static boolean isRfSignatureCandidate(Method m) {
        if (m == null) return false;
        Class<?>[] p = m.getParameterTypes();
        if (p.length < 1 || p.length > MAX_RF_PARAMS) return false;
        int bytes = 0;
        for (Class<?> type : p) if (type == byte[].class) bytes++;
        if (bytes != 1) return false;
        Class<?> r = m.getReturnType();
        return r == Void.TYPE || r == Boolean.TYPE || r == Boolean.class ||
                r == Integer.TYPE || r == Integer.class ||
                r == Long.TYPE || r == Long.class ||
                r == Short.TYPE || r == Short.class ||
                Number.class.isAssignableFrom(r);
    }

    private void inspectTriggerClass(ClassLoader cl, String className, String source,
                                     List<HookTarget> out, Set<String> seen, boolean knownFamily) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || (p[0] != boolean.class && p[0] != Boolean.class)) continue;
                int score = scoreTriggerMethod(c, m, knownFamily);
                if (score < 45) continue;
                HookTarget target = HookTarget.fromMethod(Capability.RF_REFRESH_TRIGGER, m, score, source);
                if (seen.add(target.fingerprint())) out.add(target);
            }
        } catch (Throwable ignored) { }
    }

    private int scoreRfMethod(Class<?> c, Method m, boolean knownFamily) {
        int score = 35;
        String cn = c.getName().toLowerCase(Locale.ROOT);
        String mn = m.getName().toLowerCase(Locale.ROOT);
        String rt = m.getReturnType().getName();
        if (knownFamily) score += 80;
        if (cn.contains("nfc")) score += 20;
        if (cn.contains("nxp")) score += 20;
        if (cn.contains("native")) score += 60;
        if (cn.contains("dhimpl") || cn.contains("devicehost")) score += 20;
        if (cn.contains("adapterservice")) score -= 35;
        if (cn.contains("oplus")) score += 10;
        if (cn.contains("manager")) score += 10;
        if (cn.contains("service")) score += 2;
        if (mn.contains("rf")) score += 25;
        if (mn.contains("config")) score += 30;
        if (mn.contains("param")) score += 20;
        if (mn.contains("change") || mn.contains("set") || mn.contains("apply") || mn.contains("update")) score += 10;
        if ("int".equals(rt) || "java.lang.Integer".equals(rt)) score += 15;
        if ("boolean".equals(rt) || "java.lang.Boolean".equals(rt)) score += 5;
        if ("void".equals(rt)) score += 2;
        if (m.getParameterTypes().length == 1) score += 12;
        else score -= (m.getParameterTypes().length - 1) * 4;
        if ("changeRfParamsByConfig".equals(m.getName())) score += 100;
        return score;
    }

    private int scoreTriggerMethod(Class<?> c, Method m, boolean knownFamily) {
        int score = 25;
        String cn = c.getName().toLowerCase(Locale.ROOT);
        String mn = m.getName().toLowerCase(Locale.ROOT);
        String rt = m.getReturnType().getName();
        if (knownFamily) score += 70;
        if (cn.contains("nfc")) score += 20;
        if (cn.contains("vendor") || cn.contains("oplus") || cn.contains("nxp")) score += 15;
        if (cn.contains("service") || cn.contains("adapter")) score += 10;
        if (mn.contains("share")) score += 45;
        if (mn.contains("rf")) score += 25;
        if (mn.contains("refresh") || mn.contains("reload")) score += 25;
        if (mn.contains("enable") || mn.contains("set") || mn.contains("apply")) score += 15;
        if ("boolean".equals(rt) || "java.lang.Boolean".equals(rt)) score += 15;
        if ("enableNfcShareMode".equals(m.getName())) score += 100;
        return score;
    }

    private boolean looksNfcRelated(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("nfc") || n.contains("nxp") || n.contains("oplus") || n.contains("rfconfig") ||
                n.contains("devicehost") || n.contains("nci") || n.contains("native") || n.contains("hal");
    }

    private List<String> enumerateClassNames(ClassLoader classLoader) {
        List<String> names = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        try {
            Class<?> baseDex = Class.forName("dalvik.system.BaseDexClassLoader");
            if (!baseDex.isInstance(classLoader)) return names;
            Field pathListField = baseDex.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);
            if (pathList == null) return names;

            Field elementsField = pathList.getClass().getDeclaredField("dexElements");
            elementsField.setAccessible(true);
            Object[] elements = (Object[]) elementsField.get(pathList);
            if (elements == null) return names;

            for (Object element : elements) {
                if (element == null) continue;
                Field dexFileField = findField(element.getClass(), "dexFile");
                if (dexFileField == null) continue;
                dexFileField.setAccessible(true);
                Object dexFile = dexFileField.get(element);
                if (dexFile == null) continue;
                Method entriesMethod = dexFile.getClass().getMethod("entries");
                @SuppressWarnings("unchecked")
                Enumeration<String> entries = (Enumeration<String>) entriesMethod.invoke(dexFile);
                while (entries != null && entries.hasMoreElements()) {
                    String name = entries.nextElement();
                    if (name != null && unique.add(name)) names.add(name);
                }
            }
        } catch (Throwable ignored) { }
        return names;
    }

    private Field findField(Class<?> c, String name) {
        for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
            try { return cur.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }
}
'''
(root / 'app/src/main/java/com/example/nfcdoorcard/xposed/discovery/HookDiscoveryEngine.java').write_text(discovery)

# Proxy-first Binder capability adapter. Transaction fallback uses reflected IDs only: no magic numbers.
controller = r'''package com.example.nfcdoorcard.xposed;

import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Executes OEM RF triggers and NFC controller lifecycle operations from com.android.nfc. */
final class NfcProcessVendorController {
    private static final String NFC_SERVICE_NAME = "nfc";
    private static final String INFC_DESCRIPTOR = "android.nfc.INfcAdapter";
    private static final String INFC_STUB = "android.nfc.INfcAdapter$Stub";
    private static final String VENDOR_NAME = "vendor";
    private static final String VENDOR_DESCRIPTOR = "com.vendor.nfc.IVendorNfcAdapter";
    private static final String VENDOR_STUB = "com.vendor.nfc.IVendorNfcAdapter$Stub";
    private static final int STARTUP_RETRY_COUNT = 30;
    private static final long STARTUP_RETRY_DELAY_MS = 100L;

    static final class Result {
        final boolean success;
        final String stage;
        final String detail;
        final String vendorDescriptor;

        Result(boolean success, String stage, String detail, String vendorDescriptor) {
            this.success = success;
            this.stage = stage;
            this.detail = detail;
            this.vendorDescriptor = vendorDescriptor;
        }
    }

    Result setShareMode(boolean enabled) {
        Result last = null;
        for (int attempt = 0; attempt < STARTUP_RETRY_COUNT; attempt++) {
            last = setShareModeOnce(enabled);
            if (last.success || !isTransient(last.stage)) return last;
            if (attempt + 1 < STARTUP_RETRY_COUNT) sleep(STARTUP_RETRY_DELAY_MS);
        }
        if (last == null) return new Result(false, "UNKNOWN", "No vendor result", null);
        return new Result(false, last.stage,
                last.detail + " after " + STARTUP_RETRY_COUNT + " attempts/~" +
                        ((STARTUP_RETRY_COUNT - 1) * STARTUP_RETRY_DELAY_MS) + "ms",
                last.vendorDescriptor);
    }

    /** Experimental lifecycle capability; normal STOP currently uses process restart. */
    Result reinitializeController() {
        try {
            IBinder main = serviceManagerBinder();
            if (!alive(main)) return new Result(false, "CONTROLLER_BINDER", "NFC binder unavailable", null);
            if (!INFC_DESCRIPTOR.equals(safeDescriptor(main))) {
                return new Result(false, "CONTROLLER_DESCRIPTOR", "Unexpected descriptor=" + safeDescriptor(main), null);
            }
            Object proxy = asInterface(INFC_STUB, main);
            if (proxy == null) return new Result(false, "CONTROLLER_PROXY", "INfcAdapter Stub.asInterface unavailable", null);
            Boolean disabled = invokeBooleanMethod(proxy, "disable", new Class<?>[]{boolean.class}, new Object[]{false});
            if (!Boolean.TRUE.equals(disabled)) return new Result(false, "CONTROLLER_DISABLE", "INfcAdapter.disable(false) unavailable/rejected", null);
            sleep(500L);
            IBinder after = serviceManagerBinder();
            if (!alive(after)) after = main;
            Object afterProxy = asInterface(INFC_STUB, after);
            Boolean enabled = invokeBooleanMethod(afterProxy, "enable", new Class<?>[0], new Object[0]);
            if (!Boolean.TRUE.equals(enabled)) return new Result(false, "CONTROLLER_ENABLE", "INfcAdapter.enable() unavailable/rejected", null);
            return new Result(true, "CONTROLLER_REINITIALIZED", "NFC controller reinitialized through reflected AIDL proxy", null);
        } catch (Throwable t) {
            return new Result(false, "CONTROLLER_EXCEPTION", t.getClass().getName() + ": " + String.valueOf(t.getMessage()), null);
        }
    }

    private Result setShareModeOnce(boolean enabled) {
        try {
            IBinder main = serviceManagerBinder();
            if (!alive(main)) return new Result(false, "MAIN_BINDER", "NFC binder unavailable", null);
            String mainDescriptor = safeDescriptor(main);
            if (!INFC_DESCRIPTOR.equals(mainDescriptor)) return new Result(false, "MAIN_DESCRIPTOR", "Unexpected descriptor=" + mainDescriptor, null);

            // Preferred path: generated AIDL proxies. This follows the runtime method ABI and does
            // not depend on transaction numbering. If the OEM interface cannot be reflected, use
            // only transaction IDs reflected from the current Stub; never guess a numeric ID.
            Object mainProxy = asInterface(INFC_STUB, main);
            IBinder vendor = getVendorBinderViaProxy(mainProxy);
            String path = "aidl-proxy";
            if (vendor == null) {
                int txGetVendor = resolveRequiredTransaction(INFC_STUB, "TRANSACTION_getNfcAdapterVendorInterface");
                if (txGetVendor <= 0) return new Result(false, "GET_VENDOR_CAPABILITY", "No proxy method or reflected vendor transaction", null);
                vendor = getVendorBinderTransact(main, txGetVendor);
                path = "reflected-transaction";
            }
            if (!alive(vendor)) return new Result(false, "GET_VENDOR_BINDER", "Vendor binder unavailable", null);

            String vendorDescriptor = safeDescriptor(vendor);
            if (!VENDOR_DESCRIPTOR.equals(vendorDescriptor)) {
                return new Result(false, "VENDOR_DESCRIPTOR", "Unexpected descriptor=" + vendorDescriptor, vendorDescriptor);
            }

            Object vendorProxy = asInterface(VENDOR_STUB, vendor);
            Boolean proxyAccepted = invokeBooleanMethod(vendorProxy, "enableNfcShareMode",
                    new Class<?>[]{boolean.class}, new Object[]{enabled});
            if (proxyAccepted != null) {
                return new Result(proxyAccepted, proxyAccepted ? "TRIGGERED" : "SHARE_MODE",
                        "enableNfcShareMode(" + enabled + ") via AIDL proxy", vendorDescriptor);
            }

            int txShare = resolveRequiredTransaction(VENDOR_STUB, "TRANSACTION_enableNfcShareMode");
            if (txShare <= 0) return new Result(false, "SHARE_MODE_CAPABILITY", "No proxy method or reflected share-mode transaction", vendorDescriptor);
            boolean accepted = transactShareMode(vendor, enabled, txShare);
            return new Result(accepted, accepted ? "TRIGGERED" : "SHARE_MODE",
                    "enableNfcShareMode(" + enabled + ") via " + path + "/reflected-tx=" + txShare,
                    vendorDescriptor);
        } catch (Throwable t) {
            return new Result(false, "EXCEPTION", t.getClass().getName() + ": " + String.valueOf(t.getMessage()), null);
        }
    }

    private static boolean isTransient(String stage) {
        return "MAIN_BINDER".equals(stage) || "GET_VENDOR_BINDER".equals(stage) || "EXCEPTION".equals(stage);
    }

    private static IBinder serviceManagerBinder() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method method = sm.getDeclaredMethod("getService", String.class);
            method.setAccessible(true);
            return (IBinder) method.invoke(null, NFC_SERVICE_NAME);
        } catch (Throwable ignored) { return null; }
    }

    private static Object asInterface(String stubClass, IBinder binder) {
        if (binder == null) return null;
        try {
            Class<?> stub = Class.forName(stubClass);
            Method m = stub.getDeclaredMethod("asInterface", IBinder.class);
            m.setAccessible(true);
            return m.invoke(null, binder);
        } catch (Throwable ignored) { return null; }
    }

    private static IBinder getVendorBinderViaProxy(Object proxy) {
        if (proxy == null) return null;
        try {
            for (Method m : proxy.getClass().getMethods()) {
                if (!"getNfcAdapterVendorInterface".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == String.class) {
                    m.setAccessible(true);
                    Object result = m.invoke(proxy, VENDOR_NAME);
                    if (result instanceof IBinder) return (IBinder) result;
                    if (result != null) {
                        try {
                            Method asBinder = result.getClass().getMethod("asBinder");
                            Object b = asBinder.invoke(result);
                            if (b instanceof IBinder) return (IBinder) b;
                        } catch (Throwable ignored) { }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Returns null when the capability/signature is absent, otherwise the boolean result. */
    private static Boolean invokeBooleanMethod(Object proxy, String name, Class<?>[] params, Object[] args) {
        if (proxy == null) return null;
        try {
            Method m = proxy.getClass().getMethod(name, params);
            m.setAccessible(true);
            Object result = m.invoke(proxy, args);
            if (m.getReturnType() == Void.TYPE) return Boolean.TRUE;
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Throwable t) {
            Throwable cause = t.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause == null ? t : cause);
        }
    }

    private static int resolveRequiredTransaction(String stubClass, String fieldName) {
        try {
            Class<?> c = Class.forName(stubClass);
            Field f = c.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) { return -1; }
    }

    private static IBinder getVendorBinderTransact(IBinder mainBinder, int transaction) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INFC_DESCRIPTOR);
            data.writeString(VENDOR_NAME);
            if (!mainBinder.transact(transaction, data, reply, 0)) return null;
            reply.setDataPosition(0);
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean transactShareMode(IBinder vendor, boolean enabled, int transaction) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VENDOR_DESCRIPTOR);
            data.writeBoolean(enabled);
            if (!vendor.transact(transaction, data, reply, 0)) return false;
            reply.setDataPosition(0);
            reply.readException();
            return reply.dataAvail() >= 4 && reply.readBoolean();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean alive(IBinder binder) {
        return binder != null && binder.isBinderAlive() && binder.pingBinder();
    }

    private static String safeDescriptor(IBinder binder) {
        try {
            String value = binder.getInterfaceDescriptor();
            return value == null ? "" : value;
        } catch (Throwable ignored) { return ""; }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
'''
(root / 'app/src/main/java/com/example/nfcdoorcard/xposed/NfcProcessVendorController.java').write_text(controller)

# Generalize the RF hook hot path to locate the single byte[] argument rather than requiring arg[0].
mod = root / 'app/src/main/java/com/example/nfcdoorcard/xposed/NfcInjectionModule.java'
s = mod.read_text()
old = '''            Object[] args = chain.getArgs().toArray();
            if (args.length != 1 || !(args[0] instanceof byte[])) return chain.proceed();
            byte[] original = (byte[]) args[0];'''
new = '''            Object[] args = chain.getArgs().toArray();
            int payloadArg = findSingleByteArrayArg(args);
            if (payloadArg < 0) return chain.proceed();
            byte[] original = (byte[]) args[payloadArg];'''
if s.count(old) != 1: raise SystemExit('RF arg pattern mismatch')
s = s.replace(old, new)
old = 'Object result = chain.proceed(new Object[]{stock.clone()});'
new = 'Object result = proceedWithByteArrayArg(chain, args, payloadArg, stock.clone());'
if s.count(old) != 1: raise SystemExit('stock proceed pattern mismatch')
s = s.replace(old, new)
old = 'Object result = chain.proceed(new Object[]{rewritten.data});'
new = 'Object result = proceedWithByteArrayArg(chain, args, payloadArg, rewritten.data);'
if s.count(old) != 1: raise SystemExit('rewrite proceed pattern mismatch')
s = s.replace(old, new)
anchor = '''    private static NativeOutcome interpretNativeResult(Method method, Object result) {'''
helper = '''    private static int findSingleByteArrayArg(Object[] args) {
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

    private static Object proceedWithByteArrayArg(io.github.libxposed.api.XposedInterface.AfterHookCallback chain,
                                                   Object[] originalArgs, int payloadArg, byte[] payload) throws Throwable {
        Object[] changed = originalArgs.clone();
        changed[payloadArg] = payload;
        return chain.proceed(changed);
    }

'''
# The callback concrete type above may not be the interceptor chain type; use generic helper-free replacement instead.
# Replace with inline cloned args to avoid coupling to a libxposed internal callback type.
s = s.replace(new, '''Object[] stockArgs = args.clone();
                        stockArgs[payloadArg] = stock.clone();
                        Object result = chain.proceed(stockArgs);''', 1)
s = s.replace('Object result = proceedWithByteArrayArg(chain, args, payloadArg, rewritten.data);', '''Object[] rewrittenArgs = args.clone();
            rewrittenArgs[payloadArg] = rewritten.data;
            Object result = chain.proceed(rewrittenArgs);''', 1)
helper = '''    private static int findSingleByteArrayArg(Object[] args) {
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

'''
if s.count(anchor) != 1: raise SystemExit('native outcome anchor mismatch')
s = s.replace(anchor, helper + anchor)
mod.write_text(s)

# Discovery signature regression tests.
test = r'''package com.example.nfcdoorcard.xposed.discovery;

import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class HookDiscoveryEngineTest {
    private static class Samples {
        int one(byte[] data) { return 0; }
        void oneVoid(byte[] data) { }
        boolean second(int flags, byte[] data) { return true; }
        long fourth(String a, int b, boolean c, byte[] data) { return 0; }
        String badReturn(byte[] data) { return "x"; }
        int noBytes(int x) { return 0; }
        int twoBytes(byte[] a, byte[] b) { return 0; }
        int tooMany(int a, int b, int c, int d, byte[] data) { return 0; }
    }

    private Method m(String name, Class<?>... p) throws Exception { return Samples.class.getDeclaredMethod(name, p); }

    @Test public void acceptsSupportedRfShapes() throws Exception {
        assertTrue(HookDiscoveryEngine.isRfSignatureCandidate(m("one", byte[].class)));
        assertTrue(HookDiscoveryEngine.isRfSignatureCandidate(m("oneVoid", byte[].class)));
        assertTrue(HookDiscoveryEngine.isRfSignatureCandidate(m("second", int.class, byte[].class)));
        assertTrue(HookDiscoveryEngine.isRfSignatureCandidate(m("fourth", String.class, int.class, boolean.class, byte[].class)));
    }

    @Test public void rejectsAmbiguousOrUnsupportedShapes() throws Exception {
        assertFalse(HookDiscoveryEngine.isRfSignatureCandidate(m("badReturn", byte[].class)));
        assertFalse(HookDiscoveryEngine.isRfSignatureCandidate(m("noBytes", int.class)));
        assertFalse(HookDiscoveryEngine.isRfSignatureCandidate(m("twoBytes", byte[].class, byte[].class)));
        assertFalse(HookDiscoveryEngine.isRfSignatureCandidate(m("tooMany", int.class, int.class, int.class, int.class, byte[].class)));
    }
}
'''
tp = root / 'app/src/test/java/com/example/nfcdoorcard/xposed/discovery/HookDiscoveryEngineTest.java'
tp.parent.mkdir(parents=True, exist_ok=True)
tp.write_text(test)

print('hook generalization patch applied')
