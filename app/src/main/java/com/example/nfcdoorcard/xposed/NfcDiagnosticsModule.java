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
import java.util.Locale;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** LSPosed/libxposed API 102 diagnostics module. */
public class NfcDiagnosticsModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.uidconfig/target");
    private static final String[] SCAN_KEYWORDS = {
            "uid", "config", "nci", "rf", "discover", "routing", "listen", "poll", "set", "write"
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
        installNfcServiceHooks(lp);
    }

    private void installNfcServiceHooks(XposedModuleInterface.PackageLoadedParam lp) {
        try {
            ClassLoader cl = lp.getDefaultClassLoader();
            Class<?> nativeManager = cl.loadClass("com.android.nfc.dhimpl.NativeNfcManager");
            Method initMethod = nativeManager.getDeclaredMethod("doInitialize");

            deoptimize(initMethod);
            hook(initMethod).intercept(chain -> {
                info("NFC doInitialize entered");
                logConfiguredTestRequest();
                try {
                    Object result = chain.proceed();
                    info("NFC doInitialize returned: " + String.valueOf(result));
                    return result;
                } catch (Throwable t) {
                    error("NFC doInitialize threw: " + t.getClass().getName(), t);
                    throw t;
                }
            });

            info("NativeNfcManager.doInitialize hook installed");
            reportKnownVendorSignature(nativeManager);
            scanNfcBackend(cl, nativeManager);
        } catch (ClassNotFoundException e) {
            warn("NativeNfcManager class not found on this NFC stack", e);
        } catch (NoSuchMethodException e) {
            warn("NativeNfcManager.doInitialize not found on this NFC stack", e);
        } catch (Throwable t) {
            error("NFC hook installation failed: " + t.getClass().getSimpleName(), t);
        }
    }

    private void logConfiguredTestRequest() {
        Cursor cursor = null;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object value = currentApplication.invoke(null);
            if (!(value instanceof Application app)) {
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

    private void reportKnownVendorSignature(Class<?> nativeManager) {
        try {
            nativeManager.getDeclaredMethod("doWriteNciConfig", int.class, byte[].class);
            info("Known vendor signature doWriteNciConfig(int, byte[]) is present; invocation disabled");
        } catch (NoSuchMethodException e) {
            info("Known vendor signature doWriteNciConfig(int, byte[]) not found; other vendor APIs may differ");
        } catch (Throwable t) {
            warn("Unable to inspect known vendor NFC signature", t);
        }
    }

    private void scanNfcBackend(ClassLoader cl, Class<?> nativeManager) {
        info("NFC backend scan begin; diagnostics only, no vendor method will be invoked");
        scanClass(nativeManager);

        String[] candidates = {
                "com.android.nfc.NfcService",
                "com.android.nfc.DeviceHost",
                "com.android.nfc.dhimpl.NativeNfcManager",
                "com.android.nfc.dhimpl.NativeNfcTag",
                "com.android.nfc.cardemulation.CardEmulationManager",
                "com.android.nfc.cardemulation.RegisteredAidCache",
                "com.android.nfc.cardemulation.RegisteredNfcFServicesCache"
        };

        for (String className : candidates) {
            if (className.equals(nativeManager.getName())) continue;
            try {
                scanClass(cl.loadClass(className));
            } catch (ClassNotFoundException ignored) {
                debug("NFC backend class absent: " + className);
            } catch (Throwable t) {
                warn("NFC backend class scan failed: " + className + " / " + t.getClass().getSimpleName());
            }
        }
        info("NFC backend scan end");
    }

    private void scanClass(Class<?> clazz) {
        try {
            int methodMatches = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!matchesKeyword(method.getName())) continue;
                methodMatches++;
                info("NFC-SCAN METHOD " + formatMethod(method));
            }

            int fieldMatches = 0;
            for (Field field : clazz.getDeclaredFields()) {
                if (!matchesKeyword(field.getName())) continue;
                fieldMatches++;
                info("NFC-SCAN FIELD " + formatField(field));
            }

            if (methodMatches > 0 || fieldMatches > 0) {
                for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                    debug("NFC-SCAN CTOR " + formatConstructor(constructor));
                }
                info("NFC-SCAN CLASS " + clazz.getName()
                        + " matches: methods=" + methodMatches + ", fields=" + fieldMatches);
            }
        } catch (Throwable t) {
            warn("Unable to scan NFC class " + clazz.getName(), t);
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
