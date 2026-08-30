from pathlib import Path

p = Path('app/src/main/java/com/example/nfcdoorcard/MainActivity.kt')
s = p.read_text()

# Move ConfigProvider observation/state lifetime out of the Composable and into RuntimeStatusViewModel.
s = s.replace('import android.database.ContentObserver\n', '')
s = s.replace('import android.os.Handler\n', '')
s = s.replace('import android.os.Looper\n', '')
if 'import androidx.lifecycle.viewmodel.compose.viewModel\n' not in s:
    s = s.replace('import androidx.compose.ui.unit.sp\n', 'import androidx.compose.ui.unit.sp\nimport androidx.lifecycle.viewmodel.compose.viewModel\n')

old = '''        val cards = savedCardsState
        var status by remember { mutableStateOf(RuntimeStatus()) }
        val logLines = remember { mutableStateListOf<String>() }
        var selectedSource by remember { mutableStateOf(LogSource.STATUS) }
        var diagnosticRunning by remember { mutableStateOf(false) }
        var logsEnabled by remember { mutableStateOf(false) }
        var expandedUid by remember { mutableStateOf<String?>(null) }
        var operationMessage by remember { mutableStateOf<String?>(null) }
        var providerRevision by remember { mutableLongStateOf(0L) }
        val logListState = rememberLazyListState()

        DisposableEffect(Unit) {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { providerRevision++ }
                override fun onChange(selfChange: Boolean, uri: android.net.Uri?) { providerRevision++ }
            }
            contentResolver.registerContentObserver(ConfigProvider.URI, true, observer)
            providerRevision++
            onDispose { runCatching { contentResolver.unregisterContentObserver(observer) } }
        }
'''
new = '''        val cards = savedCardsState
        val runtimeViewModel: RuntimeStatusViewModel = viewModel()
        val status by runtimeViewModel.status.collectAsState()
        val providerRevision by runtimeViewModel.providerRevision.collectAsState()
        val logLines = remember { mutableStateListOf<String>() }
        var selectedSource by remember { mutableStateOf(LogSource.STATUS) }
        var diagnosticRunning by remember { mutableStateOf(false) }
        var logsEnabled by remember { mutableStateOf(false) }
        var expandedUid by remember { mutableStateOf<String?>(null) }
        var operationMessage by remember { mutableStateOf<String?>(null) }
        val logListState = rememberLazyListState()
'''
if s.count(old) != 1:
    raise SystemExit(f'viewmodel state block count={s.count(old)}')
s = s.replace(old, new, 1)

s = s.replace('''            status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                readRuntimeStatus(includeRootPid = false)
            }
''', '''            runtimeViewModel.update(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                readRuntimeStatus(includeRootPid = false)
            })
''', 1)
s = s.replace('''                status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    readRuntimeStatus(includeRootPid = true)
                }
''', '''                runtimeViewModel.update(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    readRuntimeStatus(includeRootPid = true)
                })
''', 1)
s = s.replace('''                status = snapshot.first
                updateLogWindow(logLines, snapshot.second)
''', '''                runtimeViewModel.update(snapshot.first)
                updateLogWindow(logLines, snapshot.second)
''', 1)

# Action callbacks and optimistic UI states now update the StateFlow holder.
s = s.replace('runOnUiThread { status = newStatus; operationMessage = message }',
              'runOnUiThread { runtimeViewModel.update(newStatus); operationMessage = message }')
s = s.replace('''                                status = status.copy(
                                    simulationEnabled = true, selectedUid = card.uid, rfStatus = "WAITING",
                                    operationState = "APPLYING", effectiveState = "UNKNOWN", verificationConfidence = "PENDING", rfAccepted = false
                                )
''', '''                                runtimeViewModel.update(status.copy(
                                    simulationEnabled = true, selectedUid = card.uid, rfStatus = "WAITING",
                                    operationState = "APPLYING", effectiveState = "UNKNOWN", verificationConfidence = "PENDING", rfAccepted = false
                                ))
''', 1)
s = s.replace('''                                status = status.copy(
                                    simulationEnabled = false, rfStatus = "STOPPING",
                                    operationState = "STOPPING", effectiveState = "UNKNOWN", verificationConfidence = "PENDING", rfAccepted = false
                                )
''', '''                                runtimeViewModel.update(status.copy(
                                    simulationEnabled = false, rfStatus = "STOPPING",
                                    operationState = "STOPPING", effectiveState = "UNKNOWN", verificationConfidence = "PENDING", rfAccepted = false
                                ))
''', 1)

# Include a bounded, text-only NFC configuration snapshot in full diagnostics.
old = '''        appendLine("--- NFC PROCESS / HAL ---"); appendLine(runRootCmd("pm path com.android.nfc; pidof com.android.nfc; ps -A | grep -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|com.android.nfc|$packageName'"))
        appendLine("--- NFC SERVICE FULL ---")
'''
new = '''        appendLine("--- NFC PROCESS / HAL ---"); appendLine(runRootCmd("pm path com.android.nfc; pidof com.android.nfc; ps -A | grep -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|com.android.nfc|$packageName'"))
        appendLine("--- NFC CONFIG SNAPSHOT ---")
        appendLine(collectNfcConfigSnapshot())
        appendLine("--- NFC SERVICE FULL ---")
'''
if s.count(old) != 1:
    raise SystemExit(f'report marker count={s.count(old)}')
s = s.replace(old, new, 1)

marker = '''    private fun ensureRootAccess(showToast: Boolean = true): Boolean {
'''
insert = r'''    private fun collectNfcConfigSnapshot(): String {
        val script = """
            echo '--- BUILD / NFC IDENTITY ---'
            getprop ro.product.manufacturer
            getprop ro.product.device
            getprop ro.build.fingerprint
            getprop ro.boot.hardware
            pm path com.android.nfc 2>/dev/null || true
            dumpsys package com.android.nfc 2>/dev/null | grep -E 'versionName=|versionCode=' | head -n 10 || true
            echo '--- NFC CONFIG FILES ---'
            roots='/vendor/etc /odm/etc /product/etc /system/etc /my_product/etc'
            files=""
            for root in ${'$'}roots; do
              [ -d "${'$'}root" ] || continue
              found=${'$'}(find "${'$'}root" -maxdepth 5 -type f \
                \( -iname '*nfc*.conf' -o -iname '*nfc*.cfg' -o -iname '*nfc*.xml' -o \
                   -iname '*nfc*.txt' -o -iname '*nfc*.json' -o -iname '*nfc*.properties' -o \
                   -path '*/nfc/*.conf' -o -path '*/nfc/*.cfg' -o -path '*/nfc/*.xml' \) \
                -size -262144c 2>/dev/null | sort -u | head -n 80)
              files="${'$'}files
${'$'}found"
            done
            echo "${'$'}files" | sed '/^${'$'}/d' | sort -u | while IFS= read -r f; do
              [ -f "${'$'}f" ] || continue
              echo
              echo "===== FILE: ${'$'}f ====="
              ls -lZ "${'$'}f" 2>/dev/null || ls -l "${'$'}f" 2>/dev/null || true
              cat "${'$'}f" 2>/dev/null || echo '[read failed]'
            done
        """.trimIndent()
        return runRootCmd(script, 25, 400_000)
    }

'''
if s.count(marker) != 1:
    raise SystemExit(f'ensureRootAccess marker count={s.count(marker)}')
s = s.replace(marker, insert + marker, 1)

if 'status = ' in s[s.index('private fun NfcAppContent'):s.index('private fun RuntimeStatusPanel')]:
    raise SystemExit('mutable status assignment remains in NfcAppContent')

p.write_text(s)
