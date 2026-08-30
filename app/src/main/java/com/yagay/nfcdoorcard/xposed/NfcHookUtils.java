package com.yagay.nfcdoorcard.xposed;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Method;
import java.util.Locale;

/** Stateless runtime helpers kept separate from Hook orchestration and state. */
final class NfcHookUtils {
    private NfcHookUtils() { }

    static Context currentContext() {
        Application app = currentApplication();
        if (app != null) return app;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method current = at.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            if (thread == null) return null;
            Method systemContext = at.getDeclaredMethod("getSystemContext");
            systemContext.setAccessible(true);
            Object ctx = systemContext.invoke(thread);
            return ctx instanceof Context ? (Context) ctx : null;
        } catch (Throwable ignored) { return null; }
    }

    static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method method = at.getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            return (Application) method.invoke(null);
        } catch (Throwable ignored) { return null; }
    }

    static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    static String normalizeUid(String uid) {
        return uid == null ? "" : uid.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
    }

    static int findSingleByteArrayArg(Object[] args) {
        if (args == null || args.length == 0) return -1;
        int index = -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof byte[]) {
                if (index >= 0) return -1;
                index = i;
            }
        }
        return index;
    }

    static NativeOutcome interpretNativeResult(Method method, Object result) {
        Class<?> type = method == null ? null : method.getReturnType();
        if (type == Void.TYPE) return new NativeOutcome(true, "null", "void");
        if (type == Boolean.TYPE || type == Boolean.class) {
            boolean accepted = Boolean.TRUE.equals(result);
            return new NativeOutcome(accepted, String.valueOf(result), "boolean");
        }
        if (result instanceof Number) {
            boolean accepted = ((Number) result).intValue() == 0;
            return new NativeOutcome(accepted, String.valueOf(result),
                    type == null ? result.getClass().getName() : type.getName());
        }
        return new NativeOutcome(false, String.valueOf(result), type == null ? "unknown" : type.getName());
    }

    static byte[] hexToBytes(String hex) {
        byte[] output = new byte[hex.length() / 2];
        for (int i = 0; i < output.length; i++) {
            output[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return output;
    }

    static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Throwable ignored) { return fallback; }
    }

    static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
