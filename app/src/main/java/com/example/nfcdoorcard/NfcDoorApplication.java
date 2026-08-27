package com.example.nfcdoorcard;

import android.app.Application;

import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Modern libxposed module apps are not self-hooked. The framework delivers an
 * XposedService binder to the module application instead; use that binder as the
 * source of truth for activation/scope state.
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
        if (service == deadService) {
            service = null;
        }
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
