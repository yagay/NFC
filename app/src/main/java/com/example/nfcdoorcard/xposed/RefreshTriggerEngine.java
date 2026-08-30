package com.example.nfcdoorcard.xposed;

import com.example.nfcdoorcard.xposed.discovery.HookTarget;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/** Captures a proven in-process OEM RF-refresh service instance and safely reuses it. */
final class RefreshTriggerEngine {
    static final class Invocation {
        final boolean success;
        final String stage;
        final String detail;
        final String targetFingerprint;

        Invocation(boolean success, String stage, String detail, String targetFingerprint) {
            this.success = success;
            this.stage = stage;
            this.detail = detail;
            this.targetFingerprint = targetFingerprint == null ? "" : targetFingerprint;
        }
    }

    private volatile HookTarget target;
    private volatile Method method;
    private volatile WeakReference<Object> instance = new WeakReference<>(null);

    synchronized boolean observe(HookTarget candidate, Method candidateMethod, Object thisObject, Object result) {
        if (candidate == null || candidateMethod == null || thisObject == null || !accepted(candidateMethod, result)) return false;
        HookTarget current = target;
        Object currentInstance = instance.get();
        if (current != null && currentInstance != null && current.score > candidate.score) return false;
        if (current != null && current.fingerprint().equals(candidate.fingerprint()) && currentInstance == thisObject) return false;
        candidateMethod.setAccessible(true);
        target = candidate;
        method = candidateMethod;
        instance = new WeakReference<>(thisObject);
        return true;
    }

    Invocation invoke(boolean enabled) {
        HookTarget t = target;
        Method m = method;
        Object receiver = instance.get();
        if (t == null || m == null || receiver == null) {
            return new Invocation(false, "JAVA_TRIGGER_UNAVAILABLE", "No verified in-process refresh trigger instance", "");
        }
        try {
            Object result = m.invoke(receiver, enabled);
            boolean ok = accepted(m, result);
            return new Invocation(ok, ok ? "JAVA_TRIGGERED" : "JAVA_TRIGGER_REJECTED",
                    t.className + "#" + t.methodName + "(" + enabled + ") result=" + String.valueOf(result),
                    t.fingerprint());
        } catch (Throwable t0) {
            Throwable cause = t0.getCause() == null ? t0 : t0.getCause();
            clear();
            return new Invocation(false, "JAVA_TRIGGER_EXCEPTION",
                    cause.getClass().getName() + ": " + String.valueOf(cause.getMessage()),
                    t.fingerprint());
        }
    }

    synchronized void clear() {
        target = null;
        method = null;
        instance = new WeakReference<>(null);
    }

    HookTarget currentTarget() { return target; }

    static boolean accepted(Method method, Object result) {
        if (method == null) return false;
        Class<?> type = method.getReturnType();
        if (type == Void.TYPE) return true;
        if (result instanceof Boolean) return (Boolean) result;
        if (result instanceof Number) return ((Number) result).longValue() == 0L;
        return false;
    }
}
