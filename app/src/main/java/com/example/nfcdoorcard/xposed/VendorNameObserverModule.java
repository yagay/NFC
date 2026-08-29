package com.example.nfcdoorcard.xposed;

import android.app.Application;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** Read-only observer for the real INfcAdapter vendorName lookup path. */
public class VendorNameObserverModule extends XposedModule {
    private static final String TAG = "NfcVendorNameObs";
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings");

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
        super.onPackageLoaded(lp);
        if (!"com.android.nfc".equals(lp.getPackageName())) return;

        final ClassLoader cl = lp.getDefaultClassLoader();
        final int nfcPid = Process.myPid();
        try {
            Class<?> serviceClass = Class.forName("com.android.nfc.NfcService$NfcAdapterService", false, cl);
            Method target = serviceClass.getDeclaredMethod("getNfcAdapterVendorInterface", String.class);
            hook(target).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                String vendorName = args.length > 0 ? String.valueOf(args[0]) : "<missing>";
                int callingUid = Binder.getCallingUid();
                int callingPid = Binder.getCallingPid();
                String packages = packagesForUid(callingUid);

                Object result = chain.proceed();
                String descriptor = "<null>";
                if (result instanceof IBinder) {
                    try {
                        descriptor = ((IBinder) result).getInterfaceDescriptor();
                    } catch (Throwable t) {
                        descriptor = "<" + t.getClass().getSimpleName() + ":" + t.getMessage() + ">";
                    }
                }

                String report = "vendorName=" + vendorName +
                        " descriptor=" + descriptor +
                        " callingUid=" + callingUid +
                        " callingPid=" + callingPid +
                        " callingPackages=" + packages +
                        " nfcPid=" + nfcPid;
                Log.i(TAG, report);

                ContentValues v = new ContentValues();
                v.put("vendor_observed_name", vendorName);
                v.put("vendor_observed_descriptor", descriptor);
                v.put("vendor_observed_calling_uid", callingUid);
                v.put("vendor_observed_calling_pid", callingPid);
                v.put("vendor_observed_calling_packages", packages);
                v.put("vendor_observed_report", report);
                writeValues(v);
                return result;
            });

            ContentValues ready = new ContentValues();
            ready.put("vendor_observer_ready", true);
            ready.put("vendor_observer_error", "");
            writeValues(ready);
            Log.i(TAG, "observer ready for NfcAdapterService#getNfcAdapterVendorInterface(String)");
        } catch (Throwable t) {
            ContentValues err = new ContentValues();
            err.put("vendor_observer_ready", false);
            err.put("vendor_observer_error", t.getClass().getSimpleName() + ": " + t.getMessage());
            writeValues(err);
            Log.e(TAG, "observer install failed", t);
        }
    }

    private String packagesForUid(int uid) {
        Application app = currentApplication();
        if (app == null) return "<no-app>";
        try {
            String[] names = app.getPackageManager().getPackagesForUid(uid);
            return names == null ? "[]" : Arrays.toString(names);
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
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
        }, "NfcVendorNameObs-State");
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
