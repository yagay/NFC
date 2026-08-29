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
import java.lang.reflect.Constructor;
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

    private static final String KEY_REQUEST_GENERATION = "rf_request_generation";
    private static final String KEY_APPLIED_GENERATION = "rf_refresh_applied_generation";
    private static final String KEY_REFRESH_STATUS = "rf_refresh_status";
    private static final String KEY_REFRESH_DETAIL = "rf_refresh_detail";
    private static final String KEY_REFRESH_PID = "rf_refresh_pid";

    private static final String[] REFRESH_PROBE_CLASSES = new String[]{
            "com.android.nfc.VendorNfcService",
            "com.android.nfc.NfcService",
            "com.oplus.nfc.common.NfcChipDeviceImpl",
            "com.android.nfc.nxp.NxpNfcService",
            "com.android.nfc.nxp.NxpNfcService$NxpNfcAdapterService"
    };

    private final NfcStackAdapter[] adapters = new NfcStackAdapter[]{ new OplusNxpAdapter() };

    private volatile boolean disabledAfterFailure;
    private volatile NfcStackAdapter activeAdapter;
    private volatile int refreshProbeEventCount;
    private volatile boolean refreshCoordinatorStarted;

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

        installRefreshTargetCapture(cl, pid, adapter);
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
                    String state = "LA_NFCID1_ALREADY_PRESENT".equals(injected.reason) ? "ALREADY_PRESENT" : "WAITING";
                    writeRfStatus(state, uidHex, injected.reason, "");
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
            startRefreshCoordinator(cl, pid, adapter);
        } catch (Throwable t) {
            Log.e(TAG, "PROD HOOK FAILED build=" + HOOK_BUILD + " adapter=" + adapter.id() + " pid=" + pid + " " +
                    t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            reportStatusWithRetry(pid, false, 0, "HOOK_FAILED", t.getClass().getSimpleName() + ": " + t.getMessage(), adapter.id());
        }
    }

    private void installRefreshTargetCapture(ClassLoader cl, int pid, NfcStackAdapter adapter) {
        try {
            Class<?> clazz = Class.forName("com.oplus.nfc.common.NfcChipDeviceImpl", false, cl);
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                try {
                    hook(ctor).intercept(chain -> {
                        Object result = chain.proceed();
                        Object receiver = chain.getThisObject();
                        if (receiver != null) {
                            adapter.observeConstructedObject(receiver);
                            Log.i(TAG, "RFREFRESH: captured NfcChipDeviceImpl constructor instance pid=" + pid);
                        }
                        return result;
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "RFREFRESH: constructor hook failed " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "RFREFRESH: target capture unavailable " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void startRefreshCoordinator(ClassLoader cl, int pid, NfcStackAdapter adapter) {
        if (refreshCoordinatorStarted) return;
        refreshCoordinatorStarted = true;
        Thread thread = new Thread(() -> {
            long blockedGeneration = -1L;
            int invokedAttempts = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    SimConfig cfg = readConfig();
                    if (cfg.requestGeneration <= 0L) {
                        Thread.sleep(750L);
                        continue;
                    }

                    boolean pending = cfg.requestGeneration > cfg.appliedGeneration || cfg.refreshPid != pid;
                    if (!pending) {
                        blockedGeneration = -1L;
                        invokedAttempts = 0;
                        Thread.sleep(750L);
                        continue;
                    }

                    if (blockedGeneration == cfg.requestGeneration) {
                        Thread.sleep(1500L);
                        continue;
                    }

                    writeRefreshState(pid, cfg.requestGeneration, "APPLYING", "adapter=" + adapter.id(), false);
                    Log.i(TAG, "RFREFRESH: REQUEST gen=" + cfg.requestGeneration + " active=" + cfg.active + " uid=" + cfg.uid);

                    NfcStackAdapter.RefreshResult refresh = adapter.requestRfRefresh(cl);
                    Log.i(TAG, "RFREFRESH: RESULT gen=" + cfg.requestGeneration +
                            " invoked=" + refresh.invoked + " accepted=" + refresh.accepted + " detail=" + refresh.detail);

                    if (!refresh.invoked) {
                        writeRefreshState(pid, cfg.requestGeneration, "WAITING_TARGET", refresh.detail, false);
                        Thread.sleep(500L);
                        continue;
                    }

                    invokedAttempts++;
                    if (!refresh.accepted) {
                        writeRefreshState(pid, cfg.requestGeneration, "REJECTED", refresh.detail, false);
                        if (invokedAttempts >= 3) blockedGeneration = cfg.requestGeneration;
                        Thread.sleep(800L);
                        continue;
                    }

                    if (!cfg.active) {
                        writeRfStatus("IDLE", "", "stock RF config restored", "0");
                        writeRefreshState(pid, cfg.requestGeneration, "SUCCESS", refresh.detail, true);
                        invokedAttempts = 0;
                        Thread.sleep(750L);
                        continue;
                    }

                    RuntimeRfState rf = waitForRfApplied(cfg.uid, pid, 1200L);
                    if (rf.applied) {
                        writeRefreshState(pid, cfg.requestGeneration, "SUCCESS", refresh.detail, true);
                        invokedAttempts = 0;
                    } else {
                        writeRefreshState(pid, cfg.requestGeneration, "NO_UID_CONFIRMATION", refresh.detail + " rf=" + rf.status, false);
                        if (invokedAttempts >= 3) blockedGeneration = cfg.requestGeneration;
                    }
                    Thread.sleep(750L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    Log.e(TAG, "RFREFRESH: coordinator error " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
                    try { Thread.sleep(1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            }
        }, "NfcUIDSim-RfCoordinator");
        thread.setDaemon(true);
        thread.start();
    }

    private RuntimeRfState waitForRfApplied(String uid, int pid, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        RuntimeRfState last = new RuntimeRfState(false, "unknown");
        while (System.currentTimeMillis() < end) {
            SimConfig cfg = readConfig();
            boolean applied = cfg.rfPid == pid && "RF_UID_APPLIED".equals(cfg.rfStatus) &&
                    uid != null && uid.equalsIgnoreCase(cfg.rfUid == null ? "" : cfg.rfUid);
            last = new RuntimeRfState(applied, cfg.rfStatus);
            if (applied) return last;
            Thread.sleep(100L);
        }
        return last;
    }

    private void writeRefreshState(int pid, long generation, String status, String detail, boolean applied) {
        ContentValues v = new ContentValues();
        v.put(KEY_REFRESH_STATUS, status);
        v.put(KEY_REFRESH_DETAIL, detail == null ? "" : detail);
        v.put(KEY_REFRESH_PID, pid);
        if (applied) v.put(KEY_APPLIED_GENERATION, generation);
        v.put("full_diag_stage", "RF_REFRESH_" + status);
        v.put("full_diag_summary", "gen=" + generation + " " + (detail == null ? "" : detail));
        writeValuesWithRetry(v, 20, 100L);
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
                            NfcStackAdapter adapter = activeAdapter;
                            if (adapter != null) adapter.observeInvocation(chain.getThisObject(), candidate, args);

                            String enter = "ENTER " + signature + " event=" + event +
                                    " thread=" + Thread.currentThread().getName() +
                                    " args=" + summarizeArgs(args) + " stack=" + compactCallStack(24);
                            Log.i(TAG, "RFPROBE: " + enter);
                            persistRefreshProbeEvent(pid, enter);
                            Object result = chain.proceed();
                            String exit = "EXIT " + signature + " event=" + event + " result=" + summarizeValue(result);
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
            StringBuilder sb = new StringBuilder(type.getComponentType().getSimpleName()).append('[').append(length).append("]{");
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
        writeValuesWithRetry(v, 30, 100L);
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
                try { Thread.sleep(delayMs); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            Log.w(TAG, "status write gave up after " + attempts + " attempts");
        }, "NfcUIDSim-StateSync");
        t.setDaemon(true);
        t.start();
    }

    private SimConfig readConfig() {
        Application app = currentApplication();
        if (app == null) return new SimConfig(false, null, 0L, 0L, 0, null, null, 0);
        boolean active = false;
        String uid = null;
        long requestGeneration = 0L;
        long appliedGeneration = 0L;
        int refreshPid = 0;
        String rfStatus = null;
        String rfUid = null;
        int rfPid = 0;
        try (Cursor c = app.getContentResolver().query(CONFIG_URI, null, null, null, null)) {
            if (c != null) while (c.moveToNext()) {
                String key = c.getString(0);
                String value = c.getString(1);
                if ("simulation_enabled".equals(key)) active = Boolean.parseBoolean(value);
                else if ("uid".equals(key)) uid = value;
                else if (KEY_REQUEST_GENERATION.equals(key)) requestGeneration = parseLong(value);
                else if (KEY_APPLIED_GENERATION.equals(key)) appliedGeneration = parseLong(value);
                else if (KEY_REFRESH_PID.equals(key)) refreshPid = parseInt(value);
                else if ("rf_status".equals(key)) rfStatus = value;
                else if ("rf_uid".equals(key)) rfUid = value;
                else if ("rf_pid".equals(key)) rfPid = parseInt(value);
            }
        } catch (Throwable t) {
            Log.w(TAG, "config read failed: " + t.getMessage());
        }
        return new SimConfig(active, uid, requestGeneration, appliedGeneration, refreshPid, rfStatus, rfUid, rfPid);
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value == null ? "0" : value); } catch (Throwable ignored) { return 0L; }
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value == null ? "0" : value); } catch (Throwable ignored) { return 0; }
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

    private static final class RuntimeRfState {
        final boolean applied;
        final String status;
        RuntimeRfState(boolean applied, String status) {
            this.applied = applied;
            this.status = status == null ? "" : status;
        }
    }

    private static final class SimConfig {
        final boolean active;
        final String uid;
        final long requestGeneration;
        final long appliedGeneration;
        final int refreshPid;
        final String rfStatus;
        final String rfUid;
        final int rfPid;

        SimConfig(boolean active, String uid, long requestGeneration, long appliedGeneration, int refreshPid,
                  String rfStatus, String rfUid, int rfPid) {
            this.active = active;
            this.uid = uid;
            this.requestGeneration = requestGeneration;
            this.appliedGeneration = appliedGeneration;
            this.refreshPid = refreshPid;
            this.rfStatus = rfStatus;
            this.rfUid = rfUid;
            this.rfPid = rfPid;
        }
    }
}
