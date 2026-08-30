package com.example.nfcdoorcard.xposed;

import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

/**
 * Executes the proven Oplus vendor NFC trigger from inside com.android.nfc.
 *
 * Keeping this bridge in the NFC process removes the stale cross-process Binder lifetime
 * problem that occurred when the UI process survived a com.android.nfc restart.
 */
final class NfcProcessVendorController {
    private static final String NFC_SERVICE_NAME = "nfc";
    private static final String INFC_DESCRIPTOR = "android.nfc.INfcAdapter";
    private static final int TX_GET_VENDOR_INTERFACE = 6;
    private static final String VENDOR_NAME = "vendor";
    private static final String VENDOR_DESCRIPTOR = "com.vendor.nfc.IVendorNfcAdapter";
    private static final int TX_ENABLE_SHARE_MODE = 15;

    static final class Result {
        final boolean success;
        final String stage;
        final String detail;
        final String vendorDescriptor;

        Result(boolean success, String stage, String detail, String vendorDescriptor) {
            this.success = success;
            this.stage = stage;
            this.detail = detail;
            this.vendorDescriptor = vendorDescriptor;
        }
    }

    Result setShareMode(boolean enabled) {
        Result last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            last = setShareModeOnce(enabled);
            if (last.success) return last;
            if (!isTransient(last.stage)) return last;
            if (attempt == 0) sleep(80L);
        }
        return last == null ? new Result(false, "UNKNOWN", "No vendor result", null) : last;
    }

    private Result setShareModeOnce(boolean enabled) {
        try {
            IBinder main = serviceManagerBinder();
            if (main == null) return new Result(false, "MAIN_BINDER", "ServiceManager returned null NFC binder", null);
            if (!main.isBinderAlive() || !main.pingBinder()) {
                return new Result(false, "MAIN_BINDER", "NFC binder is not alive", null);
            }

            String mainDescriptor = safeDescriptor(main);
            if (!INFC_DESCRIPTOR.equals(mainDescriptor)) {
                return new Result(false, "MAIN_DESCRIPTOR", "Unexpected descriptor=" + mainDescriptor, null);
            }

            IBinder vendor = getVendorBinder(main);
            if (vendor == null) return new Result(false, "GET_VENDOR_BINDER", "transaction 6 returned null/rejected", null);
            if (!vendor.isBinderAlive() || !vendor.pingBinder()) {
                return new Result(false, "GET_VENDOR_BINDER", "Vendor binder is not alive", null);
            }

            String vendorDescriptor = safeDescriptor(vendor);
            if (!VENDOR_DESCRIPTOR.equals(vendorDescriptor)) {
                return new Result(false, "VENDOR_DESCRIPTOR", "Unexpected descriptor=" + vendorDescriptor, vendorDescriptor);
            }

            boolean accepted = transactShareMode(vendor, enabled);
            return new Result(
                    accepted,
                    accepted ? "TRIGGERED" : "SHARE_MODE",
                    accepted ? "enableNfcShareMode(" + enabled + ") accepted in NFC process" : "transaction 15 rejected",
                    vendorDescriptor
            );
        } catch (Throwable t) {
            return new Result(false, "EXCEPTION", t.getClass().getName() + ": " + String.valueOf(t.getMessage()), null);
        }
    }

    private static boolean isTransient(String stage) {
        return "MAIN_BINDER".equals(stage)
                || "MAIN_DESCRIPTOR".equals(stage)
                || "GET_VENDOR_BINDER".equals(stage)
                || "EXCEPTION".equals(stage);
    }

    private static IBinder serviceManagerBinder() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method method = sm.getDeclaredMethod("getService", String.class);
            method.setAccessible(true);
            return (IBinder) method.invoke(null, NFC_SERVICE_NAME);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static IBinder getVendorBinder(IBinder mainBinder) throws Exception {
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

    private static boolean transactShareMode(IBinder vendor, boolean enabled) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VENDOR_DESCRIPTOR);
            data.writeBoolean(enabled);
            if (!vendor.transact(TX_ENABLE_SHARE_MODE, data, reply, 0)) return false;
            reply.setDataPosition(0);
            reply.readException();
            return reply.dataAvail() < 4 || reply.readBoolean();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static String safeDescriptor(IBinder binder) {
        try {
            String value = binder.getInterfaceDescriptor();
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
