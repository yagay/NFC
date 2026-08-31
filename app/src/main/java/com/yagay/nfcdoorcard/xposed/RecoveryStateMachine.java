package com.yagay.nfcdoorcard.xposed;

/**
 * Pure recovery policy for controller lifecycle restoration.
 *
 * Android/Xposed code owns timing and side effects; this class only decides which recovery action
 * is valid from the current durable/physical state. Keeping the policy pure makes OFF -> ON and
 * controller-reset behaviour unit-testable without an Android runtime.
 */
public final class RecoveryStateMachine {
    public enum Action {
        NONE,
        EXACT_REPLAY,
        FALLBACK_TRIGGER,
        WAIT_FOR_REPLAY,
        MARK_FAILED
    }

    public static final class Snapshot {
        public final boolean desiredActive;
        public final boolean commandSucceeded;
        public final boolean currentProofVerified;
        public final boolean exactReplayAvailable;
        public final boolean exactReplayAttempted;
        public final int fallbackAttempts;
        public final int maxFallbackAttempts;

        public Snapshot(boolean desiredActive,
                        boolean commandSucceeded,
                        boolean currentProofVerified,
                        boolean exactReplayAvailable,
                        boolean exactReplayAttempted,
                        int fallbackAttempts,
                        int maxFallbackAttempts) {
            this.desiredActive = desiredActive;
            this.commandSucceeded = commandSucceeded;
            this.currentProofVerified = currentProofVerified;
            this.exactReplayAvailable = exactReplayAvailable;
            this.exactReplayAttempted = exactReplayAttempted;
            this.fallbackAttempts = Math.max(0, fallbackAttempts);
            this.maxFallbackAttempts = Math.max(0, maxFallbackAttempts);
        }
    }

    private RecoveryStateMachine() { }

    public static Action next(Snapshot state) {
        if (state == null || !state.desiredActive || !state.commandSucceeded) return Action.NONE;
        if (state.currentProofVerified) return Action.NONE;
        if (!state.exactReplayAttempted && state.exactReplayAvailable) return Action.EXACT_REPLAY;
        if (state.fallbackAttempts < state.maxFallbackAttempts) return Action.FALLBACK_TRIGGER;
        if (!state.exactReplayAttempted) return Action.WAIT_FOR_REPLAY;
        return Action.MARK_FAILED;
    }
}
