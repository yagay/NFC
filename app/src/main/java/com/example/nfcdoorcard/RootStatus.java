package com.example.nfcdoorcard;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public final class RootStatus {
    private RootStatus() {}

    public static boolean hasRoot() {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                return "0".equals(r.readLine());
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
