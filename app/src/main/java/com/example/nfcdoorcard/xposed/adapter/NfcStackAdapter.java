package com.example.nfcdoorcard.xposed.adapter;

import java.lang.reflect.Method;

public interface NfcStackAdapter {
    String id();
    Detection detect(ClassLoader classLoader);
    Method resolveInjectionMethod(ClassLoader classLoader) throws Exception;
    InjectionResult inject(byte[] original, byte[] uid);

    /** Observe vendor calls so an adapter can retain a safe, already-proven refresh target/config. */
    default void observeInvocation(Object receiver, Method method, Object[] args) {}

    /** Observe a newly constructed vendor object when available. */
    default void observeConstructedObject(Object object) {}

    /** Re-apply the vendor's normal RF configuration path. */
    default RefreshResult requestRfRefresh(ClassLoader classLoader) {
        return RefreshResult.unavailable("REFRESH_NOT_IMPLEMENTED");
    }

    final class Detection {
        public final boolean supported;
        public final String detail;
        public Detection(boolean supported, String detail) {
            this.supported = supported;
            this.detail = detail;
        }
        public static Detection supported(String detail) { return new Detection(true, detail); }
        public static Detection unsupported(String detail) { return new Detection(false, detail); }
    }

    final class RefreshResult {
        public final boolean invoked;
        public final boolean accepted;
        public final String detail;

        private RefreshResult(boolean invoked, boolean accepted, String detail) {
            this.invoked = invoked;
            this.accepted = accepted;
            this.detail = detail == null ? "" : detail;
        }

        public static RefreshResult accepted(String detail) {
            return new RefreshResult(true, true, detail);
        }

        public static RefreshResult rejected(String detail) {
            return new RefreshResult(true, false, detail);
        }

        public static RefreshResult unavailable(String detail) {
            return new RefreshResult(false, false, detail);
        }
    }

    final class InjectionResult {
        public final boolean changed;
        public final String reason;
        public final byte[] data;
        public final int oldPayloadLength;
        public final int newPayloadLength;
        public final int oldParamCount;
        public final int newParamCount;

        private InjectionResult(boolean changed, String reason, byte[] data, int oldPayloadLength, int newPayloadLength, int oldParamCount, int newParamCount) {
            this.changed = changed;
            this.reason = reason;
            this.data = data;
            this.oldPayloadLength = oldPayloadLength;
            this.newPayloadLength = newPayloadLength;
            this.oldParamCount = oldParamCount;
            this.newParamCount = newParamCount;
        }

        public static InjectionResult skip(String reason) {
            return new InjectionResult(false, reason, null, 0, 0, 0, 0);
        }

        public static InjectionResult changed(byte[] data, int oldPayloadLength, int newPayloadLength, int oldParamCount, int newParamCount) {
            return new InjectionResult(true, "OK", data, oldPayloadLength, newPayloadLength, oldParamCount, newParamCount);
        }
    }
}
