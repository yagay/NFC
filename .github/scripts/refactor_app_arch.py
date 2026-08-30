from pathlib import Path
from textwrap import dedent
import re

ROOT = Path('app/src/main/java/com/example/nfcdoorcard')
MAIN = ROOT / 'MainActivity.kt'
s = MAIN.read_text()


def extract_function(src: str, name: str, include_annotation: bool = False):
    marker = f'    private fun {name}'
    idx = src.find(marker)
    if idx < 0:
        raise SystemExit(f'function not found: {name}')
    start = idx
    if include_annotation:
        ann = src.rfind('    @Composable', 0, idx)
        if ann >= 0 and src[ann:idx].strip() == '@Composable':
            start = ann
    brace = src.find('{', idx)
    if brace < 0:
        raise SystemExit(f'opening brace not found: {name}')
    depth = 0
    in_string = False
    triple = False
    escape = False
    i = brace
    while i < len(src):
        if src.startswith('"""', i):
            triple = not triple
            i += 3
            continue
        ch = src[i]
        if not triple:
            if in_string:
                if escape:
                    escape = False
                elif ch == '\\':
                    escape = True
                elif ch == '"':
                    in_string = False
            elif ch == '"':
                in_string = True
            elif ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    end = i + 1
                    while end < len(src) and src[end] in '\r\n':
                        end += 1
                    return src[start:end], src[:start] + src[end:]
        i += 1
    raise SystemExit(f'unclosed function: {name}')

# Move shared runtime models out of the Activity file.
model_start = s.index('enum class LogSource')
activity_start = s.index('class MainActivity')
models = s[model_start:activity_start].rstrip()
s = s[:model_start] + s[activity_start:]
(ROOT / 'RuntimeModels.kt').write_text('package com.example.nfcdoorcard\n\n' + models + '\n')

# Move leaf Compose components without changing their business behavior.
ui_blocks = []
for fn in ['RuntimeStatusPanel', 'ReadCardPanel', 'CardDetails', 'StatusRow', 'CardItem']:
    block, s = extract_function(s, fn, include_annotation=True)
    ui_blocks.append(dedent(block).rstrip())
ui = '\n\n'.join(ui_blocks)
ui = ui.replace(
    'fun RuntimeStatusPanel(status: RuntimeStatus, operationMessage: String?)',
    'fun RuntimeStatusPanel(status: RuntimeStatus, operationMessage: String?, readModeEnabled: Boolean)'
)
ui = ui.replace('EXPECTED_HOOK_BUILD', 'BuildConfig.HOOK_BUILD')
ui = ui.replace('@Composable\nprivate fun', '@Composable\nfun')
ui_dir = ROOT / 'ui'
ui_dir.mkdir(parents=True, exist_ok=True)
(ui_dir / 'NfcComponents.kt').write_text(dedent('''
    package com.example.nfcdoorcard.ui

    import androidx.compose.foundation.clickable
    import androidx.compose.foundation.layout.*
    import androidx.compose.material3.*
    import androidx.compose.runtime.Composable
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.text.font.FontFamily
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.unit.dp
    import androidx.compose.ui.unit.sp
    import com.example.nfcdoorcard.BuildConfig
    import com.example.nfcdoorcard.CardModel
    import com.example.nfcdoorcard.RuntimeStatus
    import com.example.nfcdoorcard.StatusTone

''').lstrip() + ui + '\n')

# NFC foreground dispatch adapter: Android NFC mechanics only, no business state.
nfc_dir = ROOT / 'nfc'
nfc_dir.mkdir(parents=True, exist_ok=True)
(nfc_dir / 'NfcForegroundDispatcher.kt').write_text(dedent(r'''
    package com.example.nfcdoorcard.nfc

    import android.app.Activity
    import android.app.PendingIntent
    import android.content.Intent
    import android.nfc.NfcAdapter
    import android.nfc.Tag
    import android.nfc.tech.NfcA
    import com.example.nfcdoorcard.CardModel

    class NfcForegroundDispatcher(private val activity: Activity) {
        private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
        private val pendingIntent: PendingIntent = PendingIntent.getActivity(
            activity,
            0,
            Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun enable(): Result<Unit> = runCatching {
            adapter?.enableForegroundDispatch(activity, pendingIntent, null, null)
        }

        fun disable() {
            runCatching { adapter?.disableForegroundDispatch(activity) }
        }

        @Suppress("DEPRECATION")
        fun parse(intent: Intent?): CardModel? {
            val tag: Tag = intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return null
            val uid = tag.id.joinToString("") { "%02X".format(it) }.uppercase()
            var sak = "08"
            var atqa = "0400"
            runCatching {
                NfcA.get(tag)?.let {
                    sak = "%02X".format(it.sak.toInt() and 0xFF)
                    atqa = it.atqa.reversedArray().joinToString("") { b -> "%02X".format(b) }.uppercase()
                }
            }
            return CardModel("Card ${uid.takeLast(4)}", uid, sak, atqa)
        }
    }
''').lstrip())

# Root shell execution is isolated from Activity and remains process-per-command for reliability.
system_dir = ROOT / 'system'
system_dir.mkdir(parents=True, exist_ok=True)
(system_dir / 'RootShell.kt').write_text(dedent(r'''
    package com.example.nfcdoorcard.system

    import android.content.Context
    import android.os.Handler
    import android.os.Looper
    import android.widget.Toast
    import java.util.concurrent.TimeUnit

    class RootShell(context: Context) {
        private val appContext = context.applicationContext
        @Volatile private var rootAvailableCache: Boolean? = null
        @Volatile private var lastRootToastAt: Long = 0L

        fun run(command: String, timeoutSeconds: Long = 20, maxChars: Int = 1_000_000, showToast: Boolean = true): String {
            if (!ensureRootAccess(showToast)) return "ROOT_UNAVAILABLE"
            return try {
                val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                val output = StringBuilder()
                val reader = Thread({
                    runCatching {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (output.length < maxChars) {
                                    val remaining = maxChars - output.length
                                    val piece = if (line.length + 1 <= remaining) line + "\n" else line.take(remaining)
                                    output.append(piece)
                                }
                            }
                        }
                    }
                }, "NfcDoorCard-RootReader").apply { isDaemon = true; start() }

                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                }
                reader.join(1500)
                if (!finished) output.appendLine("[timeout=${timeoutSeconds}s]")
                else output.appendLine("[exit=${process.exitValue()}]")
                output.toString()
            } catch (t: Throwable) {
                rootAvailableCache = null
                if (showToast) notifyRootUnavailable()
                "ERROR ${t.javaClass.simpleName}: ${t.message}"
            }
        }

        private fun ensureRootAccess(showToast: Boolean): Boolean {
            rootAvailableCache?.let { if (it) return true }
            val ok = try {
                val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
                val finished = process.waitFor(4, TimeUnit.SECONDS)
                val output = if (finished) process.inputStream.bufferedReader().readText().trim() else ""
                if (!finished) process.destroyForcibly()
                finished && process.exitValue() == 0 && output.lineSequence().any { it.trim() == "0" }
            } catch (_: Throwable) { false }
            rootAvailableCache = if (ok) true else null
            if (!ok && showToast) notifyRootUnavailable()
            return ok
        }

        private fun notifyRootUnavailable() {
            val now = System.currentTimeMillis()
            if (now - lastRootToastAt < 3000L) return
            lastRootToastAt = now
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, "Root 获取失败，请在 Root 管理器中授予本应用权限", Toast.LENGTH_LONG).show()
            }
        }
    }
''').lstrip())

(system_dir / 'NfcSystemService.kt').write_text(dedent(r'''
    package com.example.nfcdoorcard.system

    class NfcSystemService(private val rootShell: RootShell) {
        fun currentNfcPid(): String = rootShell.run(
            "pidof com.android.nfc 2>/dev/null | awk '{print $1}'",
            timeoutSeconds = 5,
            maxChars = 4096,
            showToast = false
        ).lineSequence().firstOrNull { it.trim().matches(Regex("\\d+")) }?.trim().orEmpty()

        fun restartNfcProcessKeepingEnabled(reason: String): String {
            val script = """
                old=$(pidof com.android.nfc 2>/dev/null | awk '{print $1}')
                before=$(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' || true)
                echo "REASON=$reason"
                echo "OLD_PID=$old"
                echo "BEFORE_STATE=$before"
                if [ -n "$old" ]; then
                  kill -TERM "$old" 2>/dev/null || true
                  sleep 0.5
                  kill -0 "$old" 2>/dev/null && kill -KILL "$old" 2>/dev/null || true
                fi
                i=0; new=""
                while [ $i -lt 60 ]; do
                  new=$(pidof com.android.nfc 2>/dev/null | awk '{print $1}')
                  if [ -n "$new" ] && [ "$new" != "$old" ]; then break; fi
                  sleep 0.2; i=$((i+1))
                done
                state=$(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' || true)
                if ! echo "$state" | grep -Eqi 'mState=on|state=on|STATE_ON|mState=3'; then svc nfc enable 2>/dev/null || true; fi
                j=0
                while [ $j -lt 60 ]; do
                  state=$(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' || true)
                  echo "$state" | grep -Eqi 'mState=on|state=on|STATE_ON|mState=3' && break
                  if [ $((j % 10)) -eq 0 ]; then svc nfc enable 2>/dev/null || true; fi
                  sleep 0.25; j=$((j+1))
                done
                new=$(pidof com.android.nfc 2>/dev/null | awk '{print $1}')
                echo "NEW_PID=$new"
                echo "NFC_STATE=$state"
            """.trimIndent()
            return rootShell.run(script, 35)
        }

        fun collectNfcConfigSnapshot(): String {
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
                for root in $roots; do
                  [ -d "$root" ] || continue
                  found=$(find "$root" -maxdepth 5 -type f \
                    \( -iname '*nfc*.conf' -o -iname '*nfc*.cfg' -o -iname '*nfc*.xml' -o \
                       -iname '*nfc*.txt' -o -iname '*nfc*.json' -o -iname '*nfc*.properties' -o \
                       -path '*/nfc/*.conf' -o -path '*/nfc/*.cfg' -o -path '*/nfc/*.xml' \) \
                    -size -262144c 2>/dev/null | sort -u | head -n 80)
                  files="$files
$found"
                done
                echo "$files" | sed '/^$/d' | sort -u | while IFS= read -r f; do
                  [ -f "$f" ] || continue
                  echo
                  echo "===== FILE: $f ====="
                  ls -lZ "$f" 2>/dev/null || ls -l "$f" 2>/dev/null || true
                  cat "$f" 2>/dev/null || echo '[read failed]'
                done
            """.trimIndent()
            return rootShell.run(script, 25, 400_000)
        }
    }
''').lstrip())

# Runtime provider decoder/observer lives outside Activity.
(ROOT / 'RuntimeStatusRepository.kt').write_text(dedent(r'''
    package com.example.nfcdoorcard

    import android.content.ContentObserver
    import android.content.Context
    import android.os.Handler
    import android.os.Looper
    import com.example.nfcdoorcard.system.NfcSystemService
    import kotlinx.coroutines.channels.awaitClose
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.callbackFlow
    import kotlinx.coroutines.flow.conflate

    class RuntimeStatusRepository(context: Context, private val nfcSystemService: NfcSystemService) {
        private val resolver = context.applicationContext.contentResolver

        fun observeChanges(): Flow<Unit> = callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
                override fun onChange(selfChange: Boolean, uri: android.net.Uri?) { trySend(Unit) }
            }
            resolver.registerContentObserver(ConfigProvider.URI, true, observer)
            trySend(Unit)
            awaitClose { runCatching { resolver.unregisterContentObserver(observer) } }
        }.conflate()

        fun readProviderMap(): Map<String, String> {
            val map = mutableMapOf<String, String>()
            runCatching {
                resolver.query(ConfigProvider.URI, null, null, null, null)?.use { c ->
                    while (c.moveToNext()) map[c.getString(0)] = c.getString(1)
                }
            }
            return map
        }

        fun read(includeRootPid: Boolean = false): RuntimeStatus {
            val map = readProviderMap()
            val rootPid = if (includeRootPid) nfcSystemService.currentNfcPid().toIntOrNull() else null
            return decode(map, rootPid)
        }

        fun isCurrentCommandGeneration(generation: Long): Boolean =
            readProviderMap()[ConfigProvider.KEY_COMMAND_GENERATION]?.toLongOrNull() == generation

        fun simulationEnabled(): Boolean = readProviderMap()[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()

        fun decode(map: Map<String, String>, rootPid: Int?): RuntimeStatus {
            val scopePid = map[ConfigProvider.KEY_SCOPE_PID]?.toIntOrNull() ?: 0
            val hookPid = map[ConfigProvider.KEY_HOOK_PID]?.toIntOrNull() ?: 0
            val runtimePid = map[ConfigProvider.KEY_RUNTIME_PID]?.toIntOrNull() ?: 0
            val rfPid = map[ConfigProvider.KEY_RF_PID]?.toIntOrNull() ?: 0
            val commandPid = map[ConfigProvider.KEY_COMMAND_PID]?.toIntOrNull() ?: 0
            val currentPid = rootPid ?: runtimePid.takeIf { it > 0 }
                ?: hookPid.takeIf { it > 0 } ?: commandPid.takeIf { it > 0 } ?: scopePid.takeIf { it > 0 } ?: rfPid
            val hookBuild = map[ConfigProvider.KEY_HOOK_BUILD]?.toIntOrNull() ?: 0
            val rawRfStatus = map[ConfigProvider.KEY_RF_STATUS] ?: "IDLE"
            val rfFresh = currentPid > 0 && rfPid > 0 && rfPid == currentPid && (runtimePid == 0 || runtimePid == currentPid)
            val restartTransition = map[ConfigProvider.KEY_COMMAND_STATUS] == "RESTART_REQUIRED" &&
                map[ConfigProvider.KEY_COMMAND_GENERATION]?.toLongOrNull() == map[ConfigProvider.KEY_RF_GENERATION]?.toLongOrNull()
            val visibleRfStatus = when {
                rawRfStatus == "IDLE" -> "IDLE"
                rfFresh -> rawRfStatus
                restartTransition -> "RESETTING($rawRfStatus)"
                rfPid == 0 && rawRfStatus in setOf("WAITING", "APPLYING", "STOPPING") -> rawRfStatus
                else -> "STALE($rawRfStatus)"
            }
            val semanticVisible = rfFresh || rfPid == 0 || restartTransition
            return RuntimeStatus(
                appBuild = map[ConfigProvider.KEY_APP_BUILD]?.toIntOrNull() ?: 0,
                hookBuild = hookBuild,
                currentPid = currentPid,
                runtimePid = runtimePid,
                scopePid = scopePid,
                hookPid = hookPid,
                scopeOk = currentPid > 0 && scopePid == currentPid && map[ConfigProvider.KEY_SCOPE_OK].toBoolean(),
                hookInstalled = currentPid > 0 && hookPid == currentPid && map[ConfigProvider.KEY_HOOK_INSTALLED].toBoolean(),
                simulationEnabled = map[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean(),
                selectedUid = map[ConfigProvider.KEY_UID]?.takeIf { it.isNotBlank() },
                commandGeneration = map[ConfigProvider.KEY_COMMAND_GENERATION]?.toLongOrNull() ?: 0L,
                consumedGeneration = map[ConfigProvider.KEY_COMMAND_CONSUMED_GENERATION]?.toLongOrNull() ?: Long.MIN_VALUE,
                handledGeneration = map[ConfigProvider.KEY_COMMAND_HANDLED_GENERATION]?.toLongOrNull() ?: Long.MIN_VALUE,
                commandAction = map[ConfigProvider.KEY_COMMAND_ACTION].orEmpty(),
                commandStatus = map[ConfigProvider.KEY_COMMAND_STATUS] ?: "IDLE",
                commandDetail = map[ConfigProvider.KEY_COMMAND_DETAIL]?.takeIf { it.isNotBlank() },
                commandPid = commandPid,
                operationState = if (semanticVisible) map[ConfigProvider.KEY_OPERATION_STATE] ?: "IDLE" else "STALE",
                effectiveState = if (semanticVisible) map[ConfigProvider.KEY_EFFECTIVE_STATE] ?: "UNKNOWN" else "UNKNOWN",
                verificationConfidence = if (semanticVisible) map[ConfigProvider.KEY_VERIFICATION_CONFIDENCE] ?: "NONE" else "NONE",
                rfAccepted = rfFresh && map[ConfigProvider.KEY_RF_ACCEPTED].toBoolean(),
                rfStatus = visibleRfStatus,
                rfUid = if (rfFresh) map[ConfigProvider.KEY_RF_UID]?.takeIf { it.isNotBlank() } else null,
                rfSource = if (rfFresh) map[ConfigProvider.KEY_RF_SOURCE]?.takeIf { it.isNotBlank() } else null,
                rfResult = if (rfFresh) map[ConfigProvider.KEY_RF_RESULT]?.takeIf { it.isNotBlank() } else null,
                rfNativeResult = if (rfFresh) map[ConfigProvider.KEY_RF_NATIVE_RESULT]?.takeIf { it.isNotBlank() } else null,
                rfNativeResultType = if (rfFresh) map[ConfigProvider.KEY_RF_NATIVE_RESULT_TYPE]?.takeIf { it.isNotBlank() } else null,
                rfError = if (rfFresh) map[ConfigProvider.KEY_RF_ERROR]?.takeIf { it.isNotBlank() } else null,
                rfPid = rfPid,
                rfGeneration = map[ConfigProvider.KEY_RF_GENERATION]?.toLongOrNull() ?: 0L,
                rfVerification = if (rfFresh) map[ConfigProvider.KEY_RF_VERIFICATION]?.takeIf { it.isNotBlank() } else if (restartTransition) "LIFECYCLE_PENDING" else null,
                fullDiagStage = map[ConfigProvider.KEY_FULL_DIAG_STAGE]?.takeIf { it.isNotBlank() },
                fullDiagSummary = map[ConfigProvider.KEY_FULL_DIAG_SUMMARY]?.takeIf { it.isNotBlank() }
            )
        }
    }
''').lstrip())

# Replace the ViewModel container with a real reactive state owner.
(ROOT / 'RuntimeStatusViewModel.kt').write_text(dedent(r'''
    package com.example.nfcdoorcard

    import android.app.Application
    import androidx.lifecycle.AndroidViewModel
    import androidx.lifecycle.viewModelScope
    import com.example.nfcdoorcard.system.NfcSystemService
    import com.example.nfcdoorcard.system.RootShell
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.delay
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.asStateFlow
    import kotlinx.coroutines.flow.collectLatest
    import kotlinx.coroutines.isActive
    import kotlinx.coroutines.launch
    import kotlinx.coroutines.withContext

    class RuntimeStatusViewModel(application: Application) : AndroidViewModel(application) {
        private val repository = RuntimeStatusRepository(
            application,
            NfcSystemService(RootShell(application))
        )
        private val _status = MutableStateFlow(RuntimeStatus())
        val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

        init {
            viewModelScope.launch {
                repository.observeChanges().collectLatest {
                    _status.value = withContext(Dispatchers.IO) { repository.read(includeRootPid = false) }
                }
            }
            viewModelScope.launch {
                while (isActive) {
                    _status.value = withContext(Dispatchers.IO) { repository.read(includeRootPid = true) }
                    delay(20_000)
                }
            }
        }

        fun update(value: RuntimeStatus) {
            _status.value = value
        }
    }
''').lstrip())

# MainActivity imports/fields.
for old in [
    'import android.app.PendingIntent\n',
    'import android.nfc.NfcAdapter\n',
    'import android.nfc.Tag\n',
    'import android.nfc.tech.NfcA\n',
    'import java.util.concurrent.TimeUnit\n',
]:
    s = s.replace(old, '')
if 'import com.example.nfcdoorcard.nfc.NfcForegroundDispatcher\n' not in s:
    s = s.replace('import androidx.lifecycle.viewmodel.compose.viewModel\n',
                  'import androidx.lifecycle.viewmodel.compose.viewModel\nimport com.example.nfcdoorcard.nfc.NfcForegroundDispatcher\nimport com.example.nfcdoorcard.system.NfcSystemService\nimport com.example.nfcdoorcard.system.RootShell\nimport com.example.nfcdoorcard.ui.*\n')

s = s.replace(
    '    private var nfcAdapter: NfcAdapter? = null\n    private var pendingIntent: PendingIntent? = null\n',
    '    private lateinit var nfcDispatcher: NfcForegroundDispatcher\n    private lateinit var rootShell: RootShell\n    private lateinit var nfcSystemService: NfcSystemService\n    private lateinit var runtimeRepository: RuntimeStatusRepository\n'
)
s = s.replace('    @Volatile private var rootAvailableCache: Boolean? = null\n    @Volatile private var lastRootToastAt: Long = 0L\n', '')

old_create = '''        nfcAdapter = NfcAdapter.getDefaultAdapter(this)\n        pendingIntent = PendingIntent.getActivity(\n            this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),\n            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT\n        )\n'''
new_create = '''        nfcDispatcher = NfcForegroundDispatcher(this)\n        rootShell = RootShell(this)\n        nfcSystemService = NfcSystemService(rootShell)\n        runtimeRepository = RuntimeStatusRepository(this, nfcSystemService)\n'''
if s.count(old_create) != 1:
    raise SystemExit(f'onCreate NFC init count={s.count(old_create)}')
s = s.replace(old_create, new_create, 1)

old_intent = '''        setIntent(intent)\n        if (readModeEnabled) handleIntent(intent)\n'''
new_intent = '''        setIntent(intent)\n        if (readModeEnabled && !getSimulationEnabled()) {\n            nfcDispatcher.parse(intent)?.let { card ->\n                scannedCardState = card\n                AppLogger.i("CARD: READ uid=${card.uid} sak=${card.sak} atqa=${card.atqa}")\n                stopReadMode("card_read_complete")\n            }\n        }\n'''
if s.count(old_intent) != 1:
    raise SystemExit(f'onNewIntent count={s.count(old_intent)}')
s = s.replace(old_intent, new_intent, 1)

# Replace foreground-dispatch member methods and remove intent parser.
block, s = extract_function(s, 'enableReadDispatch')
replacement = dedent('''
        private fun enableReadDispatch() {
            if (!readModeEnabled) return
            nfcDispatcher.enable()
                .onSuccess { AppLogger.i("READ_MODE: foreground dispatch enabled") }
                .onFailure { AppLogger.i("READ_MODE: enable dispatch failed ${it.javaClass.simpleName}: ${it.message}") }
        }

''')
s = s.replace(block, replacement, 1)
s = s.replace('    private fun disableReadDispatch() { runCatching { nfcAdapter?.disableForegroundDispatch(this) } }\n',
              '    private fun disableReadDispatch() { nfcDispatcher.disable() }\n')
_, s = extract_function(s, 'handleIntent')

# ViewModel owns provider observer + 20s PID watchdog; Activity only collects StateFlow.
s = s.replace('        val providerRevision by runtimeViewModel.providerRevision.collectAsState()\n', '')
observer_block = '''        // Provider changes are the primary state clock: fast, event-driven and root-free.\n        LaunchedEffect(providerRevision) {\n            runtimeViewModel.update(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {\n                readRuntimeStatus(includeRootPid = false)\n            })\n        }\n\n        // External watchdog catches an NFC process replacement that happens before the new\n        // process has had a chance to publish fresh provider state. Keep this deliberately low-rate.\n        LaunchedEffect(Unit) {\n            while (true) {\n                runtimeViewModel.update(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {\n                    readRuntimeStatus(includeRootPid = true)\n                })\n                kotlinx.coroutines.delay(20_000)\n            }\n        }\n\n'''
if s.count(observer_block) != 1:
    raise SystemExit(f'old observer/watchdog block count={s.count(observer_block)}')
s = s.replace(observer_block, '', 1)
s = s.replace('item { RuntimeStatusPanel(status, operationMessage) }',
              'item { RuntimeStatusPanel(status, operationMessage, readModeEnabled) }')

# Runtime state helpers now delegate to RuntimeStatusRepository.
for fn in ['readProviderMap', 'readRuntimeStatus', 'decodeRuntimeStatus']:
    _, s = extract_function(s, fn)
insert_marker = '    private fun buildStatusSummary(s: RuntimeStatus): String = buildString {'
delegates = '''    private fun readProviderMap(): Map<String, String> = runtimeRepository.readProviderMap()\n\n    private fun readRuntimeStatus(includeRootPid: Boolean = false): RuntimeStatus =\n        runtimeRepository.read(includeRootPid)\n\n    private fun decodeRuntimeStatus(map: Map<String, String>, rootPid: Int?): RuntimeStatus =\n        runtimeRepository.decode(map, rootPid)\n\n'''
if s.count(insert_marker) != 1:
    raise SystemExit('buildStatusSummary marker missing')
s = s.replace(insert_marker, delegates + insert_marker, 1)

s = re.sub(
    r'    private fun isCurrentCommandGeneration\(generation: Long\): Boolean =\n        readProviderMap\(\)\[ConfigProvider.KEY_COMMAND_GENERATION\]\?\.toLongOrNull\(\) == generation\n',
    '    private fun isCurrentCommandGeneration(generation: Long): Boolean = runtimeRepository.isCurrentCommandGeneration(generation)\n',
    s,
    count=1
)
s = s.replace(
    '    private fun getSimulationEnabled(): Boolean = readProviderMap()[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()\n',
    '    private fun getSimulationEnabled(): Boolean = runtimeRepository.simulationEnabled()\n'
)

# System service owns NFC process lifecycle/config collection.
_, s = extract_function(s, 'restartNfcProcessKeepingEnabled')
restart_delegate = '''    private fun restartNfcProcessKeepingEnabled(reason: String): String =\n        nfcSystemService.restartNfcProcessKeepingEnabled(reason)\n\n'''
marker = '    private fun waitForCommandCompletion('
s = s.replace(marker, restart_delegate + marker, 1)

current_old = '''    private fun currentNfcPid(): String = runRootCmd("pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}'", 5, 4096)\n        .lineSequence().firstOrNull { it.trim().matches(Regex("\\\\d+")) }?.trim().orEmpty()\n'''
if current_old in s:
    s = s.replace(current_old, '    private fun currentNfcPid(): String = nfcSystemService.currentNfcPid()\n', 1)
else:
    # tolerant alternative for escaped source representation
    start = s.find('    private fun currentNfcPid(): String =')
    if start < 0:
        raise SystemExit('currentNfcPid missing')
    end = s.find('\n\n', start)
    s = s[:start] + '    private fun currentNfcPid(): String = nfcSystemService.currentNfcPid()\n' + s[end:]

_, s = extract_function(s, 'collectNfcConfigSnapshot')
collect_delegate = '    private fun collectNfcConfigSnapshot(): String = nfcSystemService.collectNfcConfigSnapshot()\n\n'
marker = '    private fun ensureRootAccess('
s = s.replace(marker, collect_delegate + marker, 1)

# Remove Activity root implementation and leave one compatibility wrapper used by diagnostics.
for fn in ['ensureRootAccess', 'notifyRootUnavailable', 'runRootCmd']:
    _, s = extract_function(s, fn)
root_delegate = '''    private fun runRootCmd(command: String, timeoutSeconds: Long = 20, maxChars: Int = 1_000_000): String =\n        rootShell.run(command, timeoutSeconds, maxChars)\n\n'''
# Remove obsolete bytesToHex expression and insert root delegate before class close.
s = re.sub(r'    private fun bytesToHex\(bytes: ByteArray\): String = bytes\.joinToString\(""\) \{ "%02X"\.format\(it\) \}\n', '', s, count=1)
last = s.rfind('}')
s = s[:last] + root_delegate + s[last:]

# Bump app version only; Xposed hook build is unchanged because RF core is untouched.
gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
g = g.replace('versionCode = 29', 'versionCode = 30')
g = g.replace('versionName = "1.0.28"', 'versionName = "1.0.29"')
if 'lifecycle-viewmodel-ktx' not in g:
    g = g.replace('implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")',
                  'implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")\n    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")')
gradle.write_text(g)

# Sanity checks: no old providerRevision or direct NFC adapter mechanics remain in Activity.
for forbidden in ['providerRevision', 'NfcAdapter.getDefaultAdapter', 'enableForegroundDispatch(this', 'ProcessBuilder("su"', 'private fun decodeRuntimeStatus(map']:
    if forbidden in s:
        raise SystemExit(f'forbidden legacy pattern remains: {forbidden}')
MAIN.write_text(s)
