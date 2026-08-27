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
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dalvik.system.DexFile;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** LSPosed/libxposed API 102 diagnostics module. */
public class NfcDiagnosticsModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.uidconfig/target");
    private static final int MAX_CLASS_CANDIDATES = 180;
    private static final int MAX_MEMBERS_PER_CLASS = 50;
    private static final Pattern DEX_PATH_PATTERN = Pattern.compile("(?:zip file |dex file )?\\\"([^\\\"]+\\.(?:apk|jar))\\\"");

    private static final String[] SCAN_KEYWORDS = {
            "uid", "config", "nci", "rf", "discover", "routing", "listen", "poll", "set", "write",
            "initialize", "enable", "disable", "hal", "vendor"
    };

    private static final String[] CLASS_KEYWORDS = {
            "native", "devicehost", "manager", "service", "hal", "vendor", "config", "routing",
            "discover", "controller", "stnfc", "st_nfc", "nfcst", "nci"
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
        inspectNfcService(lp);
    }

    private void inspectNfcService(XposedModuleInterface.PackageLoadedParam lp) {
        ClassLoader cl = lp.getDefaultClassLoader();
        Class<?> nativeManager = null;

        try {
            nativeManager = Class.forName("com.android.nfc.dhimpl.NativeNfcManager", false, cl);
            installKnownNativeManagerHook(nativeManager);
        } catch (ClassNotFoundException e) {
            warn("NativeNfcManager class not found on this NFC stack; continuing with vendor class enumeration");
        } catch (Throwable t) {
            error("Known NativeNfcManager inspection failed: " + t.getClass().getSimpleName(), t);
        }

        scanNfcBackend(cl, nativeManager);
    }

    private void installKnownNativeManagerHook(Class<?> nativeManager) {
        try {
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
        } catch (NoSuchMethodException e) {
            warn("NativeNfcManager.doInitialize not found on this NFC stack");
        } catch (Throwable t) {
            error("NFC hook installation failed: " + t.getClass().getSimpleName(), t);
        }

        reportKnownVendorSignature(nativeManager);
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
            debug("currentApplication unavailable: " + t.getClass().getSimpleName());
            return null;
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
        if (nativeManager != null) scanClass(nativeManager);

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
            if (nativeManager != null && className.equals(nativeManager.getName())) continue;
            try {
                scanClass(Class.forName(className, false, cl));
            } catch (ClassNotFoundException ignored) {
                debug("NFC backend class absent: " + className);
            } catch (Throwable t) {
                warn("NFC backend class scan failed: " + className + " / " + t.getClass().getSimpleName());
            }
        }

        enumerateVendorNfcClasses(cl);
        info("NFC backend scan end");
    }

    private void enumerateVendorNfcClasses(ClassLoader cl) {
        Set<String> dexPaths = collectDexPaths(cl);
        if (dexPaths.isEmpty()) {
            warn("NFC-CLASS no APK/JAR path could be resolved from NFC process");
            return;
        }

        int candidates = 0;
        for (String dexPath : dexPaths) {
            info("NFC-DEX scanning " + dexPath);
            DexFile dexFile = null;
            try {
                dexFile = new DexFile(dexPath);
                Enumeration<String> entries = dexFile.entries();
                while (entries.hasMoreElements() && candidates < MAX_CLASS_CANDIDATES) {
                    String className = entries.nextElement();
                    if (!isNfcCandidateClass(className)) continue;
                    candidates++;
                    info("NFC-CLASS candidate " + className);
                    try {
                        Class<?> clazz = Class.forName(className, false, cl);
                        scanClass(clazz);
                    } catch (Throwable t) {
                        debug("NFC-CLASS load skipped " + className + " / " + t.getClass().getSimpleName());
                    }
                }
            } catch (Throwable t) {
                warn("NFC-DEX scan failed for " + dexPath + " / " + t.getClass().getSimpleName(), t);
            } finally {
                if (dexFile != null) {
                    try {
                        dexFile.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (candidates >= MAX_CLASS_CANDIDATES) break;
        }
        info("NFC-CLASS enumeration complete; candidates=" + candidates + ", limit=" + MAX_CLASS_CANDIDATES);
    }

    private Set<String> collectDexPaths(ClassLoader cl) {
        Set<String> paths = new LinkedHashSet<>();

        Application app = currentApplication();
        if (app != null && app.getApplicationInfo() != null) {
            if (app.getApplicationInfo().sourceDir != null) paths.add(app.getApplicationInfo().sourceDir);
            if (app.getApplicationInfo().splitSourceDirs != null) {
                paths.addAll(Arrays.asList(app.getApplicationInfo().splitSourceDirs));
            }
        }

        String classLoaderText = String.valueOf(cl);
        Matcher matcher = DEX_PATH_PATTERN.matcher(classLoaderText);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }

        debug("NFC-DEX resolved paths=" + paths);
        return paths;
    }

    private boolean isNfcCandidateClass(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        boolean relevantPackage = lower.startsWith("com.android.nfc.")
                || lower.startsWith("com.st.")
                || lower.startsWith("vendor.st.")
                || lower.startsWith("com.stmicroelectronics.")
                || lower.startsWith("android.hardware.nfc.");
        if (!relevantPackage) return false;

        for (String keyword : CLASS_KEYWORDS) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    private void scanClass(Class<?> clazz) {
        try {
            int methodMatches = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!matchesKeyword(method.getName())) continue;
                methodMatches++;
                if (methodMatches <= MAX_MEMBERS_PER_CLASS) {
                    info("NFC-SCAN METHOD " + formatMethod(method));
                }
            }

            int fieldMatches = 0;
            for (Field field : clazz.getDeclaredFields()) {
                if (!matchesKeyword(field.getName())) continue;
                fieldMatches++;
                if (fieldMatches <= MAX_MEMBERS_PER_CLASS) {
                    info("NFC-SCAN FIELD " + formatField(field));
                }
            }

            if (methodMatches > 0 || fieldMatches > 0) {
                int ctorCount = 0;
                for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                    if (ctorCount++ >= 8) break;
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
