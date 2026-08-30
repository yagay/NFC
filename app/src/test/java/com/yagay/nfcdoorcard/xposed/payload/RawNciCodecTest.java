package com.yagay.nfcdoorcard.xposed.payload;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

public class RawNciCodecTest {
    private static final byte[] UID = new byte[] { (byte) 0xC1, (byte) 0xB0, (byte) 0xBC, 0x1B };
    private final RawNciCodec codec = new RawNciCodec();

    @Test public void replacesExistingNfcid1WithoutChangingFrameShape() {
        byte[] input = hex("20020701330411223344");
        byte[] original = input.clone();
        RewriteResult r = codec.rewrite(input, UID);

        assertTrue(r.changed);
        assertEquals("REPLACED_EXISTING_LA_NFCID1", r.reason);
        assertEquals(input.length, r.data.length);
        assertEquals(7, r.oldPayloadLength);
        assertEquals(7, r.newPayloadLength);
        assertEquals(1, r.oldParamCount);
        assertEquals(1, r.newParamCount);
        assertArrayEquals(hex("200207013304C1B0BC1B"), r.data);
        assertArrayEquals("input must never be mutated", original, input);
    }

    @Test public void resizesZeroLengthStockNfcid1InsteadOfAppendingDuplicate() {
        byte[] input = hex("200203013300");
        RewriteResult r = codec.rewrite(input, UID);

        assertTrue(r.changed);
        assertEquals("RESIZED_EXISTING_LA_NFCID1", r.reason);
        assertArrayEquals(hex("200207013304C1B0BC1B"), r.data);
        assertEquals(3, r.oldPayloadLength);
        assertEquals(7, r.newPayloadLength);
        assertEquals(1, r.oldParamCount);
        assertEquals(1, r.newParamCount);
    }

    @Test public void zeroLengthStockNfcid1SupportsSevenAndTenByteUid() {
        RewriteResult r7 = codec.rewrite(hex("200203013300"), hex("04112233445566"));
        assertTrue(r7.changed);
        assertEquals("RESIZED_EXISTING_LA_NFCID1", r7.reason);
        assertArrayEquals(hex("20020A01330704112233445566"), r7.data);

        RewriteResult r10 = codec.rewrite(hex("200203013300"), hex("0102030405060708090A"));
        assertTrue(r10.changed);
        assertEquals("RESIZED_EXISTING_LA_NFCID1", r10.reason);
        assertArrayEquals(hex("20020D01330A0102030405060708090A"), r10.data);
    }

    @Test public void appendsNfcid1OnlyToCompleteParameterList() {
        byte[] input = hex("20020401320108");
        RewriteResult r = codec.rewrite(input, UID);

        assertTrue(r.changed);
        assertEquals("APPENDED_LA_NFCID1", r.reason);
        assertArrayEquals(hex("20020A023201083304C1B0BC1B"), r.data);
        assertEquals(4, r.oldPayloadLength);
        assertEquals(10, r.newPayloadLength);
        assertEquals(1, r.oldParamCount);
        assertEquals(2, r.newParamCount);
    }

    @Test public void unsupportedExistingNfcid1LengthIsNotDuplicated() {
        RewriteResult r = codec.rewrite(hex("200206013303010203"), UID);
        assertFalse(r.changed);
        assertEquals("NO_SAFE_REWRITE_TARGET", r.reason);
    }

    @Test public void understandsExtendedA0ParameterIds() {
        byte[] input = hex("20020501A001017F");
        RewriteResult r = codec.rewrite(input, UID);

        assertTrue(r.changed);
        assertEquals("APPENDED_LA_NFCID1", r.reason);
        assertArrayEquals(hex("20020B02A001017F3304C1B0BC1B"), r.data);
    }

    @Test public void rejectsDeclaredPayloadThatRunsPastBuffer() {
        RewriteResult r = codec.rewrite(hex("20020A01320108"), UID);
        assertFalse(r.changed);
        assertEquals("CORE_SET_CONFIG_NOT_FOUND", r.reason);
    }

    @Test public void rejectsIncompleteParameterCount() {
        RewriteResult r = codec.rewrite(hex("20020402320108"), UID);
        assertFalse(r.changed);
        assertEquals("NO_SAFE_REWRITE_TARGET", r.reason);
    }

    @Test public void rejectsTruncatedExtendedParameter() {
        RewriteResult r = codec.rewrite(hex("20020301A001"), UID);
        assertFalse(r.changed);
        assertEquals("NO_SAFE_REWRITE_TARGET", r.reason);
    }

    @Test public void multipleFramesPreferExistingNfcid1OverAppending() {
        byte[] first = hex("20020401320108");
        byte[] second = hex("20020701330455667788");
        byte[] input = concat(hex("010203"), first, second);
        RewriteResult r = codec.rewrite(input, UID);

        assertTrue(r.changed);
        assertEquals("REPLACED_EXISTING_LA_NFCID1", r.reason);
        byte[] expected = concat(hex("010203"), first, hex("200207013304C1B0BC1B"));
        assertArrayEquals(expected, r.data);
    }

    @Test public void appendPreservesTrailingNonNciBytes() {
        byte[] tail = hex("7E7D7C01020304");
        byte[] input = concat(hex("20020401320108"), tail);
        RewriteResult r = codec.rewrite(input, UID);

        assertTrue(r.changed);
        assertEquals("APPENDED_LA_NFCID1", r.reason);
        assertArrayEquals(concat(hex("20020A023201083304C1B0BC1B"), tail), r.data);
    }

    @Test public void supportsSevenByteUidAppendAndResize() {
        byte[] uid7 = hex("04112233445566");
        RewriteResult appended = codec.rewrite(hex("20020401320108"), uid7);
        assertTrue(appended.changed);
        assertArrayEquals(hex("20020D02320108330704112233445566"), appended.data);
        RewriteResult resized = codec.rewrite(hex("20020701330411223344"), uid7);
        assertTrue(resized.changed);
        assertEquals("RESIZED_EXISTING_LA_NFCID1", resized.reason);
        assertArrayEquals(hex("20020A01330704112233445566"), resized.data);
    }

    @Test public void supportsTenByteUidAppendAndResize() {
        byte[] uid10 = hex("0102030405060708090A");
        RewriteResult appended = codec.rewrite(hex("20020401320108"), uid10);
        assertTrue(appended.changed);
        assertArrayEquals(hex("20021002320108330A0102030405060708090A"), appended.data);
        RewriteResult resized = codec.rewrite(hex("20020A01330704112233445566"), uid10);
        assertTrue(resized.changed);
        assertArrayEquals(hex("20020D01330A0102030405060708090A"), resized.data);
    }

    @Test public void canShrinkLongUidWhenFrameIsFullyVerified() {
        RewriteResult r = codec.rewrite(hex("20020D01330A0102030405060708090A"), UID);
        assertTrue(r.changed);
        assertEquals("RESIZED_EXISTING_LA_NFCID1", r.reason);
        assertArrayEquals(hex("200207013304C1B0BC1B"), r.data);
    }

    @Test public void invalidUidIsRejected() {
        RewriteResult r = codec.rewrite(hex("20020401320108"), new byte[] { 1, 2, 3 });
        assertFalse(r.changed);
        assertEquals("UID_LENGTH_NOT_4_7_10_BYTES", r.reason);
    }

    @Test public void shortAndNullInputsAreSafe() {
        assertFalse(codec.rewrite(null, UID).changed);
        assertFalse(codec.rewrite(new byte[0], UID).changed);
        assertFalse(codec.rewrite(new byte[] { 0x20, 0x02, 0x01 }, UID).changed);
        assertEquals(0, codec.inspect(null));
        assertEquals(0, codec.inspect(new byte[3]));
    }

    @Test public void randomInputsNeverThrowOrMutateInput() {
        Random random = new Random(0x4E46434CL);
        for (int i = 0; i < 10_000; i++) {
            byte[] input = new byte[random.nextInt(129)];
            random.nextBytes(input);
            byte[] before = input.clone();

            int score = codec.inspect(input);
            assertTrue(score >= 0);
            RewriteResult r = codec.rewrite(input, UID);
            assertNotNull(r);
            assertArrayEquals("codec mutated input at iteration " + i, before, input);
            if (r.changed) {
                assertNotNull(r.data);
                assertTrue(r.data.length > 0);
                assertTrue(r.newPayloadLength > 0);
                assertTrue(r.newParamCount > 0);
            }
        }
    }

    private static byte[] hex(String s) {
        String clean = s.replaceAll("[^0-9A-Fa-f]", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] concat(byte[]... arrays) {
        int len = Arrays.stream(arrays).mapToInt(a -> a.length).sum();
        byte[] out = new byte[len];
        int p = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, p, a.length);
            p += a.length;
        }
        return out;
    }
}
