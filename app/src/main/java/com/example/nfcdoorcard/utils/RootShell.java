package com.example.nfcdoorcard.utils;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

public class RootShell {
    public static boolean run(List<String> commands) {
        Process p = null;
        DataOutputStream os = null;
        try {
            p = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(p.getOutputStream());
            for (String cmd : commands) {
                os.writeBytes(cmd + "\n");
            }
            os.writeBytes("exit\n");
            os.flush();
            return p.waitFor() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            try {
                if (os != null) os.close();
                if (p != null) p.destroy();
            } catch (IOException ignored) {}
        }
    }

    public static boolean run(String... commands) {
        return run(java.util.Arrays.asList(commands));
    }
}
