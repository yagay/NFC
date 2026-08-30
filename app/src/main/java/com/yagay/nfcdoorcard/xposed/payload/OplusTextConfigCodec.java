package com.yagay.nfcdoorcard.xposed.payload;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles textual OPLUS_CONF_EXTN wrappers.
 *
 * First use the generic structural NCI codec. If an OEM-private parameter layout prevents
 * full parsing, fall back to the boundary-only append algorithm already proven on the
 * target OxygenOS/NXP stack. The fallback is limited to an explicit OPLUS_CONF_EXTN block
 * and a length-bounded CORE_SET_CONFIG frame; native result=0 remains final proof.
 */
public final class OplusTextConfigCodec implements RfPayloadCodec {
    private static final Pattern OPLUS_BLOCK = Pattern.compile("(?ms)(OPLUS_CONF_EXTN\\s*=\\s*\\{)(.*?)(\\})");
    private static final Pattern HEX_TOKEN = Pattern.compile("(?i)(?<![0-9A-F])([0-9A-F]{2})(?![0-9A-F])");
    private final RawNciCodec raw = new RawNciCodec();

    @Override public String id() { return "oplus-text-config-v3"; }

    @Override public int inspect(byte[] input) {
        if (input == null || input.length == 0) return 0;
        String text = new String(input, StandardCharsets.UTF_8);
        return text.contains("OPLUS_CONF_EXTN") ? 100 : 0;
    }

    @Override public RewriteResult rewrite(byte[] input, byte[] uid) {
        if (input == null || input.length == 0) return RewriteResult.skip(id(), "EMPTY_INPUT");
        if (uid == null || uid.length != 4) return RewriteResult.skip(id(), "UID_NOT_4_BYTES");
        String text = new String(input, StandardCharsets.UTF_8);
        Matcher matcher = OPLUS_BLOCK.matcher(text);
        if (!matcher.find()) return RewriteResult.skip(id(), "OPLUS_CONF_EXTN_NOT_FOUND");

        byte[] block = parseHexTokens(matcher.group(2));
        if (block.length < 4) return RewriteResult.skip(id(), "OPLUS_BLOCK_TOO_SHORT");

        RewriteResult nested = raw.rewrite(block, uid);
        if (!nested.changed) nested = provenOplusAppend(block, uid, nested.reason);
        if (!nested.changed) return RewriteResult.skip(id(), "RAW_NCI:" + nested.reason);

        String replacement = matcher.group(1) + "\n" + formatHexBlock(nested.data) + "\n" + matcher.group(3);
        String rewritten = text.substring(0, matcher.start()) + replacement + text.substring(matcher.end());
        return RewriteResult.changed(id(), nested.reason, rewritten.getBytes(StandardCharsets.UTF_8),
                nested.oldPayloadLength, nested.newPayloadLength,
                nested.oldParamCount, nested.newParamCount);
    }

    private RewriteResult provenOplusAppend(byte[] block, byte[] uid, String strictReason) {
        for (int i = 0; i + 3 < block.length; i++) {
            if ((block[i] & 0xFF) != 0x20 || (block[i + 1] & 0xFF) != 0x02) continue;
            int oldPayload = block[i + 2] & 0xFF;
            int frameEnd = i + 3 + oldPayload;
            if (oldPayload < 1 || frameEnd > block.length) continue;
            int oldCount = block[i + 3] & 0xFF;
            if (oldPayload + 6 > 0xFF || oldCount >= 0xFF) continue;

            byte[] out = new byte[block.length + 6];
            System.arraycopy(block, 0, out, 0, frameEnd);
            out[i + 2] = (byte) (oldPayload + 6);
            out[i + 3] = (byte) (oldCount + 1);
            int p = frameEnd;
            out[p++] = 0x33;
            out[p++] = 0x04;
            System.arraycopy(uid, 0, out, p, 4);
            System.arraycopy(block, frameEnd, out, frameEnd + 6, block.length - frameEnd);
            return RewriteResult.changed(id(), "OPLUS_PROVEN_APPEND_AFTER_" + strictReason, out,
                    oldPayload, oldPayload + 6, oldCount, oldCount + 1);
        }
        return RewriteResult.skip(id(), strictReason + "/CORE_SET_CONFIG_NOT_FOUND");
    }

    private static byte[] parseHexTokens(String body) {
        Matcher m = HEX_TOKEN.matcher(body == null ? "" : body);
        List<Byte> list = new ArrayList<>();
        while (m.find()) list.add((byte) Integer.parseInt(m.group(1), 16));
        byte[] out = new byte[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private static String formatHexBlock(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i % 12 == 0) sb.append("        ");
            sb.append(String.format(Locale.ROOT, "%02X", data[i] & 0xFF));
            if (i != data.length - 1) sb.append(',');
            if (i % 12 == 11 || i == data.length - 1) sb.append('\n');
            else sb.append("  ");
        }
        return sb.toString().stripTrailing();
    }
}
