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
        for (String cmd : commands) script.append(cmd).append('\n');
        return execute(script.toString()).success();
    }

    public static boolean run(String... commands) {
        return run(java.util.Arrays.asList(commands));
    }

    public static String runWithResult(String command) {
        return execute(command).describe();
    }

    public static Result execute(String command) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            Process target = process;
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(target.getInputStream()))) {
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
                process.waitFor(2, TimeUnit.SECONDS);
                readerThread.join(2000);
                return new Result(false, -1, trim(output), true);
            }

            readerThread.join();
            int exitCode = process.exitValue();
            return new Result(exitCode == 0, exitCode, trim(output), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return new Result(false, -1, "Interrupted", false);
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

    public record Result(boolean success, int exitCode, String output, boolean timedOut) {
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("exit=").append(exitCode);
            if (timedOut) sb.append(" timeout");
            if (!output.isEmpty()) sb.append("\n").append(output);
            return sb.toString();
        }
    }
}
