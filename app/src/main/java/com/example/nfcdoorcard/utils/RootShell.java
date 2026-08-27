package com.example.nfcdoorcard.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class RootShell {
    private static final long TIMEOUT_SECONDS = 10;

    private RootShell() {}

    public static boolean run(List<String> commands) {
        if (commands == null || commands.isEmpty()) return true;
        StringBuilder script = new StringBuilder("set -e\n");
        for (String cmd : commands) {
            script.append(cmd).append('\n');
        }
        Result result = execute(script.toString());
        return result.success();
    }

    public static boolean run(String... commands) {
        return run(java.util.Arrays.asList(commands));
    }

    public static String runWithResult(String command) {
        Result result = execute(command);
        if (result.success()) return result.output();
        if (result.output().isEmpty()) {
            return "ERROR: exit=" + result.exitCode() + (result.timedOut() ? " timeout" : "");
        }
        return result.output();
    }

    public static Result execute(String command) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            Process finalProcess = process;
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(finalProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "root-shell-reader");
            readerThread.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                readerThread.join(500);
                return new Result(false, -1, trim(output), true);
            }

            readerThread.join(500);
            int exitCode = process.exitValue();
            return new Result(exitCode == 0, exitCode, trim(output), false);
        } catch (Exception e) {
            return new Result(false, -1, e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String trim(StringBuilder output) {
        synchronized (output) {
            return output.toString().trim();
        }
    }

    public record Result(boolean success, int exitCode, String output, boolean timedOut) {}
}
