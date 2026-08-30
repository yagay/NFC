package com.yagay.nfcdoorcard.xposed.payload;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class OplusTextConfigCodecTest {
    private final OplusTextConfigCodec codec = new OplusTextConfigCodec();
    @Test public void supportsSevenByteUidInsideTextWrapper() {
        String input = "OPLUS_CONF_EXTN = { 20,02,04,01,32,01,08 }";
        RewriteResult r = codec.rewrite(input.getBytes(StandardCharsets.UTF_8), hex("04112233445566"));
        assertTrue(r.changed);
        assertTrue(new String(r.data, StandardCharsets.UTF_8).replaceAll("\\s+", "").contains("33,07,04,11,22,33,44,55,66"));
    }
    @Test public void supportsTenByteUidInsideTextWrapper() {
        String input = "OPLUS_CONF_EXTN = { 20,02,04,01,32,01,08 }";
        RewriteResult r = codec.rewrite(input.getBytes(StandardCharsets.UTF_8), hex("0102030405060708090A"));
        assertTrue(r.changed);
        assertTrue(new String(r.data, StandardCharsets.UTF_8).replaceAll("\\s+", "").contains("33,0A,01,02,03,04,05,06,07,08,09,0A"));
    }
    @Test public void prefersBoundedExistingZeroLengthNfcid1BeforeAppend() {
        // Deliberately make the parameter list impossible for the strict parser to fully prove,
        // while keeping one unique bounded stock LA_NFCID1=33 00 inside the declared frame.
        String input = "OPLUS_CONF_EXTN = { 20,02,08,02,33,00,FE,03,11,22,33 }";
        RewriteResult r = codec.rewrite(input.getBytes(StandardCharsets.UTF_8), hex("C1B0BC1B"));
        assertTrue(r.changed);
        assertTrue(r.reason.contains("BOUNDED_RESIZED_EXISTING_LA_NFCID1"));
        String compact = new String(r.data, StandardCharsets.UTF_8).replaceAll("\\s+", "");
        assertTrue(compact.contains("33,04,C1,B0,BC,1B"));
        assertFalse(compact.contains("33,00"));
        assertEquals(r.oldParamCount, r.newParamCount);
    }
    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
