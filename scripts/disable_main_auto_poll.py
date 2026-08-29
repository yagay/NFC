from pathlib import Path

p = Path('app/src/main/java/com/example/nfcdoorcard/MainActivity.kt')
s = p.read_text()
old = '''        LaunchedEffect(selectedSource) {
            while (true) {
                executor.execute {
                    val newStatus = readRuntimeStatus()
                    val logs = fetchLogsSync(selectedSource)
                    runOnUiThread {
                        status = newStatus
                        logText = if (selectedSource == LogSource.STATUS) buildStatusSummary(newStatus) + "\\n\\n" + logs else logs
                    }
                }
                kotlinx.coroutines.delay(2000)
            }
        }
'''
if old not in s:
    raise SystemExit('auto polling block not found')
s = s.replace(old, '''        // Startup-safe mode: do not automatically run root/logcat/provider diagnostics.
        // HeyTap bridge continues independently in the background LSPosed process.
''', 1)
p.write_text(s)
