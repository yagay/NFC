package com.example.nfcdoorcard.xposed;

import android.app.Application;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import com.example.nfcdoorcard.xposed.adapter.NfcStackAdapter;
import com.example.nfcdoorcard.xposed.adapter.GenericNxpAdapter;
import com.example.nfcdoorcard.xposed.adapter.OplusNxpAdapter;

import java.lang.reflect.Method;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Stable LSPosed entry. Vendor-specific behavior lives in NfcStackAdapter implementations. */
public class NfcInjectionModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";
    private static final int HOOK_BUILD = 10;
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings");


    private final NfcStackAdapter[] adapters = new NfcStackAdapter[]{
            // Prefer the proven vendor-specific implementation, then fall back to
            // a strictly validated raw CORE_SET_CONFIG NXP implementation.
            new OplusNxpAdapter(),
            new GenericNxpAdapter()
    };

    private volatile boolean disabledAfterFailure;
    private volatile NfcStackAdapter activeAdapter;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        Log.i(TAG, "PROD MODULE loaded build=" + HOOK_BUILD + " process=" + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
        super.onPackageLoaded(lp);
        if (!"com.android.nfc".equals(lp.getPackageName())) return;

        final int pid = Process.myPid();
        final ClassLoader cl = lp.getDefaultClassLoader();
        reportStatusWithRetry(pid, false, 0, "DETECTING", "Detecting NFC stack adapter", null);

        NfcStackAdapter adapter = selectAdapter(cl, pid);
        if (adapter == null) {
            Log.e(TAG, "ADAPTER UNSUPPORTED pid=" + pid);
            reportStatusWithRetry(pid, false, 0, "UNSUPPORTED", "No compatible NFC stack adapter", null);
            return;
        }
        activeAdapter = adapter;

        try {
            Method method = adapter.resolveInjectionMethod(cl);
            hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length != 1 || !(args[0] instanceof byte[])) return chain.proceed();

                SimConfig cfg = readConfig();
                if (!cfg.active || cfg.uid == null || disabledAfterFailure) return chain.proceed();

                String uidHex = cfg.uid.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
                if (cfg.diagnostics) {
                    String caller = compactCallStack(24);
                    Log.i(TAG, "RFPROBE: CHANGE_RF_CALLER pid=" + pid + " uid=" + uidHex + " stack=" + caller);
                    persistRfCaller(pid, caller);
                }

                if (uidHex.length() != 8) {
                    writeRfStatus("UID_INVALID", uidHex, "UID must be 4 bytes", "");
                    return chain.proceed();
                }

                NfcStackAdapter.InjectionResult injected = adapter.inject((byte[]) args[0], hexToBytes(uidHex));
                if (!injected.changed) {
                    writeRfStatus("WAITING", uidHex, injected.reason, "");
                    return chain.proceed();
                }

                Log.i(TAG, "NFCID1 APPLY adapter=" + adapter.id() + " pid=" + pid + " uid=" + uidHex +
                        " payload=" + injected.oldPayloadLength + "->" + injected.newPayloadLength +
                        " params=" + injected.oldParamCount + "->" + injected.newParamCount);
                writeRfStatus("APPLYING", uidHex, adapter.id(), "pending");

                Object result = chain.proceed(new Object[]{injected.data});
                boolean ok = result instanceof Number && ((Number) result).intValue() == 0;
                if (ok) {
                    Log.i(TAG, "NFCID1 ACCEPTED adapter=" + adapter.id() + " pid=" + pid + " uid=" + uidHex + " result=" + result);
                    writeRfStatus("RF_UID_APPLIED", uidHex, adapter.id(), String.valueOf(result));
                } else {
                    disabledAfterFailure = true;
                    Log.e(TAG, "NFCID1 FAILED adapter=" + adapter.id() + " pid=" + pid + " uid=" + uidHex + " result=" + result);
                    writeRfStatus("RF_UID_FAILED", uidHex, "native rejected; injection disabled until NFC process restart", String.valueOf(result));
                }
                return result;
            });

            reportStatusWithRetry(pid, true, 1, "READY", "Adapter " + adapter.id() + " ready", adapter.id());
            Log.i(TAG, "PROD HOOK READY build=" + HOOK_BUILD + " adapter=" + adapter.id() + " pid=" + pid);
            notifyAppHookReady(pid, adapter.id());
        } catch (Throwable t) {
            Log.e(TAG, "PROD HOOK FAILED build=" + HOOK_BUILD + " adapter=" + adapter.id() + " pid=" + pid + " " +
                    t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            reportStatusWithRetry(pid, false, 0, "HOOK_FAILED", t.getClass().getSimpleName() + ": " + t.getMessage(), adapter.id());
        }
    }

    private void notifyAppHookReady(int pid, String adapterId) {
        Application app = currentApplication();
        if (app == null) {
            Log.w(TAG, "AUTO_RESTORE: currentApplication unavailable pid=" + pid);
            return;
        }
        try {
            Intent intent = new Intent("com.example.nfcdoorcard.action.NFC_HOOK_READY");
            intent.setPackage("com.example.nfcdoorcard");
            intent.putExtra("nfc_pid", pid);
            intent.putExtra("adapter", adapterId == null ? "" : adapterId);
            app.sendBroadcast(intent);
            Log.i(TAG, "AUTO_RESTORE: hook-ready broadcast sent pid=" + pid + " adapter=" + adapterId);
        } catch (Throwable t) {
            Log.w(TAG, "AUTO_RESTORE: broadcast failed " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private String compactCallStack(int maxFrames) {
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String c = e.getClassName();
            if (c.equals(Thread.class.getName()) || c.equals(NfcInjectionModule.class.getName())) continue;
            if (c.startsWith("java.lang.Thread")) continue;
            if (kept++ > 0) sb.append(" <- ");
            sb.append(c).append('#').append(e.getMethodName()).append(':').append(e.getLineNumber());
            if (kept >= maxFrames) break;
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    private void persistRfCaller(int pid, String caller) {
        ContentValues v = new ContentValues();
        v.put("rf_caller", caller.length() > 3500 ? caller.substring(0, 3500) : caller);
        v.put("rf_caller_pid", pid);
        writeValuesWithRetry(v, 8, 100L);
    }

    private NfcStackAdapter selectAdapter(ClassLoader cl, int pid) {
        for (NfcStackAdapter adapter : adapters) {
            NfcStackAdapter.Detection detection = adapter.detect(cl);
            Log.i(TAG, "ADAPTER DETECT id=" + adapter.id() + " supported=" + detection.supported + " detail=" + detection.detail);
            persistAdapterState(pid, adapter.id(), detection.supported, detection.detail);
            if (detection.supported) return adapter;
        }
        return null;
    }

    private void persistAdapterState(int pid, String adapterId, boolean supported, String detail) {
        ContentValues v = new ContentValues();
        v.put("adapter_id", adapterId == null ? "" : adapterId);
        v.put("adapter_supported", supported);
        v.put("adapter_detail", detail == null ? "" : detail);
        v.put("adapter_pid", pid);
        writeValuesWithRetry(v, 30, 200L);
    }

    private void reportStatusWithRetry(int pid, boolean ready, int count, String stage, String summary, String adapterId) {
        ContentValues v = new ContentValues();
        v.put("hook_build", HOOK_BUILD);
        v.put("scope_ok", true);
        v.put("scope_process", "com.android.nfc");
        v.put("scope_pid", pid);
        v.put("hook_installed", ready);
        v.put("hook_class", "NfcInjectionModule");
        v.put("hook_count", count);
        v.put("hook_pid", pid);
        v.put("full_diag_stage", stage);
        v.put("full_diag_summary", summary);
        if (adapterId != null) v.put("adapter_id", adapterId);
        writeValuesWithRetry(v, 30, 200L);
    }

    private void writeRfStatus(String state, String uid, String detail, String result) {
        ContentValues v = new ContentValues();
        v.put("rf_status", state);
        v.put("rf_uid", uid == null ? "" : uid);
        v.put("rf_source", activeAdapter == null ? "" : activeAdapter.id());
        v.put("rf_result", result == null ? "" : result);
        v.put("rf_error", state.endsWith("FAILED") || state.equals("UID_INVALID") ? detail : "");
        v.put("rf_pid", Process.myPid());
        v.put("full_diag_stage", state);
        v.put("full_diag_summary", detail == null ? "" : detail);
        writeValuesWithRetry(v, 30, 200L);
    }

    private void writeValuesWithRetry(ContentValues values, int attempts, long delayMs) {
        ContentValues copy = new ContentValues(values);
        Thread t = new Thread(() -> {
            for (int i = 0; i < attempts; i++) {
                Application app = currentApplication();
                if (app != null) {
                    try {
                        app.getContentResolver().insert(CONFIG_URI, copy);
                        return;
                    } catch (Throwable e) {
                        Log.w(TAG, "status write attempt " + (i + 1) + " failed: " + e.getMessage());
                    }
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            Log.w(TAG, "status write gave up after " + attempts + " attempts");
        }, "NfcUIDSim-StateSync");
        t.setDaemon(true);
        t.start();
    }

    private SimConfig readConfig() {
        Application app = currentApplication();
        if (app == null) return new SimConfig(false, null, false);
        boolean active = false;
        String uid = null;
        boolean diagnostics = false;
        try (Cursor c = app.getContentResolver().query(CONFIG_URI, null, null, null, null)) {
            if (c != null) while (c.moveToNext()) {
                String key = c.getString(0);
                String value = c.getString(1);
                if ("simulation_enabled".equals(key)) active = Boolean.parseBoolean(value);
                else if ("uid".equals(key)) uid = value;
                else if ("diagnostic_logging_enabled".equals(key)) diagnostics = Boolean.parseBoolean(value);
            }
        } catch (Throwable t) {
            Log.w(TAG, "config read failed: " + t.getMessage());
        }
        return new SimConfig(active, uid, diagnostics);
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
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static final class SimConfig {
        final boolean active;
        final String uid;
        final boolean diagnostics;

        SimConfig(boolean active, String uid, boolean diagnostics) {
            this.active = active;
            this.uid = uid;
            this.diagnostics = diagnostics;
        }
    }
}
