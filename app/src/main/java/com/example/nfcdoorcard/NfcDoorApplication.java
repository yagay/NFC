package com.example.nfcdoorcard;

import android.app.Application;

import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Modern libxposed module apps are not self-hooked. The framework delivers an
 * XposedService binder to the module application instead; use that binder as the
 * source of truth for activation/scope and running-target state.
 */
public final class NfcDoorApplication extends Application implements XposedServiceHelper.OnServiceListener {

    public interface Listener {
        void onXposedStateChanged();
    }

    private static final CopyOnWriteArraySet<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile XposedService service;

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService boundService) {
        service = boundService;
        notifyListeners();
    }

    @Override
    public void onServiceDied(XposedService deadService) {
        if (service == deadService) service = null;
        notifyListeners();
    }

    public static boolean isFrameworkConnected() {
        return service != null;
    }

    public static boolean isNfcScopeEnabled() {
        XposedService current = service;
        if (current == null) return false;
        try {
            List<String> scope = current.getScope();
            return scope != null && scope.contains("com.android.nfc");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Returns true only when API 102 reports com.android.nfc as a currently hooked target. */
    public static boolean isNfcProcessHooked() {
        XposedService current = service;
        if (current == null) return false;
        try {
            if (current.getApiVersion() < XposedService.API_102) return false;
            for (HookedTarget target : current.getRunningTargets()) {
                if ("com.android.nfc".equals(target.getProcessName())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static String getRunningTargetsSummary() {
        XposedService current = service;
        if (current == null) return "LSPosed service 未连接";
        try {
            if (current.getApiVersion() < XposedService.API_102) {
                return "框架 Service API < 102，不支持运行目标查询";
            }
            List<HookedTarget> targets = current.getRunningTargets();
            if (targets == null || targets.isEmpty()) return "当前模块没有已报告的运行 Hook 目标";
            StringBuilder sb = new StringBuilder();
            for (HookedTarget target : targets) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(target.getProcessName())
                        .append(" pid=").append(target.getPid())
                        .append(" uid=").append(target.getUid())
                        .append(" state=").append(target.getState())
                        .append(" moduleVersionCode=").append(target.getLoadedVersionCode());
            }
            return sb.toString();
        } catch (UnsupportedOperationException e) {
            return "当前框架未实现 API 102 运行目标查询";
        } catch (Throwable e) {
            return "运行目标查询失败: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        }
    }

    public static String getFrameworkSummary() {
        XposedService current = service;
        if (current == null) return "LSPosed service 未连接";
        try {
            return current.getFrameworkName() + " " + current.getFrameworkVersion()
                    + " / API " + current.getApiVersion();
        } catch (Throwable e) {
            return "Xposed service 已连接";
        }
    }

    public static void addListener(Listener listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        for (Listener listener : LISTENERS) {
            try {
                listener.onXposedStateChanged();
            } catch (Throwable ignored) {
            }
        }
    }
}
