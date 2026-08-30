package com.yagay.nfcdoorcard.xposed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RfSettlingPolicyTest {
    @Test public void requiresMinimumStartupSettleTime() {
        assertFalse(RfSettlingPolicy.isSettled(1_900L, 1_000L, 1_100L, 1_200L, 700L));
        assertTrue(RfSettlingPolicy.isSettled(2_200L, 1_000L, 1_100L, 1_200L, 700L));
    }

    @Test public void requiresQuietPeriodAfterLateRfWrite() {
        assertFalse(RfSettlingPolicy.isSettled(3_000L, 1_000L, 2_600L, 1_200L, 700L));
        assertTrue(RfSettlingPolicy.isSettled(3_300L, 1_000L, 2_600L, 1_200L, 700L));
    }

    @Test public void noObservedWriteCanSettleAfterMinimumWindow() {
        assertTrue(RfSettlingPolicy.isSettled(2_500L, 1_000L, 0L, 1_200L, 700L));
    }

    @Test public void finalProofRequiresStrictlyNewSequence() {
        assertFalse(RfSettlingPolicy.isStrictlyNewer(5L, 5L));
        assertFalse(RfSettlingPolicy.isStrictlyNewer(4L, 5L));
        assertTrue(RfSettlingPolicy.isStrictlyNewer(6L, 5L));
    }
}
