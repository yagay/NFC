package com.example.nfcdoorcard.xposed.discovery;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Capability-oriented hook discovery.
 *
 * Production fast path first checks proven targets. If those disappear after an OTA,
 * a bounded Dex scan ranks byte[] NFC methods by structural features instead of relying
 * on one permanent class/method name. The scan only discovers candidates; runtime RF
 * payload verification remains the authoritative proof that a target is correct.
 */
public final class HookDiscoveryEngine {
    private static final int MAX_CLASSES = 1500;
    private static final int MAX_RESULTS = 12;

    private static final String[] PROVEN_RF_CLASSES = new String[] {
            "com.android.nfc.dhimpl.NxpNativeNfcManager",
            "com.android.nfc.nxp.NxpNfcService$NxpNfcAdapterService",
            "com.android.nfc.nxp.NxpNfcService"
    };

    public HookTarget discoverRfConfigWrite(ClassLoader classLoader) {
        List<HookTarget> ranked = discoverRfCandidates(classLoader);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    public List<HookTarget> discoverRfCandidates(ClassLoader classLoader) {
        List<HookTarget> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Fast path for known-good families. These names are hints, not the architecture.
        for (String name : PROVEN_RF_CLASSES) {
            inspectClass(classLoader, name, "known-family", out, seen, true);
        }

        // OTA fallback: enumerate NFC-related dex classes and score methods by shape.
        int inspected = 0;
        for (String name : enumerateClassNames(classLoader)) {
            if (inspected >= MAX_CLASSES) break;
            if (!looksNfcRelated(name)) continue;
            inspected++;
            inspectClass(classLoader, name, "dex-scan", out, seen, false);
        }

        out.sort(Comparator.comparingInt((HookTarget t) -> t.score).reversed());
        if (out.size() > MAX_RESULTS) return new ArrayList<>(out.subList(0, MAX_RESULTS));
        return out;
    }

    private void inspectClass(ClassLoader cl, String className, String source,
                              List<HookTarget> out, Set<String> seen, boolean knownFamily) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != byte[].class) continue;
                if (m.getReturnType() == Void.TYPE) continue;

                int score = scoreRfMethod(c, m, knownFamily);
                if (score < 40) continue;
                HookTarget target = HookTarget.fromMethod(Capability.RF_CONFIG_WRITE, m, score, source);
                if (seen.add(target.fingerprint())) out.add(target);
            }
        } catch (Throwable ignored) {
        }
    }

    private int scoreRfMethod(Class<?> c, Method m, boolean knownFamily) {
        int score = 35; // one byte[] parameter + non-void return already matched
        String cn = c.getName().toLowerCase(Locale.ROOT);
        String mn = m.getName().toLowerCase(Locale.ROOT);
        String rt = m.getReturnType().getName();

        if (knownFamily) score += 80;
        if (cn.contains("nfc")) score += 20;
        if (cn.contains("nxp")) score += 20;
        if (cn.contains("oplus") || cn.contains("native")) score += 10;
        if (cn.contains("manager") || cn.contains("service")) score += 5;

        if (mn.contains("rf")) score += 25;
        if (mn.contains("config")) score += 30;
        if (mn.contains("param")) score += 20;
        if (mn.contains("change") || mn.contains("set") || mn.contains("apply") || mn.contains("update")) score += 10;
        if ("int".equals(rt) || "java.lang.Integer".equals(rt)) score += 15;
        if ("boolean".equals(rt) || "java.lang.Boolean".equals(rt)) score += 5;

        // Current proven point gets the highest fast-path score without making the
        // framework depend on it when it disappears after an OTA.
        if ("changeRfParamsByConfig".equals(m.getName())) score += 100;
        return score;
    }

    private boolean looksNfcRelated(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("nfc") || n.contains("nxp") || n.contains("oplus") || n.contains("rfconfig");
    }

    private List<String> enumerateClassNames(ClassLoader classLoader) {
        List<String> names = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        try {
            Class<?> baseDex = Class.forName("dalvik.system.BaseDexClassLoader");
            if (!baseDex.isInstance(classLoader)) return names;
            Field pathListField = baseDex.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);
            if (pathList == null) return names;

            Field elementsField = pathList.getClass().getDeclaredField("dexElements");
            elementsField.setAccessible(true);
            Object[] elements = (Object[]) elementsField.get(pathList);
            if (elements == null) return names;

            for (Object element : elements) {
                if (element == null) continue;
                Field dexFileField = findField(element.getClass(), "dexFile");
                if (dexFileField == null) continue;
                dexFileField.setAccessible(true);
                Object dexFile = dexFileField.get(element);
                if (dexFile == null) continue;
                Method entriesMethod = dexFile.getClass().getMethod("entries");
                @SuppressWarnings("unchecked")
                Enumeration<String> entries = (Enumeration<String>) entriesMethod.invoke(dexFile);
                while (entries != null && entries.hasMoreElements()) {
                    String name = entries.nextElement();
                    if (name != null && unique.add(name)) names.add(name);
                }
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    private Field findField(Class<?> c, String name) {
        for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
            try { return cur.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }
}
