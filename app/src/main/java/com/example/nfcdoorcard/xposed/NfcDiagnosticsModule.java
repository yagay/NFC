package com.example.nfcdoorcard.xposed;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * OxygenOS 16 NFC diagnostics / UID simulation hook.
 *
 * V12 deliberately separates Java HCE config success from RF NFCID1 success.
 * It observes candidate vendor config buffers, detects NCI TLVs, and can safely
 * rewrite an existing LA_NFCID1 (0x33) TLV when simulation is enabled.
 *
 * It never blindly injects raw vendor commands and never invents a new config
 * packet when no NFCID1 TLV is present. This keeps the change narrow and lets
 * diagnostics prove whether the RF path was actually reached.
 */
public class NfcDiagnosticsModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings");

    private static final int PARAM_LA_NFCID1 = 0x33;

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
    private static final String KEY_RF_STATUS = "rf_status";
    private static final String KEY_RF_UID = "rf_uid";
    private static final String KEY_RF_SOURCE = "rf_source";
    private static final String KEY_RF_ERROR = "rf_error";

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
                KEY_HIJACK_ERROR, "",
                KEY_RF_STATUS, "WAITING",
                KEY_RF_ERROR, ""
        ));

        ClassLoader cl = lp.getDefaultClassLoader();
        int installed = 0;
        installed += installVerifiedHceHook(cl, "com.android.nfc.dhimpl.NxpNativeNfcManager");
        installed += installVerifiedHceHook(cl, "com.android.nfc.dhimpl.StNativeNfcManager");
        installed += installRfConfigHooks(cl, "com.android.nfc.dhimpl.NxpNativeNfcManager");
        installed += installRfConfigHooks(cl, "com.android.nfc.dhimpl.StNativeNfcManager");

        writeStatus(values(KEY_HOOK_INSTALLED, installed > 0, KEY_HOOK_COUNT, installed));
        if (installed > 0) info("HOOK: SUCCESS installed=" + installed);
        else warn("HOOK: FAILED no verified NFC hooks installed");
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
                Object[] incoming = chain.getArgs().toArray();
                info("HCE: ENTER " + runtime.getSimpleName() + ".setHceTypeAConfig args=" + summarizeArgs(incoming)
                        + " active=" + config.active);

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
                            KEY_HIJACK_STATUS, success ? "NATIVE_ACCEPTED" : "FAILED",
                            KEY_HIJACK_RESULT, String.valueOf(result),
                            KEY_HIJACK_UID, normalizedUid,
                            KEY_HIJACK_ERROR, success ? "" : "native returned false"
                    ));
                    info("HIJACK: " + (success ? "NATIVE_ACCEPTED" : "FAILED")
                            + " result=" + result + " uid=" + normalizedUid);
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
            error("HOOK: install failed " + className, t);
            return 0;
        }
    }

    private int installRfConfigHooks(ClassLoader cl, String className) {
        try {
            Class<?> runtime = Class.forName(className, false, cl);
            int installed = 0;
            for (Method method : runtime.getDeclaredMethods()) {
                String name = method.getName();
                Class<?>[] types = method.getParameterTypes();
                if (!isRfConfigCandidate(name, types)) continue;

                hook(method).intercept(chain -> {
                    Object[] args = chain.getArgs().toArray();
                    SimConfig config = readConfig();
                    boolean changed = false;
                    String source = runtime.getSimpleName() + "." + method.getName();

                    for (int i = 0; i < args.length; i++) {
                        if (!(args[i] instanceof byte[])) continue;
                        byte[] original = (byte[]) args[i];
                        Nfcid1Tlv tlv = findNfcid1Tlv(original);
                        if (tlv == null) continue;

                        info("RF: NFCID1 FOUND source=" + source + " arg=" + i
                                + " len=" + tlv.length + " uid=" + bytesToHex(tlv.value));

                        if (config.active && config.uid != null && !config.uid.isBlank()) {
                            byte[] desired = hexToBytes(config.uid);
                            if (!(desired.length == 4 || desired.length == 7 || desired.length == 10)) {
                                writeStatus(values(KEY_RF_STATUS, "FAILED", KEY_RF_ERROR,
                                        "UID length must be 4/7/10 bytes for LA_NFCID1"));
                                warn("RF: invalid NFCID1 length=" + desired.length);
                                continue;
                            }

                            byte[] rewritten = replaceExistingNfcid1Tlv(original, tlv, desired);
                            if (rewritten != null) {
                                args[i] = rewritten;
                                changed = true;
                                writeStatus(values(
                                        KEY_RF_STATUS, "APPLYING",
                                        KEY_RF_UID, bytesToHex(desired),
                                        KEY_RF_SOURCE, source,
                                        KEY_RF_ERROR, ""
                                ));
                                info("RF: NFCID1 REWRITE source=" + source
                                        + " oldLen=" + tlv.length
                                        + " oldUid=" + bytesToHex(tlv.value)
                                        + " newLen=" + desired.length
                                        + " newUid=" + bytesToHex(desired));
                            }
                        } else {
                            writeStatus(values(
                                    KEY_RF_STATUS, "OBSERVED",
                                    KEY_RF_UID, bytesToHex(tlv.value),
                                    KEY_RF_SOURCE, source,
                                    KEY_RF_ERROR, ""
                            ));
                        }
                    }

                    Object result = changed ? chain.proceed(args) : chain.proceed();
                    if (changed) {
                        boolean success = !(result instanceof Boolean) || Boolean.TRUE.equals(result);
                        writeStatus(values(
                                KEY_RF_STATUS, success ? "RF_CONFIG_ACCEPTED" : "FAILED",
                                KEY_RF_RESULT_KEY(), String.valueOf(result),
                                KEY_RF_ERROR, success ? "" : "RF config returned false"
                        ));
                        info("RF: CONFIG " + (success ? "ACCEPTED" : "FAILED")
                                + " source=" + source + " result=" + String.valueOf(result));
                    }
                    return result;
                });

                info("RF: HOOK INSTALLED " + method.toGenericString());
                installed++;
            }
            return installed;
        } catch (ClassNotFoundException e) {
            return 0;
        } catch (Throwable t) {
            error("RF: hook install failed " + className, t);
            return 0;
        }
    }

    private boolean isRfConfigCandidate(String name, Class<?>[] types) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.contains("config") || lower.contains("vendor") || lower.contains("raw")
                || lower.contains("rf") || lower.contains("write"))) return false;
        for (Class<?> type : types) if (type == byte[].class) return true;
        return false;
    }

    /**
     * Searches for a simple NCI TLV representation [tag][len][value...].
     * We only rewrite an already present 0x33 TLV; we never fabricate a packet.
     */
    private Nfcid1Tlv findNfcid1Tlv(byte[] data) {
        if (data == null || data.length < 2) return null;
        for (int i = 0; i + 1 < data.length; i++) {
            int tag = data[i] & 0xFF;
            if (tag != PARAM_LA_NFCID1) continue;
            int len = data[i + 1] & 0xFF;
            if (!(len == 0 || len == 4 || len == 7 || len == 10)) continue;
            if (i + 2 + len > data.length) continue;
            return new Nfcid1Tlv(i, len, Arrays.copyOfRange(data, i + 2, i + 2 + len));
        }
        return null;
    }

    private byte[] replaceExistingNfcid1Tlv(byte[] data, Nfcid1Tlv tlv, byte[] desired) {
        int oldEnd = tlv.offset + 2 + tlv.length;
        int newSize = data.length - tlv.length + desired.length;
        byte[] out = new byte[newSize];
        System.arraycopy(data, 0, out, 0, tlv.offset);
        out[tlv.offset] = (byte) PARAM_LA_NFCID1;
        out[tlv.offset + 1] = (byte) desired.length;
        System.arraycopy(desired, 0, out, tlv.offset + 2, desired.length);
        System.arraycopy(data, oldEnd, out, tlv.offset + 2 + desired.length, data.length - oldEnd);
        return out;
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

    private String KEY_RF_RESULT_KEY() { return "rf_result"; }

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
        if (hex.isEmpty() || (hex.length() & 1) != 0)
            throw new IllegalArgumentException("invalid hex length=" + hex.length());
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private String normalizeHex(String value) {
        if (value == null) return "";
        return value.replace(":", "").replace(" ", "")
                .replace("0x", "").replace("0X", "").toUpperCase(Locale.ROOT);
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String bytesToHex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
        return sb.toString();
    }

    private String summarizeArgs(Object[] args) {
        if (args == null) return "null";
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof byte[]) {
                byte[] bytes = (byte[]) arg;
                parts.add(i + "=byte[len=" + bytes.length + ",hex=" + bytesToHex(bytes) + "]");
            } else parts.add(i + "=" + String.valueOf(arg));
        }
        return parts.toString();
    }

    private static final class Nfcid1Tlv {
        final int offset;
        final int length;
        final byte[] value;
        Nfcid1Tlv(int offset, int length, byte[] value) {
            this.offset = offset;
            this.length = length;
            this.value = value;
        }
    }

    private static final class SimConfig {
        static final SimConfig DISABLED = new SimConfig(false, null, null, null);
        final boolean active;
        final String uid, sak, atqa;
        SimConfig(boolean active, String uid, String sak, String atqa) {
            this.active = active;
            this.uid = uid;
            this.sak = sak;
            this.atqa = atqa;
        }
    }

    private void info(String msg) { log(Log.INFO, TAG, msg); Log.i(TAG, msg); }
    private void warn(String msg) { log(Log.WARN, TAG, msg); Log.w(TAG, msg); }
    private void error(String msg, Throwable t) { log(Log.ERROR, TAG, msg, t); Log.e(TAG, msg, t); }
}
