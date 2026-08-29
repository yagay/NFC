package com.example.nfcdoorcard.xposed.adapter;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OplusNxpAdapter implements NfcStackAdapter {
    private static final String MANAGER_CLASS = "com.android.nfc.dhimpl.NxpNativeNfcManager";
    private static final String INJECTION_METHOD = "changeRfParamsByConfig";
    private static final Pattern OPLUS_BLOCK = Pattern.compile("(?ms)(OPLUS_CONF_EXTN\\s*=\\s*\\{)(.*?)(\\})");
    private static final Pattern HEX_TOKEN = Pattern.compile("(?i)(?<![0-9A-F])([0-9A-F]{2})(?![0-9A-F])");

    @Override
    public String id() {
        return "oplus-nxp-v1";
    }

    @Override
    public Detection detect(ClassLoader classLoader) {
        try {
            Class<?> manager = Class.forName(MANAGER_CLASS, false, classLoader);
            Method method = manager.getDeclaredMethod(INJECTION_METHOD, byte[].class);
            if (method.getReturnType() == Void.TYPE) {
                return Detection.unsupported("changeRfParamsByConfig has unexpected void return type");
            }
            return Detection.supported(MANAGER_CLASS + "#" + INJECTION_METHOD + "(byte[])");
        } catch (Throwable t) {
            return Detection.unsupported(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Override
    public Method resolveInjectionMethod(ClassLoader classLoader) throws Exception {
        Class<?> manager = Class.forName(MANAGER_CLASS, false, classLoader);
        return manager.getDeclaredMethod(INJECTION_METHOD, byte[].class);
    }

    @Override
    public InjectionResult inject(byte[] input, byte[] uid) {
        if (input == null || input.length == 0) return InjectionResult.skip("EMPTY_INPUT");
        if (uid == null || uid.length != 4) return InjectionResult.skip("UID_NOT_4_BYTES");

        String text = new String(input, StandardCharsets.UTF_8);
        Matcher matcher = OPLUS_BLOCK.matcher(text);
        if (!matcher.find()) return InjectionResult.skip("OPLUS_CONF_EXTN_NOT_FOUND");

        byte[] block = parseHexTokens(matcher.group(2));
        if (block.length < 4) return InjectionResult.skip("OPLUS_BLOCK_TOO_SHORT");

        int frameStart = -1;
        int frameEnd = -1;
        for (int i = 0; i + 3 < block.length; i++) {
            if ((block[i] & 0xFF) == 0x20 && (block[i + 1] & 0xFF) == 0x02) {
                int payloadLen = block[i + 2] & 0xFF;
                int end = i + 3 + payloadLen;
                if (end <= block.length) {
                    frameStart = i;
                    frameEnd = end;
                    break;
                }
            }
        }
        if (frameStart < 0) return InjectionResult.skip("CORE_SET_CONFIG_NOT_FOUND");

        byte[] frame = Arrays.copyOfRange(block, frameStart, frameEnd);
        int oldPayload = frame[2] & 0xFF;
        int oldCount = frame[3] & 0xFF;
        if (oldPayload + 6 > 0xFF || oldCount >= 0xFF) return InjectionResult.skip("FRAME_LENGTH_OVERFLOW");
        if (containsNfcid1(frame)) return InjectionResult.skip("LA_NFCID1_ALREADY_PRESENT");

        byte[] newFrame = Arrays.copyOf(frame, frame.length + 6);
        newFrame[2] = (byte) (oldPayload + 6);
        newFrame[3] = (byte) (oldCount + 1);
        int p = frame.length;
        newFrame[p++] = 0x33;
        newFrame[p++] = 0x04;
        System.arraycopy(uid, 0, newFrame, p, 4);

        byte[] newBlock = new byte[block.length + 6];
        System.arraycopy(block, 0, newBlock, 0, frameStart);
        System.arraycopy(newFrame, 0, newBlock, frameStart, newFrame.length);
        System.arraycopy(block, frameEnd, newBlock, frameStart + newFrame.length, block.length - frameEnd);

        String replacement = matcher.group(1) + "\n" + formatHexBlock(newBlock) + "\n" + matcher.group(3);
        String rewritten = text.substring(0, matcher.start()) + replacement + text.substring(matcher.end());
        return InjectionResult.changed(rewritten.getBytes(StandardCharsets.UTF_8), oldPayload, oldPayload + 6, oldCount, oldCount + 1);
    }

    private static boolean containsNfcid1(byte[] frame) {
        if (frame.length < 4) return false;
        int pos = 4;
        int count = frame[3] & 0xFF;
        for (int n = 0; n < count && pos < frame.length; n++) {
            int first = frame[pos] & 0xFF;
            int id;
            int lenPos;
            if (first == 0xA0) {
                if (pos + 2 >= frame.length) return false;
                id = (first << 8) | (frame[pos + 1] & 0xFF);
                lenPos = pos + 2;
            } else {
                if (pos + 1 >= frame.length) return false;
                id = first;
                lenPos = pos + 1;
            }
            int len = frame[lenPos] & 0xFF;
            int valuePos = lenPos + 1;
            if (valuePos + len > frame.length) return false;
            if (id == 0x33) return true;
            pos = valuePos + len;
        }
        return false;
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
