package com.example.nfcdoorcard.xposed;

import android.app.Application;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Read-only metadata probe. It does not invoke any vendor NFC method. */
public class VendorBinderMetadataModule extends XposedModule {
    private static final String TAG = "NfcVendorMeta";
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings");

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
        super.onPackageLoaded(lp);
        if (!"com.android.nfc".equals(lp.getPackageName())) return;

        final ClassLoader cl = lp.getDefaultClassLoader();
        final int pid = Process.myPid();
        ContentValues values = new ContentValues();
        values.put("vendor_meta_pid", pid);

        StringBuilder report = new StringBuilder();
        try {
            Class<?> iface = Class.forName("com.vendor.nfc.IVendorNfcAdapter", false, cl);
            report.append("IFACE=").append(iface.getName());
            Class<?>[] parents = iface.getInterfaces();
            for (Class<?> p : parents) report.append(" parent=").append(p.getName());

            for (Method m : iface.getDeclaredMethods()) {
                if ("enableNfcShareMode".equals(m.getName())) {
                    values.put("vendor_share_mode_method", m.toGenericString());
                    report.append(" | METHOD=").append(m.toGenericString());
                }
            }
        } catch (Throwable t) {
            values.put("vendor_meta_iface_error", t.getClass().getSimpleName() + ": " + t.getMessage());
            report.append("IFACE_ERROR=").append(t.getClass().getSimpleName()).append(':').append(t.getMessage());
        }

        try {
            Class<?> stub = Class.forName("com.vendor.nfc.IVendorNfcAdapter$Stub", false, cl);
            values.put("vendor_binder_stub_class", stub.getName());
            report.append(" | STUB=").append(stub.getName());

            for (Field f : stub.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                String name = f.getName();
                if (!name.startsWith("TRANSACTION_") && !"DESCRIPTOR".equals(name)) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    report.append(" | ").append(name).append('=').append(String.valueOf(v));
                    if ("TRANSACTION_enableNfcShareMode".equals(name) && v instanceof Number) {
                        values.put("vendor_share_mode_transaction", ((Number) v).intValue());
                        values.put("vendor_share_mode_transaction_source", "REFLECT_STUB_CONSTANT");
                    }
                    if ("DESCRIPTOR".equals(name) && v != null) {
                        values.put("vendor_binder_descriptor", String.valueOf(v));
                    }
                } catch (Throwable ignored) {
                }
            }

            Class<?> parent = stub.getSuperclass();
            if (parent != null) report.append(" | STUB_SUPER=").append(parent.getName());
            for (Class<?> i : stub.getInterfaces()) report.append(" | STUB_IFACE=").append(i.getName());
        } catch (Throwable t) {
            values.put("vendor_meta_stub_error", t.getClass().getSimpleName() + ": " + t.getMessage());
            report.append(" | STUB_ERROR=").append(t.getClass().getSimpleName()).append(':').append(t.getMessage());
        }

        try {
            Class<?> service = Class.forName("com.android.nfc.VendorNfcService$VendorNfcAdapterService", false, cl);
            report.append(" | SERVICE=").append(service.getName());
            Class<?> parent = service.getSuperclass();
            if (parent != null) report.append(" super=").append(parent.getName());
            for (Class<?> i : service.getInterfaces()) report.append(" iface=").append(i.getName());
        } catch (Throwable t) {
            report.append(" | SERVICE_ERROR=").append(t.getClass().getSimpleName()).append(':').append(t.getMessage());
        }

        String text = report.length() > 3500 ? report.substring(0, 3500) : report.toString();
        values.put("vendor_meta_report", text);
        writeValues(values);
        Log.i(TAG, text);
    }

    private void writeValues(ContentValues values) {
        ContentValues copy = new ContentValues(values);
        Thread t = new Thread(() -> {
            for (int i = 0; i < 30; i++) {
                Application app = currentApplication();
                if (app != null) {
                    try {
                        app.getContentResolver().insert(CONFIG_URI, copy);
                        return;
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "NfcVendorMeta-State");
        t.setDaemon(true);
        t.start();
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
}
