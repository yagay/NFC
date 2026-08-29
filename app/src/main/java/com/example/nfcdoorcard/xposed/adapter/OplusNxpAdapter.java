package com.example.nfcdoorcard.xposed.adapter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
    private static final String CHIP_CLASS = "com.oplus.nfc.common.NfcChipDeviceImpl";
    private static final String REFRESH_METHOD = "setRfConfig";
    private static final Pattern OPLUS_BLOCK = Pattern.compile("(?ms)(OPLUS_CONF_EXTN\\s*=\\s*\\{)(.*?)(\\})");
    private static final Pattern HEX_TOKEN = Pattern.compile("(?i)(?<![0-9A-F])([0-9A-F]{2})(?![0-9A-F])");
    private static final String[] CONFIG_PATHS = new String[]{
            "/data/vendor/nfc/libnfc_default_config.conf",
            "/data/vendor/nfc/libnfc-nxpTransit.conf"
    };

    private volatile Object refreshTarget;
    private volatile Method refreshMethod;
    private volatile String capturedStockConfig;
    private volatile String capturedSource = "none";

    @Override
    public String id() {
        return "oplus-nxp-v2";
    }

    @Override
    public Detection detect(ClassLoader classLoader) {
        try {
            Class<?> manager = Class.forName(MANAGER_CLASS, false, classLoader);
            Method injection = manager.getDeclaredMethod(INJECTION_METHOD, byte[].class);
            if (injection.getReturnType() == Void.TYPE) {
                return Detection.unsupported("changeRfParamsByConfig has unexpected void return type");
            }
            Class<?> chip = Class.forName(CHIP_CLASS, false, classLoader);
            Method refresh = chip.getDeclaredMethod(REFRESH_METHOD, String.class);
            refresh.setAccessible(true);
            refreshMethod = refresh;
            return Detection.supported(
                    MANAGER_CLASS + "#" + INJECTION_METHOD + "(byte[]) + " +
                            CHIP_CLASS + "#" + REFRESH_METHOD + "(String)");
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
    public void observeInvocation(Object receiver, Method method, Object[] args) {
        if (method == null) return;
        if (!CHIP_CLASS.equals(method.getDeclaringClass().getName())) return;
        if (!REFRESH_METHOD.equals(method.getName())) return;
        Class<?>[] types = method.getParameterTypes();
        if (types.length != 1 || types[0] != String.class) return;

        if (receiver != null) {
            refreshTarget = receiver;
            refreshMethod = method;
            try { refreshMethod.setAccessible(true); } catch (Throwable ignored) {}
            capturedSource = "observed-instance";
        }
        if (args != null && args.length == 1 && args[0] instanceof String) {
            String config = (String) args[0];
            if (isUsableConfig(config)) {
                capturedStockConfig = config;
                capturedSource = receiver == null ? "observed-config" : "observed-instance+config";
            }
        }
    }

    @Override
    public void observeConstructedObject(Object object) {
        if (object != null && CHIP_CLASS.equals(object.getClass().getName())) {
            refreshTarget = object;
            capturedSource = "constructor-capture";
        }
    }

    @Override
    public RefreshResult requestRfRefresh(ClassLoader classLoader) {
        try {
            Class<?> chip = Class.forName(CHIP_CLASS, false, classLoader);
            Method method = refreshMethod;
            if (method == null || !method.getDeclaringClass().equals(chip)) {
                method = chip.getDeclaredMethod(REFRESH_METHOD, String.class);
                method.setAccessible(true);
                refreshMethod = method;
            }

            Object target = refreshTarget;
            String targetSource = capturedSource;
            if (target == null || !chip.isInstance(target)) {
                TargetResolution resolution = resolveExistingTarget(chip);
                target = resolution.target;
                targetSource = resolution.detail;
                if (target != null) {
                    refreshTarget = target;
                    capturedSource = targetSource;
                }
            }
            if (target == null) {
                return RefreshResult.unavailable("TARGET_NOT_READY " + targetSource);
            }

            ConfigResolution configResolution = resolveStockConfig();
            if (configResolution.config == null) {
                return RefreshResult.unavailable("STOCK_CONFIG_NOT_READY " + configResolution.detail);
            }

            Object result;
            try {
                result = method.invoke(target, configResolution.config);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause() == null ? ite : ite.getCause();
                return RefreshResult.rejected("invoke threw " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }

            String detail = "target=" + targetSource + " config=" + configResolution.detail + " result=" + String.valueOf(result);
            if (result instanceof Boolean) {
                return ((Boolean) result) ? RefreshResult.accepted(detail) : RefreshResult.rejected(detail);
            }
            if (result instanceof Number) {
                return ((Number) result).intValue() == 0 ? RefreshResult.accepted(detail) : RefreshResult.rejected(detail);
            }
            return RefreshResult.accepted(detail);
        } catch (Throwable t) {
            return RefreshResult.unavailable(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private TargetResolution resolveExistingTarget(Class<?> chip) {
        StringBuilder detail = new StringBuilder();

        for (Method method : chip.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) continue;
            if (!chip.isAssignableFrom(method.getReturnType())) continue;
            if (!(name.equals("getinstance") || name.equals("instance") || name.equals("getdefault") ||
                    name.equals("getdevice") || name.equals("getnfcchipdevice"))) continue;
            try {
                method.setAccessible(true);
                Object value = method.invoke(null);
                if (chip.isInstance(value)) return new TargetResolution(value, "static-method:" + method.getName());
            } catch (Throwable t) {
                detail.append(" method:").append(method.getName()).append('=').append(t.getClass().getSimpleName());
            }
        }

        for (Field field : chip.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (!chip.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (chip.isInstance(value)) return new TargetResolution(value, "static-field:" + field.getName());
            } catch (Throwable t) {
                detail.append(" field:").append(field.getName()).append('=').append(t.getClass().getSimpleName());
            }
        }

        return new TargetResolution(null, detail.length() == 0 ? "no singleton getter/static instance field" : detail.toString());
    }

    private ConfigResolution resolveStockConfig() {
        String cached = capturedStockConfig;
        if (isUsableConfig(cached)) return new ConfigResolution(cached, "captured:" + cached.length());

        StringBuilder failures = new StringBuilder();
        for (String path : CONFIG_PATHS) {
            try {
                File file = new File(path);
                if (!file.isFile()) {
                    failures.append(path).append("=missing;");
                    continue;
                }
                String text = readText(file);
                if (!isUsableConfig(text)) {
                    failures.append(path).append("=no-oplus-block;");
                    continue;
                }
                capturedStockConfig = text;
                return new ConfigResolution(text, path + ":" + text.length());
            } catch (Throwable t) {
                failures.append(path).append('=').append(t.getClass().getSimpleName()).append(';');
            }
        }
        return new ConfigResolution(null, failures.toString());
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static boolean isUsableConfig(String text) {
        return text != null && text.length() > 100 && text.contains("OPLUS_CONF_EXTN") && text.contains("NXP_CORE_CONF_EXTN");
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

    private static final class TargetResolution {
        final Object target;
        final String detail;
        TargetResolution(Object target, String detail) {
            this.target = target;
            this.detail = detail;
        }
    }

    private static final class ConfigResolution {
        final String config;
        final String detail;
        ConfigResolution(String config, String detail) {
            this.config = config;
            this.detail = detail;
        }
    }
}
