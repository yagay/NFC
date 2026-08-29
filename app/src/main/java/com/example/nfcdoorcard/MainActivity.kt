package com.example.nfcdoorcard

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.zip.ZipFile

enum class LogSource { HIJACK, LSPosed, KernelSU }

data class RuntimeStatus(
    val scopeOk: Boolean = false,
    val scopeProcess: String? = null,
    val hookInstalled: Boolean = false,
    val hookClass: String? = null,
    val hookCount: Int = 0,
    val simulationEnabled: Boolean = false,
    val selectedUid: String? = null,
    val hijackStatus: String = "IDLE",
    val hijackResult: String? = null,
    val hijackUid: String? = null,
    val hijackError: String? = null
)

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()
    private val prefsCards = "saved_cards"
    private val keyCardsList = "cards_list"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        AppLogger.i("Diagnostics V9 started")
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { NfcAppContent() }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NfcAppContent() {
        var cards by remember { mutableStateOf(loadCards()) }
        var status by remember { mutableStateOf(readRuntimeStatus()) }
        var logText by remember { mutableStateOf("") }
        var selectedSource by remember { mutableStateOf(LogSource.HIJACK) }
        var diagnosticRunning by remember { mutableStateOf(false) }
        val logListState = rememberLazyListState()

        LaunchedEffect(selectedSource) {
            while (true) {
                executor.execute {
                    val newStatus = readRuntimeStatus()
                    fetchLogsSync(selectedSource).also { logs ->
                        runOnUiThread {
                            status = newStatus
                            logText = if (selectedSource == LogSource.HIJACK) {
                                buildStatusSummary(newStatus) + "\n\n" + logs
                            } else logs
                        }
                    }
                }
                kotlinx.coroutines.delay(3000)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("NFC Expert Pro") },
                    actions = {
                        IconButton(onClick = {
                            if (!diagnosticRunning) {
                                diagnosticRunning = true
                                runOneTapDiagnosticAndShare { diagnosticRunning = false }
                            }
                        }) { Icon(Icons.Default.Share, contentDescription = "Export diagnostics") }
                        IconButton(onClick = { AppLogger.clear(); logText = "" }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear log")
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                RuntimeStatusPanel(status)

                LazyColumn(modifier = Modifier.height(150.dp).fillMaxWidth()) {
                    items(cards) { card ->
                        CardItem(
                            card = card,
                            isActive = status.simulationEnabled && card.uid.equals(status.selectedUid, true),
                            onSimulate = {
                                simulateCard(card)
                                status = status.copy(simulationEnabled = true, selectedUid = card.uid, hijackStatus = "WAITING")
                            },
                            onDelete = {
                                cards = cards.filter { it.uid != card.uid }
                                saveCards(cards)
                                if (card.uid.equals(status.selectedUid, true)) disableSimulation()
                            }
                        )
                    }
                }

                TabRow(selectedTabIndex = selectedSource.ordinal) {
                    LogSource.entries.forEach { source ->
                        Tab(
                            selected = selectedSource == source,
                            onClick = { selectedSource = source },
                            text = { Text(source.name, fontSize = 11.sp) }
                        )
                    }
                }

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp)
                        .background(Color(0xFF050505), RoundedCornerShape(4.dp)).padding(4.dp)
                ) {
                    val lines = logText.split("\n")
                    LazyColumn(state = logListState, modifier = Modifier.fillMaxSize()) {
                        items(lines) { line ->
                            Text(
                                text = line,
                                color = when {
                                    line.contains("SUCCESS") || line.contains("INSTALLED") -> Color.Cyan
                                    line.contains("FAILED") || line.contains("ERROR") -> Color.Red
                                    line.contains("WAITING") || line.contains("IDLE") -> Color.Yellow
                                    else -> Color(0xFFD4D4D4)
                                },
                                fontSize = 9.sp,
                                lineHeight = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    LaunchedEffect(lines.size) {
                        if (lines.isNotEmpty()) logListState.animateScrollToItem(lines.size - 1)
                    }
                }
            }
        }
    }

    @Composable
    private fun RuntimeStatusPanel(status: RuntimeStatus) {
        val hijackOk = status.hijackStatus == "SUCCESS"
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Runtime status", fontWeight = FontWeight.Bold)
                StatusRow(
                    "LSPosed scope",
                    status.scopeOk,
                    if (status.scopeOk) "SUCCESS · ${status.scopeProcess ?: "com.android.nfc"}" else "NOT DETECTED"
                )
                StatusRow(
                    "HCE hook",
                    status.hookInstalled,
                    if (status.hookInstalled) "SUCCESS · ${status.hookClass?.substringAfterLast('.') ?: "setHceTypeAConfig"} · count=${status.hookCount}" else "NOT INSTALLED"
                )
                StatusRow(
                    "Simulation config",
                    status.simulationEnabled,
                    if (status.simulationEnabled) "ENABLED · UID=${status.selectedUid ?: "missing"}" else "IDLE"
                )
                StatusRow(
                    "UID hijack",
                    hijackOk,
                    when (status.hijackStatus) {
                        "SUCCESS" -> "SUCCESS · UID=${status.hijackUid ?: status.selectedUid} · native=${status.hijackResult ?: "?"}"
                        "FAILED" -> "FAILED · ${status.hijackError ?: status.hijackResult ?: "unknown"}"
                        "APPLYING", "WAITING" -> "WAITING FOR HCE CALL"
                        else -> "IDLE"
                    }
                )
                Button(
                    onClick = { runOneTapDiagnosticAndShare {} },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ONE-TAP CHECK + EXPORT") }
            }
        }
    }

    @Composable
    private fun StatusRow(label: String, ok: Boolean, detail: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(detail, fontSize = 11.sp)
            }
        }
    }

    @Composable
    private fun CardItem(card: CardModel, isActive: Boolean, onSimulate: () -> Unit, onDelete: () -> Unit) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(card.name, fontWeight = FontWeight.Bold)
                Text(card.uid, fontSize = 10.sp, color = Color.Gray)
            }
            Button(onClick = onSimulate, enabled = !isActive, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(if (isActive) "SELECTED" else "SIM", fontSize = 10.sp)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp)) }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        val uid = bytesToHex(tag.id)
        var sak = "08"
        var atqa = "0400"
        NfcA.get(tag)?.let {
            sak = "%02x".format(it.sak.toInt() and 0xFF)
            atqa = bytesToHex(it.atqa.reversedArray())
        }
        val current = loadCards().toMutableList()
        if (current.none { it.uid.equals(uid, true) }) {
            current.add(CardModel("Card ${uid.takeLast(4)}", uid, sak, atqa))
            saveCards(current)
            recreate()
        }
    }

    private fun loadCards(): List<CardModel> {
        val json = getSharedPreferences(prefsCards, MODE_PRIVATE).getString(keyCardsList, null) ?: return emptyList()
        return try { gson.fromJson(json, object : TypeToken<List<CardModel>>() {}.type) } catch (_: Exception) { emptyList() }
    }

    private fun saveCards(cards: List<CardModel>) {
        getSharedPreferences(prefsCards, MODE_PRIVATE).edit().putString(keyCardsList, gson.toJson(cards)).apply()
    }

    private fun simulateCard(card: CardModel) {
        contentResolver.insert(ConfigProvider.CONTENT_URI, ContentValues().apply {
            put(ConfigProvider.KEY_SIMULATION_ENABLED, true)
            put(ConfigProvider.KEY_UID, card.uid)
            put(ConfigProvider.KEY_SAK, card.sak)
            put(ConfigProvider.KEY_ATQA, card.atqa)
            put(ConfigProvider.KEY_HIJACK_STATUS, "WAITING")
            put(ConfigProvider.KEY_HIJACK_RESULT, "")
            put(ConfigProvider.KEY_HIJACK_ERROR, "")
        })
        restartNfcSafely()
    }

    private fun disableSimulation() {
        contentResolver.insert(ConfigProvider.CONTENT_URI, ContentValues().apply {
            put(ConfigProvider.KEY_SIMULATION_ENABLED, false)
            put(ConfigProvider.KEY_HIJACK_STATUS, "IDLE")
            put(ConfigProvider.KEY_HIJACK_RESULT, "")
            put(ConfigProvider.KEY_HIJACK_ERROR, "")
        })
        restartNfcSafely()
    }

    private fun readRuntimeStatus(): RuntimeStatus {
        val map = mutableMapOf<String, String>()
        try {
            contentResolver.query(ConfigProvider.CONTENT_URI, null, null, null, null)?.use { c ->
                while (c.moveToNext()) map[c.getString(0)] = c.getString(1)
            }
        } catch (_: Exception) {}

        val lsp = runRootCmd("grep -h -E 'com.example.nfcdoorcard|SCOPE: SUCCESS|HOOK: (SUCCESS|INSTALLED)|HIJACK: (SUCCESS|FAILED)' /data/adb/lspd/log/modules* 2>/dev/null | tail -n 200")
        val scopeByLog = lsp.contains("(com.android.nfc)[com.example.nfcdoorcard") || lsp.contains("SCOPE: SUCCESS package=com.android.nfc")
        val hookByLog = lsp.contains("HOOK: SUCCESS") || lsp.contains("HOOK: INSTALLED")

        return RuntimeStatus(
            scopeOk = map[ConfigProvider.KEY_SCOPE_OK] == "true" || scopeByLog,
            scopeProcess = map[ConfigProvider.KEY_SCOPE_PROCESS] ?: if (scopeByLog) "com.android.nfc" else null,
            hookInstalled = map[ConfigProvider.KEY_HOOK_INSTALLED] == "true" || hookByLog,
            hookClass = map[ConfigProvider.KEY_HOOK_CLASS],
            hookCount = map[ConfigProvider.KEY_HOOK_COUNT]?.toIntOrNull() ?: if (hookByLog) 1 else 0,
            simulationEnabled = map[ConfigProvider.KEY_SIMULATION_ENABLED] == "true",
            selectedUid = map[ConfigProvider.KEY_UID],
            hijackStatus = map[ConfigProvider.KEY_HIJACK_STATUS] ?: "IDLE",
            hijackResult = map[ConfigProvider.KEY_HIJACK_RESULT],
            hijackUid = map[ConfigProvider.KEY_HIJACK_UID],
            hijackError = map[ConfigProvider.KEY_HIJACK_ERROR]
        )
    }

    private fun buildStatusSummary(status: RuntimeStatus): String = buildString {
        append("=== CURRENT STATUS ===\n")
        append("SCOPE: ").append(if (status.scopeOk) "SUCCESS" else "NOT DETECTED").append(" process=").append(status.scopeProcess).append('\n')
        append("HOOK: ").append(if (status.hookInstalled) "SUCCESS" else "NOT INSTALLED").append(" class=").append(status.hookClass).append(" count=").append(status.hookCount).append('\n')
        append("CONFIG: ").append(if (status.simulationEnabled) "ENABLED" else "IDLE").append(" uid=").append(status.selectedUid).append('\n')
        append("HIJACK: ").append(status.hijackStatus).append(" uid=").append(status.hijackUid).append(" native=").append(status.hijackResult)
        if (!status.hijackError.isNullOrBlank()) append(" error=").append(status.hijackError)
    }

    private fun fetchLogsSync(source: LogSource): String {
        val cmd = when (source) {
            LogSource.HIJACK -> "logcat -d -t 700 -s NfcUIDSim"
            LogSource.LSPosed -> "grep -h -E 'com.example.nfcdoorcard|NfcUIDSim|SCOPE:|HOOK:|HIJACK:|com.android.nfc' /data/adb/lspd/log/modules* 2>/dev/null | tail -n 500"
            LogSource.KernelSU -> "ls -t /data/adb/ksu/log/sulog* 2>/dev/null | head -n 1 | xargs -r cat | tail -n 300"
        }
        return runRootCmd(cmd).ifBlank { "No matching logs found for $source" }
    }

    private fun restartNfcSafely() {
        executor.execute {
            val result = runRootCmd("svc nfc disable; sleep 1; svc nfc enable; sleep 2; dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' || true")
            AppLogger.i("NFC restart: ${result.trim()}")
        }
    }

    private fun runOneTapDiagnosticAndShare(onDone: () -> Unit) {
        executor.execute {
            try {
                val status = readRuntimeStatus()
                val file = File(cacheDir, "nfc_fullcheck_v9.txt")
                file.writeText(buildFullDiagnosticReport(status))
                runOnUiThread {
                    onDone()
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share NFC Full Check"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    onDone()
                    Toast.makeText(this@MainActivity, "Diagnostic failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildFullDiagnosticReport(status: RuntimeStatus): String = buildString {
        append("=== NFC FULL CHECK V9 ===\nGenerated: ").append(System.currentTimeMillis()).append("\n\n")
        append("--- RUNTIME STATUS ---\n").append(buildStatusSummary(status)).append("\n\n")
        append("--- APP / APK ---\n").append(inspectOwnApk()).append("\n")
        append("--- ROOT ---\n").append(runRootCmd("id; su -v 2>/dev/null || true")).append("\n")
        append("--- NFC PROCESS ---\n").append(runRootCmd("pm path com.android.nfc; pidof com.android.nfc; ps -A | grep -i '[n]fc' || true")).append("\n")
        append("--- NFC SERVICE ---\n").append(runRootCmd("dumpsys nfc 2>/dev/null | head -n 220")).append("\n")
        append("--- LSPOSED MODULE ---\n").append(runRootCmd("grep -h -n -E 'com.example.nfcdoorcard|NfcUIDSim|SCOPE:|HOOK:|HIJACK:|com.android.nfc' /data/adb/lspd/log/modules* 2>/dev/null | tail -n 1200")).append("\n")
        append("--- HIJACK LOGCAT ---\n").append(runRootCmd("logcat -d -t 1800 -s NfcUIDSim")).append("\n")
        append("--- APP LOG ---\n").append(AppLogger.getAllLogs()).append("\n")
    }

    private fun inspectOwnApk(): String {
        val out = StringBuilder()
        val info = packageManager.getPackageInfo(packageName, 0)
        val source = applicationInfo.sourceDir
        out.append("package=$packageName\nversionName=${info.versionName}\nversionCode=${info.longVersionCode}\nsourceDir=$source\n")
        try {
            ZipFile(source).use { zip ->
                listOf("META-INF/xposed/java_init.list", "META-INF/xposed/scope.list", "META-INF/xposed/module.prop").forEach { name ->
                    val entry = zip.getEntry(name)
                    out.append(name).append('=').append(if (entry != null) "OK" else "MISSING").append('\n')
                    if (entry != null) zip.getInputStream(entry).bufferedReader().use { out.append(it.readText().trim()).append('\n') }
                }
            }
        } catch (e: Exception) { out.append("APK_INSPECTION_ERROR=${e.javaClass.simpleName}:${e.message}\n") }
        return out.toString()
    }

    private fun runRootCmd(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val stdout = BufferedReader(InputStreamReader(p.inputStream)).readText()
        val stderr = BufferedReader(InputStreamReader(p.errorStream)).readText()
        val exit = p.waitFor()
        buildString {
            append(stdout)
            if (stderr.isNotBlank()) append("\n[stderr]\n").append(stderr)
            append("\n[exit=").append(exit).append("]\n")
        }
    } catch (e: Exception) { "Error: ${e.javaClass.simpleName}: ${e.message}\n" }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
