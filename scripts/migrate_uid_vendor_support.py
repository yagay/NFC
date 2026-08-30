from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

raw = ROOT / 'app/src/main/java/com/yagay/nfcdoorcard/xposed/payload/RawNciCodec.java'
raw.write_text(r'''package com.yagay.nfcdoorcard.xposed.payload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Safe generic CORE_SET_CONFIG / LA_NFCID1 codec for 4, 7 and 10 byte UIDs. */
public final class RawNciCodec implements RfPayloadCodec {
    private static final int CORE_SET_CONFIG_GID_OID_0 = 0x20;
    private static final int CORE_SET_CONFIG_GID_OID_1 = 0x02;
    private static final int LA_NFCID1 = 0x33;

    @Override public String id() { return "raw-nci-core-set-config-v3"; }

    @Override public int inspect(byte[] input) {
        if (input == null || input.length < 4) return 0;
        return findFrames(input).isEmpty() ? 0 : 80;
    }

    @Override public RewriteResult rewrite(byte[] input, byte[] uid) {
        if (input == null || input.length < 4) return RewriteResult.skip(id(), "INPUT_TOO_SHORT");
        if (!isSupportedUid(uid)) return RewriteResult.skip(id(), "UID_LENGTH_NOT_4_7_10_BYTES");

        List<Frame> frames = findFrames(input);
        if (frames.isEmpty()) return RewriteResult.skip(id(), "CORE_SET_CONFIG_NOT_FOUND");

        for (Frame frame : frames) {
            Parse parse = parseParams(input, frame);
            if (parse.nfcid1ValueOffset < 0) continue;
            if (parse.nfcid1Length == uid.length) {
                byte[] out = Arrays.copyOf(input, input.length);
                System.arraycopy(uid, 0, out, parse.nfcid1ValueOffset, uid.length);
                return RewriteResult.changed(id(), "REPLACED_EXISTING_LA_NFCID1", out,
                        frame.payloadLength, frame.payloadLength, frame.paramCount, frame.paramCount);
            }
            if (!parse.complete) continue;
            int delta = uid.length - parse.nfcid1Length;
            int newPayload = frame.payloadLength + delta;
            if (newPayload < 1 || newPayload > 0xFF) continue;

            byte[] out = new byte[input.length + delta];
            System.arraycopy(input, 0, out, 0, parse.nfcid1ValueOffset);
            out[parse.nfcid1LengthOffset] = (byte) uid.length;
            System.arraycopy(uid, 0, out, parse.nfcid1ValueOffset, uid.length);
            int oldTail = parse.nfcid1ValueOffset + parse.nfcid1Length;
            int newTail = parse.nfcid1ValueOffset + uid.length;
            System.arraycopy(input, oldTail, out, newTail, input.length - oldTail);
            out[frame.start + 2] = (byte) newPayload;
            return RewriteResult.changed(id(), "RESIZED_EXISTING_LA_NFCID1", out,
                    frame.payloadLength, newPayload, frame.paramCount, frame.paramCount);
        }

        for (Frame frame : frames) {
            Parse parse = parseParams(input, frame);
            if (!parse.complete) continue;
            int added = 2 + uid.length;
            if (frame.payloadLength + added > 0xFF || frame.paramCount >= 0xFF) continue;

            byte[] out = new byte[input.length + added];
            System.arraycopy(input, 0, out, 0, frame.end);
            out[frame.start + 2] = (byte) (frame.payloadLength + added);
            out[frame.start + 3] = (byte) (frame.paramCount + 1);
            int p = frame.end;
            out[p++] = 0x33;
            out[p++] = (byte) uid.length;
            System.arraycopy(uid, 0, out, p, uid.length);
            System.arraycopy(input, frame.end, out, frame.end + added, input.length - frame.end);
            return RewriteResult.changed(id(), "APPENDED_LA_NFCID1", out,
                    frame.payloadLength, frame.payloadLength + added,
                    frame.paramCount, frame.paramCount + 1);
        }
        return RewriteResult.skip(id(), "NO_SAFE_REWRITE_TARGET");
    }

    static boolean isSupportedUid(byte[] uid) {
        return uid != null && (uid.length == 4 || uid.length == 7 || uid.length == 10);
    }

    private static List<Frame> findFrames(byte[] data) {
        List<Frame> out = new ArrayList<>();
        for (int i = 0; i + 3 < data.length; i++) {
            if ((data[i] & 0xFF) != CORE_SET_CONFIG_GID_OID_0 ||
                    (data[i + 1] & 0xFF) != CORE_SET_CONFIG_GID_OID_1) continue;
            int payload = data[i + 2] & 0xFF;
            int end = i + 3 + payload;
            if (payload < 1 || end > data.length) continue;
            out.add(new Frame(i, end, payload, data[i + 3] & 0xFF));
            i = Math.max(i, end - 1);
        }
        return out;
    }

    private static Parse parseParams(byte[] data, Frame frame) {
        int pos = frame.start + 4;
        int valueOffset = -1, lengthOffset = -1, nfcid1Length = -1;
        for (int n = 0; n < frame.paramCount; n++) {
            if (pos >= frame.end) return new Parse(false, valueOffset, lengthOffset, nfcid1Length);
            int first = data[pos] & 0xFF;
            int id, lenPos;
            if (first == 0xA0) {
                if (pos + 2 >= frame.end) return new Parse(false, valueOffset, lengthOffset, nfcid1Length);
                id = (first << 8) | (data[pos + 1] & 0xFF); lenPos = pos + 2;
            } else {
                if (pos + 1 >= frame.end) return new Parse(false, valueOffset, lengthOffset, nfcid1Length);
                id = first; lenPos = pos + 1;
            }
            int len = data[lenPos] & 0xFF;
            int value = lenPos + 1;
            if (value + len > frame.end) return new Parse(false, valueOffset, lengthOffset, nfcid1Length);
            if (id == LA_NFCID1 && (len == 4 || len == 7 || len == 10)) {
                valueOffset = value; lengthOffset = lenPos; nfcid1Length = len;
            }
            pos = value + len;
        }
        return new Parse(pos == frame.end, valueOffset, lengthOffset, nfcid1Length);
    }

    private static final class Frame {
        final int start, end, payloadLength, paramCount;
        Frame(int start, int end, int payloadLength, int paramCount) {
            this.start = start; this.end = end; this.payloadLength = payloadLength; this.paramCount = paramCount;
        }
    }
    private static final class Parse {
        final boolean complete; final int nfcid1ValueOffset, nfcid1LengthOffset, nfcid1Length;
        Parse(boolean complete, int valueOffset, int lengthOffset, int length) {
            this.complete = complete; this.nfcid1ValueOffset = valueOffset;
            this.nfcid1LengthOffset = lengthOffset; this.nfcid1Length = length;
        }
    }
}
''')

op = ROOT / 'app/src/main/java/com/yagay/nfcdoorcard/xposed/payload/OplusTextConfigCodec.java'
s = op.read_text()
s = s.replace('if (uid == null || uid.length != 4) return RewriteResult.skip(id(), "UID_NOT_4_BYTES");',
              'if (!RawNciCodec.isSupportedUid(uid)) return RewriteResult.skip(id(), "UID_LENGTH_NOT_4_7_10_BYTES");')
s = s.replace('if (oldPayload + 6 > 0xFF || oldCount >= 0xFF) continue;\n\n            byte[] out = new byte[block.length + 6];',
              'int added = 2 + uid.length;\n            if (oldPayload + added > 0xFF || oldCount >= 0xFF) continue;\n\n            byte[] out = new byte[block.length + added];')
s = s.replace('out[i + 2] = (byte) (oldPayload + 6);', 'out[i + 2] = (byte) (oldPayload + added);')
s = s.replace('out[p++] = 0x04;\n            System.arraycopy(uid, 0, out, p, 4);\n            System.arraycopy(block, frameEnd, out, frameEnd + 6, block.length - frameEnd);',
              'out[p++] = (byte) uid.length;\n            System.arraycopy(uid, 0, out, p, uid.length);\n            System.arraycopy(block, frameEnd, out, frameEnd + added, block.length - frameEnd);')
s = s.replace('oldPayload, oldPayload + 6, oldCount, oldCount + 1);', 'oldPayload, oldPayload + added, oldCount, oldCount + 1);')
op.write_text(s)

mod = ROOT / 'app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java'
s = mod.read_text()
old = 'if (uidHex.length() != 8) {\n                failCommand(cfg, "UID_INVALID", uidHex, "UID must be 4 bytes", NativeOutcome.notInvoked());'
new = 'if (uidHex.length() != 8 && uidHex.length() != 14 && uidHex.length() != 20) {\n                failCommand(cfg, "UID_INVALID", uidHex, "UID must be 4, 7 or 10 bytes", NativeOutcome.notInvoked());'
if old not in s:
    raise SystemExit('NfcInjectionModule UID guard not found')
mod.write_text(s.replace(old, new))

disc = ROOT / 'app/src/main/java/com/yagay/nfcdoorcard/xposed/discovery/HookDiscoveryEngine.java'
s = disc.read_text()
s = s.replace('private static final String[] PROVEN_RF_CLASSES = new String[] {\n            "com.android.nfc.dhimpl.NxpNativeNfcManager",',
              'private static final String[] PROVEN_RF_CLASSES = new String[] {\n            "com.android.nfc.dhimpl.NativeNfcManager",\n            "com.android.nfc.dhimpl.StNativeNfcManager",\n            "com.android.nfc.dhimpl.NxpNativeNfcManager",')
s = s.replace('if (cn.contains("nxp")) score += 20;', 'if (containsKnownVendorToken(cn)) score += 20;')
s = s.replace('if (cn.contains("vendor") || cn.contains("oplus") || cn.contains("nxp")) score += 15;',
              'if (cn.contains("vendor") || cn.contains("oplus") || containsKnownVendorToken(cn)) score += 15;')
s = s.replace('return n.contains("nfc") || n.contains("nxp") || n.contains("oplus") || n.contains("rfconfig") ||\n                n.contains("devicehost") || n.contains("nci") || n.contains("native") || n.contains("hal");\n    }',
              'return n.contains("nfc") || containsKnownVendorToken(n) || n.contains("oplus") || n.contains("rfconfig") ||\n                n.contains("devicehost") || n.contains("nci") || n.contains("native") || n.contains("hal");\n    }\n\n    private static boolean containsKnownVendorToken(String value) {\n        if (value == null) return false;\n        String n = value.toLowerCase(Locale.ROOT);\n        return n.contains("nxp") || n.contains("st21") || n.contains("st54") || n.contains("stmicro") ||\n                n.contains("broadcom") || n.contains("brcm") || n.contains("bcm") ||\n                n.contains("tsingteng") || n.contains("tsing");\n    }')
disc.write_text(s)

test = ROOT / 'app/src/test/java/com/yagay/nfcdoorcard/xposed/payload/RawNciCodecTest.java'
s = test.read_text()
old_test = '''    @Test public void invalidUidIsRejected() {\n        RewriteResult r = codec.rewrite(hex("20020401320108"), new byte[] { 1, 2, 3 });\n        assertFalse(r.changed);\n        assertEquals("UID_NOT_4_BYTES", r.reason);\n    }'''
new_test = '''    @Test public void supportsSevenByteUidAppendAndResize() {\n        byte[] uid7 = hex("04112233445566");\n        RewriteResult appended = codec.rewrite(hex("20020401320108"), uid7);\n        assertTrue(appended.changed);\n        assertArrayEquals(hex("20020D02320108330704112233445566"), appended.data);\n        RewriteResult resized = codec.rewrite(hex("20020701330411223344"), uid7);\n        assertTrue(resized.changed);\n        assertEquals("RESIZED_EXISTING_LA_NFCID1", resized.reason);\n        assertArrayEquals(hex("20020A01330704112233445566"), resized.data);\n    }\n\n    @Test public void supportsTenByteUidAppendAndResize() {\n        byte[] uid10 = hex("0102030405060708090A");\n        RewriteResult appended = codec.rewrite(hex("20020401320108"), uid10);\n        assertTrue(appended.changed);\n        assertArrayEquals(hex("20021002320108330A0102030405060708090A"), appended.data);\n        RewriteResult resized = codec.rewrite(hex("20020A01330704112233445566"), uid10);\n        assertTrue(resized.changed);\n        assertArrayEquals(hex("20020D01330A0102030405060708090A"), resized.data);\n    }\n\n    @Test public void canShrinkLongUidWhenFrameIsFullyVerified() {\n        RewriteResult r = codec.rewrite(hex("20020D01330A0102030405060708090A"), UID);\n        assertTrue(r.changed);\n        assertEquals("RESIZED_EXISTING_LA_NFCID1", r.reason);\n        assertArrayEquals(hex("200207013304C1B0BC1B"), r.data);\n    }\n\n    @Test public void invalidUidIsRejected() {\n        RewriteResult r = codec.rewrite(hex("20020401320108"), new byte[] { 1, 2, 3 });\n        assertFalse(r.changed);\n        assertEquals("UID_LENGTH_NOT_4_7_10_BYTES", r.reason);\n    }'''
if old_test not in s:
    raise SystemExit('RawNciCodecTest guard not found')
test.write_text(s.replace(old_test, new_test))

op_test = ROOT / 'app/src/test/java/com/yagay/nfcdoorcard/xposed/payload/OplusTextConfigCodecTest.java'
op_test.write_text('''package com.yagay.nfcdoorcard.xposed.payload;\n\nimport static org.junit.Assert.*;\nimport java.nio.charset.StandardCharsets;\nimport org.junit.Test;\n\npublic class OplusTextConfigCodecTest {\n    private final OplusTextConfigCodec codec = new OplusTextConfigCodec();\n    @Test public void supportsSevenByteUidInsideTextWrapper() {\n        String input = "OPLUS_CONF_EXTN = { 20,02,04,01,32,01,08 }";\n        RewriteResult r = codec.rewrite(input.getBytes(StandardCharsets.UTF_8), hex("04112233445566"));\n        assertTrue(r.changed);\n        assertTrue(new String(r.data, StandardCharsets.UTF_8).replaceAll("\\\\s+", "").contains("33,07,04,11,22,33,44,55,66"));\n    }\n    @Test public void supportsTenByteUidInsideTextWrapper() {\n        String input = "OPLUS_CONF_EXTN = { 20,02,04,01,32,01,08 }";\n        RewriteResult r = codec.rewrite(input.getBytes(StandardCharsets.UTF_8), hex("0102030405060708090A"));\n        assertTrue(r.changed);\n        assertTrue(new String(r.data, StandardCharsets.UTF_8).replaceAll("\\\\s+", "").contains("33,0A,01,02,03,04,05,06,07,08,09,0A"));\n    }\n    private static byte[] hex(String s) {\n        byte[] out = new byte[s.length() / 2];\n        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);\n        return out;\n    }\n}\n''')

gradle = ROOT / 'app/build.gradle.kts'
s = gradle.read_text()
s = s.replace('versionCode = 40', 'versionCode = 41')
s = s.replace('versionName = "1.0.39"', 'versionName = "1.0.40"')
s = s.replace('hook build 27.', 'hook build 28; safe 4/7/10-byte NFCID1 and vendor-neutral NCI discovery.')
s = s.replace('buildConfigField("int", "HOOK_BUILD", "27")', 'buildConfigField("int", "HOOK_BUILD", "28")')
gradle.write_text(s)
