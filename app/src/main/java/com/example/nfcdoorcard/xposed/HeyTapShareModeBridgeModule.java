package com.example.nfcdoorcard.xposed;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Runs only inside com.heytap.accessory.
 *
 * The app writes simulation_enabled to ConfigProvider. This bridge observes that state and,
 * from the HeyTap process UID, performs the already device-verified Binder path:
 *   android.nfc.INfcAdapter transaction 6 + vendorName="vendor"
 *   -> com.vendor.nfc.IVendorNfcAdapter transaction 15 enableNfcShareMode(boolean)
 *
 * No call is made from the app UI process and no NFC process restart is used.
 */
public class HeyTapShareModeBridgeModule extends XposedModule {
    private static final String TAG = "NfcHeyTapBridge";
    private static final String TARGET_PACKAGE = "com.heytap.accessory";
    private static final Uri CONFIG_URI = Uri.parse("content://com.example.nfcdoorcard.config/settings");

    private static final String INFC_DESCRIPTOR = "android.nfc.INfcAdapter";
    private static final int TX_GET_VENDOR_INTERFACE = 6;
    private static final String VENDOR_NAME = "vendor";
    private static final String VENDOR_DESCRIPTOR = "com.vendor.nfc.IVendorNfcAdapter";
    private static final int TX_ENABLE_SHARE_MODE = 15;

    private volatile boolean watcherStarted;

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
        super.onPackageLoaded(lp);
        if (!TARGET_PACKAGE.equals(lp.getPackageName())) return;
        if (watcherStarted) return;
        watcherStarted = true;
        startWatcher();
    }

    private void startWatcher() {
        Thread t = new Thread(() -> {
            final int pid = Process.myPid();
            Application app = waitForApplication();
            if (app == null) {
                Log.e(TAG, "bridge failed: application unavailable pid=" + pid);
                return;
            }

            publish(app, false, "STARTING", "HeyTap bridge watcher starting", null);
            Boolean lastEnabled = null;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    boolean enabled = readSimulationEnabled(app);
                    if (lastEnabled == null || enabled != lastEnabled) {
                        Result result = setShareMode(app, enabled);
                        Log.i(TAG, "state=" + enabled + " success=" + result.success +
                                " stage=" + result.stage + " detail=" + result.detail + " pid=" + pid);
                        publish(app, result.success, result.stage, result.detail, enabled);
                        if (result.success) lastEnabled = enabled;
                    }
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t1) {
                    Log.e(TAG, "watcher error", t1);
                    publish(app, false, "WATCHER_ERROR", t1.getClass().getSimpleName() + ": " + t1.getMessage(), null);
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "NfcHeyTapBridge-Watcher");
        t.setDaemon(true);
        t.start();
    }

    private boolean readSimulationEnabled(Application app) {
        try (Cursor c = app.getContentResolver().query(CONFIG_URI, null, null, null, null)) {
            if (c == null) return false;
            while (c.moveToNext()) {
                String key = c.getString(0);
                if ("simulation_enabled".equals(key)) {
                    return Boolean.parseBoolean(c.getString(1));
                }
            }
        }
        return false;
    }

    private Result setShareMode(Application app, boolean enabled) {
        try {
            // Initialize framework NFC service in this HeyTap process before reading sService.
            NfcAdapter.getDefaultAdapter(app);

            Field field = NfcAdapter.class.getDeclaredField("sService");
            field.setAccessible(true);
            Object service = field.get(null);
            if (service == null) return new Result(false, "INFC_SERVICE", "NfcAdapter.sService is null");

            IBinder mainBinder = toBinder(service);
            if (mainBinder == null) return new Result(false, "MAIN_BINDER", "Cannot obtain INfcAdapter binder");
            String mainDescriptor = safeDescriptor(mainBinder);
            if (!INFC_DESCRIPTOR.equals(mainDescriptor)) {
                return new Result(false, "MAIN_DESCRIPTOR", "Unexpected descriptor=" + mainDescriptor);
            }

            IBinder vendorBinder = getVendorBinder(mainBinder);
            if (vendorBinder == null) return new Result(false, "GET_VENDOR_BINDER", "transaction 6 returned null/rejected");
            String vendorDescriptor = safeDescriptor(vendorBinder);
            if (!VENDOR_DESCRIPTOR.equals(vendorDescriptor)) {
                return new Result(false, "VENDOR_DESCRIPTOR", "Unexpected descriptor=" + vendorDescriptor);
            }

            boolean accepted = transactShareMode(vendorBinder, enabled);
            return new Result(accepted, accepted ? "DONE" : "SHARE_MODE",
                    accepted ? "HeyTap enableNfcShareMode(" + enabled + ") accepted" : "transaction 15 rejected");
        } catch (Throwable t) {
            return new Result(false, "EXCEPTION", t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private IBinder getVendorBinder(IBinder mainBinder) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INFC_DESCRIPTOR);
            data.writeString(VENDOR_NAME);
            if (!mainBinder.transact(TX_GET_VENDOR_INTERFACE, data, reply, 0)) return null;
            reply.setDataPosition(0);
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean transactShareMode(IBinder binder, boolean enabled) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VENDOR_DESCRIPTOR);
            data.writeBoolean(enabled);
            if (!binder.transact(TX_ENABLE_SHARE_MODE, data, reply, 0)) return false;
            reply.setDataPosition(0);
            reply.readException();
            return reply.dataAvail() >= 4 ? reply.readBoolean() : true;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private IBinder toBinder(Object value) {
        if (value instanceof IBinder) return (IBinder) value;
        try {
            Method m;
            try {
                m = value.getClass().getMethod("asBinder");
            } catch (NoSuchMethodException e) {
                m = value.getClass().getDeclaredMethod("asBinder");
            }
            m.setAccessible(true);
            Object out = m.invoke(value);
            return out instanceof IBinder ? (IBinder) out : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String safeDescriptor(IBinder binder) {
        try {
            return binder.getInterfaceDescriptor();
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
    }

    private void publish(Application app, boolean ready, String stage, String detail, Boolean enabled) {
        try {
            ContentValues v = new ContentValues();
            v.put("heytap_bridge_ready", ready);
            v.put("heytap_bridge_pid", Process.myPid());
            v.put("heytap_bridge_stage", stage);
            v.put("heytap_bridge_detail", detail == null ? "" : detail);
            if (enabled != null) v.put("heytap_bridge_enabled", enabled);
            app.getContentResolver().insert(CONFIG_URI, v);
        } catch (Throwable ignored) {
        }
    }

    private Application waitForApplication() {
        for (int i = 0; i < 100; i++) {
            Application app = currentApplication();
            if (app != null) return app;
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
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

    private static final class Result {
        final boolean success;
        final String stage;
        final String detail;

        Result(boolean success, String stage, String detail) {
            this.success = success;
            this.stage = stage;
            this.detail = detail;
        }
    }
}
