from pathlib import Path

# MainActivity: persist the diagnostics switch and bump expected hook build.
p = Path('app/src/main/java/com/example/nfcdoorcard/MainActivity.kt')
s = p.read_text()
s = s.replace('companion object { private const val EXPECTED_HOOK_BUILD = 9 }', 'companion object { private const val EXPECTED_HOOK_BUILD = 10 }')
start_log = '        AppLogger.i("NFC mode controller started; production vendor binder enabled")\n'
if 'KEY_DIAGNOSTIC_LOGGING_ENABLED, false' not in s:
    s = s.replace(start_log, start_log + '''        // Diagnostics are opt-in for every app launch. This keeps the Hook hot path quiet by default.
        contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
            put(ConfigProvider.KEY_DIAGNOSTIC_LOGGING_ENABLED, false)
        })
''', 1)
old_destroy = '    override fun onDestroy() { disableReadDispatch(); operationExecutor.shutdownNow(); diagnosticExecutor.shutdownNow(); super.onDestroy() }'
new_destroy = '''    override fun onDestroy() {
        disableReadDispatch()
        runCatching {
            contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
                put(ConfigProvider.KEY_DIAGNOSTIC_LOGGING_ENABLED, false)
            })
        }
        operationExecutor.shutdownNow()
        diagnosticExecutor.shutdownNow()
        super.onDestroy()
    }'''
if old_destroy in s:
    s = s.replace(old_destroy, new_destroy, 1)
old_switch = '''                                onCheckedChange = { enabled ->
                                    logsEnabled = enabled
                                    if (!enabled) logText = ""
                                }'''
new_switch = '''                                onCheckedChange = { enabled ->
                                    logsEnabled = enabled
                                    contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
                                        put(ConfigProvider.KEY_DIAGNOSTIC_LOGGING_ENABLED, enabled)
                                    })
                                    if (!enabled) logText = ""
                                }'''
if old_switch not in s:
    raise SystemExit('log switch block not found')
s = s.replace(old_switch, new_switch, 1)
p.write_text(s)

# NfcInjectionModule: remove disabled probes and make expensive caller-stack diagnostics opt-in.
p = Path('app/src/main/java/com/example/nfcdoorcard/xposed/NfcInjectionModule.java')
s = p.read_text()
s = s.replace('private static final int HOOK_BUILD = 9;', 'private static final int HOOK_BUILD = 10;')
for imp in [
    'import android.os.Binder;\n',
    'import java.lang.reflect.Array;\n',
    'import java.util.ArrayList;\n',
    'import java.util.Arrays;\n',
    'import java.util.List;\n',
]:
    s = s.replace(imp, '')

# Remove the unused probe class table.
probe_start = s.find('    private static final String[] REFRESH_PROBE_CLASSES = new String[]{')
if probe_start >= 0:
    probe_end = s.find('    };', probe_start)
    if probe_end < 0:
        raise SystemExit('probe table end not found')
    s = s[:probe_start] + s[probe_end + len('    };\n'):] 
s = s.replace('    private volatile int refreshProbeEventCount;\n', '')
s = s.replace('        // Production path: keep only the UID injection hook. Diagnostic refresh probes are disabled.\n\n', '')

old_diag = '''                String caller = compactCallStack(24);
                Log.i(TAG, "RFPROBE: CHANGE_RF_CALLER pid=" + pid + " uid=" + uidHex + " stack=" + caller);
                persistRfCaller(pid, caller);
'''
new_diag = '''                if (cfg.diagnostics) {
                    String caller = compactCallStack(24);
                    Log.i(TAG, "RFPROBE: CHANGE_RF_CALLER pid=" + pid + " uid=" + uidHex + " stack=" + caller);
                    persistRfCaller(pid, caller);
                }
'''
if old_diag not in s:
    raise SystemExit('active diagnostics block not found')
s = s.replace(old_diag, new_diag, 1)

# Remove disabled probe methods from installRefreshProbes through methodSignature.
a = s.find('    private void installRefreshProbes(')
b = s.find('    private String compactCallStack(', a)
if a < 0 or b < 0:
    raise SystemExit('probe method region not found')
s = s[:a] + s[b:]

# Remove summarize helpers and refresh probe persistence, while preserving persistRfCaller.
a = s.find('    private String summarizeArgs(')
b = s.find('    private void persistRfCaller(', a)
if a >= 0 and b >= 0:
    s = s[:a] + s[b:]

# Extend config with diagnostics flag.
s = s.replace('if (app == null) return new SimConfig(false, null);', 'if (app == null) return new SimConfig(false, null, false);')
s = s.replace('        boolean active = false;\n        String uid = null;', '        boolean active = false;\n        String uid = null;\n        boolean diagnostics = false;')
s = s.replace('                else if ("uid".equals(key)) uid = value;', '                else if ("uid".equals(key)) uid = value;\n                else if ("diagnostic_logging_enabled".equals(key)) diagnostics = Boolean.parseBoolean(value);')
s = s.replace('        return new SimConfig(active, uid);', '        return new SimConfig(active, uid, diagnostics);')
old_cfg = '''    private static final class SimConfig {
        final boolean active;
        final String uid;

        SimConfig(boolean active, String uid) {
            this.active = active;
            this.uid = uid;
        }
    }'''
new_cfg = '''    private static final class SimConfig {
        final boolean active;
        final String uid;
        final boolean diagnostics;

        SimConfig(boolean active, String uid, boolean diagnostics) {
            this.active = active;
            this.uid = uid;
            this.diagnostics = diagnostics;
        }
    }'''
if old_cfg not in s:
    raise SystemExit('SimConfig block not found')
s = s.replace(old_cfg, new_cfg, 1)
p.write_text(s)
