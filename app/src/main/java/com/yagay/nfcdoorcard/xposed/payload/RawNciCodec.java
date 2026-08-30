package com.yagay.nfcdoorcard.xposed.payload;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Generic CORE_SET_CONFIG codec.
 *
 * It prefers in-place replacement of an existing LA_NFCID1 parameter because that leaves
 * the OEM frame shape untouched. Appending a new parameter is allowed only when the whole
 * parameter list is structurally verified.
 */
public final class RawNciCodec implements RfPayloadCodec {
    private static final int CORE_SET_CONFIG_GID_OID_0 = 0x20;
    private static final int CORE_SET_CONFIG_GID_OID_1 = 0x02;
    private static final int LA_NFCID1 = 0x33;

    @Override public String id() { return "raw-nci-core-set-config-v2"; }

    @Override public int inspect(byte[] input) {
        if (input == null || input.length < 4) return 0;
        return findFrames(input).isEmpty() ? 0 : 80;
    }

    @Override public RewriteResult rewrite(byte[] input, byte[] uid) {
        if (input == null || input.length < 4) return RewriteResult.skip(id(), "INPUT_TOO_SHORT");
        if (uid == null || uid.length != 4) return RewriteResult.skip(id(), "UID_NOT_4_BYTES");

        List<Frame> frames = findFrames(input);
        if (frames.isEmpty()) return RewriteResult.skip(id(), "CORE_SET_CONFIG_NOT_FOUND");

        // Best path: update an existing 33 04 value without changing any frame lengths.
        for (Frame frame : frames) {
            Parse parse = parseParams(input, frame);
            if (parse.nfcid1ValueOffset >= 0) {
                byte[] out = Arrays.copyOf(input, input.length);
                System.arraycopy(uid, 0, out, parse.nfcid1ValueOffset, 4);
                return RewriteResult.changed(id(), "REPLACED_EXISTING_LA_NFCID1", out,
                        frame.payloadLength, frame.payloadLength, frame.paramCount, frame.paramCount);
            }
        }

        // Append only to a fully verified standard parameter list. Unknown OEM tails are
        // intentionally left untouched rather than guessed.
        for (Frame frame : frames) {
            Parse parse = parseParams(input, frame);
            if (!parse.complete) continue;
            if (frame.payloadLength + 6 > 0xFF || frame.paramCount >= 0xFF) continue;

            byte[] out = new byte[input.length + 6];
            System.arraycopy(input, 0, out, 0, frame.end);
            out[frame.start + 2] = (byte) (frame.payloadLength + 6);
            out[frame.start + 3] = (byte) (frame.paramCount + 1);
            int p = frame.end;
            out[p++] = 0x33;
            out[p++] = 0x04;
            System.arraycopy(uid, 0, out, p, 4);
            System.arraycopy(input, frame.end, out, frame.end + 6, input.length - frame.end);
            return RewriteResult.changed(id(), "APPENDED_LA_NFCID1", out,
                    frame.payloadLength, frame.payloadLength + 6,
                    frame.paramCount, frame.paramCount + 1);
        }

        return RewriteResult.skip(id(), "NO_SAFE_REWRITE_TARGET");
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
        int nfcid1Offset = -1;
        for (int n = 0; n < frame.paramCount; n++) {
            if (pos >= frame.end) return new Parse(false, nfcid1Offset);
            int first = data[pos] & 0xFF;
            int id;
            int lenPos;
            if (first == 0xA0) {
                if (pos + 2 >= frame.end) return new Parse(false, nfcid1Offset);
                id = (first << 8) | (data[pos + 1] & 0xFF);
                lenPos = pos + 2;
            } else {
                if (pos + 1 >= frame.end) return new Parse(false, nfcid1Offset);
                id = first;
                lenPos = pos + 1;
            }
            int len = data[lenPos] & 0xFF;
            int value = lenPos + 1;
            if (value + len > frame.end) return new Parse(false, nfcid1Offset);
            if (id == LA_NFCID1 && len == 4) nfcid1Offset = value;
            pos = value + len;
        }
        return new Parse(pos == frame.end, nfcid1Offset);
    }

    private static final class Frame {
        final int start, end, payloadLength, paramCount;
        Frame(int start, int end, int payloadLength, int paramCount) {
            this.start = start;
            this.end = end;
            this.payloadLength = payloadLength;
            this.paramCount = paramCount;
        }
    }

    private static final class Parse {
        final boolean complete;
        final int nfcid1ValueOffset;
        Parse(boolean complete, int nfcid1ValueOffset) {
            this.complete = complete;
            this.nfcid1ValueOffset = nfcid1ValueOffset;
        }
    }
}
