package com.example.nfcdoorcard.xposed.discovery;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Capability-oriented hook discovery for RF writes and refresh triggers. */
public final class HookDiscoveryEngine {
    private static final int MAX_CLASSES = 1800;
    private static final int MAX_RESULTS = 12;
    private static final int MAX_RF_PARAMS = 4;

    private static final String[] PROVEN_RF_CLASSES = new String[] {
            "com.android.nfc.dhimpl.NxpNativeNfcManager",
            "com.android.nfc.nxp.NxpNfcService$NxpNfcAdapterService",
            "com.android.nfc.nxp.NxpNfcService"
    };

    private static final String[] PROVEN_TRIGGER_CLASSES = new String[] {
            "com.android.nfc.VendorNfcService$VendorNfcAdapterService",
            "com.android.nfc.nxp.NxpNfcService$NxpNfcAdapterService"
    };

    public HookTarget discoverRfConfigWrite(ClassLoader classLoader) {
        List<HookTarget> ranked = discoverRfCandidates(classLoader);
        return ranked.isEmpty() ? null : ranked.get(0);
    }

    /** Fast path used during com.android.nfc startup before the full application is ready. */
    public List<HookTarget> discoverKnownRfCandidates(ClassLoader classLoader) {
        List<HookTarget> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : PROVEN_RF_CLASSES) inspectRfClass(classLoader, name, "known-family", out, seen, true);
        return top(out);
    }

    public List<HookTarget> discoverRfCandidates(ClassLoader classLoader) {
        List<HookTarget> out = new ArrayList<>(discoverKnownRfCandidates(classLoader));
        Set<String> seen = new HashSet<>();
        for (HookTarget target : out) seen.add(target.fingerprint());

        int inspected = 0;
        for (String name : enumerateClassNames(classLoader)) {
            if (inspected >= MAX_CLASSES) break;
            if (!looksNfcRelated(name)) continue;
            inspected++;
            inspectRfClass(classLoader, name, "dex-scan", out, seen, false);
        }
        return top(out);
    }

    public List<HookTarget> discoverKnownTriggerCandidates(ClassLoader classLoader) {
        List<HookTarget> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : PROVEN_TRIGGER_CLASSES) inspectTriggerClass(classLoader, name, "known-family", out, seen, true);
        return top(out);
    }

    public List<HookTarget> discoverTriggerCandidates(ClassLoader classLoader) {
        List<HookTarget> out = new ArrayList<>(discoverKnownTriggerCandidates(classLoader));
        Set<String> seen = new HashSet<>();
        for (HookTarget target : out) seen.add(target.fingerprint());

        int inspected = 0;
        for (String name : enumerateClassNames(classLoader)) {
            if (inspected >= MAX_CLASSES) break;
            if (!looksNfcRelated(name)) continue;
            inspected++;
            inspectTriggerClass(classLoader, name, "dex-scan", out, seen, false);
        }
        return top(out);
    }

    private List<HookTarget> top(List<HookTarget> out) {
        out.sort(Comparator.comparingInt((HookTarget t) -> t.score).reversed());
        if (out.size() > MAX_RESULTS) return new ArrayList<>(out.subList(0, MAX_RESULTS));
        return out;
    }

    private void inspectRfClass(ClassLoader cl, String className, String source,
                                List<HookTarget> out, Set<String> seen, boolean knownFamily) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!isRfSignatureCandidate(m) || !isRfSemanticCandidate(m)) continue;
                int score = scoreRfMethod(c, m, knownFamily);
                if (score < 40) continue;
                HookTarget target = HookTarget.fromMethod(Capability.RF_CONFIG_WRITE, m, score, source);
                if (seen.add(target.fingerprint())) out.add(target);
            }
        } catch (Throwable ignored) { }
    }

    /** Package-visible for unit tests. Runtime payload inspection is the final safety gate. */
    static boolean isRfSignatureCandidate(Method m) {
        if (m == null) return false;
        int modifiers = m.getModifiers();
        if (Modifier.isAbstract(modifiers) || m.getDeclaringClass().isInterface() || m.isBridge() || m.isSynthetic()) return false;
        Class<?>[] p = m.getParameterTypes();
        if (p.length < 1 || p.length > MAX_RF_PARAMS) return false;
        int bytes = 0;
        for (Class<?> type : p) if (type == byte[].class) bytes++;
        if (bytes != 1) return false;
        Class<?> r = m.getReturnType();
        return r == Void.TYPE || r == Boolean.TYPE || r == Boolean.class ||
                r == Integer.TYPE || r == Integer.class ||
                r == Long.TYPE || r == Long.class ||
                r == Short.TYPE || r == Short.class ||
                Number.class.isAssignableFrom(r);
    }

    static boolean isRfSemanticCandidate(Method m) {
        String n = m.getName().toLowerCase(Locale.ROOT);
        boolean domain = n.contains("rf") || n.contains("config") || n.contains("param");
        boolean action = n.contains("change") || n.contains("set") || n.contains("apply") ||
                n.contains("update") || n.contains("write");
        return domain && action;
    }

    private void inspectTriggerClass(ClassLoader cl, String className, String source,
                                     List<HookTarget> out, Set<String> seen, boolean knownFamily) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                int modifiers = m.getModifiers();
                if (Modifier.isAbstract(modifiers) || c.isInterface() || m.isBridge() || m.isSynthetic()) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || (p[0] != boolean.class && p[0] != Boolean.class)) continue;
                if (!isTriggerSemanticCandidate(m)) continue;
                int score = scoreTriggerMethod(c, m, knownFamily);
                if (score < 45) continue;
                HookTarget target = HookTarget.fromMethod(Capability.RF_REFRESH_TRIGGER, m, score, source);
                if (seen.add(target.fingerprint())) out.add(target);
            }
        } catch (Throwable ignored) { }
    }

    static boolean isTriggerSemanticCandidate(Method m) {
        String n = m.getName().toLowerCase(Locale.ROOT);
        if (n.contains("share") || n.contains("refresh") || n.contains("reload")) return true;
        return n.contains("rf") && (n.contains("enable") || n.contains("set") || n.contains("apply") ||
                n.contains("update") || n.contains("rotate") || n.contains("restart"));
    }

    private int scoreRfMethod(Class<?> c, Method m, boolean knownFamily) {
        int score = 35;
        String cn = c.getName().toLowerCase(Locale.ROOT);
        String mn = m.getName().toLowerCase(Locale.ROOT);
        String rt = m.getReturnType().getName();
        if (knownFamily) score += 80;
        if (cn.contains("nfc")) score += 20;
        if (cn.contains("nxp")) score += 20;
        if (cn.contains("native")) score += 60;
        if (cn.contains("dhimpl") || cn.contains("devicehost")) score += 20;
        if (cn.contains("adapterservice")) score -= 35;
        if (cn.contains("oplus")) score += 10;
        if (cn.contains("manager")) score += 10;
        if (cn.contains("service")) score += 2;
        if (mn.contains("rf")) score += 25;
        if (mn.contains("config")) score += 30;
        if (mn.contains("param")) score += 20;
        if (mn.contains("change") || mn.contains("set") || mn.contains("apply") || mn.contains("update")) score += 10;
        if ("int".equals(rt) || "java.lang.Integer".equals(rt)) score += 15;
        if ("boolean".equals(rt) || "java.lang.Boolean".equals(rt)) score += 5;
        if ("void".equals(rt)) score += 2;
        if (m.getParameterTypes().length == 1) score += 12;
        else score -= (m.getParameterTypes().length - 1) * 4;
        if ("changeRfParamsByConfig".equals(m.getName())) score += 100;
        return score;
    }

    private int scoreTriggerMethod(Class<?> c, Method m, boolean knownFamily) {
        int score = 25;
        String cn = c.getName().toLowerCase(Locale.ROOT);
        String mn = m.getName().toLowerCase(Locale.ROOT);
        String rt = m.getReturnType().getName();
        if (knownFamily) score += 70;
        if (cn.contains("nfc")) score += 20;
        if (cn.contains("vendor") || cn.contains("oplus") || cn.contains("nxp")) score += 15;
        if (cn.contains("service") || cn.contains("adapter")) score += 10;
        if (mn.contains("share")) score += 45;
        if (mn.contains("rf")) score += 25;
        if (mn.contains("refresh") || mn.contains("reload")) score += 25;
        if (mn.contains("enable") || mn.contains("set") || mn.contains("apply")) score += 15;
        if ("boolean".equals(rt) || "java.lang.Boolean".equals(rt)) score += 15;
        if ("enableNfcShareMode".equals(m.getName())) score += 100;
        return score;
    }

    private boolean looksNfcRelated(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("nfc") || n.contains("nxp") || n.contains("oplus") || n.contains("rfconfig") ||
                n.contains("devicehost") || n.contains("nci") || n.contains("native") || n.contains("hal");
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
        } catch (Throwable ignored) { }
        return names;
    }

    private Field findField(Class<?> c, String name) {
        for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
            try { return cur.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }
}