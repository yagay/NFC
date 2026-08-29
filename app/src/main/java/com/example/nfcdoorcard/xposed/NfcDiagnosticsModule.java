package com.example.nfcdoorcard.xposed;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** One-shot full-chain OxygenOS/NXP NFC diagnostics. */
public class NfcDiagnosticsModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings");
    private static final int HOOK_BUILD = 9;
    private static final int PARAM_LA_NFCID1 = 0x33;

    private static final String KEY_SIMULATION_ENABLED = "simulation_enabled";
    private static final String KEY_UID = "uid";
    private static final String KEY_SAK = "sak";
    private static final String KEY_ATQA = "atqa";
    private static final String KEY_HOOK_BUILD = "hook_build";
    private static final String KEY_SCOPE_OK = "scope_ok";
    private static final String KEY_SCOPE_PROCESS = "scope_process";
    private static final String KEY_SCOPE_PID = "scope_pid";
    private static final String KEY_HOOK_INSTALLED = "hook_installed";
    private static final String KEY_HOOK_CLASS = "hook_class";
    private static final String KEY_HOOK_COUNT = "hook_count";
    private static final String KEY_HOOK_PID = "hook_pid";
    private static final String KEY_HIJACK_STATUS = "hijack_status";
    private static final String KEY_HIJACK_RESULT = "hijack_result";
    private static final String KEY_HIJACK_UID = "hijack_uid";
    private static final String KEY_HIJACK_ERROR = "hijack_error";
    private static final String KEY_HIJACK_PID = "hijack_pid";
    private static final String KEY_RF_STATUS = "rf_status";
    private static final String KEY_RF_UID = "rf_uid";
    private static final String KEY_RF_SOURCE = "rf_source";
    private static final String KEY_RF_RESULT = "rf_result";
    private static final String KEY_RF_ERROR = "rf_error";
    private static final String KEY_RF_PID = "rf_pid";
    private static final String KEY_TRACE_STAGE = "trace_stage";
    private static final String KEY_TRACE_SOURCE = "trace_source";
    private static final String KEY_TRACE_PID = "trace_pid";
    private static final String KEY_TEXT_CONFIG_SEEN = "text_config_seen";
    private static final String KEY_TEXT_CONFIG_SOURCE = "text_config_source";
    private static final String KEY_TEXT_CONFIG_LENGTH = "text_config_length";
    private static final String KEY_CONFIG_BLOCK_COUNT = "config_block_count";
    private static final String KEY_NCI_FRAME_COUNT = "nci_frame_count";
    private static final String KEY_NFCID1_COUNT = "nfcid1_count";
    private static final String KEY_HCE_GET_UID = "hce_get_uid";
    private static final String KEY_RF_FIELD_COUNT = "rf_field_count";
    private static final String KEY_LAST_NATIVE_RESULT = "last_native_result";
    private static final String KEY_FULL_DIAG_STAGE = "full_diag_stage";
    private static final String KEY_FULL_DIAG_SUMMARY = "full_diag_summary";

    private static final Pattern CONFIG_BLOCK = Pattern.compile("(?ms)([A-Z][A-Z0-9_]+)\\s*=\\s*\\{(.*?)\\}");
    private static final Pattern HEX_TOKEN = Pattern.compile("(?i)(?<![0-9A-F])([0-9A-F]{2})(?![0-9A-F])");
    private final AtomicInteger rfFieldCount = new AtomicInteger();
    private final AtomicInteger observedTextBlocks = new AtomicInteger();
    private final AtomicInteger observedNciFrames = new AtomicInteger();
    private final AtomicInteger observedNfcid1 = new AtomicInteger();

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        info("MODULE: loaded build=" + HOOK_BUILD + " process=" + param.getProcessName() + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
        super.onPackageLoaded(lp);
        if (!"com.android.nfc".equals(lp.getPackageName())) return;

        int pid = Process.myPid();
        info("SCOPE: SUCCESS build=" + HOOK_BUILD + " package=com.android.nfc pid=" + pid);
        writeStatus(values(
                KEY_HOOK_BUILD, HOOK_BUILD,
                KEY_SCOPE_OK, true,
                KEY_SCOPE_PROCESS, "com.android.nfc",
                KEY_SCOPE_PID, pid,
                KEY_HOOK_INSTALLED, false,
                KEY_HOOK_COUNT, 0,
                KEY_HOOK_PID, pid,
                KEY_HIJACK_STATUS, "IDLE",
                KEY_HIJACK_RESULT, "",
                KEY_HIJACK_UID, "",
                KEY_HIJACK_ERROR, "",
                KEY_HIJACK_PID, pid,
                KEY_RF_STATUS, "WAITING",
                KEY_RF_UID, "",
                KEY_RF_SOURCE, "",
                KEY_RF_RESULT, "",
                KEY_RF_ERROR, "",
                KEY_RF_PID, pid,
                KEY_TRACE_STAGE, "HOOKING",
                KEY_TRACE_SOURCE, "",
                KEY_TRACE_PID, pid,
                KEY_TEXT_CONFIG_SEEN, false,
                KEY_TEXT_CONFIG_SOURCE, "",
                KEY_TEXT_CONFIG_LENGTH, 0,
                KEY_CONFIG_BLOCK_COUNT, 0,
                KEY_NCI_FRAME_COUNT, 0,
                KEY_NFCID1_COUNT, 0,
                KEY_HCE_GET_UID, "",
                KEY_RF_FIELD_COUNT, 0,
                KEY_LAST_NATIVE_RESULT, "",
                KEY_FULL_DIAG_STAGE, "HOOKING",
                KEY_FULL_DIAG_SUMMARY, ""
        ));

        ClassLoader cl = lp.getDefaultClassLoader();
        int installed = 0;
        installed += installVerifiedHceHook(cl, "com.android.nfc.dhimpl.NxpNativeNfcManager");
        installed += installVerifiedHceHook(cl, "com.android.nfc.dhimpl.StNativeNfcManager");
        installed += installTraceHooks(cl, "com.android.nfc.dhimpl.NxpNativeNfcManager");
        installed += installTraceHooks(cl, "com.android.nfc.dhimpl.StNativeNfcManager");
        installed += installTraceHooks(cl, "com.android.nfc.VendorNfcService");
        installed += installTraceHooks(cl, "com.android.nfc.NfcService");

        writeStatus(values(
                KEY_HOOK_INSTALLED, installed > 0,
                KEY_HOOK_COUNT, installed,
                KEY_HOOK_PID, pid,
                KEY_HOOK_BUILD, HOOK_BUILD,
                KEY_TRACE_STAGE, installed > 0 ? "READY" : "HOOK_FAILED",
                KEY_FULL_DIAG_STAGE, installed > 0 ? "READY" : "HOOK_FAILED"
        ));
        if (installed > 0) info("HOOK: SUCCESS build=" + HOOK_BUILD + " installed=" + installed + " pid=" + pid);
        else warn("HOOK: FAILED build=" + HOOK_BUILD + " pid=" + pid);
    }

    private int installVerifiedHceHook(ClassLoader cl, String className) {
        try {
            Class<?> runtime = Class.forName(className, false, cl);
            Method method = runtime.getDeclaredMethod("setHceTypeAConfig", boolean.class, byte[].class, byte[].class, byte[].class);
            hook(method).intercept(chain -> {
                int pid = Process.myPid();
                Object[] incoming = chain.getArgs().toArray();
                SimConfig config = readConfig();
                String source = runtime.getSimpleName() + ".setHceTypeAConfig";
                info("HCE: ENTER build=" + HOOK_BUILD + " pid=" + pid + " source=" + source + " args=" + summarizeArgs(incoming) + " active=" + config.active);
                writeStatus(values(KEY_TRACE_STAGE, "HCE_ENTER", KEY_TRACE_SOURCE, source, KEY_TRACE_PID, pid, KEY_HIJACK_PID, pid, KEY_FULL_DIAG_STAGE, "HCE_ENTER"));

                if (!config.active || config.uid == null || config.uid.isBlank()) {
                    Object result = chain.proceed();
                    info("HCE: PASSIVE RETURN build=" + HOOK_BUILD + " pid=" + pid + " result=" + result);
                    return result;
                }

                String normalizedUid = normalizeHex(config.uid);
                try {
                    byte[] uid = hexToBytes(normalizedUid);
                    byte[] sak = hexToBytes(defaultIfBlank(config.sak, "08"));
                    byte[] atqa = hexToBytes(defaultIfBlank(config.atqa, "0400"));
                    info("HCE: APPLY build=" + HOOK_BUILD + " pid=" + pid + " uid=" + normalizedUid + " sak=" + bytesToHex(sak) + " atqa=" + bytesToHex(atqa));
                    writeStatus(values(KEY_HIJACK_STATUS, "APPLYING", KEY_HIJACK_UID, normalizedUid, KEY_HIJACK_ERROR, "", KEY_HIJACK_PID, pid));
                    Object result = chain.proceed(new Object[]{true, uid, sak, atqa});
                    boolean success = Boolean.TRUE.equals(result);
                    writeStatus(values(
                            KEY_HIJACK_STATUS, success ? "NATIVE_ACCEPTED" : "FAILED",
                            KEY_HIJACK_RESULT, String.valueOf(result),
                            KEY_HIJACK_UID, normalizedUid,
                            KEY_HIJACK_ERROR, success ? "" : "setHceTypeAConfig returned " + result,
                            KEY_HIJACK_PID, pid,
                            KEY_LAST_NATIVE_RESULT, source + "=" + result,
                            KEY_FULL_DIAG_STAGE, success ? "HCE_NATIVE_ACCEPTED" : "HCE_NATIVE_FAILED"
                    ));
                    info("HCE: " + (success ? "NATIVE_ACCEPTED" : "FAILED") + " build=" + HOOK_BUILD + " pid=" + pid + " result=" + result + " uid=" + normalizedUid);
                    return result;
                } catch (Throwable t) {
                    writeStatus(values(KEY_HIJACK_STATUS, "FAILED", KEY_HIJACK_RESULT, "exception", KEY_HIJACK_UID, normalizedUid,
                            KEY_HIJACK_ERROR, t.getClass().getSimpleName() + ": " + t.getMessage(), KEY_HIJACK_PID, pid));
                    error("HCE: FAILED", t);
                    return chain.proceed();
                }
            });
            writeStatus(values(KEY_HOOK_INSTALLED, true, KEY_HOOK_CLASS, className, KEY_HOOK_PID, Process.myPid(), KEY_HOOK_BUILD, HOOK_BUILD));
            info("HOOK: INSTALLED " + method.toGenericString());
            return 1;
        } catch (Throwable t) {
            warn("HOOK: verified HCE unavailable " + className + " " + t.getClass().getSimpleName());
            return 0;
        }
    }

    private int installTraceHooks(ClassLoader cl, String className) {
        final Class<?> runtime;
        try {
            runtime = Class.forName(className, false, cl);
        } catch (Throwable t) {
            info("TRACE: class absent " + className);
            return 0;
        }

        int installed = 0;
        for (Method method : runtime.getDeclaredMethods()) {
            if ("setHceTypeAConfig".equals(method.getName()) && className.contains("NativeNfcManager")) continue;
            if (!isTraceCandidate(method.getName(), method.getParameterTypes())) continue;
            try {
                hook(method).intercept(chain -> {
                    int pid = Process.myPid();
                    Object[] args = chain.getArgs().toArray();
                    String source = runtime.getSimpleName() + "." + method.getName();
                    info("TRACE: ENTER build=" + HOOK_BUILD + " pid=" + pid + " source=" + source + " args=" + summarizeArgs(args));
                    writeStatus(values(KEY_TRACE_STAGE, "CALL_ENTER", KEY_TRACE_SOURCE, source, KEY_TRACE_PID, pid));

                    if ("notifyRfFieldActivated".equals(method.getName())) {
                        int count = rfFieldCount.incrementAndGet();
                        writeStatus(values(KEY_RF_FIELD_COUNT, count, KEY_FULL_DIAG_STAGE, "RF_FIELD_SEEN"));
                        info("RF_FIELD: ACTIVATED build=" + HOOK_BUILD + " pid=" + pid + " count=" + count);
                    }

                    for (int i = 0; i < args.length; i++) {
                        if (!(args[i] instanceof byte[])) continue;
                        byte[] data = (byte[]) args[i];
                        if (looksLikeConfigText(data)) {
                            analyzeConfigText(source, i, data, pid);
                        } else {
                            analyzeRawNci(source, i, data, pid);
                        }
                    }

                    Object result = chain.proceed();
                    info("TRACE: RETURN build=" + HOOK_BUILD + " pid=" + pid + " source=" + source + " result=" + String.valueOf(result));
                    writeStatus(values(KEY_LAST_NATIVE_RESULT, source + "=" + String.valueOf(result)));

                    if ("doGetHceTypeAUid".equals(method.getName())) {
                        String uid = String.valueOf(result);
                        writeStatus(values(KEY_HCE_GET_UID, uid, KEY_FULL_DIAG_STAGE, "HCE_UID_READ"));
                        info("HCE_UID: GET build=" + HOOK_BUILD + " pid=" + pid + " value=" + uid);
                    }

                    if ("changeRfParamsByConfig".equals(method.getName())) {
                        Boolean ok = classifyResult(result);
                        String state = Boolean.TRUE.equals(ok) ? "TEXT_CONFIG_ACCEPTED" : Boolean.FALSE.equals(ok) ? "TEXT_CONFIG_FAILED" : "TEXT_CONFIG_RESULT_UNKNOWN";
                        writeStatus(values(KEY_RF_STATUS, state, KEY_RF_SOURCE, source, KEY_RF_RESULT, String.valueOf(result), KEY_RF_PID, pid,
                                KEY_RF_ERROR, Boolean.FALSE.equals(ok) ? "changeRfParamsByConfig returned " + result : "", KEY_FULL_DIAG_STAGE, state));
                        info("TEXT_CONFIG: RESULT build=" + HOOK_BUILD + " pid=" + pid + " source=" + source + " result=" + result + " state=" + state);
                    }
                    return result;
                });
                installed++;
                info("TRACE: HOOK INSTALLED " + method.toGenericString());
            } catch (Throwable one) {
                warn("TRACE: method hook failed " + method.toGenericString() + " " + one.getClass().getSimpleName());
            }
        }
        return installed;
    }

    private void analyzeConfigText(String source, int argIndex, byte[] data, int pid) {
        String text = new String(data, StandardCharsets.UTF_8);
        int blocks = 0;
        int frames = 0;
        int nfcid = 0;
        List<String> blockNames = new ArrayList<>();

        Matcher matcher = CONFIG_BLOCK.matcher(text);
        while (matcher.find()) {
            blocks++;
            String name = matcher.group(1);
            String body = matcher.group(2);
            blockNames.add(name);
            byte[] blockBytes = parseHexTokens(body);
            int blockFrames = 0;
            int blockNfcid = 0;
            for (int off = 0; off + 3 <= blockBytes.length; off++) {
                if ((blockBytes[off] & 0xFF) != 0x20 || (blockBytes[off + 1] & 0xFF) != 0x02) continue;
                int payloadLen = blockBytes[off + 2] & 0xFF;
                int frameEnd = off + 3 + payloadLen;
                if (frameEnd > blockBytes.length) continue;
                blockFrames++;
                frames++;
                byte[] frame = Arrays.copyOfRange(blockBytes, off, frameEnd);
                info("NCI_TEXT: CORE_SET_CONFIG build=" + HOOK_BUILD + " pid=" + pid + " block=" + name + " offset=" + off + " len=" + frame.length + " hex=" + bytesToHex(frame));
                List<Nfcid1Tlv> ids = findNfcid1InsideFrame(frame);
                for (Nfcid1Tlv id : ids) {
                    nfcid++;
                    blockNfcid++;
                    info("NCI_TEXT: LA_NFCID1 build=" + HOOK_BUILD + " pid=" + pid + " block=" + name + " len=" + id.length + " uid=" + bytesToHex(id.value));
                    writeStatus(values(KEY_RF_UID, bytesToHex(id.value), KEY_RF_SOURCE, source + ":" + name, KEY_RF_PID, pid));
                }
            }
            info("TEXT_CONFIG: BLOCK build=" + HOOK_BUILD + " pid=" + pid + " name=" + name + " bytes=" + blockBytes.length + " coreSet=" + blockFrames + " nfcid1=" + blockNfcid);
        }

        observedTextBlocks.set(blocks);
        observedNciFrames.set(frames);
        observedNfcid1.set(nfcid);
        String summary = "text=true len=" + data.length + " blocks=" + blocks + " coreSet=" + frames + " nfcid1=" + nfcid + " names=" + blockNames;
        writeStatus(values(
                KEY_TEXT_CONFIG_SEEN, true,
                KEY_TEXT_CONFIG_SOURCE, source,
                KEY_TEXT_CONFIG_LENGTH, data.length,
                KEY_CONFIG_BLOCK_COUNT, blocks,
                KEY_NCI_FRAME_COUNT, frames,
                KEY_NFCID1_COUNT, nfcid,
                KEY_RF_STATUS, nfcid > 0 ? "NFCID1_FOUND_IN_TEXT_CONFIG" : "TEXT_CONFIG_PARSED_NO_NFCID1",
                KEY_RF_SOURCE, source,
                KEY_RF_RESULT, summary,
                KEY_RF_ERROR, "",
                KEY_RF_PID, pid,
                KEY_FULL_DIAG_STAGE, "TEXT_CONFIG_PARSED",
                KEY_FULL_DIAG_SUMMARY, summary
        ));
        info("TEXT_CONFIG: SUMMARY build=" + HOOK_BUILD + " pid=" + pid + " source=" + source + " arg=" + argIndex + " " + summary);
    }

    private void analyzeRawNci(String source, int argIndex, byte[] data, int pid) {
        int frames = 0;
        int nfcid = 0;
        for (int off = 0; off + 3 <= data.length; off++) {
            if ((data[off] & 0xFF) != 0x20 || (data[off + 1] & 0xFF) != 0x02) continue;
            int payloadLen = data[off + 2] & 0xFF;
            int frameEnd = off + 3 + payloadLen;
            if (frameEnd > data.length) continue;
            frames++;
            byte[] frame = Arrays.copyOfRange(data, off, frameEnd);
            info("NCI_RAW: CORE_SET_CONFIG build=" + HOOK_BUILD + " pid=" + pid + " source=" + source + " arg=" + argIndex + " offset=" + off + " hex=" + bytesToHex(frame));
            List<Nfcid1Tlv> ids = findNfcid1InsideFrame(frame);
            for (Nfcid1Tlv id : ids) {
                nfcid++;
                info("NCI_RAW: LA_NFCID1 build=" + HOOK_BUILD + " pid=" + pid + " source=" + source + " len=" + id.length + " uid=" + bytesToHex(id.value));
            }
        }
        if (frames > 0) {
            writeStatus(values(KEY_NCI_FRAME_COUNT, frames, KEY_NFCID1_COUNT, nfcid,
                    KEY_RF_STATUS, nfcid > 0 ? "RAW_NFCID1_FOUND" : "RAW_CORE_SET_CONFIG_FOUND",
                    KEY_RF_SOURCE, source, KEY_RF_RESULT, "frames=" + frames + " nfcid1=" + nfcid, KEY_RF_PID, pid,
                    KEY_FULL_DIAG_STAGE, "RAW_NCI_PARSED"));
        }
    }

    private List<Nfcid1Tlv> findNfcid1InsideFrame(byte[] frame) {
        List<Nfcid1Tlv> out = new ArrayList<>();
        if (frame == null || frame.length < 5) return out;
        int payloadLen = frame[2] & 0xFF;
        int end = Math.min(frame.length, 3 + payloadLen);
        for (int i = 4; i + 1 < end; i++) {
            if ((frame[i] & 0xFF) != PARAM_LA_NFCID1) continue;
            int len = frame[i + 1] & 0xFF;
            if (!(len == 0 || len == 4 || len == 7 || len == 10)) continue;
            if (i + 2 + len > end) continue;
            out.add(new Nfcid1Tlv(i, len, Arrays.copyOfRange(frame, i + 2, i + 2 + len)));
        }
        return out;
    }

    private byte[] parseHexTokens(String body) {
        List<Byte> list = new ArrayList<>();
        Matcher m = HEX_TOKEN.matcher(body == null ? "" : body);
        while (m.find()) {
            try { list.add((byte) Integer.parseInt(m.group(1), 16)); } catch (Throwable ignored) { }
        }
        byte[] out = new byte[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private boolean looksLikeConfigText(byte[] data) {
        if (data == null || data.length < 16) return false;
        String prefix = new String(data, 0, Math.min(data.length, 512), StandardCharsets.UTF_8);
        if (prefix.contains("NXP_") || prefix.contains("OPLUS_") || prefix.contains("CONF_EXTN")) return true;
        int printable = 0;
        int sample = Math.min(data.length, 512);
        for (int i = 0; i < sample; i++) {
            int b = data[i] & 0xFF;
            if (b == 9 || b == 10 || b == 13 || (b >= 32 && b <= 126)) printable++;
        }
        return sample > 0 && printable * 100 / sample >= 90;
    }

    private Boolean classifyResult(Object result) {
        if (result instanceof Boolean) return (Boolean) result;
        if (result instanceof Number) return ((Number) result).longValue() >= 0L;
        if (result == null) return null;
        return null;
    }

    private boolean isTraceCandidate(String name, Class<?>[] types) {
        String lower = name.toLowerCase(Locale.ROOT);
        boolean named = lower.contains("config") || lower.contains("vendor") || lower.contains("raw") || lower.contains("rf")
                || lower.contains("hce") || lower.contains("nci") || lower.contains("discover") || lower.contains("listen") || lower.contains("write")
                || lower.contains("transit") || lower.contains("field");
        if (!named) return false;
        for (Class<?> type : types) if (type == byte[].class) return true;
        return lower.contains("config") || lower.contains("hce") || lower.contains("rf") || lower.contains("discover") || lower.contains("listen") || lower.contains("field");
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
        ContentValues v = new ContentValues();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = String.valueOf(pairs[i]);
            Object value = pairs[i + 1];
            if (value instanceof Boolean) v.put(key, (Boolean) value);
            else if (value instanceof Integer) v.put(key, (Integer) value);
            else if (value instanceof Long) v.put(key, (Long) value);
            else v.put(key, String.valueOf(value));
        }
        return v;
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
                if (looksLikeConfigText(bytes)) {
                    String preview = new String(bytes, 0, Math.min(bytes.length, 180), StandardCharsets.UTF_8).replace('\n', ' ').replace('\r', ' ');
                    parts.add(i + "=textConfig[len=" + bytes.length + ",preview=" + preview + "]");
                } else {
                    String hex = bytesToHex(bytes);
                    parts.add(i + "=byte[len=" + bytes.length + ",hex=" + (hex.length() > 1024 ? hex.substring(0, 1024) + "..." : hex) + "]");
                }
            } else parts.add(i + "=" + String.valueOf(arg));
        }
        return parts.toString();
    }

    private static final class Nfcid1Tlv {
        final int offset;
        final int length;
        final byte[] value;
        Nfcid1Tlv(int offset, int length, byte[] value) { this.offset = offset; this.length = length; this.value = value; }
    }

    private static final class SimConfig {
        static final SimConfig DISABLED = new SimConfig(false, null, null, null);
        final boolean active;
        final String uid, sak, atqa;
        SimConfig(boolean active, String uid, String sak, String atqa) { this.active = active; this.uid = uid; this.sak = sak; this.atqa = atqa; }
    }

    private void info(String msg) { log(Log.INFO, TAG, msg); Log.i(TAG, msg); }
    private void warn(String msg) { log(Log.WARN, TAG, msg); Log.w(TAG, msg); }
    private void error(String msg, Throwable t) { log(Log.ERROR, TAG, msg, t); Log.e(TAG, msg, t); }
}
