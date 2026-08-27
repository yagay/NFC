package com.example.nfcdoorcard;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public final class RootStatus {
    private static volatile Boolean cachedResult;

    private RootStatus() {}

    public static Boolean getCachedResult() {
        return cachedResult;
    }

    public static boolean hasRoot() {
        Boolean cached = cachedResult;
        if (cached != null) return cached;

        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "id -u")
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                cachedResult = false;
                return false;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                cachedResult = "0".equals(line);
                return cachedResult;
            }
        } catch (Exception e) {
            cachedResult = false;
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    public static void clearCache() {
        cachedResult = null;
    }
}
