package com.yagay.nfcdoorcard.xposed;

import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Executes OEM RF triggers and NFC controller lifecycle operations from com.android.nfc. */
final class NfcProcessVendorController {
    private static final String NFC_SERVICE_NAME = "nfc";
    private static final String INFC_DESCRIPTOR = "android.nfc.INfcAdapter";
    private static final String INFC_STUB = "android.nfc.INfcAdapter$Stub";
    private static final String VENDOR_NAME = "vendor";
    private static final String VENDOR_DESCRIPTOR = "com.vendor.nfc.IVendorNfcAdapter";
    private static final String VENDOR_STUB = "com.vendor.nfc.IVendorNfcAdapter$Stub";
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

    /** Experimental lifecycle capability; normal STOP currently uses process restart. */
    Result reinitializeController() {
        try {
            IBinder main = serviceManagerBinder();
            if (!alive(main)) return new Result(false, "CONTROLLER_BINDER", "NFC binder unavailable", null);
            if (!INFC_DESCRIPTOR.equals(safeDescriptor(main))) {
                return new Result(false, "CONTROLLER_DESCRIPTOR", "Unexpected descriptor=" + safeDescriptor(main), null);
            }
            Object proxy = asInterface(INFC_STUB, main);
            if (proxy == null) return new Result(false, "CONTROLLER_PROXY", "INfcAdapter Stub.asInterface unavailable", null);
            // Android 15+ INfcAdapter requires the caller package for attribution/security
            // checks. Prefer the modern signatures and retain the legacy forms only as fallback for
            // older vendor branches. This code executes inside com.android.nfc (UID 1027), so the
            // package attribution is valid for the Binder caller identity.
            Boolean disabled = invokeBooleanMethod(proxy, "disable",
                    new Class<?>[]{boolean.class, String.class}, new Object[]{false, "com.android.nfc"});
            if (disabled == null) {
                disabled = invokeBooleanMethod(proxy, "disable", new Class<?>[]{boolean.class}, new Object[]{false});
            }
            if (!Boolean.TRUE.equals(disabled)) return new Result(false, "CONTROLLER_DISABLE", "INfcAdapter.disable unavailable/rejected", null);
            sleep(500L);
            IBinder after = serviceManagerBinder();
            if (!alive(after)) after = main;
            Object afterProxy = asInterface(INFC_STUB, after);
            Boolean enabled = invokeBooleanMethod(afterProxy, "enable",
                    new Class<?>[]{String.class}, new Object[]{"com.android.nfc"});
            if (enabled == null) {
                enabled = invokeBooleanMethod(afterProxy, "enable", new Class<?>[0], new Object[0]);
            }
            if (!Boolean.TRUE.equals(enabled)) return new Result(false, "CONTROLLER_ENABLE", "INfcAdapter.enable unavailable/rejected", null);
            return new Result(true, "CONTROLLER_REINITIALIZED", "NFC controller reinitialized through reflected AIDL proxy", null);
        } catch (Throwable t) {
            return new Result(false, "CONTROLLER_EXCEPTION", t.getClass().getName() + ": " + String.valueOf(t.getMessage()), null);
        }
    }

    private Result setShareModeOnce(boolean enabled) {
        try {
            IBinder main = serviceManagerBinder();
            if (!alive(main)) return new Result(false, "MAIN_BINDER", "NFC binder unavailable", null);
            String mainDescriptor = safeDescriptor(main);
            if (!INFC_DESCRIPTOR.equals(mainDescriptor)) return new Result(false, "MAIN_DESCRIPTOR", "Unexpected descriptor=" + mainDescriptor, null);

            // Preferred path: generated AIDL proxies. This follows the runtime method ABI and does
            // not depend on transaction numbering. If the OEM interface cannot be reflected, use
            // only transaction IDs reflected from the current Stub; never guess a numeric ID.
            Object mainProxy = asInterface(INFC_STUB, main);
            IBinder vendor = getVendorBinderViaProxy(mainProxy);
            String path = "aidl-proxy";
            if (vendor == null) {
                int txGetVendor = resolveRequiredTransaction(INFC_STUB, "TRANSACTION_getNfcAdapterVendorInterface");
                if (txGetVendor <= 0) return new Result(false, "GET_VENDOR_CAPABILITY", "No proxy method or reflected vendor transaction", null);
                vendor = getVendorBinderTransact(main, txGetVendor);
                path = "reflected-transaction";
            }
            if (!alive(vendor)) return new Result(false, "GET_VENDOR_BINDER", "Vendor binder unavailable", null);

            String vendorDescriptor = safeDescriptor(vendor);
            if (!VENDOR_DESCRIPTOR.equals(vendorDescriptor)) {
                return new Result(false, "VENDOR_DESCRIPTOR", "Unexpected descriptor=" + vendorDescriptor, vendorDescriptor);
            }

            // In com.android.nfc the returned vendor binder can be the actual local Stub/service.
            // Prefer that real Java object first: it follows the OEM implementation directly and
            // avoids both transaction-number coupling and generated Stub class visibility issues.
            Object localInterface = null;
            try { localInterface = vendor.queryLocalInterface(vendorDescriptor); } catch (Throwable ignored) { }
            Boolean directAccepted = invokeBooleanMethod(localInterface, "enableNfcShareMode",
                    new Class<?>[]{boolean.class}, new Object[]{enabled});
            if (directAccepted == null) {
                directAccepted = invokeBooleanMethod(vendor, "enableNfcShareMode",
                        new Class<?>[]{boolean.class}, new Object[]{enabled});
            }
            if (directAccepted != null) {
                return new Result(directAccepted, directAccepted ? "TRIGGERED" : "SHARE_MODE",
                        "enableNfcShareMode(" + enabled + ") via in-process vendor service", vendorDescriptor);
            }

            Object vendorProxy = asInterface(VENDOR_STUB, vendor);
            Boolean proxyAccepted = invokeBooleanMethod(vendorProxy, "enableNfcShareMode",
                    new Class<?>[]{boolean.class}, new Object[]{enabled});
            if (proxyAccepted != null) {
                return new Result(proxyAccepted, proxyAccepted ? "TRIGGERED" : "SHARE_MODE",
                        "enableNfcShareMode(" + enabled + ") via AIDL proxy", vendorDescriptor);
            }

            int txShare = resolveRequiredTransaction(VENDOR_STUB, "TRANSACTION_enableNfcShareMode");
            if (txShare <= 0) return new Result(false, "SHARE_MODE_CAPABILITY", "No proxy method or reflected share-mode transaction", vendorDescriptor);
            boolean accepted = transactShareMode(vendor, enabled, txShare);
            return new Result(accepted, accepted ? "TRIGGERED" : "SHARE_MODE",
                    "enableNfcShareMode(" + enabled + ") via " + path + "/reflected-tx=" + txShare,
                    vendorDescriptor);
        } catch (Throwable t) {
            return new Result(false, "EXCEPTION", t.getClass().getName() + ": " + String.valueOf(t.getMessage()), null);
        }
    }

    private static boolean isTransient(String stage) {
        return "MAIN_BINDER".equals(stage) || "GET_VENDOR_BINDER".equals(stage) || "EXCEPTION".equals(stage);
    }

    private static IBinder serviceManagerBinder() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method method = sm.getDeclaredMethod("getService", String.class);
            method.setAccessible(true);
            return (IBinder) method.invoke(null, NFC_SERVICE_NAME);
        } catch (Throwable ignored) { return null; }
    }

    private static Object asInterface(String stubClass, IBinder binder) {
        if (binder == null) return null;
        try {
            Class<?> stub = Class.forName(stubClass);
            Method m = stub.getDeclaredMethod("asInterface", IBinder.class);
            m.setAccessible(true);
            return m.invoke(null, binder);
        } catch (Throwable ignored) { return null; }
    }

    private static IBinder getVendorBinderViaProxy(Object proxy) {
        if (proxy == null) return null;
        try {
            for (Method m : proxy.getClass().getMethods()) {
                if (!"getNfcAdapterVendorInterface".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == String.class) {
                    m.setAccessible(true);
                    Object result = m.invoke(proxy, VENDOR_NAME);
                    if (result instanceof IBinder) return (IBinder) result;
                    if (result != null) {
                        try {
                            Method asBinder = result.getClass().getMethod("asBinder");
                            Object b = asBinder.invoke(result);
                            if (b instanceof IBinder) return (IBinder) b;
                        } catch (Throwable ignored) { }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Returns null when the capability/signature is absent, otherwise the boolean result. */
    private static Boolean invokeBooleanMethod(Object proxy, String name, Class<?>[] params, Object[] args) {
        if (proxy == null) return null;
        try {
            Method m = proxy.getClass().getMethod(name, params);
            m.setAccessible(true);
            Object result = m.invoke(proxy, args);
            if (m.getReturnType() == Void.TYPE) return Boolean.TRUE;
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Throwable t) {
            Throwable cause = t.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause == null ? t : cause);
        }
    }

    private static int resolveRequiredTransaction(String stubClass, String fieldName) {
        try {
            Class<?> c = Class.forName(stubClass);
            Field f = c.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) { return -1; }
    }

    private static IBinder getVendorBinderTransact(IBinder mainBinder, int transaction) throws Exception {
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

    private static boolean alive(IBinder binder) {
        return binder != null && binder.isBinderAlive() && binder.pingBinder();
    }

    private static String safeDescriptor(IBinder binder) {
        try {
            String value = binder.getInterfaceDescriptor();
            return value == null ? "" : value;
        } catch (Throwable ignored) { return ""; }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
