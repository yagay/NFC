package com.yagay.nfcdoorcard.xposed;

import java.lang.reflect.Method;

/** Owns immutable RF invocation snapshots and replay mechanics for one NFC process. */
public final class RfReplayEngine {
    public static final class Snapshot {
        final Method method;
        final Object receiver;
        final Object[] args;
        final String targetFingerprint;
        final long capturedAt;
        final int capturedPid;

        Snapshot(Method method, Object receiver, Object[] args, String targetFingerprint,
                 long capturedAt, int capturedPid) {
            this.method = method;
            this.receiver = receiver;
            this.args = args;
            this.targetFingerprint = targetFingerprint;
            this.capturedAt = capturedAt;
            this.capturedPid = capturedPid;
        }
    }

    public static final class ReplayResult {
        public final boolean invoked;
        public final Throwable error;

        ReplayResult(boolean invoked, Throwable error) {
            this.invoked = invoked;
            this.error = error;
        }
    }

    private volatile Snapshot pending;
    private volatile Snapshot verified;

    public Snapshot capturePending(Method method, Object receiver, Object[] args,
                                   String targetFingerprint, long capturedAt, int pid) {
        Snapshot snapshot = snapshot(method, receiver, args, targetFingerprint, capturedAt, pid);
        if (snapshot != null) pending = snapshot;
        return snapshot;
    }

    public Snapshot captureVerified(Method method, Object receiver, Object[] args,
                                    String targetFingerprint, long capturedAt, int pid) {
        Snapshot snapshot = snapshot(method, receiver, args, targetFingerprint, capturedAt, pid);
        if (snapshot != null) verified = snapshot;
        return snapshot;
    }

    public Snapshot pending() { return pending; }

    public Snapshot verified(int currentPid) {
        Snapshot snapshot = verified;
        return snapshot != null && snapshot.capturedPid == currentPid ? snapshot : null;
    }

    public boolean hasVerified(int currentPid) { return verified(currentPid) != null; }

    public synchronized void clearPending(String targetFingerprint) {
        Snapshot snapshot = pending;
        if (snapshot == null) return;
        if (targetFingerprint != null && !targetFingerprint.isEmpty() &&
                !targetFingerprint.equals(snapshot.targetFingerprint)) return;
        pending = null;
    }

    public ReplayResult invoke(Snapshot snapshot) {
        if (snapshot == null) return new ReplayResult(false, null);
        try {
            snapshot.method.setAccessible(true);
            snapshot.method.invoke(snapshot.receiver, cloneArgs(snapshot.args));
            return new ReplayResult(true, null);
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            return new ReplayResult(false, cause);
        }
    }

    private static Snapshot snapshot(Method method, Object receiver, Object[] args,
                                     String targetFingerprint, long capturedAt, int pid) {
        if (method == null || args == null || targetFingerprint == null) return null;
        return new Snapshot(method, receiver, cloneArgs(args), targetFingerprint, capturedAt, pid);
    }

    private static Object[] cloneArgs(Object[] args) {
        Object[] copy = args.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] instanceof byte[]) copy[i] = ((byte[]) copy[i]).clone();
        }
        return copy;
    }
}
