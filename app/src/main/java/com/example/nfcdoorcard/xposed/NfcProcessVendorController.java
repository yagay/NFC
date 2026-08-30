package com.example.nfcdoorcard.xposed;

import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Executes OEM RF triggers and NFC controller lifecycle operations from com.android.nfc. */
final class NfcProcessVendorController {
    private static final String NFC_SERVICE_NAME = "nfc";
    private static final String INFC_DESCRIPTOR = "android.nfc.INfcAdapter";
    private static final String INFC_STUB = "android.nfc.INfcAdapter$Stub";
    private static final int FALLBACK_TX_GET_VENDOR_INTERFACE = 6;
    private static final String VENDOR_NAME = "vendor";
    private static final String VENDOR_DESCRIPTOR = "com.vendor.nfc.IVendorNfcAdapter";
    private static final String VENDOR_STUB = "com.vendor.nfc.IVendorNfcAdapter$Stub";
    private static final int FALLBACK_TX_ENABLE_SHARE_MODE = 15;
    private static final int STARTUP_RETRY_COUNT = 30;
    private static final long STARTUP_RETRY_DELAY_MS = 100L;

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
        for (int attempt = 0; attempt < STARTUP_RETRY_COUNT; attempt++) {
            last = setShareModeOnce(enabled);
            if (last.success || !isTransient(last.stage)) return last;
            if (attempt + 1 < STARTUP_RETRY_COUNT) sleep(STARTUP_RETRY_DELAY_MS);
        }
        if (last == null) return new Result(false, "UNKNOWN", "No vendor result", null);
        return new Result(false, last.stage,
                last.detail + " after " + STARTUP_RETRY_COUNT + " attempts/~" +
                        ((STARTUP_RETRY_COUNT - 1) * STARTUP_RETRY_DELAY_MS) + "ms",
                last.vendorDescriptor);
    }

    /**
     * Reinitializes the actual NFC controller instead of only restarting the Java process.
     * This is required when LA_NFCID1 was appended to CORE_SET_CONFIG: replaying a payload
     * that omits parameter 0x33 does not delete the value already stored by the controller.
     */
    Result reinitializeController() {
        try {
            IBinder main = serviceManagerBinder();
            if (main == null || !main.isBinderAlive() || !main.pingBinder()) {
                return new Result(false, "CONTROLLER_BINDER", "NFC binder unavailable for controller reinit", null);
            }
            String descriptor = safeDescriptor(main);
            if (!INFC_DESCRIPTOR.equals(descriptor)) {
                return new Result(false, "CONTROLLER_DESCRIPTOR", "Unexpected descriptor=" + descriptor, null);
            }

            int txDisable = resolveRequiredTransaction(INFC_STUB, "TRANSACTION_disable");
            int txEnable = resolveRequiredTransaction(INFC_STUB, "TRANSACTION_enable");
            if (txDisable <= 0 || txEnable <= 0) {
                return new Result(false, "CONTROLLER_TRANSACTIONS",
                        "Unable to reflect INfcAdapter disable/enable transaction IDs", null);
            }

            boolean disabled = transactDisable(main, txDisable, false);
            if (!disabled) {
                return new Result(false, "CONTROLLER_DISABLE",
                        "INfcAdapter.disable(false) rejected tx=" + txDisable, null);
            }

            // Let NfcService drive native close/deinit before reopening the controller.
            sleep(500L);

            IBinder afterDisable = serviceManagerBinder();
            if (afterDisable == null || !afterDisable.isBinderAlive()) afterDisable = main;
            boolean enabled = transactEnable(afterDisable, txEnable);
            if (!enabled) {
                return new Result(false, "CONTROLLER_ENABLE",
                        "INfcAdapter.enable() rejected tx=" + txEnable, null);
            }
            return new Result(true, "CONTROLLER_REINITIALIZED",
                    "NFC controller reinitialized through INfcAdapter disable(false)->enable() tx=" +
                            txDisable + "->" + txEnable,
                    null);
        } catch (Throwable t) {
            return new Result(false, "CONTROLLER_EXCEPTION",
                    t.getClass().getName() + ": " + String.valueOf(t.getMessage()), null);
        }
    }

    private Result setShareModeOnce(boolean enabled) {
        try {
            IBinder main = serviceManagerBinder();
            if (main == null) return new Result(false, "MAIN_BINDER", "ServiceManager returned null NFC binder", null);
            if (!main.isBinderAlive() || !main.pingBinder()) return new Result(false, "MAIN_BINDER", "NFC binder is not alive", null);

            String mainDescriptor = safeDescriptor(main);
            if (!INFC_DESCRIPTOR.equals(mainDescriptor)) {
                return new Result(false, "MAIN_DESCRIPTOR", "Unexpected descriptor=" + mainDescriptor, null);
            }

            int txGetVendor = resolveTransaction(INFC_STUB, "TRANSACTION_getNfcAdapterVendorInterface", FALLBACK_TX_GET_VENDOR_INTERFACE);
            IBinder vendor = getVendorBinder(main, txGetVendor);
            if (vendor == null) return new Result(false, "GET_VENDOR_BINDER", "transaction " + txGetVendor + " returned null/rejected", null);
            if (!vendor.isBinderAlive() || !vendor.pingBinder()) return new Result(false, "GET_VENDOR_BINDER", "Vendor binder is not alive", null);

            String vendorDescriptor = safeDescriptor(vendor);
            if (!VENDOR_DESCRIPTOR.equals(vendorDescriptor)) {
                return new Result(false, "VENDOR_DESCRIPTOR", "Unexpected descriptor=" + vendorDescriptor, vendorDescriptor);
            }

            int txShare = resolveTransaction(VENDOR_STUB, "TRANSACTION_enableNfcShareMode", FALLBACK_TX_ENABLE_SHARE_MODE);
            boolean accepted = transactShareMode(vendor, enabled, txShare);
            return new Result(
                    accepted,
                    accepted ? "TRIGGERED" : "SHARE_MODE",
                    accepted
                            ? "enableNfcShareMode(" + enabled + ") accepted in NFC process tx=" + txShare + " getterTx=" + txGetVendor
                            : "enableNfcShareMode reply rejected/empty tx=" + txShare,
                    vendorDescriptor
            );
        } catch (Throwable t) {
            return new Result(false, "EXCEPTION", t.getClass().getName() + ": " + String.valueOf(t.getMessage()), null);
        }
    }

    private static boolean isTransient(String stage) {
        return "MAIN_BINDER".equals(stage)
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

    private static int resolveTransaction(String stubClass, String fieldName, int fallback) {
        int reflected = resolveRequiredTransaction(stubClass, fieldName);
        return reflected > 0 ? reflected : fallback;
    }

    private static int resolveRequiredTransaction(String stubClass, String fieldName) {
        try {
            Class<?> c = Class.forName(stubClass);
            Field f = c.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static IBinder getVendorBinder(IBinder mainBinder, int transaction) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INFC_DESCRIPTOR);
            data.writeString(VENDOR_NAME);
            if (!mainBinder.transact(transaction, data, reply, 0)) return null;
            reply.setDataPosition(0);
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean transactShareMode(IBinder vendor, boolean enabled, int transaction) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(VENDOR_DESCRIPTOR);
            data.writeBoolean(enabled);
            if (!vendor.transact(transaction, data, reply, 0)) return false;
            reply.setDataPosition(0);
            reply.readException();
            return reply.dataAvail() >= 4 && reply.readBoolean();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean transactDisable(IBinder main, int transaction, boolean saveState) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INFC_DESCRIPTOR);
            data.writeBoolean(saveState);
            if (!main.transact(transaction, data, reply, 0)) return false;
            reply.setDataPosition(0);
            reply.readException();
            return reply.dataAvail() >= 4 && reply.readBoolean();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean transactEnable(IBinder main, int transaction) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INFC_DESCRIPTOR);
            if (!main.transact(transaction, data, reply, 0)) return false;
            reply.setDataPosition(0);
            reply.readException();
            return reply.dataAvail() >= 4 && reply.readBoolean();
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
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
