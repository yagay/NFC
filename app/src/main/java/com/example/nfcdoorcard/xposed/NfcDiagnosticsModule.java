package com.example.nfcdoorcard.xposed;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * LSPosed/libxposed API 102 diagnostics module.
 *
 * This module intentionally does not write controller-specific NFC configuration.
 * It only confirms that the Android NFC service target was loaded and that
 * NativeNfcManager.doInitialize() can be observed on the current NFC stack.
 */
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
                Object result = chain.proceed();
                if (result instanceof Boolean) {
                    Log.i(TAG, "NFC doInitialize observed: " + ((Boolean) result ? "success" : "failed"));
                } else {
                    Log.i(TAG, "NFC doInitialize observed; return=" + String.valueOf(result));
                }
                return result;
            });

            Log.i(TAG, "NativeNfcManager.doInitialize hook installed");
            reportVendorBackend(nativeManager);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "NativeNfcManager class not found on this NFC stack", e);
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "NativeNfcManager.doInitialize not found on this NFC stack", e);
        } catch (Throwable t) {
            Log.e(TAG, "NFC hook installation failed: " + t.getClass().getSimpleName(), t);
        }
    }

    /**
     * Only reports whether the prototype vendor method exists. It is never invoked.
     */
    private void reportVendorBackend(Class<?> nativeManager) {
        try {
            nativeManager.getDeclaredMethod("doWriteNciConfig", int.class, byte[].class);
            Log.i(TAG, "Vendor method doWriteNciConfig(int, byte[]) is present; writes remain disabled");
        } catch (NoSuchMethodException e) {
            Log.i(TAG, "Vendor method doWriteNciConfig(int, byte[]) is absent");
        } catch (Throwable t) {
            Log.w(TAG, "Unable to inspect vendor NFC backend", t);
        }
    }
}
