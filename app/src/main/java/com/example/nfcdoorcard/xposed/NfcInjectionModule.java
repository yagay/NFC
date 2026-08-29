package com.example.nfcdoorcard.xposed;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import com.example.nfcdoorcard.xposed.adapter.NfcStackAdapter;
import com.example.nfcdoorcard.xposed.adapter.OplusNxpAdapter;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Stable LSPosed entry. Vendor-specific behavior lives in NfcStackAdapter implementations. */
public class NfcInjectionModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";
    private static final int HOOK_BUILD = 9;
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings");

    private static final String[] REFRESH_PROBE_CLASSES = new String[]{
            "com.android.nfc.VendorNfcService",
            "com.android.nfc.NfcService",
            "com.oplus.nfc.common.NfcChipDeviceImpl",
            "com.android.nfc.nxp.NxpNfcService",
            "com.android.nfc.nxp.NxpNfcService$NxpNfcAdapterService",
            "com.android.nfc.cardemulation.RegisteredServicesCache",
            "com.android.nfc.cardemulation.RegisteredAidCache",
            "com.android.nfc.cardemulation.AidRoutingManager",
            "com.android.nfc.cardemulation.CardEmulationManager"
    };

    private final NfcStackAdapter[] adapters = new NfcStackAdapter[]{
            new OplusNxpAdapter()
    };

    private volatile boolean disabledAfterFailure;
    private volatile NfcStackAdapter activeAdapter;
    private volatile int refreshProbeEventCount;

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

        installRefreshProbes(cl, pid);

        try {
            Method method = adapter.resolveInjectionMethod(cl);
            hook(method).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length != 1 || !(args[0] instanceof byte[])) return chain.proceed();

                SimConfig cfg = readConfig();
                if (!cfg.active || cfg.uid == null || disabledAfterFailure) return chain.proceed();

                String uidHex = cfg.uid.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
                String caller = compactCallStack(24);
                Log.i(TAG, "RFPROBE: CHANGE_RF_CALLER pid=" + pid + " uid=" + uidHex + " stack=" + caller);
                persistRfCaller(pid, caller);

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
        } catch (Throwable t) {
            Log.e(TAG, "PROD HOOK FAILED build=" + HOOK_BUILD + " adapter=" + adapter.id() + " pid=" + pid + " " +
                    t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            reportStatusWithRetry(pid, false, 0, "HOOK_FAILED", t.getClass().getSimpleName() + ": " + t.getMessage(), adapter.id());
        }
    }

    private void installRefreshProbes(ClassLoader cl, int pid) {
        List<String> installed = new ArrayList<>();
        for (String className : REFRESH_PROBE_CLASSES) {
            try {
                Class<?> clazz = Class.forName(className, false, cl);
                for (Method candidate : clazz.getDeclaredMethods()) {
                    if (!isRefreshProbeCandidate(candidate)) continue;
                    String signature = methodSignature(candidate);
                    try {
                        hook(candidate).intercept(chain -> {
                            int event = ++refreshProbeEventCount;
                            Object[] args = chain.getArgs().toArray();
                            String argText = summarizeArgs(args);
                            String stack = compactCallStack(24);
                            String enter = "ENTER " + signature + " event=" + event +
                                    " thread=" + Thread.currentThread().getName() +
                                    " args=" + argText + " stack=" + stack;
                            Log.i(TAG, "RFPROBE: " + enter);
                            persistRefreshProbeEvent(pid, enter);
                            Object result = chain.proceed();
                            String exit = "EXIT " + signature + " event=" + event +
                                    " result=" + summarizeValue(result);
                            Log.i(TAG, "RFPROBE: " + exit);
                            persistRefreshProbeEvent(pid, exit);
                            return result;
                        });
                        installed.add(signature);
                        Log.i(TAG, "RFPROBE: CANDIDATE INSTALLED " + signature);
                    } catch (Throwable t) {
                        Log.w(TAG, "RFPROBE: hook candidate failed " + signature + " " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }
            } catch (Throwable t) {
                Log.i(TAG, "RFPROBE: class unavailable " + className + " " + t.getClass().getSimpleName());
            }
        }

        String candidates = String.join(" | ", installed);
        ContentValues v = new ContentValues();
        v.put("refresh_probe_count", installed.size());
        v.put("refresh_probe_candidates", candidates);
        v.put("refresh_probe_last", "INSTALLED");
        v.put("refresh_probe_pid", pid);
        writeValuesWithRetry(v, 30, 200L);
        Log.i(TAG, "RFPROBE: READY candidates=" + installed.size() + " " + candidates);
    }

    private boolean isRefreshProbeCandidate(Method method) {
        String name = method.getName().toLowerCase(Locale.ROOT);
        String owner = method.getDeclaringClass().getName();

        if (owner.equals("com.android.nfc.cardemulation.RegisteredServicesCache")) {
            return name.equals("registeraidgroupforservice") || name.equals("removeaidgroupforservice") ||
                    name.equals("onservicesupdated") || name.contains("invalidatecache");
        }
        if (owner.equals("com.android.nfc.cardemulation.RegisteredAidCache")) {
            return name.equals("onservicesupdated") || name.equals("generateaidcachelocked") ||
                    name.equals("updateroutinglocked") || name.contains("routing");
        }
        if (owner.equals("com.android.nfc.cardemulation.AidRoutingManager")) {
            return name.equals("configurerouting") || name.equals("commit") || name.contains("routing");
        }
        if (owner.equals("com.android.nfc.cardemulation.CardEmulationManager")) {
            return name.equals("onservicesupdated") || name.contains("aidgroup") || name.contains("routing");
        }
        if (owner.equals("com.oplus.nfc.common.NfcChipDeviceImpl")) {
            return name.equals("setrfconfig") || name.equals("setconfig") || name.contains("transitconfig") ||
                    name.contains("restore") || name.contains("rfconfig");
        }
        if (owner.startsWith("com.android.nfc.nxp.NxpNfcService")) {
            return name.equals("setrfconfig") || name.equals("setconfig") || name.contains("transitconfig") ||
                    name.equals("changerfparamsbyconfig");
        }

        if (name.equals("applyprerfconfig")) return true;
        if (name.contains("transitconfig")) return true;
        if (name.contains("routing") && (name.contains("apply") || name.contains("update") || name.contains("commit") || name.contains("configure"))) return true;
        if (name.contains("discovery") && (name.contains("start") || name.contains("stop") || name.contains("enable") || name.contains("disable") || name.contains("restart") || name.contains("update"))) return true;
        return name.contains("rf") && name.contains("config") && !name.equals("changerfparamsbyconfig");
    }

    private String methodSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getDeclaringClass().getName()).append('#').append(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(types[i].getSimpleName());
        }
        return sb.append(')').toString();
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
        Class<?> type = value.getClass();
        if (type == byte[].class) {
            byte[] data = (byte[]) value;
            StringBuilder hex = new StringBuilder();
            int limit = Math.min(data.length, 96);
            for (int i = 0; i < limit; i++) {
                if (i > 0) hex.append(' ');
                hex.append(String.format(Locale.ROOT, "%02X", data[i] & 0xFF));
            }
            if (data.length > limit) hex.append(" ...");
            return "byte[" + data.length + "]{" + hex + "}";
        }
        if (type.isArray()) {
            int length = Array.getLength(value);
            StringBuilder sb = new StringBuilder(type.getComponentType().getSimpleName())
                    .append('[').append(length).append("]{");
            int limit = Math.min(length, 12);
            for (int i = 0; i < limit; i++) {
                if (i > 0) sb.append(", ");
                sb.append(String.valueOf(Array.get(value, i)));
            }
            if (length > limit) sb.append(", ...");
            return sb.append('}').toString();
        }
        String s = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
        if (s.length() > 240) s = s.substring(0, 240) + "...";
        return type.getSimpleName() + "{" + s + "}";
    }

    private void persistRefreshProbeEvent(int pid, String event) {
        ContentValues v = new ContentValues();
        v.put("refresh_probe_events", refreshProbeEventCount);
        v.put("refresh_probe_last", event.length() > 3500 ? event.substring(0, 3500) : event);
        v.put("refresh_probe_pid", pid);
        writeValuesWithRetry(v, 8, 100L);
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
        if (app == null) return new SimConfig(false, null);
        boolean active = false;
        String uid = null;
        try (Cursor c = app.getContentResolver().query(CONFIG_URI, null, null, null, null)) {
            if (c != null) while (c.moveToNext()) {
                String key = c.getString(0);
                String value = c.getString(1);
                if ("simulation_enabled".equals(key)) active = Boolean.parseBoolean(value);
                else if ("uid".equals(key)) uid = value;
            }
        } catch (Throwable t) {
            Log.w(TAG, "config read failed: " + t.getMessage());
        }
        return new SimConfig(active, uid);
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

        SimConfig(boolean active, String uid) {
            this.active = active;
            this.uid = uid;
        }
    }
}
