package com.example.nfcdoorcard.xposed;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Method;

public class NfcDiagnosticsModule extends XposedModule {

    private static final String TAG = "NfcUIDSim";

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam lp) {
        super.onPackageLoaded(lp);
        String packageName = lp.getPackageName();

        // 自 Hook 激活状态
        if (packageName.equals("com.example.nfcdoorcard")) {
            Log.i(TAG, "主 App 已被 LSPosed 加载: " + packageName);
            try {
                ClassLoader cl = lp.getDefaultClassLoader();
                Class<?> mainActivity = cl.loadClass("com.example.nfcdoorcard.MainActivity");
                Method activeMethod = mainActivity.getDeclaredMethod("isModuleActive");
                Log.i(TAG, "已找到 isModuleActive 方法");

                // 防止 ART 将这个始终返回 false 的小方法内联，导致 Hook 看似成功但调用点仍返回 false。
                deoptimize(activeMethod);
                Log.i(TAG, "isModuleActive 已 deoptimize");

                hook(activeMethod).intercept(chain -> {
                    Log.i(TAG, "isModuleActive 已被拦截 -> true");
                    return true;
                });
                Log.i(TAG, "isModuleActive Hook 已安装");
            } catch (Throwable e) {
                Log.e(TAG, "自 Hook 失败", e);
            }
        }

        // 针对 NFC 服务进行 Hook
        if (packageName.equals("com.android.nfc")) {
            Log.i(TAG, "检测到 NFC 服务加载...");
            try {
                ClassLoader cl = lp.getDefaultClassLoader();
                Class<?> nativeManager = cl.loadClass("com.android.nfc.dhimpl.NativeNfcManager");
                Method initMethod = nativeManager.getDeclaredMethod("doInitialize");

                hook(initMethod).intercept(chain -> {
                    Object result = chain.proceed();
                    injectUid(chain.getThisObject(), cl);
                    return result;
                });

                // 屏蔽智能切卡
                Class<?> featureManager = cl.loadClass("com.android.nfc.NfcFeatureManager");
                Method featureMethod = featureManager.getDeclaredMethod("isFeatureEnable", String.class);
                hook(featureMethod).intercept(chain -> {
                    String feature = (String) chain.getArgs().get(0);
                    if ("SMART_SWITCH_CARD".equals(feature) || "REALTIME_SWITCH_CARD".equals(feature)) {
                        Log.i(TAG, "已屏蔽系统功能: " + feature);
                        return false;
                    }
                    return chain.proceed();
                });
            } catch (Exception e) {
                Log.e(TAG, "NFC Hook 失败: " + e.getMessage());
            }
        }
    }

    private void injectUid(Object managerInstance, ClassLoader classLoader) {
        try {
            Class<?> activityThreadClass = classLoader.loadClass("android.app.ActivityThread");
            Method currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication");
            Context context = (Context) currentApplicationMethod.invoke(null);

            if (context == null) {
                Log.w(TAG, "无法获取 Context，注入失败");
                return;
            }

            SharedPreferences prefs = context.getSharedPreferences("sim_prefs", Context.MODE_PRIVATE);
            String targetUidHex = prefs.getString("target_uid", "AABBCCDD");

            byte[] targetUid = hexToBytes(targetUidHex);
            byte[] config = new byte[targetUid.length + 2];
            config[0] = 0x01; // LA_NFCID1
            config[1] = (byte) targetUid.length;
            System.arraycopy(targetUid, 0, config, 2, targetUid.length);

            Method writeConfig = managerInstance.getClass().getDeclaredMethod("doWriteNciConfig", int.class, byte[].class);
            writeConfig.setAccessible(true);
            writeConfig.invoke(managerInstance, 1, config);

            Log.i(TAG, "UID 物理层注入完成: " + targetUidHex);
        } catch (Exception e) {
            Log.e(TAG, "注入 UID 异常: " + e.getMessage());
        }
    }

    private byte[] hexToBytes(String s) {
        String clean = s.replace(" ", "");
        int len = clean.length();
        if (len % 2 != 0) return new byte[0];
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(clean.charAt(i), 16) << 4)
                    + Character.digit(clean.charAt(i + 1), 16));
        }
        return data;
    }
}
