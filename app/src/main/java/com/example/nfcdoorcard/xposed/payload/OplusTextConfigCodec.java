package com.example.nfcdoorcard.xposed.payload;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Handles textual OPLUS_CONF_EXTN wrappers while delegating NCI semantics to RawNciCodec. */
public final class OplusTextConfigCodec implements RfPayloadCodec {
    private static final Pattern OPLUS_BLOCK = Pattern.compile("(?ms)(OPLUS_CONF_EXTN\\s*=\\s*\\{)(.*?)(\\})");
    private static final Pattern HEX_TOKEN = Pattern.compile("(?i)(?<![0-9A-F])([0-9A-F]{2})(?![0-9A-F])");
    private final RawNciCodec raw = new RawNciCodec();

    @Override public String id() { return "oplus-text-config-v2"; }

    @Override public int inspect(byte[] input) {
        if (input == null || input.length == 0) return 0;
        String text = new String(input, StandardCharsets.UTF_8);
        return text.contains("OPLUS_CONF_EXTN") ? 100 : 0;
    }

    @Override public RewriteResult rewrite(byte[] input, byte[] uid) {
        if (input == null || input.length == 0) return RewriteResult.skip(id(), "EMPTY_INPUT");
        String text = new String(input, StandardCharsets.UTF_8);
        Matcher matcher = OPLUS_BLOCK.matcher(text);
        if (!matcher.find()) return RewriteResult.skip(id(), "OPLUS_CONF_EXTN_NOT_FOUND");

        byte[] block = parseHexTokens(matcher.group(2));
        if (block.length < 4) return RewriteResult.skip(id(), "OPLUS_BLOCK_TOO_SHORT");
        RewriteResult nested = raw.rewrite(block, uid);
        if (!nested.changed) return RewriteResult.skip(id(), "RAW_NCI:" + nested.reason);

        String replacement = matcher.group(1) + "\n" + formatHexBlock(nested.data) + "\n" + matcher.group(3);
        String rewritten = text.substring(0, matcher.start()) + replacement + text.substring(matcher.end());
        return RewriteResult.changed(id(), nested.reason, rewritten.getBytes(StandardCharsets.UTF_8),
                nested.oldPayloadLength, nested.newPayloadLength,
                nested.oldParamCount, nested.newParamCount);
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
