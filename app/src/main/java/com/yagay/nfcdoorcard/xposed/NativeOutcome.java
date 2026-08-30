package com.yagay.nfcdoorcard.xposed;

/** Immutable interpretation of one native RF call result. */
final class NativeOutcome {
    final boolean accepted;
    final String rawValue;
    final String resultType;

    NativeOutcome(boolean accepted, String rawValue, String resultType) {
        this.accepted = accepted;
        this.rawValue = rawValue == null ? "" : rawValue;
        this.resultType = resultType == null ? "unknown" : resultType;
    }

    static NativeOutcome notInvoked() { return new NativeOutcome(false, "", "not-invoked"); }
    static NativeOutcome lifecycleAccepted(String source) { return new NativeOutcome(true, source, "lifecycle"); }
}
