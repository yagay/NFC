package com.example.nfcdoorcard;

import com.example.nfcdoorcard.utils.RootShell;

public final class RootStatus {
    private static volatile Boolean cachedResult;

    private RootStatus() {}

    public static Boolean getCachedResult() {
        return cachedResult;
    }

    public static boolean hasRoot() {
        Boolean cached = cachedResult;
        if (cached != null) return cached;

        RootShell.Result result = RootShell.execute("id -u");
        String output = result.output().trim();
        cachedResult = result.success() && "0".equals(output);
        return cachedResult;
    }

    public static void clearCache() {
        cachedResult = null;
    }
}
