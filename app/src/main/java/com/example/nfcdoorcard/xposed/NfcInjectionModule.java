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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Experimental fixed NFCID1 injector for the confirmed OxygenOS/NXP text-config path.
 *
 * Safety rules:
 *  - keeps the proven diagnostics module intact (super.onPackageLoaded)
 *  - only touches NxpNativeNfcManager.changeRfParamsByConfig(byte[])
 *  - only rewrites OPLUS_CONF_EXTN
 *  - only operates on a structurally valid CORE_SET_CONFIG frame
 *  - only accepts a 4-byte requested UID
 *  - appends LA_NFCID1 (33 04 xx xx xx xx) and fixes payload length + parameter count
 *  - disables further injection for the current NFC process after any non-zero native result
 */
public class NfcInjectionModule extends NfcDiagnosticsModule {
    private static final String TAG = "NfcUIDSim";
    private static final int INJECT_BUILD = 10;
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings");
    private static final Pattern OPLUS_BLOCK = Pattern.compile("(?ms)(OPLUS_CONF_EXTN\\s*=\\s*\\{)(.*?)(\\})");
    private static final Pattern HEX_TOKEN = Pattern.compile("(?i)(?<![0-9A-F])([0-9A-F]{2})(?![0-9A-F])");
    private volatile boolean disabledAfterFailure = false;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        Log.i(TAG, "INJECT: MODULE loaded build=" + INJECT_BUILD + " process=" + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
        super.onPackageLoaded(lp);
        if (!"com.android.nfc".equals(lp.getPackageName())) return;
        installInjector(lp.getDefaultClassLoader());
    }

    private void installInjector(ClassLoader cl) {
        final int pid = Process.myPid();
        try {
            Class<?> runtime = Class.forName("com.android.nfc.dhimpl.NxpNativeNfcManager", false, cl);
            Method method = runtime.getDeclaredMethod("changeRfParamsByConfig", byte[].class);
            hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length != 1 || !(args[0] instanceof byte[])) return chain.proceed();

                byte[] original = (byte[]) args[0];
                SimConfig cfg = readConfig();
                if (!cfg.active || cfg.uid == null || disabledAfterFailure) return chain.proceed();

                String normalized = cfg.uid.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
                if (normalized.length() != 8) {
                    Log.w(TAG, "INJECT: SKIP build=" + INJECT_BUILD + " pid=" + pid + " reason=UID_NOT_4_BYTES uid=" + normalized);
                    return chain.proceed();
                }

                byte[] uid = hexToBytes(normalized);
                InjectionResult injected = injectIntoOplusConfig(original, uid);
                if (!injected.changed) {
                    Log.w(TAG, "INJECT: SKIP build=" + INJECT_BUILD + " pid=" + pid + " reason=" + injected.reason);
                    writeStatus("NFCID1_INJECT_SKIPPED", normalized, injected.reason, "");
                    return chain.proceed();
                }

                Log.i(TAG, "INJECT: APPLY build=" + INJECT_BUILD + " pid=" + pid
                        + " uid=" + normalized
                        + " oldPayload=" + injected.oldPayloadLength
                        + " newPayload=" + injected.newPayloadLength
                        + " oldCount=" + injected.oldParamCount
                        + " newCount=" + injected.newParamCount
                        + " oldTextLen=" + original.length
                        + " newTextLen=" + injected.data.length);
                Log.i(TAG, "INJECT: FRAME build=" + INJECT_BUILD + " pid=" + pid + " hex=" + bytesToHex(injected.frame));
                writeStatus("NFCID1_INJECTING", normalized, "OPLUS_CONF_EXTN", "pending");

                Object result = chain.proceed(new Object[]{injected.data});
                boolean ok = result instanceof Number && ((Number) result).intValue() == 0;
                if (ok) {
                    Log.i(TAG, "INJECT: ACCEPTED build=" + INJECT_BUILD + " pid=" + pid + " uid=" + normalized + " result=" + result);
                    writeStatus("NFCID1_INJECT_ACCEPTED", normalized, "OPLUS_CONF_EXTN", String.valueOf(result));
                } else {
                    disabledAfterFailure = true;
                    Log.e(TAG, "INJECT: FAILED build=" + INJECT_BUILD + " pid=" + pid + " uid=" + normalized + " result=" + result + " futureInjection=DISABLED");
                    writeStatus("NFCID1_INJECT_FAILED", normalized, "disabled after native result", String.valueOf(result));
                }
                return result;
            });
            Log.i(TAG, "INJECT: READY build=" + INJECT_BUILD + " pid=" + pid + " method=" + method.toGenericString());
        } catch (Throwable t) {
            Log.e(TAG, "INJECT: INSTALL_FAILED build=" + INJECT_BUILD + " pid=" + pid + " " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    private InjectionResult injectIntoOplusConfig(byte[] input, byte[] uid) {
        if (input == null || input.length == 0) return InjectionResult.skip("EMPTY_INPUT");
        String text = new String(input, StandardCharsets.UTF_8);
        Matcher matcher = OPLUS_BLOCK.matcher(text);
        if (!matcher.find()) return InjectionResult.skip("OPLUS_CONF_EXTN_NOT_FOUND");

        byte[] block = parseHexTokens(matcher.group(2));
        if (block.length < 4) return InjectionResult.skip("OPLUS_BLOCK_TOO_SHORT");

        int frameStart = -1;
        int frameEnd = -1;
        for (int i = 0; i + 3 < block.length; i++) {
            if ((block[i] & 0xFF) == 0x20 && (block[i + 1] & 0xFF) == 0x02) {
                int payloadLen = block[i + 2] & 0xFF;
                int end = i + 3 + payloadLen;
                if (end <= block.length) {
                    frameStart = i;
                    frameEnd = end;
                    break;
                }
            }
        }
        if (frameStart < 0) return InjectionResult.skip("CORE_SET_CONFIG_NOT_FOUND");

        byte[] frame = Arrays.copyOfRange(block, frameStart, frameEnd);
        if (frame.length < 4) return InjectionResult.skip("CORE_SET_CONFIG_TOO_SHORT");
        int oldPayload = frame[2] & 0xFF;
        int oldCount = frame[3] & 0xFF;
        if (oldPayload + 6 > 0xFF || oldCount >= 0xFF) return InjectionResult.skip("FRAME_LENGTH_OVERFLOW");
        if (containsNfcid1(frame)) return InjectionResult.skip("LA_NFCID1_ALREADY_PRESENT");

        byte[] newFrame = Arrays.copyOf(frame, frame.length + 6);
        newFrame[2] = (byte) (oldPayload + 6);
        newFrame[3] = (byte) (oldCount + 1);
        int p = frame.length;
        newFrame[p++] = 0x33;
        newFrame[p++] = 0x04;
        System.arraycopy(uid, 0, newFrame, p, 4);

        byte[] newBlock = new byte[block.length + 6];
        System.arraycopy(block, 0, newBlock, 0, frameStart);
        System.arraycopy(newFrame, 0, newBlock, frameStart, newFrame.length);
        System.arraycopy(block, frameEnd, newBlock, frameStart + newFrame.length, block.length - frameEnd);

        String replacement = matcher.group(1) + "\n" + formatHexBlock(newBlock) + "\n" + matcher.group(3);
        String rewritten = text.substring(0, matcher.start()) + replacement + text.substring(matcher.end());
        return InjectionResult.changed(rewritten.getBytes(StandardCharsets.UTF_8), newFrame,
                oldPayload, oldPayload + 6, oldCount, oldCount + 1);
    }

    /** Structural CORE_SET_CONFIG parameter walk, including NXP A0xx two-byte parameter IDs. */
    private boolean containsNfcid1(byte[] frame) {
        if (frame.length < 4) return false;
        int pos = 4;
        int count = frame[3] & 0xFF;
        for (int n = 0; n < count && pos < frame.length; n++) {
            int first = frame[pos] & 0xFF;
            int id;
            int lenPos;
            if (first == 0xA0) {
                if (pos + 2 >= frame.length) return false;
                id = (first << 8) | (frame[pos + 1] & 0xFF);
                lenPos = pos + 2;
            } else {
                if (pos + 1 >= frame.length) return false;
                id = first;
                lenPos = pos + 1;
            }
            int len = frame[lenPos] & 0xFF;
            int valuePos = lenPos + 1;
            if (valuePos + len > frame.length) return false;
            if (id == 0x33) return true;
            pos = valuePos + len;
        }
        return false;
    }

    private static byte[] parseHexTokens(String body) {
        Matcher m = HEX_TOKEN.matcher(body == null ? "" : body);
        List<Byte> list = new ArrayList<>();
        while (m.find()) list.add((byte) Integer.parseInt(m.group(1), 16));
        byte[] out = new byte[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private static String formatHexBlock(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i % 12 == 0) sb.append("        ");
            sb.append(String.format(Locale.ROOT, "%02X", data[i] & 0xFF));
            if (i != data.length - 1) sb.append(",");
            if (i % 12 == 11 || i == data.length - 1) sb.append("\n");
            else sb.append("  ");
        }
        return sb.toString().stripTrailing();
    }

    private SimConfig readConfig() {
        Application app = currentApplication();
        if (app == null) return new SimConfig(false, null);
        boolean active = false;
        String uid = null;
        try (Cursor c = app.getContentResolver().query(CONFIG_URI, null, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String key = c.getString(0);
                    String value = c.getString(1);
                    if ("simulation_enabled".equals(key)) active = Boolean.parseBoolean(value);
                    else if ("uid".equals(key)) uid = value;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "INJECT: config read failed " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return new SimConfig(active, uid);
    }

    private void writeStatus(String state, String uid, String detail, String result) {
        Application app = currentApplication();
        if (app == null) return;
        try {
            ContentValues v = new ContentValues();
            v.put("rf_status", state);
            v.put("rf_uid", uid == null ? "" : uid);
            v.put("rf_source", "NfcInjectionModule:OPLUS_CONF_EXTN");
            v.put("rf_result", result == null ? "" : result);
            v.put("rf_error", state.endsWith("FAILED") || state.endsWith("SKIPPED") ? detail : "");
            v.put("rf_pid", Process.myPid());
            v.put("full_diag_stage", state);
            v.put("full_diag_summary", "injectBuild=" + INJECT_BUILD + " " + detail);
            app.getContentResolver().insert(CONFIG_URI, v);
        } catch (Throwable t) {
            Log.w(TAG, "INJECT: status write failed " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            return (Application) m.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
        return sb.toString();
    }

    private static final class SimConfig {
        final boolean active;
        final String uid;
        SimConfig(boolean active, String uid) { this.active = active; this.uid = uid; }
    }

    private static final class InjectionResult {
        final boolean changed;
        final String reason;
        final byte[] data;
        final byte[] frame;
        final int oldPayloadLength;
        final int newPayloadLength;
        final int oldParamCount;
        final int newParamCount;

        private InjectionResult(boolean changed, String reason, byte[] data, byte[] frame,
                                int oldPayloadLength, int newPayloadLength, int oldParamCount, int newParamCount) {
            this.changed = changed;
            this.reason = reason;
            this.data = data;
            this.frame = frame;
            this.oldPayloadLength = oldPayloadLength;
            this.newPayloadLength = newPayloadLength;
            this.oldParamCount = oldParamCount;
            this.newParamCount = newParamCount;
        }

        static InjectionResult skip(String reason) {
            return new InjectionResult(false, reason, null, null, 0, 0, 0, 0);
        }

        static InjectionResult changed(byte[] data, byte[] frame, int oldPayload, int newPayload, int oldCount, int newCount) {
            return new InjectionResult(true, "OK", data, frame, oldPayload, newPayload, oldCount, newCount);
        }
    }
}
