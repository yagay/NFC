package com.example.nfcdoorcard.xposed;

import android.app.Application;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** LSPosed/libxposed API 102 diagnostics module. Observation only. */
public class NfcDiagnosticsModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.uidconfig/target");
    private static final int MAX_STACK_FRAMES = 10;
    private static final int MAX_AUTO_TRACE_PARAMS = 4;

    private static final String[] CANDIDATE_KEYWORDS = {
            "config", "core", "vendor", "raw", "write", "rf", "listen", "discovery", "nfcid", "uid",
            "hce", "polling", "card", "transit", "tap", "access"
    };

    private static final String[] DIRECT_RUNTIME_CLASS_CANDIDATES = {
            "com.android.nfc.dhimpl.NxpNativeNfcManager",
            "com.android.nfc.NxpNativeNfcManager",
            "com.android.nfc.dhimpl.NativeNfcManager",
            "com.android.nfc.dhimpl.StNativeNfcManager",
            "com.android.nfc.StNativeNfcManager"
    };

    private static final String[] CONTROL_FLOW_CLASS_CANDIDATES = {
            "com.android.nfc.VendorNfcService",
            "com.android.nfc.nxp.NxpNfcService$NxpNfcAdapterService",
            "com.android.nfc.nxp.NxpNfcService"
    };

    private final Set<String> installedTraceHooks = new HashSet<>();
    private final Set<String> inspectedRuntimeClasses = new HashSet<>();

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        info("onModuleLoaded process=" + param.getProcessName() + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
        super.onPackageLoaded(lp);
        if (!"com.android.nfc".equals(lp.getPackageName())) return;
        info("onPackageLoaded package=com.android.nfc");
        installComprehensiveTrace(lp.getDefaultClassLoader());
    }

    private void installComprehensiveTrace(ClassLoader cl) {
        info("NFC-FLOW comprehensive observation begin; no NFC/vendor command will be invoked");

        Class<?> deviceHost = loadOptional(cl, "com.android.nfc.DeviceHost");
        Class<?> nfcService = loadOptional(cl, "com.android.nfc.NfcService");

        probeKnownRuntimeClasses(cl);
        probeControlFlowClasses(cl);

        if (nfcService != null) {
            installNfcServiceConstructorProbe(nfcService, deviceHost);
            installTraceHook(nfcService, "getNfcListenTech");
            installTraceHook(nfcService, "saveNfcListenTech", int.class);
            installTraceHook(nfcService, "clearListenTech", boolean.class);
            installTraceHook(nfcService, "getNfcPollTech");
            installTraceHook(nfcService, "saveNfcPollTech", int.class);
            installNamedTraceHooks(nfcService, "restoreSavedTech");
            installNamedTraceHooks(nfcService, "applyRouting");
        } else {
            warn("NFC-FLOW NfcService class unavailable");
        }

        info("NFC-FLOW comprehensive hooks requested");
    }

    private void probeKnownRuntimeClasses(ClassLoader cl) {
        info("NFC-RUNTIME DIRECT PROBE BEGIN");
        int found = 0;
        for (String className : DIRECT_RUNTIME_CLASS_CANDIDATES) {
            try {
                Class<?> runtime = Class.forName(className, false, cl);
                found++;
                info("NFC-RUNTIME DIRECT CLASS FOUND " + runtime.getName());
                inspectAndTraceRuntimeClass(runtime);
                installHostTraceHooks(runtime);
            } catch (ClassNotFoundException e) {
                info("NFC-RUNTIME DIRECT CLASS ABSENT " + className);
            } catch (Throwable t) {
                warn("NFC-RUNTIME DIRECT CLASS ERROR " + className + " / " + t.getClass().getSimpleName(), t);
            }
        }
        info("NFC-RUNTIME DIRECT PROBE END found=" + found);
    }

    private void probeControlFlowClasses(ClassLoader cl) {
        info("NFC-FLOW CONTROL PROBE BEGIN");
        int found = 0;
        for (String className : CONTROL_FLOW_CLASS_CANDIDATES) {
            try {
                Class<?> control = Class.forName(className, false, cl);
                found++;
                info("NFC-FLOW CLASS FOUND " + control.getName());
                enumerateAndHookControlMethods(control);
            } catch (ClassNotFoundException e) {
                info("NFC-FLOW CLASS ABSENT " + className);
            } catch (Throwable t) {
                warn("NFC-FLOW CLASS ERROR " + className + " / " + t.getClass().getSimpleName(), t);
            }
        }
        info("NFC-FLOW CONTROL PROBE END found=" + found);
    }

    private void enumerateAndHookControlMethods(Class<?> control) {
        int candidates = 0;
        int hooked = 0;
        for (Method method : control.getDeclaredMethods()) {
            if (!isControlFlowMethod(method)) continue;
            candidates++;
            info("NFC-FLOW METHOD " + formatMethod(method));
            if (method.getParameterCount() <= MAX_AUTO_TRACE_PARAMS) {
                installTraceHook(control, method.getName(), method.getParameterTypes());
                hooked++;
            } else {
                info("NFC-FLOW METHOD not auto-hooked params=" + method.getParameterCount() + " name=" + method.getName());
            }
        }
        info("NFC-FLOW METHOD ENUM END class=" + control.getName() + " candidates=" + candidates + " hookRequests=" + hooked);
    }

    private boolean isControlFlowMethod(Method method) {
        if (method.isSynthetic() || method.isBridge()) return false;
        String name = method.getName().toLowerCase(Locale.ROOT);
        if (name.startsWith("access$")) return false;
        return name.contains("config") || name.contains("hce") || name.contains("polling")
                || name.contains("card") || name.contains("transit") || name.contains("tap")
                || name.contains("access") || name.contains("rf") || name.contains("discovery");
    }

    private Class<?> loadOptional(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (ClassNotFoundException e) {
            info("NFC-TRACE class absent: " + name);
            return null;
        } catch (Throwable t) {
            warn("NFC-TRACE unable to load " + name + ": " + t.getClass().getSimpleName());
            return null;
        }
    }

    private void installNfcServiceConstructorProbe(Class<?> nfcService, Class<?> deviceHost) {
        int installed = 0;
        for (Constructor<?> constructor : nfcService.getDeclaredConstructors()) {
            try {
                hook(constructor).intercept(chain -> {
                    Object thisObject = chain.getThisObject();
                    Object result = chain.proceed();
                    try {
                        inspectNfcServiceInstance(thisObject, deviceHost);
                    } catch (Throwable t) {
                        warn("NFC-RUNTIME DeviceHost inspection failed: " + t.getClass().getSimpleName(), t);
                    }
                    return result;
                });
                installed++;
            } catch (Throwable t) {
                warn("NFC-RUNTIME constructor hook failed: " + t.getClass().getSimpleName(), t);
            }
        }
        info("NFC-RUNTIME constructor probes installed=" + installed);
    }

    private void inspectNfcServiceInstance(Object service, Class<?> deviceHost) {
        if (service == null) {
            warn("NFC-RUNTIME NfcService thisObject=null");
            return;
        }

        Object host = null;
        String hostField = null;
        Class<?> cursor = service.getClass();
        while (cursor != null && cursor != Object.class) {
            for (Field field : cursor.getDeclaredFields()) {
                try {
                    boolean declaredAsDeviceHost = deviceHost != null && deviceHost.isAssignableFrom(field.getType());
                    if (!declaredAsDeviceHost && !field.getName().toLowerCase(Locale.ROOT).contains("devicehost")) continue;
                    field.setAccessible(true);
                    Object value = field.get(service);
                    if (value != null && deviceHost != null && deviceHost.isInstance(value)) {
                        host = value;
                        hostField = field.getName();
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (host != null) break;
            cursor = cursor.getSuperclass();
        }

        if (host == null) {
            warn("NFC-RUNTIME DeviceHost instance not found");
            return;
        }

        Class<?> runtime = host.getClass();
        info("NFC-RUNTIME DeviceHost field=" + hostField);
        info("NFC-RUNTIME DeviceHost class=" + runtime.getName());
        info("NFC-RUNTIME DeviceHost interfaces=" + joinTypes(runtime.getInterfaces()));
        inspectAndTraceRuntimeClass(runtime);
        installHostTraceHooks(runtime);
        logConfiguredTestRequest();
    }

    private void inspectAndTraceRuntimeClass(Class<?> runtime) {
        synchronized (inspectedRuntimeClasses) {
            if (!inspectedRuntimeClasses.add(runtime.getName())) return;
        }

        info("NFC-RUNTIME METHOD ENUM BEGIN class=" + runtime.getName());
        int total = 0;
        int candidates = 0;
        for (Method method : runtime.getDeclaredMethods()) {
            total++;
            if (!isCandidateMethod(method)) continue;
            candidates++;
            info("NFC-RUNTIME CANDIDATE " + formatMethod(method));
            if (method.getParameterCount() <= MAX_AUTO_TRACE_PARAMS) {
                installTraceHook(runtime, method.getName(), method.getParameterTypes());
            } else {
                info("NFC-RUNTIME candidate not auto-hooked (params=" + method.getParameterCount() + "): " + method.getName());
            }
        }
        info("NFC-RUNTIME METHOD ENUM END class=" + runtime.getName() + " total=" + total + " candidates=" + candidates);
    }

    private boolean isCandidateMethod(Method method) {
        if (method.isSynthetic() || method.isBridge()) return false;
        String name = method.getName().toLowerCase(Locale.ROOT);
        for (String keyword : CANDIDATE_KEYWORDS) {
            if (name.contains(keyword)) return true;
        }
        return false;
    }

    private void installHostTraceHooks(Class<?> runtime) {
        installTraceHook(runtime, "changeRfParams", byte[].class, boolean.class);
        installTraceHook(runtime, "changeRfParamsByConfig", byte[].class);
        installTraceHook(runtime, "doWriteData", byte[].class, byte[].class);
        installTraceHook(runtime, "nativeSendRawVendorCmd", int.class, int.class, int.class, byte[].class);
        installTraceHook(runtime, "setDiscoveryTech", int.class, int.class);
        installTraceHook(runtime, "resetDiscoveryTech");
        installTraceHook(runtime, "restartRfDiscovery");
        installTraceHook(runtime, "doRestartRFDiscovery");
    }

    private void installNamedTraceHooks(Class<?> runtime, String methodName) {
        int found = 0;
        for (Method method : runtime.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())) continue;
            found++;
            installTraceHook(runtime, methodName, method.getParameterTypes());
        }
        if (found == 0) info("NFC-TRACE named method absent: " + runtime.getName() + "." + methodName);
    }

    private void installTraceHook(Class<?> runtime, String methodName, Class<?>... parameterTypes) {
        String key = runtime.getName() + "#" + methodName + Arrays.toString(parameterTypes);
        synchronized (installedTraceHooks) {
            if (installedTraceHooks.contains(key)) return;
        }

        try {
            Method method = runtime.getDeclaredMethod(methodName, parameterTypes);
            hook(method).intercept(chain -> {
                Object thisObject = chain.getThisObject();
                Object[] argsArray = chain.getArgs().toArray();
                String callName = runtime.getSimpleName() + "." + methodName;
                info("NFC-TRACE ENTER " + callName + " args=" + summarizeArgs(argsArray));
                logPayloadDigests(methodName, argsArray);
                if (shouldLogBinderCaller(runtime, methodName)) logBinderCaller(callName);
                if (isImportantFlowMethod(methodName)) logConfiguredTestRequest();

                if ("restoreSavedTech".equals(methodName)) {
                    logActualSavedTech(thisObject, "BEFORE");
                }
                logDiscoveryMasks(methodName, argsArray);
                logShortStack(callName);

                try {
                    Object result = chain.proceed();
                    if ("restoreSavedTech".equals(methodName)) {
                        logActualSavedTech(thisObject, "AFTER");
                    }
                    if (result instanceof Integer) {
                        logIntBreakdown(methodName + ".result", (Integer) result);
                    }
                    info("NFC-TRACE RETURN " + callName + " result=" + summarizeValue(result));
                    return result;
                } catch (Throwable t) {
                    warn("NFC-TRACE THROW " + callName + " exception=" + t.getClass().getName());
                    throw t;
                }
            });
            synchronized (installedTraceHooks) {
                installedTraceHooks.add(key);
            }
            info("NFC-TRACE hook installed: " + formatMethod(method));
        } catch (NoSuchMethodException e) {
            info("NFC-TRACE method absent: " + runtime.getName() + "." + methodName);
        } catch (Throwable t) {
            warn("NFC-TRACE hook failed: " + runtime.getName() + "." + methodName + " / " + t.getClass().getSimpleName(), t);
        }
    }

    private boolean shouldLogBinderCaller(Class<?> runtime, String methodName) {
        String className = runtime.getName();
        if (className.contains("VendorNfcService") || className.contains("NxpNfcService")) return true;
        return "setConfig".equals(methodName) || "doSetHceTypeAConfig".equals(methodName)
                || "setPollingTechMask".equals(methodName);
    }

    private boolean isImportantFlowMethod(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        return lower.contains("hce") || lower.contains("config") || lower.contains("polling")
                || lower.contains("transit") || lower.contains("rfparams");
    }

    private void logBinderCaller(String callName) {
        try {
            int uid = Binder.getCallingUid();
            int pid = Binder.getCallingPid();
            String packages = "[]";
            Application app = currentApplication();
            if (app != null) {
                String[] names = app.getPackageManager().getPackagesForUid(uid);
                packages = names == null ? "[]" : Arrays.toString(names);
            }
            info("NFC-FLOW CALLER method=" + callName + " uid=" + uid + " pid=" + pid + " packages=" + packages);
        } catch (Throwable t) {
            info("NFC-FLOW CALLER method=" + callName + " unavailable=" + t.getClass().getSimpleName());
        }
    }

    private void logPayloadDigests(String methodName, Object[] args) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        if (!lower.contains("config") && !lower.contains("rfparams") && !lower.contains("transit")) return;

        for (int i = 0; i < args.length; i++) {
            byte[] payload = null;
            String sourceType = null;
            if (args[i] instanceof byte[]) {
                payload = (byte[]) args[i];
                sourceType = "byte[]";
            } else if (args[i] instanceof String) {
                payload = ((String) args[i]).getBytes(StandardCharsets.UTF_8);
                sourceType = "String/UTF-8";
            }
            if (payload == null) continue;
            info("NFC-TRACE DIGEST " + methodName + ".arg" + i
                    + " type=" + sourceType
                    + " bytes=" + payload.length
                    + " sha256=" + sha256(payload));
        }
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            return "unavailable:" + t.getClass().getSimpleName();
        }
    }

    private void logActualSavedTech(Object service, String phase) {
        Integer poll = invokeIntGetter(service, "getNfcPollTech");
        Integer listen = invokeIntGetter(service, "getNfcListenTech");
        info("NFC-TRACE SAVED " + phase + " poll=" + summarizeValue(poll) + " listen=" + summarizeValue(listen));
        if (poll != null) logIntBreakdown("SAVED." + phase + ".poll", poll);
        if (listen != null) logIntBreakdown("SAVED." + phase + ".listen", listen);
    }

    private Integer invokeIntGetter(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(target);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Throwable t) {
            info("NFC-TRACE SAVED unable to read " + methodName + ": " + t.getClass().getSimpleName());
            return null;
        }
    }

    private void logDiscoveryMasks(String methodName, Object[] args) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        if (!lower.contains("discoverytech") && !lower.contains("listentech") && !lower.contains("polltech")) return;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer) logIntBreakdown(methodName + ".arg" + i, (Integer) args[i]);
        }
    }

    private void logIntBreakdown(String label, int value) {
        int high2 = value & 0xC0000000;
        int middle = value & 0x3FFFFF00;
        int low8 = value & 0x000000FF;
        info("NFC-TRACE MASK " + label
                + " raw=0x" + hex8(value)
                + " high2=0x" + hex8(high2)
                + " middle=0x" + hex8(middle)
                + " low8=0x" + hex8(low8));
    }

    private String summarizeArgs(Object[] args) {
        if (args == null || args.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(i).append('=').append(summarizeValue(args[i]));
        }
        return sb.append(']').toString();
    }

    private String summarizeValue(Object value) {
        if (value == null) return "null";
        if (value instanceof byte[]) return "byte[len=" + ((byte[]) value).length + "]";
        if (value instanceof int[]) return "int[len=" + ((int[]) value).length + "]";
        if (value instanceof boolean[]) return "boolean[len=" + ((boolean[]) value).length + "]";
        if (value instanceof Integer) {
            int v = (Integer) value;
            return v + "(0x" + Integer.toHexString(v).toUpperCase(Locale.ROOT) + ")";
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) return String.valueOf(value);
        if (value instanceof String) return "String[len=" + ((String) value).length() + "]";
        return value.getClass().getName();
    }

    private String hex8(int value) {
        return String.format(Locale.ROOT, "%08X", value);
    }

    private void logShortStack(String methodName) {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int emitted = 0;
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className.equals(Thread.class.getName()) || className.equals(getClass().getName())) continue;
            if (className.startsWith("io.github.libxposed.")) continue;
            info("NFC-TRACE STACK " + methodName + " #" + emitted + " "
                    + className + "." + frame.getMethodName() + ":" + frame.getLineNumber());
            if (++emitted >= MAX_STACK_FRAMES) break;
        }
    }

    private void logConfiguredTestRequest() {
        Cursor cursor = null;
        try {
            Application app = currentApplication();
            if (app == null) {
                warn("UID config bridge unavailable: currentApplication is null");
                return;
            }
            cursor = app.getContentResolver().query(CONFIG_URI, null, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                warn("UID config bridge returned no row");
                return;
            }
            int uidColumn = cursor.getColumnIndex("uid");
            int activeColumn = cursor.getColumnIndex("active");
            String uid = uidColumn >= 0 ? cursor.getString(uidColumn) : null;
            boolean active = activeColumn >= 0 && cursor.getInt(activeColumn) == 1;
            info("UID test config observed: active=" + active + ", uidPresent=" + (uid != null && !uid.isBlank()));
        } catch (Throwable t) {
            warn("Unable to read UID test configuration: " + t.getClass().getSimpleName(), t);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object value = currentApplication.invoke(null);
            return value instanceof Application ? (Application) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private String formatMethod(Method method) {
        return Modifier.toString(method.getModifiers()) + " "
                + method.getReturnType().getTypeName() + " "
                + method.getDeclaringClass().getName() + "." + method.getName()
                + "(" + joinTypes(method.getParameterTypes()) + ")";
    }

    private String joinTypes(Class<?>[] types) {
        return Arrays.stream(types).map(Class::getTypeName).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private void info(String message) {
        log(Log.INFO, TAG, message);
        Log.i(TAG, message);
    }

    private void warn(String message) {
        log(Log.WARN, TAG, message);
        Log.w(TAG, message);
    }

    private void warn(String message, Throwable t) {
        log(Log.WARN, TAG, message, t);
        Log.w(TAG, message, t);
    }
}
