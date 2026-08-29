package com.example.nfcdoorcard.xposed.adapter;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Generic NXP adapter for stacks exposing changeRfParamsByConfig(byte[])
 * with a raw NCI CORE_SET_CONFIG frame (20 02 ...).
 *
 * It never guesses vendor Binder transactions and only rewrites a validated
 * CORE_SET_CONFIG frame containing a standard parameter list.
 */
public final class GenericNxpAdapter implements NfcStackAdapter {
    private static final String METHOD = "changeRfParamsByConfig";
    private static final String[] CANDIDATE_CLASSES = new String[] {
            "com.android.nfc.dhimpl.NxpNativeNfcManager",
            "com.android.nfc.nxp.NxpNfcService$NxpNfcAdapterService",
            "com.android.nfc.nxp.NxpNfcService"
    };

    private volatile String resolvedClass;

    @Override
    public String id() { return "generic-nxp-core-set-config-v1"; }

    @Override
    public Detection detect(ClassLoader classLoader) {
        for (String name : CANDIDATE_CLASSES) {
            try {
                Class<?> c = Class.forName(name, false, classLoader);
                Method m = c.getDeclaredMethod(METHOD, byte[].class);
                if (m.getReturnType() == Void.TYPE) continue;
                resolvedClass = name;
                return Detection.supported(name + "#" + METHOD + "(byte[])");
            } catch (Throwable ignored) {
            }
        }
        return Detection.unsupported("No compatible NXP changeRfParamsByConfig(byte[]) method");
    }

    @Override
    public Method resolveInjectionMethod(ClassLoader classLoader) throws Exception {
        String name = resolvedClass;
        if (name == null) {
            Detection d = detect(classLoader);
            if (!d.supported || resolvedClass == null) throw new ClassNotFoundException(d.detail);
            name = resolvedClass;
        }
        return Class.forName(name, false, classLoader).getDeclaredMethod(METHOD, byte[].class);
    }

    @Override
    public InjectionResult inject(byte[] input, byte[] uid) {
        if (input == null || input.length < 4) return InjectionResult.skip("INPUT_TOO_SHORT");
        if (uid == null || uid.length != 4) return InjectionResult.skip("UID_NOT_4_BYTES");

        int frameStart = findCoreSetConfig(input);
        if (frameStart < 0) return InjectionResult.skip("CORE_SET_CONFIG_NOT_FOUND");
        int oldPayload = input[frameStart + 2] & 0xFF;
        int frameEnd = frameStart + 3 + oldPayload;
        if (frameEnd > input.length || oldPayload < 1) return InjectionResult.skip("INVALID_FRAME_LENGTH");

        int oldCount = input[frameStart + 3] & 0xFF;
        if (!validParamList(input, frameStart, frameEnd, oldCount)) return InjectionResult.skip("INVALID_PARAM_LIST");
        if (containsNfcid1(input, frameStart, frameEnd, oldCount)) return InjectionResult.skip("LA_NFCID1_ALREADY_PRESENT");
        if (oldPayload + 6 > 0xFF || oldCount >= 0xFF) return InjectionResult.skip("FRAME_LENGTH_OVERFLOW");

        byte[] out = new byte[input.length + 6];
        System.arraycopy(input, 0, out, 0, frameEnd);
        out[frameStart + 2] = (byte) (oldPayload + 6);
        out[frameStart + 3] = (byte) (oldCount + 1);
        int p = frameEnd;
        out[p++] = 0x33;
        out[p++] = 0x04;
        System.arraycopy(uid, 0, out, p, 4);
        System.arraycopy(input, frameEnd, out, frameEnd + 6, input.length - frameEnd);
        return InjectionResult.changed(out, oldPayload, oldPayload + 6, oldCount, oldCount + 1);
    }

    private static int findCoreSetConfig(byte[] data) {
        for (int i = 0; i + 3 < data.length; i++) {
            if ((data[i] & 0xFF) == 0x20 && (data[i + 1] & 0xFF) == 0x02) {
                int len = data[i + 2] & 0xFF;
                if (i + 3 + len <= data.length) return i;
            }
        }
        return -1;
    }

    private static boolean validParamList(byte[] data, int start, int end, int count) {
        int pos = start + 4;
        for (int n = 0; n < count; n++) {
            if (pos >= end) return false;
            int first = data[pos] & 0xFF;
            int lenPos;
            if (first == 0xA0) {
                if (pos + 2 >= end) return false;
                lenPos = pos + 2;
            } else {
                if (pos + 1 >= end) return false;
                lenPos = pos + 1;
            }
            int len = data[lenPos] & 0xFF;
            pos = lenPos + 1 + len;
            if (pos > end) return false;
        }
        return pos == end;
    }

    private static boolean containsNfcid1(byte[] data, int start, int end, int count) {
        int pos = start + 4;
        for (int n = 0; n < count && pos < end; n++) {
            int first = data[pos] & 0xFF;
            int id;
            int lenPos;
            if (first == 0xA0) {
                if (pos + 2 >= end) return false;
                id = (first << 8) | (data[pos + 1] & 0xFF);
                lenPos = pos + 2;
            } else {
                if (pos + 1 >= end) return false;
                id = first;
                lenPos = pos + 1;
            }
            int len = data[lenPos] & 0xFF;
            if (id == 0x33) return true;
            pos = lenPos + 1 + len;
        }
        return false;
    }
}
