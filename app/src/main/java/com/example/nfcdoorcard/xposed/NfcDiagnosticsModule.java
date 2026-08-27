package com.example.nfcdoorcard.xposed;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** LSPosed/libxposed API 102 diagnostics module. */
public class NfcDiagnosticsModule extends XposedModule {
    private static final String TAG = "NfcUIDSim";

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
        super.onPackageLoaded(lp);
        String packageName = lp.getPackageName();
        if (!"com.android.nfc".equals(packageName)) return;
        Log.i(TAG, "LSPosed loaded NFC target: " + packageName);
        installNfcServiceHooks(lp);
    }

    private void installNfcServiceHooks(XposedModuleInterface.PackageLoadedParam lp) {
        try {
            ClassLoader cl = lp.getDefaultClassLoader();
            Class<?> nativeManager = cl.loadClass("com.android.nfc.dhimpl.NativeNfcManager");
            Method initMethod = nativeManager.getDeclaredMethod("doInitialize");

            deoptimize(initMethod);
            hook(initMethod).intercept(chain -> {
                Log.i(TAG, "NFC doInitialize entered");
                try {
                    Object result = chain.proceed();
                    if (result instanceof Boolean) {
                        Log.i(TAG, "NFC doInitialize returned: " + ((Boolean) result ? "success" : "failed"));
                    } else {
                        Log.i(TAG, "NFC doInitialize returned: " + String.valueOf(result));
                    }
                    return result;
                } catch (Throwable t) {
                    Log.e(TAG, "NFC doInitialize threw: " + t.getClass().getName(), t);
                    throw t;
                }
            });

            Log.i(TAG, "NativeNfcManager.doInitialize hook installed");
            reportKnownVendorSignature(nativeManager);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "NativeNfcManager class not found on this NFC stack", e);
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "NativeNfcManager.doInitialize not found on this NFC stack", e);
        } catch (Throwable t) {
            Log.e(TAG, "NFC hook installation failed: " + t.getClass().getSimpleName(), t);
        }
    }

    /** Checks one known signature only; absence does not imply the controller lacks configuration support. */
    private void reportKnownVendorSignature(Class<?> nativeManager) {
        try {
            nativeManager.getDeclaredMethod("doWriteNciConfig", int.class, byte[].class);
            Log.i(TAG, "Known vendor signature doWriteNciConfig(int, byte[]) is present; invocation disabled");
        } catch (NoSuchMethodException e) {
            Log.i(TAG, "Known vendor signature doWriteNciConfig(int, byte[]) not found; other vendor APIs may differ");
        } catch (Throwable t) {
            Log.w(TAG, "Unable to inspect known vendor NFC signature", t);
        }
    }
}
