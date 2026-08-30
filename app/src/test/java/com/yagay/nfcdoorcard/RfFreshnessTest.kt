package com.yagay.nfcdoorcard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RfFreshnessTest {
    @Test fun samePidAndSameControllerEpochIsFresh() {
        assertTrue(RfFreshness.isFresh(1234, 1234, 1234, 10L, 10L))
    }

    @Test fun samePidButNewControllerEpochIsStale() {
        assertFalse(RfFreshness.isFresh(1234, 1234, 1234, 11L, 10L))
    }

    @Test fun newRfProofForNewEpochBecomesFreshAgain() {
        assertTrue(RfFreshness.isFresh(1234, 1234, 1234, 11L, 11L))
    }

    @Test fun processMismatchIsStaleEvenWhenEpochMatches() {
        assertFalse(RfFreshness.isFresh(5678, 5678, 1234, 11L, 11L))
    }

    @Test fun missingControllerEpochCanNeverBeFresh() {
        assertFalse(RfFreshness.isFresh(1234, 1234, 1234, 0L, 0L))
    }

    @Test fun unknownRuntimePidIsAllowedWhenRfPidIsCurrent() {
        assertTrue(RfFreshness.isFresh(1234, 0, 1234, 20L, 20L))
    }
}
