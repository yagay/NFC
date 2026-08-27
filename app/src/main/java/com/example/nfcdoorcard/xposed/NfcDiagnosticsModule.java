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

/** LSPosed/libxposed API 102 diagnostics module. Observation only. */
public class NfcDiagnosticsModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.uidconfig/target");
    private static final int MAX_STACK_FRAMES = 6;

    private final Set<String> installedTraceHooks = new HashSet<>();

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
        installSlimTrace(lp.getDefaultClassLoader());
    }

    private void installSlimTrace(ClassLoader cl) {
        info("NFC-TRACE slim mode begin; observation only, no NFC/vendor command will be invoked");

        Class<?> deviceHost = loadOptional(cl, "com.android.nfc.DeviceHost");
        Class<?> nfcService = loadOptional(cl, "com.android.nfc.NfcService");

        if (nfcService != null) {
            installNfcServiceConstructorProbe(nfcService, deviceHost);
            installServiceTraceHook(nfcService, "getNfcListenTech");
            installServiceTraceHook(nfcService, "saveNfcListenTech", int.class);
            installServiceTraceHook(nfcService, "clearListenTech", boolean.class);
            installServiceTraceHook(nfcService, "getNfcPollTech");
            installServiceTraceHook(nfcService, "saveNfcPollTech", int.class);
        } else {
            warn("NFC-TRACE NfcService class unavailable");
        }

        info("NFC-TRACE slim mode hooks requested");
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

        installHostTraceHooks(runtime);
        logConfiguredTestRequest();
    }

    private void installHostTraceHooks(Class<?> runtime) {
        installTraceHook(runtime, "changeRfParams", byte[].class, boolean.class);
        installTraceHook(runtime, "changeRfParamsByConfig", byte[].class);
        installTraceHook(runtime, "doWriteData", byte[].class, byte[].class);
        installTraceHook(runtime, "nativeSendRawVendorCmd", int.class, int.class, int.class, byte[].class);

        // Follow the normal discovery configuration path without modifying it.
        installTraceHook(runtime, "setDiscoveryTech", int.class, int.class);
        installTraceHook(runtime, "resetDiscoveryTech");
        installTraceHook(runtime, "restartRfDiscovery");
        installTraceHook(runtime, "doRestartRFDiscovery");
    }

    private void installServiceTraceHook(Class<?> serviceClass, String methodName, Class<?>... parameterTypes) {
        installTraceHook(serviceClass, methodName, parameterTypes);
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
                info("NFC-TRACE ENTER " + runtime.getSimpleName() + "." + methodName + " args=" + args);
                logShortStack(methodName);
                try {
                    Object result = chain.proceed();
                    info("NFC-TRACE RETURN " + runtime.getSimpleName() + "." + methodName
                            + " result=" + summarizeValue(result));
                    return result;
                } catch (Throwable t) {
                    warn("NFC-TRACE THROW " + runtime.getSimpleName() + "." + methodName
                            + " exception=" + t.getClass().getName());
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
            warn("NFC-TRACE hook failed: " + runtime.getName() + "." + methodName
                    + " / " + t.getClass().getSimpleName(), t);
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
        if (value instanceof boolean[]) return "boolean[len=" + ((boolean[]) value).length + "]";
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            if (value instanceof Integer) {
                int v = (Integer) value;
                return v + "(0x" + Integer.toHexString(v).toUpperCase(Locale.ROOT) + ")";
            }
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

    private String formatMethod(Method method) {
        return Modifier.toString(method.getModifiers()) + " "
                + method.getReturnType().getTypeName() + " "
                + method.getDeclaringClass().getName() + "." + method.getName()
                + "(" + joinTypes(method.getParameterTypes()) + ")";
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

    private void warn(String message) {
        log(Log.WARN, TAG, message);
        Log.w(TAG, message);
    }

    private void warn(String message, Throwable t) {
        log(Log.WARN, TAG, message, t);
        Log.w(TAG, message, t);
    }
}
