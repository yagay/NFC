package com.example.nfcdoorcard.xposed;

import android.app.Application;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

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
        boolean ifaceOk = false;
        boolean stubOk = false;
        boolean txOk = false;

        try {
            Class<?> iface = Class.forName("com.vendor.nfc.IVendorNfcAdapter", false, cl);
            ifaceOk = true;
            values.put("vendor_binder_descriptor", iface.getName());
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
            stubOk = true;
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
                        txOk = true;
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

        inspectClass("com.android.nfc.VendorNfcService$VendorNfcAdapterService", cl, report);
        inspectClass("com.android.nfc.VendorNfcService", cl, report);
        inspectClass("com.android.nfc.NfcService", cl, report);
        inspectClass("com.android.nfc.NfcService$NfcAdapterService", cl, report);
        inspectClass("android.nfc.INfcAdapter", cl, report);
        inspectClass("android.nfc.INfcAdapter$Stub", cl, report);

        // Focused V8 keys: keep these short enough that the UI/report cannot truncate the useful part.
        values.put("vendor_getter_signatures", collectGetterSignatures(cl));
        values.put("vendor_infc_adapter_methods", collectInterestingMethods(cl, "android.nfc.INfcAdapter"));
        values.put("vendor_infc_stub_transactions", collectInterestingStaticFields(cl, "android.nfc.INfcAdapter$Stub"));
        values.put("vendor_nfc_adapter_service_methods", collectInterestingMethods(cl, "com.android.nfc.NfcService$NfcAdapterService"));
        values.put("vendor_impl_candidates", collectImplementationCandidates(cl));

        values.put("vendor_meta_ready", ifaceOk && stubOk && txOk);
        values.put("vendor_meta_error", "");
        String text = report.length() > 7000 ? report.substring(0, 7000) : report.toString();
        values.put("vendor_meta_report", text);
        writeValues(values);
        Log.i(TAG, text);
    }

    private String collectGetterSignatures(ClassLoader cl) {
        StringBuilder out = new StringBuilder();
        try {
            Class<?> c = Class.forName("com.android.nfc.VendorNfcService", false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"getNfcAdapterVendorInterface".equals(m.getName())) continue;
                appendPart(out, m.toGenericString());
            }
        } catch (Throwable t) {
            appendPart(out, "ERROR=" + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return trim(out, 1800);
    }

    private String collectInterestingMethods(ClassLoader cl, String className) {
        StringBuilder out = new StringBuilder();
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                String s = (m.getName() + " " + m.getReturnType().getName() + " " + Arrays.toString(m.getParameterTypes())).toLowerCase();
                if (s.contains("vendor") || s.contains("binder") || s.contains("adaptervendor")) {
                    appendPart(out, m.toGenericString());
                }
            }
        } catch (Throwable t) {
            appendPart(out, "ERROR=" + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return trim(out, 2200);
    }

    private String collectInterestingStaticFields(ClassLoader cl, String className) {
        StringBuilder out = new StringBuilder();
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                String name = f.getName();
                if (!name.toLowerCase().contains("vendor") && !name.toLowerCase().contains("binder")) continue;
                try {
                    f.setAccessible(true);
                    appendPart(out, name + "=" + String.valueOf(f.get(null)) + " type=" + f.getType().getName());
                } catch (Throwable t) {
                    appendPart(out, name + "=<" + t.getClass().getSimpleName() + ">");
                }
            }
        } catch (Throwable t) {
            appendPart(out, "ERROR=" + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return trim(out, 1800);
    }

    private String collectImplementationCandidates(ClassLoader cl) {
        StringBuilder out = new StringBuilder();
        String[] candidates = new String[] {
                "com.android.nfc.nxp.NxpNfcService",
                "com.android.nfc.st.StNfcService",
                "com.android.nfc.st.STNfcService",
                "com.android.nfc.NxpNfcService",
                "com.android.nfc.NfcVendorService"
        };
        for (String name : candidates) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                appendPart(out, "CLASS=" + c.getName() + " super=" + (c.getSuperclass() == null ? "null" : c.getSuperclass().getName()));
                for (Method m : c.getDeclaredMethods()) {
                    if ("getNfcAdapterVendorInterface".equals(m.getName()) || "getVendorName".equals(m.getName())) {
                        appendPart(out, m.toGenericString());
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                appendPart(out, name + " ERROR=" + t.getClass().getSimpleName() + ":" + t.getMessage());
            }
        }
        if (out.length() == 0) out.append("<none-of-known-candidates>");
        return trim(out, 2200);
    }

    private void appendPart(StringBuilder out, String value) {
        if (out.length() > 0) out.append(" | ");
        out.append(value);
    }

    private String trim(StringBuilder out, int max) {
        return out.length() > max ? out.substring(0, max) : out.toString();
    }

    private void inspectClass(String className, ClassLoader cl, StringBuilder report) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            report.append(" | CLASS=").append(c.getName());
            Class<?> parent = c.getSuperclass();
            if (parent != null) report.append(" super=").append(parent.getName());
            for (Class<?> i : c.getInterfaces()) report.append(" iface=").append(i.getName());

            for (Field f : c.getDeclaredFields()) {
                String type = f.getType().getName();
                String name = f.getName();
                String lower = (name + " " + type).toLowerCase();
                if (lower.contains("vendor") || lower.contains("binder") || lower.contains("nfcadapter") || lower.contains("service")) {
                    report.append(" | FIELD=").append(c.getSimpleName()).append('#').append(name).append(':').append(type)
                            .append(" mods=").append(Modifier.toString(f.getModifiers()));
                }
            }

            for (Method m : c.getDeclaredMethods()) {
                String ret = m.getReturnType().getName();
                String lower = (m.getName() + " " + ret + " " + Arrays.toString(m.getParameterTypes())).toLowerCase();
                boolean interesting = lower.contains("vendor") || lower.contains("binder") || lower.contains("nfcadapter") ||
                        ret.contains("IVendorNfcAdapter") || "getNfcAdapterVendorInterface".equals(m.getName());
                if (interesting) {
                    report.append(" | METHOD_SIG=").append(c.getSimpleName()).append('#').append(m.getName())
                            .append(" params=").append(Arrays.toString(m.getParameterTypes()))
                            .append(" ->").append(ret)
                            .append(" mods=").append(Modifier.toString(m.getModifiers()))
                            .append(" generic=").append(m.toGenericString());
                }
            }
        } catch (Throwable t) {
            report.append(" | CLASS_ERROR=").append(className).append(':').append(t.getClass().getSimpleName()).append(':').append(t.getMessage());
        }
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
