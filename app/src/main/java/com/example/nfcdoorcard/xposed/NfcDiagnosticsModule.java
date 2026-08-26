package com.example.nfcdoorcard.xposed;

<<<<<<< HEAD
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
=======
>>>>>>> d77de3d (feat: 增加实时日志控制台、硬件诊断功能及 LSPosed API 102 架构优化)
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;
<<<<<<< HEAD
import io.github.libxposed.api.XposedModuleInterface;
=======
import java.lang.reflect.Method;
>>>>>>> d77de3d (feat: 增加实时日志控制台、硬件诊断功能及 LSPosed API 102 架构优化)

/**
 * LibXposed API 102 架构
 * 注意：必须保留无参构造函数，框架会通过 attachFramework 注入接口。
 */
public class NfcDiagnosticsModule extends XposedModule {

    private static final String TAG = "NfcUIDSim";
<<<<<<< HEAD
    private static final Uri UID_CONFIG_URI =
            Uri.parse("content://com.example.nfcdoorcard.uidconfig/target");
=======
    private static final String UID_PROP = "persist.nfcuidsim.uid";
    private static String lastInjectedUid = "NONE";
>>>>>>> d77de3d (feat: 增加实时日志控制台、硬件诊断功能及 LSPosed API 102 架构优化)

    @Override
    public void onPackageLoaded(PackageLoadedParam lp) {
        super.onPackageLoaded(lp);
        String packageName = lp.getPackageName();
        
        // 关键：在模块级日志打印，确认加载
        Log.e(TAG, "LSPosed Hook 介入: " + packageName);

<<<<<<< HEAD
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
=======
        // 针对本 App 进程
        if (packageName.equals("com.example.nfcdoorcard")) {
            try {
                ClassLoader cl = lp.getDefaultClassLoader();
                Class<?> mainActivity = cl.loadClass("com.example.nfcdoorcard.MainActivity");
                for (Method m : mainActivity.getDeclaredMethods()) {
                    if ("isModuleLoaded".equals(m.getName())) {
                        hook(m).intercept(c -> true);
                    }
                    if ("getHardwareActualUid".equals(m.getName())) {
                        hook(m).intercept(c -> {
                            String currentProp = getSystemProperty(UID_PROP, "OFF");
                            if ("OFF".equalsIgnoreCase(currentProp) || currentProp.isEmpty()) {
                                return "系统原生 (随机 UID)";
                            }
                            return lastInjectedUid;
                        });
                    }
                }
                Log.e(TAG, "App 进程 Hook 成功");
            } catch (Exception e) {
                Log.e(TAG, "App 进程注入异常: " + e.getMessage());
            }
        }

        // 针对 NFC 系统进程 (核心逻辑)
        if (packageName.equals("com.android.nfc")) {
            Log.e(TAG, "发现目标 NFC 进程，正在注入 Hook...");
            try {
                ClassLoader cl = lp.getDefaultClassLoader();
                Class<?> nativeManager = cl.loadClass("com.android.nfc.dhimpl.NativeNfcManager");
                Method initMethod = nativeManager.getDeclaredMethod("doInitialize");
                
                hook(initMethod).intercept(chain -> {
                    Object result = chain.proceed();
                    Log.e(TAG, "NFC 服务初始化，正在同步硬件 UID...");
                    syncHardwareUidState(chain.getThisObject());
                    return result;
                });

                // 屏蔽智能切卡
                Class<?> featureManager = cl.loadClass("com.android.nfc.NfcFeatureManager");
                Method featureMethod = featureManager.getDeclaredMethod("isFeatureEnable", String.class);
                hook(featureMethod).intercept(chain -> {
                    String feature = (String) chain.getArgs().get(0);
                    if ("SMART_SWITCH_CARD".equals(feature) || "REALTIME_SWITCH_CARD".equals(feature)) {
                        String target = getSystemProperty(UID_PROP, "OFF");
                        if (!"OFF".equalsIgnoreCase(target) && !target.isEmpty()) {
                            Log.e(TAG, "已拦截系统干扰功能: " + feature);
                            return false;
                        }
                    }
                    return chain.proceed();
                });
            } catch (Exception e) {
                Log.e(TAG, "NFC Hook 失败: " + e.getMessage());
            }
        }
    }

    private void syncHardwareUidState(Object managerInstance) {
        try {
            String targetUidHex = getSystemProperty(UID_PROP, "OFF");
            if (targetUidHex == null || targetUidHex.isEmpty() || "OFF".equalsIgnoreCase(targetUidHex)) {
                Log.e(TAG, "重置硬件 UID 至随机模式");
                lastInjectedUid = "NONE";
                Method writeConfig = managerInstance.getClass().getDeclaredMethod("doWriteNciConfig", int.class, byte[].class);
                writeConfig.setAccessible(true);
                writeConfig.invoke(managerInstance, 1, new byte[]{0x01, 0x00});
                return;
            }

            byte[] targetUid = hexToBytes(targetUidHex);
            if (targetUid.length == 0) return;
            byte[] config = new byte[targetUid.length + 2];
            config[0] = 0x01; 
            config[1] = (byte) targetUid.length;
            System.arraycopy(targetUid, 0, config, 2, targetUid.length);

            Method writeConfig = managerInstance.getClass().getDeclaredMethod("doWriteNciConfig", int.class, byte[].class);
            writeConfig.setAccessible(true);
            writeConfig.invoke(managerInstance, 1, config);
            
            lastInjectedUid = targetUidHex;
            Log.e(TAG, "底层 UID 已成功设为: " + targetUidHex);
        } catch (Exception e) {
            Log.e(TAG, "硬件同步操作异常: " + e.getMessage());
        }
    }

    private String getSystemProperty(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getDeclaredMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, def);
        } catch (Exception e) {
            return def;
        }
    }

    private byte[] hexToBytes(String s) {
        String clean = s.replace(" ", "").replace(":", "");
        int len = clean.length();
        if (len % 2 != 0) return new byte[0];
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(clean.charAt(i), 16) << 4)
                    + Character.digit(clean.charAt(i + 1), 16));
>>>>>>> d77de3d (feat: 增加实时日志控制台、硬件诊断功能及 LSPosed API 102 架构优化)
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
