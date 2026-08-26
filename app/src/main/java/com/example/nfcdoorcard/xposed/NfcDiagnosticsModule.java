package com.example.nfcdoorcard.xposed;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class NfcDiagnosticsModule extends XposedModule {

    private static final String TAG = "NfcUIDSim";
    private static final Uri UID_CONFIG_URI =
            Uri.parse("content://com.example.nfcdoorcard.uidconfig/target");

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
        super.onPackageLoaded(lp);
        String packageName = lp.getPackageName();

        if ("com.example.nfcdoorcard".equals(packageName)) {
            installSelfHook(lp);
            return;
        }

        if ("com.android.nfc".equals(packageName)) {
            installNfcServiceHooks(lp);
        }
    }

    private void installSelfHook(XposedModuleInterface.PackageLoadedParam lp) {
        try {
            ClassLoader cl = lp.getDefaultClassLoader();
            Class<?> mainActivity = cl.loadClass("com.example.nfcdoorcard.MainActivity");
            Method activeMethod = mainActivity.getDeclaredMethod("isModuleActive");

            deoptimize(activeMethod);
            hook(activeMethod).intercept(chain -> true);
            Log.i(TAG, "LSPosed self-check hook installed");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to install self-check hook", e);
        }
    }

    private void installNfcServiceHooks(XposedModuleInterface.PackageLoadedParam lp) {
        try {
            ClassLoader cl = lp.getDefaultClassLoader();
            Class<?> nativeManager = cl.loadClass("com.android.nfc.dhimpl.NativeNfcManager");
            Method initMethod = nativeManager.getDeclaredMethod("doInitialize");
            deoptimize(initMethod);

            hook(initMethod).intercept(chain -> {
                Object result = chain.proceed();

                if (result instanceof Boolean && !((Boolean) result)) {
                    Log.w(TAG, "NFC initialization failed; UID injection skipped");
                    return result;
                }

                try {
                    injectConfiguredUid(chain.getThisObject());
                } catch (Throwable t) {
                    Log.e(TAG, "UID injection failed after NFC initialization", t);
                }
                return result;
            });

            Log.i(TAG, "NativeNfcManager.doInitialize hook installed");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to install NFC service hook", e);
        }
    }

    private void injectConfiguredUid(Object managerInstance) throws Exception {
        Context context = getCurrentApplicationContext();
        if (context == null) {
            Log.w(TAG, "Application context unavailable; UID injection skipped");
            return;
        }

        String targetUidHex = readTargetUid(context);
        if (targetUidHex == null || targetUidHex.trim().isEmpty()) {
            Log.i(TAG, "No configured target UID; nothing to inject");
            return;
        }

        byte[] targetUid = hexToBytes(targetUidHex);
        if (!isSupportedNfcAUidLength(targetUid.length)) {
            Log.w(TAG, "Unsupported NFC-A UID length: " + targetUid.length + " bytes");
            return;
        }

        Method writeConfig;
        try {
            writeConfig = managerInstance.getClass()
                    .getDeclaredMethod("doWriteNciConfig", int.class, byte[].class);
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "doWriteNciConfig(int, byte[]) is not present on this NFC stack; " +
                    "device-specific backend mapping is required");
            return;
        }

        writeConfig.setAccessible(true);

        // Experimental vendor-specific payload retained from the prototype. The code now
        // validates input and only calls it when the expected method actually exists.
        byte[] config = new byte[targetUid.length + 2];
        config[0] = 0x01;
        config[1] = (byte) targetUid.length;
        System.arraycopy(targetUid, 0, config, 2, targetUid.length);

        Object result = writeConfig.invoke(managerInstance, 1, config);
        Log.i(TAG, "UID config request sent for " + formatHex(targetUid) +
                "; return=" + String.valueOf(result));
    }

    private Context getCurrentApplicationContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication");
            Object app = currentApplicationMethod.invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable e) {
            Log.e(TAG, "Unable to obtain application context", e);
            return null;
        }
    }

    private String readTargetUid(Context context) {
        try (Cursor cursor = context.getContentResolver().query(
                UID_CONFIG_URI,
                new String[]{"uid"},
                null,
                null,
                null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            int index = cursor.getColumnIndex("uid");
            return index >= 0 && !cursor.isNull(index) ? cursor.getString(index) : null;
        } catch (Throwable e) {
            Log.e(TAG, "Unable to read target UID from app provider", e);
            return null;
        }
    }

    private boolean isSupportedNfcAUidLength(int length) {
        return length == 4 || length == 7 || length == 10;
    }

    private byte[] hexToBytes(String value) {
        if (value == null) {
            return new byte[0];
        }

        String clean = value
                .replace(":", "")
                .replace("-", "")
                .replace(" ", "")
                .trim();

        if ((clean.length() & 1) != 0 || !clean.matches("(?i)[0-9a-f]+")) {
            throw new IllegalArgumentException("Invalid hexadecimal UID: " + value);
        }

        byte[] data = new byte[clean.length() / 2];
        for (int i = 0; i < clean.length(); i += 2) {
            data[i / 2] = (byte) Integer.parseInt(clean.substring(i, i + 2), 16);
        }
        return data;
    }

    private String formatHex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 3);
        for (int i = 0; i < value.length; i++) {
            if (i > 0) out.append(':');
            out.append(String.format(Locale.US, "%02X", value[i] & 0xFF));
        }
        return out.toString();
    }
}
