package com.example.nfcdoorcard.xposed;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * OxygenOS 16 NFC Type-A UID hook.
 *
 * V8 diagnostics confirmed the real call path on this device:
 * VendorNfcService.doSetHceTypeAConfig ->
 * NxpNativeNfcManager.setHceTypeAConfig(boolean, byte[], byte[], byte[]).
 *
 * Keep the hook deliberately narrow: when simulation is disabled the original
 * call is passed through unchanged. No vendor command is proactively invoked.
 */
public class NfcDiagnosticsModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings");

    private static final String KEY_SIMULATION_ENABLED = "simulation_enabled";
    private static final String KEY_UID = "uid";
    private static final String KEY_SAK = "sak";
    private static final String KEY_ATQA = "atqa";
    private static final String KEY_MODULE_ACTIVE = "module_active";
    private static final String KEY_MODULE_PROCESS = "module_process";
    private static final String KEY_MODULE_BOOT_COUNT = "module_boot_count";

    private volatile boolean heartbeatWritten;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        info("MODULE: onModuleLoaded process=" + param.getProcessName() + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
        super.onPackageLoaded(lp);
        if (!"com.android.nfc".equals(lp.getPackageName())) return;

        info("MODULE: onPackageLoaded package=com.android.nfc");
        reportModuleActive("com.android.nfc");

        ClassLoader cl = lp.getDefaultClassLoader();
        int installed = 0;
        installed += installVerifiedHceHook(cl, "com.android.nfc.dhimpl.NxpNativeNfcManager");
        installed += installVerifiedHceHook(cl, "com.android.nfc.dhimpl.StNativeNfcManager");
        info("MODULE: verified HCE hooks installed=" + installed);
    }

    private int installVerifiedHceHook(ClassLoader cl, String className) {
        try {
            Class<?> runtime = Class.forName(className, false, cl);
            Method method = runtime.getDeclaredMethod(
                    "setHceTypeAConfig",
                    boolean.class,
                    byte[].class,
                    byte[].class,
                    byte[].class
            );

            hook(method).intercept(chain -> {
                reportModuleActive("com.android.nfc");

                Object[] incoming = chain.getArgs().toArray();
                info("HCE: ENTER " + runtime.getSimpleName() + ".setHceTypeAConfig args=" + summarizeArgs(incoming));

                SimConfig config = readConfig();
                if (!config.active || config.uid == null || config.uid.isBlank()) {
                    info("HCE: PASS simulation disabled or UID missing");
                    Object result = chain.proceed();
                    info("HCE: RETURN pass-through result=" + String.valueOf(result));
                    return result;
                }

                try {
                    byte[] uid = hexToBytes(config.uid);
                    byte[] sak = hexToBytes(defaultIfBlank(config.sak, "08"));
                    byte[] atqa = hexToBytes(defaultIfBlank(config.atqa, "0400"));

                    Object[] replacement = new Object[] { true, uid, sak, atqa };
                    info("HCE: APPLY uid=" + normalizeHex(config.uid)
                            + " sak=" + normalizeHex(defaultIfBlank(config.sak, "08"))
                            + " atqa=" + normalizeHex(defaultIfBlank(config.atqa, "0400"))
                            + " via=" + runtime.getSimpleName());

                    Object result = chain.proceed(replacement);
                    info("HCE: APPLIED result=" + String.valueOf(result));
                    return result;
                } catch (Throwable t) {
                    error("HCE: APPLY FAILED; falling back to original call", t);
                    return chain.proceed();
                }
            });

            info("HCE: hook installed " + method.toGenericString());
            return 1;
        } catch (ClassNotFoundException e) {
            info("HCE: class absent " + className);
            return 0;
        } catch (NoSuchMethodException e) {
            warn("HCE: verified signature absent in " + className);
            return 0;
        } catch (Throwable t) {
            error("HCE: hook install failed for " + className, t);
            return 0;
        }
    }

    private SimConfig readConfig() {
        Cursor cursor = null;
        try {
            Application app = currentApplication();
            if (app == null) {
                warn("CONFIG: currentApplication unavailable");
                return SimConfig.DISABLED;
            }

            cursor = app.getContentResolver().query(CONFIG_URI, null, null, null, null);
            if (cursor == null) {
                warn("CONFIG: provider returned null cursor");
                return SimConfig.DISABLED;
            }

            boolean active = false;
            String uid = null;
            String sak = null;
            String atqa = null;
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                String value = cursor.getString(1);
                if (KEY_SIMULATION_ENABLED.equals(key)) active = "true".equalsIgnoreCase(value);
                else if (KEY_UID.equals(key)) uid = value;
                else if (KEY_SAK.equals(key)) sak = value;
                else if (KEY_ATQA.equals(key)) atqa = value;
            }

            info("CONFIG: active=" + active + " uidPresent=" + (uid != null && !uid.isBlank()));
            return new SimConfig(active, uid, sak, atqa);
        } catch (Throwable t) {
            error("CONFIG: read failed", t);
            return SimConfig.DISABLED;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private void reportModuleActive(String processName) {
        if (heartbeatWritten) return;
        try {
            Application app = currentApplication();
            if (app == null) {
                info("MODULE: heartbeat deferred; currentApplication unavailable");
                return;
            }

            int bootCount = Settings.Global.getInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            ContentValues values = new ContentValues();
            values.put(KEY_MODULE_ACTIVE, true);
            values.put(KEY_MODULE_PROCESS, processName);
            values.put(KEY_MODULE_BOOT_COUNT, bootCount);
            app.getContentResolver().insert(CONFIG_URI, values);
            heartbeatWritten = true;
            info("MODULE: heartbeat persisted process=" + processName + " boot=" + bootCount);
        } catch (Throwable t) {
            error("MODULE: heartbeat write failed", t);
        }
    }

    private Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method method = activityThread.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            Object value = method.invoke(null);
            return value instanceof Application ? (Application) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private byte[] hexToBytes(String value) {
        String hex = normalizeHex(value);
        if (hex.length() == 0 || (hex.length() & 1) != 0) {
            throw new IllegalArgumentException("invalid hex length=" + hex.length());
        }
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int offset = i * 2;
            result[i] = (byte) Integer.parseInt(hex.substring(offset, offset + 2), 16);
        }
        return result;
    }

    private String normalizeHex(String value) {
        if (value == null) return "";
        return value.replace(":", "")
                .replace(" ", "")
                .replace("0x", "")
                .replace("0X", "")
                .toUpperCase(Locale.ROOT);
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String summarizeArgs(Object[] args) {
        if (args == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            Object arg = args[i];
            sb.append(i).append('=');
            if (arg instanceof byte[]) sb.append("byte[len=").append(((byte[]) arg).length).append(']');
            else sb.append(String.valueOf(arg));
        }
        return sb.append(']').toString();
    }

    private static final class SimConfig {
        static final SimConfig DISABLED = new SimConfig(false, null, null, null);
        final boolean active;
        final String uid;
        final String sak;
        final String atqa;

        SimConfig(boolean active, String uid, String sak, String atqa) {
            this.active = active;
            this.uid = uid;
            this.sak = sak;
            this.atqa = atqa;
        }
    }

    private void info(String message) {
        log(Log.INFO, TAG, message);
        Log.i(TAG, message);
    }

    private void warn(String message) {
        log(Log.WARN, TAG, message);
        Log.w(TAG, message);
    }

    private void error(String message, Throwable t) {
        log(Log.ERROR, TAG, message, t);
        Log.e(TAG, message, t);
    }
}
