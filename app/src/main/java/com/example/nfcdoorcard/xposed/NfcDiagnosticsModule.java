package com.example.nfcdoorcard.xposed;

import android.app.Application;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** LSPosed/libxposed API 102 diagnostics module. */
public class NfcDiagnosticsModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.uidconfig/target");
    private static final int MAX_MEMBERS_PER_CLASS = 120;
    private static final int MAX_STACK_FRAMES = 6;

    private final Set<String> installedTraceHooks = new HashSet<>();

    private static final String[] SCAN_KEYWORDS = {
            "uid", "config", "nci", "rf", "discover", "routing", "listen", "poll", "set", "write",
            "initialize", "enable", "disable", "hal", "vendor", "devicehost", "injector", "create"
    };

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
        inspectNfcRuntime(lp.getDefaultClassLoader());
    }

    private void inspectNfcRuntime(ClassLoader cl) {
        info("NFC runtime inspection begin; observation only, no vendor command will be invoked");

        Class<?> deviceHost = loadOptional(cl, "com.android.nfc.DeviceHost");
        Class<?> nfcService = loadOptional(cl, "com.android.nfc.NfcService");
        Class<?> nfcInjector = loadOptional(cl, "com.android.nfc.NfcInjector");

        if (deviceHost != null) scanClass(deviceHost, "NFC-DEVICEHOST-API");
        if (nfcInjector != null) scanInjector(nfcInjector, deviceHost);
        if (nfcService != null) {
            scanClass(nfcService, "NFC-SERVICE");
            installNfcServiceConstructorProbe(nfcService, deviceHost);
        }

        Class<?> oldNativeManager = loadOptional(cl, "com.android.nfc.dhimpl.NativeNfcManager");
        if (oldNativeManager != null) {
            scanClass(oldNativeManager, "NFC-OLD-NATIVE");
        } else {
            info("Old NativeNfcManager absent; runtime DeviceHost probe is authoritative on this stack");
        }

        info("NFC runtime inspection hooks installed");
    }

    private Class<?> loadOptional(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (ClassNotFoundException e) {
            debug("NFC class absent: " + name);
            return null;
        } catch (Throwable t) {
            warn("Unable to load NFC class " + name + ": " + t.getClass().getSimpleName());
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
                        warn("Runtime DeviceHost inspection failed: " + t.getClass().getSimpleName(), t);
                    }
                    return result;
                });
                installed++;
                info("NFC-RUNTIME constructor probe installed: " + formatConstructor(constructor));
            } catch (Throwable t) {
                warn("Unable to hook NfcService constructor: " + t.getClass().getSimpleName(), t);
            }
        }
        info("NFC-RUNTIME constructor probes installed=" + installed);
    }

    private void inspectNfcServiceInstance(Object service, Class<?> deviceHost) {
        if (service == null) {
            warn("NFC-RUNTIME NfcService thisObject is null after constructor");
            return;
        }

        info("NFC-RUNTIME serviceClass=" + service.getClass().getName());
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
                    info("NFC-RUNTIME field " + cursor.getName() + "." + field.getName()
                            + " type=" + field.getType().getName()
                            + " valueClass=" + (value == null ? "null" : value.getClass().getName()));
                    if (value != null && deviceHost != null && deviceHost.isInstance(value)) {
                        host = value;
                        hostField = field.getName();
                        break;
                    }
                } catch (Throwable t) {
                    debug("NFC-RUNTIME field read skipped " + field.getName() + " / " + t.getClass().getSimpleName());
                }
            }
            if (host != null) break;
            cursor = cursor.getSuperclass();
        }

        if (host == null) {
            warn("NFC-RUNTIME DeviceHost instance not found in NfcService fields");
            return;
        }

        Class<?> runtime = host.getClass();
        info("NFC-RUNTIME DeviceHost field=" + hostField);
        info("NFC-RUNTIME DeviceHost class=" + runtime.getName());
        Class<?> superClass = runtime.getSuperclass();
        info("NFC-RUNTIME DeviceHost superclass=" + (superClass == null ? "null" : superClass.getName()));
        info("NFC-RUNTIME DeviceHost interfaces=" + joinTypes(runtime.getInterfaces()));
        scanClass(runtime, "NFC-RUNTIME-HOST");
        installObservationHooks(runtime);

        logConfiguredTestRequest();
    }

    private void installObservationHooks(Class<?> runtime) {
        installTraceHook(runtime, "changeRfParams", byte[].class, boolean.class);
        installTraceHook(runtime, "changeRfParamsByConfig", byte[].class);
        installTraceHook(runtime, "doWriteData", byte[].class, byte[].class);
        installTraceHook(runtime, "nativeSendRawVendorCmd", int.class, int.class, int.class, byte[].class);
    }

    private void installTraceHook(Class<?> runtime, String methodName, Class<?>... parameterTypes) {
        String key = runtime.getName() + "#" + methodName + Arrays.toString(parameterTypes);
        synchronized (installedTraceHooks) {
            if (installedTraceHooks.contains(key)) return;
        }

        try {
            Method method = runtime.getDeclaredMethod(methodName, parameterTypes);
            hook(method).intercept(chain -> {
                String args = summarizeArgs(chain.getArgs().toArray());
                info("NFC-TRACE ENTER " + methodName + " args=" + args);
                logShortStack(methodName);
                try {
                    Object result = chain.proceed();
                    info("NFC-TRACE RETURN " + methodName + " result=" + summarizeValue(result));
                    return result;
                } catch (Throwable t) {
                    warn("NFC-TRACE THROW " + methodName + " exception=" + t.getClass().getName());
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
            warn("NFC-TRACE hook failed for " + methodName + ": " + t.getClass().getSimpleName(), t);
        }
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
        if (value instanceof boolean[] ) return "boolean[len=" + ((boolean[]) value).length + "]";
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return String.valueOf(value);
        }
        if (value instanceof String) return "String[len=" + ((String) value).length() + "]";
        return value.getClass().getName();
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

    private void scanInjector(Class<?> injector, Class<?> deviceHost) {
        info("NFC-INJECTOR class=" + injector.getName());
        int matches = 0;
        for (Method method : injector.getDeclaredMethods()) {
            boolean returnsDeviceHost = deviceHost != null && deviceHost.isAssignableFrom(method.getReturnType());
            boolean mentionsDeviceHost = false;
            for (Class<?> parameter : method.getParameterTypes()) {
                if (deviceHost != null && deviceHost.isAssignableFrom(parameter)) {
                    mentionsDeviceHost = true;
                    break;
                }
            }
            if (!returnsDeviceHost && !mentionsDeviceHost && !matchesKeyword(method.getName())) continue;
            matches++;
            if (matches <= MAX_MEMBERS_PER_CLASS) {
                info("NFC-INJECTOR METHOD " + formatMethod(method)
                        + (returnsDeviceHost ? " [returns DeviceHost]" : ""));
            }
        }
        for (Field field : injector.getDeclaredFields()) {
            if ((deviceHost != null && deviceHost.isAssignableFrom(field.getType())) || matchesKeyword(field.getName())) {
                info("NFC-INJECTOR FIELD " + formatField(field));
            }
        }
        info("NFC-INJECTOR scan complete; methodMatches=" + matches);
    }

    private void scanClass(Class<?> clazz, String prefix) {
        try {
            int methodMatches = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!matchesKeyword(method.getName())) continue;
                methodMatches++;
                if (methodMatches <= MAX_MEMBERS_PER_CLASS) {
                    info(prefix + " METHOD " + formatMethod(method));
                }
            }

            int fieldMatches = 0;
            for (Field field : clazz.getDeclaredFields()) {
                if (!matchesKeyword(field.getName())) continue;
                fieldMatches++;
                if (fieldMatches <= MAX_MEMBERS_PER_CLASS) {
                    info(prefix + " FIELD " + formatField(field));
                }
            }

            info(prefix + " CLASS " + clazz.getName()
                    + " matches: methods=" + methodMatches + ", fields=" + fieldMatches);
        } catch (Throwable t) {
            warn("Unable to scan NFC class " + clazz.getName(), t);
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
            info("UID test config observed: active=" + active + ", uid=" + (uid == null ? "unset" : uid));
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

    private boolean matchesKeyword(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String keyword : SCAN_KEYWORDS) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    private String formatMethod(Method method) {
        return Modifier.toString(method.getModifiers()) + " "
                + method.getReturnType().getTypeName() + " "
                + method.getDeclaringClass().getName() + "." + method.getName()
                + "(" + joinTypes(method.getParameterTypes()) + ")";
    }

    private String formatField(Field field) {
        return Modifier.toString(field.getModifiers()) + " "
                + field.getType().getTypeName() + " "
                + field.getDeclaringClass().getName() + "." + field.getName();
    }

    private String formatConstructor(Constructor<?> constructor) {
        return Modifier.toString(constructor.getModifiers()) + " "
                + constructor.getDeclaringClass().getName()
                + "(" + joinTypes(constructor.getParameterTypes()) + ")";
    }

    private String joinTypes(Class<?>[] types) {
        return Arrays.stream(types)
                .map(Class::getTypeName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private void info(String message) {
        log(Log.INFO, TAG, message);
        Log.i(TAG, message);
    }

    private void debug(String message) {
        log(Log.DEBUG, TAG, message);
        Log.d(TAG, message);
    }

    private void warn(String message) {
        log(Log.WARN, TAG, message);
        Log.w(TAG, message);
    }

    private void warn(String message, Throwable t) {
        log(Log.WARN, TAG, message, t);
        Log.w(TAG, message, t);
    }

    private void error(String message, Throwable t) {
        log(Log.ERROR, TAG, message, t);
        Log.e(TAG, message, t);
    }
}
