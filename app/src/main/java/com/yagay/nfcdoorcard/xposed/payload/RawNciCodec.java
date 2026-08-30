package com.yagay.nfcdoorcard.xposed.payload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Safe generic CORE_SET_CONFIG / LA_NFCID1 codec for 4, 7 and 10 byte UIDs.
 *
 * Android/NXP stock configurations may explicitly contain LA_NFCID1 with length 0 (33 00),
 * meaning the controller is free to use its default/random NFCID1. That is still an existing
 * parameter and is the preferred rewrite target: resize it to 4/7/10 bytes instead of appending
 * a second LA_NFCID1. Because the complete parameter list is verified before a length-changing
 * rewrite, the original payload can later be restored safely.
 */
public final class RawNciCodec implements RfPayloadCodec {
    private static final int CORE_SET_CONFIG_GID_OID_0 = 0x20;
    private static final int CORE_SET_CONFIG_GID_OID_1 = 0x02;
    private static final int LA_NFCID1 = 0x33;

    @Override public String id() { return "raw-nci-core-set-config-v4"; }

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

        // Append only when the fully parsed frame truly has no LA_NFCID1 parameter at all.
        for (Frame frame : frames) {
            Parse parse = parseParams(input, frame);
            if (!parse.complete || parse.sawNfcid1) continue;
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

    private static boolean isSupportedExistingNfcid1Length(int len) {
        return len == 0 || len == 4 || len == 7 || len == 10;
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
        boolean sawNfcid1 = false;
        for (int n = 0; n < frame.paramCount; n++) {
            if (pos >= frame.end) return new Parse(false, sawNfcid1, valueOffset, lengthOffset, nfcid1Length);
            int first = data[pos] & 0xFF;
            int id, lenPos;
            if (first == 0xA0) {
                if (pos + 2 >= frame.end) return new Parse(false, sawNfcid1, valueOffset, lengthOffset, nfcid1Length);
                id = (first << 8) | (data[pos + 1] & 0xFF); lenPos = pos + 2;
            } else {
                if (pos + 1 >= frame.end) return new Parse(false, sawNfcid1, valueOffset, lengthOffset, nfcid1Length);
                id = first; lenPos = pos + 1;
            }
            int len = data[lenPos] & 0xFF;
            int value = lenPos + 1;
            if (value + len > frame.end) return new Parse(false, sawNfcid1, valueOffset, lengthOffset, nfcid1Length);
            if (id == LA_NFCID1) {
                sawNfcid1 = true;
                if (isSupportedExistingNfcid1Length(len)) {
                    valueOffset = value;
                    lengthOffset = lenPos;
                    nfcid1Length = len;
                }
            }
            pos = value + len;
        }
        return new Parse(pos == frame.end, sawNfcid1, valueOffset, lengthOffset, nfcid1Length);
    }

    private static final class Frame {
        final int start, end, payloadLength, paramCount;
        Frame(int start, int end, int payloadLength, int paramCount) {
            this.start = start; this.end = end; this.payloadLength = payloadLength; this.paramCount = paramCount;
        }
    }
    private static final class Parse {
        final boolean complete, sawNfcid1;
        final int nfcid1ValueOffset, nfcid1LengthOffset, nfcid1Length;
        Parse(boolean complete, boolean sawNfcid1, int valueOffset, int lengthOffset, int length) {
            this.complete = complete;
            this.sawNfcid1 = sawNfcid1;
            this.nfcid1ValueOffset = valueOffset;
            this.nfcid1LengthOffset = lengthOffset;
            this.nfcid1Length = length;
        }
    }
}
