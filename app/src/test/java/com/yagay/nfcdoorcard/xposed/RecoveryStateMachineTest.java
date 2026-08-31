package com.yagay.nfcdoorcard.xposed;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RecoveryStateMachineTest {
    @Test public void verifiedProofNeedsNoRecovery() {
        assertEquals(RecoveryStateMachine.Action.NONE,
                RecoveryStateMachine.next(new RecoveryStateMachine.Snapshot(
                        true, true, true, true, false, 0, 3)));
    }

    @Test public void controllerReadyPrefersExactReplay() {
        assertEquals(RecoveryStateMachine.Action.EXACT_REPLAY,
                RecoveryStateMachine.next(new RecoveryStateMachine.Snapshot(
                        true, true, false, true, false, 0, 3)));
    }

    @Test public void missingReplayFallsBackToVendorTrigger() {
        assertEquals(RecoveryStateMachine.Action.FALLBACK_TRIGGER,
                RecoveryStateMachine.next(new RecoveryStateMachine.Snapshot(
                        true, true, false, false, false, 0, 3)));
    }

    @Test public void exhaustedFallbackWaitsForLateExactReplay() {
        assertEquals(RecoveryStateMachine.Action.WAIT_FOR_REPLAY,
                RecoveryStateMachine.next(new RecoveryStateMachine.Snapshot(
                        true, true, false, false, false, 3, 3)));
    }

    @Test public void exhaustedFallbackAfterReplayMarksFailure() {
        assertEquals(RecoveryStateMachine.Action.MARK_FAILED,
                RecoveryStateMachine.next(new RecoveryStateMachine.Snapshot(
                        true, true, false, false, true, 3, 3)));
    }

    @Test public void stoppedSimulationNeverRecovers() {
        assertEquals(RecoveryStateMachine.Action.NONE,
                RecoveryStateMachine.next(new RecoveryStateMachine.Snapshot(
                        false, true, false, true, false, 0, 3)));
    }
}
