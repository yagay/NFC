package com.example.nfcdoorcard.xposed;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Narrow OxygenOS 16 NFC Type-A UID hook with explicit runtime status reporting. */
public class NfcDiagnosticsModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings");

    private static final String KEY_SIMULATION_ENABLED = "simulation_enabled";
    private static final String KEY_UID = "uid";
    private static final String KEY_SAK = "sak";
    private static final String KEY_ATQA = "atqa";
    private static final String KEY_SCOPE_OK = "scope_ok";
    private static final String KEY_SCOPE_PROCESS = "scope_process";
    private static final String KEY_HOOK_INSTALLED = "hook_installed";
    private static final String KEY_HOOK_CLASS = "hook_class";
    private static final String KEY_HOOK_COUNT = "hook_count";
    private static final String KEY_HIJACK_STATUS = "hijack_status";
    private static final String KEY_HIJACK_RESULT = "hijack_result";
    private static final String KEY_HIJACK_UID = "hijack_uid";
    private static final String KEY_HIJACK_ERROR = "hijack_error";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        info("MODULE: loaded process=" + param.getProcessName() + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
        super.onPackageLoaded(lp);
        if (!"com.android.nfc".equals(lp.getPackageName())) return;

        info("SCOPE: SUCCESS package=com.android.nfc");
        writeStatus(values(
                KEY_SCOPE_OK, true,
                KEY_SCOPE_PROCESS, "com.android.nfc",
                KEY_HIJACK_STATUS, "IDLE",
                KEY_HIJACK_ERROR, ""
        ));

        ClassLoader cl = lp.getDefaultClassLoader();
        int installed = 0;
        installed += installVerifiedHceHook(cl, "com.android.nfc.dhimpl.NxpNativeNfcManager");
        installed += installVerifiedHceHook(cl, "com.android.nfc.dhimpl.StNativeNfcManager");

        writeStatus(values(
                KEY_HOOK_INSTALLED, installed > 0,
                KEY_HOOK_COUNT, installed
        ));
        if (installed > 0) info("HOOK: SUCCESS installed=" + installed);
        else warn("HOOK: FAILED verified setHceTypeAConfig signature not installed");
    }

    private int installVerifiedHceHook(ClassLoader cl, String className) {
        try {
            Class<?> runtime = Class.forName(className, false, cl);
            Method method = runtime.getDeclaredMethod(
                    "setHceTypeAConfig",
                    boolean.class, byte[].class, byte[].class, byte[].class
            );

            hook(method).intercept(chain -> {
                SimConfig config = readConfig();
                info("HCE: ENTER " + runtime.getSimpleName() + ".setHceTypeAConfig active=" + config.active);

                if (!config.active || config.uid == null || config.uid.isBlank()) {
                    writeStatus(values(KEY_HIJACK_STATUS, "IDLE", KEY_HIJACK_RESULT, "", KEY_HIJACK_ERROR, ""));
                    return chain.proceed();
                }

                String normalizedUid = normalizeHex(config.uid);
                try {
                    byte[] uid = hexToBytes(normalizedUid);
                    byte[] sak = hexToBytes(defaultIfBlank(config.sak, "08"));
                    byte[] atqa = hexToBytes(defaultIfBlank(config.atqa, "0400"));

                    writeStatus(values(
                            KEY_HIJACK_STATUS, "APPLYING",
                            KEY_HIJACK_UID, normalizedUid,
                            KEY_HIJACK_ERROR, ""
                    ));
                    info("HIJACK: APPLY uid=" + normalizedUid
                            + " sak=" + normalizeHex(defaultIfBlank(config.sak, "08"))
                            + " atqa=" + normalizeHex(defaultIfBlank(config.atqa, "0400"))
                            + " via=" + runtime.getSimpleName());

                    Object result = chain.proceed(new Object[] { true, uid, sak, atqa });
                    boolean success = !(result instanceof Boolean) || Boolean.TRUE.equals(result);
                    writeStatus(values(
                            KEY_HIJACK_STATUS, success ? "SUCCESS" : "FAILED",
                            KEY_HIJACK_RESULT, String.valueOf(result),
                            KEY_HIJACK_UID, normalizedUid,
                            KEY_HIJACK_ERROR, success ? "" : "native returned false"
                    ));
                    info("HIJACK: " + (success ? "SUCCESS" : "FAILED") + " result=" + result + " uid=" + normalizedUid);
                    return result;
                } catch (Throwable t) {
                    writeStatus(values(
                            KEY_HIJACK_STATUS, "FAILED",
                            KEY_HIJACK_RESULT, "exception",
                            KEY_HIJACK_UID, normalizedUid,
                            KEY_HIJACK_ERROR, t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage())
                    ));
                    error("HIJACK: FAILED", t);
                    return chain.proceed();
                }
            });

            writeStatus(values(KEY_HOOK_INSTALLED, true, KEY_HOOK_CLASS, className));
            info("HOOK: INSTALLED " + method.toGenericString());
            return 1;
        } catch (ClassNotFoundException e) {
            info("HOOK: class absent " + className);
            return 0;
        } catch (NoSuchMethodException e) {
            warn("HOOK: verified signature absent " + className);
            return 0;
        } catch (Throwable t) {
            writeStatus(values(KEY_HOOK_INSTALLED, false, KEY_HIJACK_ERROR, "hook install: " + t.getClass().getSimpleName()));
            error("HOOK: install failed " + className, t);
            return 0;
        }
    }

    private SimConfig readConfig() {
        Cursor cursor = null;
        try {
            Application app = currentApplication();
            if (app == null) return SimConfig.DISABLED;
            cursor = app.getContentResolver().query(CONFIG_URI, null, null, null, null);
            if (cursor == null) return SimConfig.DISABLED;

            boolean active = false;
            String uid = null, sak = null, atqa = null;
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                String value = cursor.getString(1);
                if (KEY_SIMULATION_ENABLED.equals(key)) active = "true".equalsIgnoreCase(value);
                else if (KEY_UID.equals(key)) uid = value;
                else if (KEY_SAK.equals(key)) sak = value;
                else if (KEY_ATQA.equals(key)) atqa = value;
            }
            return new SimConfig(active, uid, sak, atqa);
        } catch (Throwable t) {
            error("CONFIG: read failed", t);
            return SimConfig.DISABLED;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private ContentValues values(Object... pairs) {
        ContentValues values = new ContentValues();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = String.valueOf(pairs[i]);
            Object value = pairs[i + 1];
            if (value instanceof Boolean) values.put(key, (Boolean) value);
            else if (value instanceof Integer) values.put(key, (Integer) value);
            else values.put(key, String.valueOf(value));
        }
        return values;
    }

    private void writeStatus(ContentValues values) {
        try {
            Application app = currentApplication();
            if (app != null) app.getContentResolver().insert(CONFIG_URI, values);
        } catch (Throwable t) {
            warn("STATUS: write failed " + t.getClass().getSimpleName());
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
        if (hex.isEmpty() || (hex.length() & 1) != 0) throw new IllegalArgumentException("invalid hex length=" + hex.length());
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private String normalizeHex(String value) {
        if (value == null) return "";
        return value.replace(":", "").replace(" ", "").replace("0x", "").replace("0X", "").toUpperCase(Locale.ROOT);
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class SimConfig {
        static final SimConfig DISABLED = new SimConfig(false, null, null, null);
        final boolean active;
        final String uid, sak, atqa;
        SimConfig(boolean active, String uid, String sak, String atqa) {
            this.active = active; this.uid = uid; this.sak = sak; this.atqa = atqa;
        }
    }

    private void info(String msg) { log(Log.INFO, TAG, msg); Log.i(TAG, msg); }
    private void warn(String msg) { log(Log.WARN, TAG, msg); Log.w(TAG, msg); }
    private void error(String msg, Throwable t) { log(Log.ERROR, TAG, msg, t); Log.e(TAG, msg, t); }
}
