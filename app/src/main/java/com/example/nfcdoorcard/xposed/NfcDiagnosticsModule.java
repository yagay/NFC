package com.example.nfcdoorcard.xposed;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * LSPosed/libxposed API 102 diagnostics module.
 *
 * The module deliberately keeps device-specific NFC controller writes disabled until
 * the target NFC stack has been identified. It provides reliable module/self diagnostics
 * and confirms that the Android NFC service hook is installed and executed.
 */
public class NfcDiagnosticsModule extends XposedModule {

    private static final String TAG = "NfcUIDSim";

    private static volatile boolean nfcHookInstalled;
    private static volatile boolean nfcInitializeObserved;
    private static volatile String lastNfcState = "LSPosed active; NFC service not observed yet";

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
        super.onPackageLoaded(lp);

        String packageName = lp.getPackageName();
        Log.i(TAG, "LSPosed loaded package: " + packageName);

        if ("com.example.nfcdoorcard".equals(packageName)) {
            installAppHooks(lp);
            return;
        }

        if ("com.android.nfc".equals(packageName)) {
            installNfcServiceHooks(lp);
        }
    }

    private void installAppHooks(XposedModuleInterface.PackageLoadedParam lp) {
        try {
            ClassLoader cl = lp.getDefaultClassLoader();
            Class<?> mainActivity = cl.loadClass("com.example.nfcdoorcard.MainActivity");

            hookStaticBooleanMethod(mainActivity, "isModuleLoaded");
            hookHardwareStatusMethod(mainActivity);

            Log.i(TAG, "App diagnostics hooks installed");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install app diagnostics hooks", t);
        }
    }

    private void hookStaticBooleanMethod(Class<?> owner, String methodName) {
        try {
            Method method = owner.getDeclaredMethod(methodName);
            deoptimize(method);
            hook(method).intercept(chain -> true);
            Log.i(TAG, methodName + " hook installed");
        } catch (NoSuchMethodException e) {
            Log.w(TAG, methodName + " is not present in this app build");
        }
    }

    private void hookHardwareStatusMethod(Class<?> mainActivity) {
        try {
            Method method = mainActivity.getDeclaredMethod("getHardwareActualUid");
            deoptimize(method);
            hook(method).intercept(chain -> currentHardwareStatus());
            Log.i(TAG, "getHardwareActualUid hook installed");
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "getHardwareActualUid is not present in this app build");
        }
    }

    private String currentHardwareStatus() {
        if (nfcInitializeObserved) {
            return lastNfcState;
        }
        if (nfcHookInstalled) {
            return "NFC hook installed; waiting for doInitialize";
        }
        return "LSPosed active; NFC service scope not observed";
    }

    private void installNfcServiceHooks(XposedModuleInterface.PackageLoadedParam lp) {
        try {
            ClassLoader cl = lp.getDefaultClassLoader();
            Class<?> nativeManager = cl.loadClass("com.android.nfc.dhimpl.NativeNfcManager");
            Method initMethod = nativeManager.getDeclaredMethod("doInitialize");

            deoptimize(initMethod);
            hook(initMethod).intercept(chain -> {
                Object result = chain.proceed();
                nfcInitializeObserved = true;

                if (result instanceof Boolean) {
                    boolean ok = (Boolean) result;
                    lastNfcState = ok
                            ? "NFC doInitialize observed: success"
                            : "NFC doInitialize observed: failed";
                } else {
                    lastNfcState = "NFC doInitialize observed; return=" + String.valueOf(result);
                }

                Log.i(TAG, lastNfcState);
                return result;
            });

            nfcHookInstalled = true;
            lastNfcState = "NFC hook installed; waiting for doInitialize";
            Log.i(TAG, "NativeNfcManager.doInitialize hook installed");

            reportVendorBackend(nativeManager);
        } catch (ClassNotFoundException e) {
            lastNfcState = "NativeNfcManager class not found on this NFC stack";
            Log.w(TAG, lastNfcState, e);
        } catch (NoSuchMethodException e) {
            lastNfcState = "NativeNfcManager.doInitialize not found on this NFC stack";
            Log.w(TAG, lastNfcState, e);
        } catch (Throwable t) {
            lastNfcState = "NFC hook installation failed: " + t.getClass().getSimpleName();
            Log.e(TAG, lastNfcState, t);
        }
    }

    /**
     * Only reports whether the prototype's vendor-specific method exists.
     * It does not invoke that method because its semantics are not portable across NFC HALs.
     */
    private void reportVendorBackend(Class<?> nativeManager) {
        try {
            nativeManager.getDeclaredMethod("doWriteNciConfig", int.class, byte[].class);
            Log.i(TAG, "Vendor method doWriteNciConfig(int, byte[]) is present; write disabled pending device mapping");
        } catch (NoSuchMethodException e) {
            Log.i(TAG, "Vendor method doWriteNciConfig(int, byte[]) is absent");
        } catch (Throwable t) {
            Log.w(TAG, "Unable to inspect vendor NFC backend", t);
        }
    }
}
