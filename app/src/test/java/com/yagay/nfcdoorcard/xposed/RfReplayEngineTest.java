package com.yagay.nfcdoorcard.xposed;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import org.junit.Test;

public class RfReplayEngineTest {
    public static final class Receiver {
        byte[] seen;
        public int apply(byte[] payload) {
            seen = payload;
            return 0;
        }
    }

    @Test public void snapshotDeepCopiesByteArraysAndInvokes() throws Exception {
        RfReplayEngine engine = new RfReplayEngine();
        Receiver receiver = new Receiver();
        Method method = Receiver.class.getDeclaredMethod("apply", byte[].class);
        byte[] payload = new byte[] {1, 2, 3};
        RfReplayEngine.Snapshot snapshot = engine.captureVerified(
                method, receiver, new Object[]{payload}, "target", 10L, 123);
        payload[0] = 9;

        assertTrue(engine.hasVerified(123));
        assertFalse(engine.hasVerified(124));
        assertTrue(engine.invoke(snapshot).invoked);
        assertArrayEquals(new byte[]{1, 2, 3}, receiver.seen);
    }

    @Test public void pendingCanBeClearedByMatchingFingerprint() throws Exception {
        RfReplayEngine engine = new RfReplayEngine();
        Receiver receiver = new Receiver();
        Method method = Receiver.class.getDeclaredMethod("apply", byte[].class);
        engine.capturePending(method, receiver, new Object[]{new byte[]{1}}, "a", 1L, 5);
        engine.clearPending("b");
        assertNotNull(engine.pending());
        engine.clearPending("a");
        assertNull(engine.pending());
    }
}
