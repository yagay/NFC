package com.yagay.nfcdoorcard.xposed;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NfcHookUtilsTest {
    public static int nativeInt(byte[] payload) { return 0; }
    public static boolean nativeBoolean(byte[] payload) { return false; }

    @Test public void normalizesAndDecodesUid() {
        assertEquals("04A1B2C3", NfcHookUtils.normalizeUid("04:a1-b2 c3"));
        assertArrayEquals(new byte[] { 0x04, (byte) 0xA1, (byte) 0xB2, (byte) 0xC3 },
                NfcHookUtils.hexToBytes("04A1B2C3"));
    }

    @Test public void requiresExactlyOneByteArrayArgument() {
        assertEquals(1, NfcHookUtils.findSingleByteArrayArg(new Object[] { "x", new byte[0] }));
        assertEquals(-1, NfcHookUtils.findSingleByteArrayArg(new Object[] { new byte[0], new byte[0] }));
    }

    @Test public void preservesNativeResultRules() throws Exception {
        Method intMethod = getClass().getMethod("nativeInt", byte[].class);
        Method boolMethod = getClass().getMethod("nativeBoolean", byte[].class);
        assertTrue(NfcHookUtils.interpretNativeResult(intMethod, 0).accepted);
        assertFalse(NfcHookUtils.interpretNativeResult(intMethod, 1).accepted);
        assertTrue(NfcHookUtils.interpretNativeResult(boolMethod, true).accepted);
        assertFalse(NfcHookUtils.interpretNativeResult(boolMethod, false).accepted);
    }
}
