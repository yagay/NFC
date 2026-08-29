from pathlib import Path

p = Path('app/src/main/java/com/example/nfcdoorcard/MainActivity.kt')
s = p.read_text()

# imports
if 'import java.util.concurrent.TimeUnit' not in s:
    s = s.replace('import java.util.concurrent.Executors\n', 'import java.util.concurrent.Executors\nimport java.util.concurrent.TimeUnit\n')

# executors + root state
s = s.replace('    private val executor = Executors.newSingleThreadExecutor()\n    private val vendorNfcController = VendorNfcController()', '''    // NFC operations must never queue behind logcat/diagnostic collection.
    private val operationExecutor = Executors.newSingleThreadExecutor()
    private val diagnosticExecutor = Executors.newSingleThreadExecutor()
    private val vendorNfcController = VendorNfcController()
    @Volatile private var rootAvailableCache: Boolean? = null
    @Volatile private var lastRootToastAt: Long = 0L''')
s = s.replace('override fun onDestroy() { disableReadDispatch(); executor.shutdownNow(); super.onDestroy() }', 'override fun onDestroy() { disableReadDispatch(); operationExecutor.shutdownNow(); diagnosticExecutor.shutdownNow(); super.onDestroy() }')

# UI polling: no root unless logs enabled
old = '''        LaunchedEffect(selectedSource, logsEnabled) {
            while (true) {
                executor.execute {
                    val newStatus = readRuntimeStatus()
                    val logs = if (logsEnabled) fetchLogsSync(selectedSource) else ""
                    runOnUiThread {
                        status = newStatus
                        if (logsEnabled) {
                            logText = if (selectedSource == LogSource.STATUS) buildStatusSummary(newStatus) + "\\n\\n" + logs else logs
                        } else {
                            logText = ""
                        }
                    }
                }
                kotlinx.coroutines.delay(if (logsEnabled) 2000 else 3000)
            }
        }'''
new = '''        LaunchedEffect(selectedSource, logsEnabled) {
            while (true) {
                diagnosticExecutor.execute {
                    // Normal UI refresh is Provider-only. Root/pidof is used only while logs are enabled.
                    val newStatus = readRuntimeStatus(includeRootPid = logsEnabled)
                    val logs = if (logsEnabled) fetchLogsSync(selectedSource) else ""
                    runOnUiThread {
                        status = newStatus
                        if (logsEnabled) {
                            logText = if (selectedSource == LogSource.STATUS) buildStatusSummary(newStatus) + "\\n\\n" + logs else logs
                        } else {
                            logText = ""
                        }
                    }
                }
                kotlinx.coroutines.delay(if (logsEnabled) 2000 else 4000)
            }
        }'''
if old not in s:
    raise SystemExit('poll block not found')
s = s.replace(old, new, 1)

# Operation executor usages. Keep diagnostics on diagnosticExecutor.
s = s.replace('        executor.execute {\n            var state = readRuntimeStatus()', '        operationExecutor.execute {\n            var state = readRuntimeStatus(includeRootPid = true)', 1)
s = s.replace('        executor.execute {\n            val binderResult = vendorNfcController.setShareMode(false)', '        operationExecutor.execute {\n            val binderResult = vendorNfcController.setShareMode(false)', 1)
# diagnostic save is the remaining executor.execute occurrence
s = s.replace('        executor.execute {\n            try {', '        diagnosticExecutor.execute {\n            try {', 1)

# Accurate operation reads
s = s.replace('else readRuntimeStatus()\n            val applied', 'else readRuntimeStatus(includeRootPid = true)\n            val applied', 1)
s = s.replace('            val finalState = readRuntimeStatus()\n            val message', '            val finalState = readRuntimeStatus(includeRootPid = true)\n            val message', 1)
s = s.replace('            state = readRuntimeStatus()\n            if (state.hookInstalled && state.hookBuild == EXPECTED_HOOK_BUILD && state.rfStatus', '            state = readRuntimeStatus(includeRootPid = true)\n            if (state.hookInstalled && state.hookBuild == EXPECTED_HOOK_BUILD && state.rfStatus', 1)
s = s.replace('            state = readRuntimeStatus()\n            if (state.hookInstalled && state.hookBuild == EXPECTED_HOOK_BUILD) return state', '            state = readRuntimeStatus(includeRootPid = true)\n            if (state.hookInstalled && state.hookBuild == EXPECTED_HOOK_BUILD) return state', 1)

# Runtime status can be provider-only.
old_sig = '    private fun readRuntimeStatus(): RuntimeStatus {\n        val map = readProviderMap()\n        val currentPid = currentNfcPid().toIntOrNull() ?: 0\n        val scopePid = map[ConfigProvider.KEY_SCOPE_PID]?.toIntOrNull() ?: 0\n        val hookPid = map[ConfigProvider.KEY_HOOK_PID]?.toIntOrNull() ?: 0'
new_sig = '''    private fun readRuntimeStatus(includeRootPid: Boolean = false): RuntimeStatus {
        val map = readProviderMap()
        val scopePid = map[ConfigProvider.KEY_SCOPE_PID]?.toIntOrNull() ?: 0
        val hookPid = map[ConfigProvider.KEY_HOOK_PID]?.toIntOrNull() ?: 0
        val rfPidFromProvider = map[ConfigProvider.KEY_RF_PID]?.toIntOrNull() ?: 0
        // Avoid su/pidof during ordinary UI refresh. The Hook already publishes its current PID.
        val currentPid = if (includeRootPid) {
            currentNfcPid().toIntOrNull() ?: 0
        } else {
            hookPid.takeIf { it > 0 } ?: scopePid.takeIf { it > 0 } ?: rfPidFromProvider
        }'''
if old_sig not in s:
    raise SystemExit('readRuntimeStatus signature block not found')
s = s.replace(old_sig, new_sig, 1)
s = s.replace('        val rfPid = map[ConfigProvider.KEY_RF_PID]?.toIntOrNull() ?: 0\n        return RuntimeStatus(', '        val rfPid = rfPidFromProvider\n        return RuntimeStatus(', 1)

# Diagnostics should use precise root-backed status.
s = s.replace('        val s = readRuntimeStatus()\n        appendLine("=== NFC FULL CHECK', '        val s = readRuntimeStatus(includeRootPid = true)\n        appendLine("=== NFC FULL CHECK', 1)

# Root-aware command runner with Toast only for actual root acquisition failure.
old_root = '''    private fun runRootCmd(command: String): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        process.waitFor()
        buildString { append(out); if (err.isNotBlank()) appendLine(err); appendLine("[exit=${process.exitValue()}]") }
    } catch (t: Throwable) { "ERROR ${t.javaClass.simpleName}: ${t.message}" }
'''
new_root = '''    private fun ensureRootAccess(showToast: Boolean = true): Boolean {
        rootAvailableCache?.let { if (it) return true }
        val ok = try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
            val finished = process.waitFor(4, TimeUnit.SECONDS)
            val output = if (finished) process.inputStream.bufferedReader().readText().trim() else ""
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0 && output.lineSequence().any { it.trim() == "0" }
        } catch (_: Throwable) {
            false
        }
        rootAvailableCache = if (ok) true else null // allow a later retry after the user grants Root.
        if (!ok && showToast) notifyRootUnavailable()
        return ok
    }

    private fun notifyRootUnavailable() {
        val now = System.currentTimeMillis()
        if (now - lastRootToastAt < 3000L) return
        lastRootToastAt = now
        runOnUiThread {
            Toast.makeText(this@MainActivity, "Root 获取失败，请在 Root 管理器中授予本应用权限", Toast.LENGTH_LONG).show()
        }
    }

    private fun runRootCmd(command: String): String {
        if (!ensureRootAccess(showToast = true)) return "ROOT_UNAVAILABLE"
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            process.waitFor()
            buildString { append(out); if (err.isNotBlank()) appendLine(err); appendLine("[exit=${process.exitValue()}]") }
        } catch (t: Throwable) {
            rootAvailableCache = null
            notifyRootUnavailable()
            "ERROR ${t.javaClass.simpleName}: ${t.message}"
        }
    }
'''
if old_root not in s:
    raise SystemExit('root runner block not found')
s = s.replace(old_root, new_root, 1)

p.write_text(s)

# AppLogger: O(1) bounded queue.
p = Path('app/src/main/java/com/example/nfcdoorcard/AppLogger.kt')
s = p.read_text()
s = s.replace('    private val lines = mutableListOf<String>()', '    private val lines = ArrayDeque<String>()')
s = s.replace('        lines += line\n        while (lines.size > 1000) lines.removeAt(0)', '        lines.addLast(line)\n        while (lines.size > 1000) lines.removeFirst()')
p.write_text(s)
