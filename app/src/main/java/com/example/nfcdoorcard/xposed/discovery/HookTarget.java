package com.example.nfcdoorcard.xposed.discovery;

import java.lang.reflect.Method;
import java.util.Arrays;

/** A resolved or persisted hook point for one stable NFC capability. */
public final class HookTarget {
    public final Capability capability;
    public final String className;
    public final String methodName;
    public final String returnType;
    public final String parameterSignature;
    public final int score;
    public final String source;

    public HookTarget(Capability capability, String className, String methodName,
                      String returnType, String parameterSignature, int score, String source) {
        this.capability = capability;
        this.className = className;
        this.methodName = methodName;
        this.returnType = returnType;
        this.parameterSignature = parameterSignature;
        this.score = score;
        this.source = source;
    }

    public static HookTarget fromMethod(Capability capability, Method method, int score, String source) {
        return new HookTarget(
                capability,
                method.getDeclaringClass().getName(),
                method.getName(),
                method.getReturnType().getName(),
                signature(method.getParameterTypes()),
                score,
                source
        );
    }

    public Method resolve(ClassLoader classLoader) throws Exception {
        Class<?> c = Class.forName(className, false, classLoader);
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) continue;
            if (!m.getReturnType().getName().equals(returnType)) continue;
            if (!signature(m.getParameterTypes()).equals(parameterSignature)) continue;
            m.setAccessible(true);
            return m;
        }
        throw new NoSuchMethodException(className + "#" + methodName + parameterSignature);
    }

    public String fingerprint() {
        return capability + "|" + className + "|" + methodName + "|" + returnType + "|" + parameterSignature;
    }

    private static String signature(Class<?>[] types) {
        return Arrays.toString(Arrays.stream(types).map(Class::getName).toArray(String[]::new));
    }

    @Override public String toString() {
        return capability + " " + className + "#" + methodName + parameterSignature + " -> " + returnType +
                " score=" + score + " source=" + source;
    }
}
