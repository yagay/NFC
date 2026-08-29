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
    private var scannedCardState by mutableStateOf<CardModel?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        AppLogger.i("Diagnostics V11 started")
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
        var reloadRunning by remember { mutableStateOf(false) }
        var reloadMessage by remember { mutableStateOf<String?>(null) }
        val scannedCard = scannedCardState
        val logListState = rememberLazyListState()

        LaunchedEffect(selectedSource) {
            while (true) {
                executor.execute {
                    val newStatus = readRuntimeStatus()
                    val logs = fetchLogsSync(selectedSource)
                    runOnUiThread {
                        status = newStatus
                        logText = if (selectedSource == LogSource.HIJACK) {
                            buildStatusSummary(newStatus) + "\n\n" + logs
                        } else logs
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
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                item {
                    RuntimeStatusPanel(
                        status = status,
                        diagnosticRunning = diagnosticRunning,
                        reloadRunning = reloadRunning,
                        reloadMessage = reloadMessage,
                        onReload = {
                            if (!reloadRunning) {
                                reloadRunning = true
                                reloadMessage = "正在重新加载 com.android.nfc..."
                                reloadNfcProcessAndHook { newStatus, message ->
                                    status = newStatus
                                    reloadMessage = message
                                    reloadRunning = false
                                }
                            }
                        },
                        onDiagnostic = {
                            if (!diagnosticRunning) {
                                diagnosticRunning = true
                                runOneTapDiagnosticAndShare { diagnosticRunning = false }
                            }
                        }
                    )
                }

                item {
                    ReadCardPanel(
                        card = scannedCard,
                        onSave = { card ->
                            if (cards.any { it.uid.equals(card.uid, true) }) {
                                Toast.makeText(this@MainActivity, "该卡片已经保存", Toast.LENGTH_SHORT).show()
                            } else {
                                val updated = cards + card
                                saveCards(updated)
                                cards = updated
                                Toast.makeText(this@MainActivity, "卡片已保存", Toast.LENGTH_SHORT).show()
                            }
                            scannedCardState = null
                        },
                        onClear = { scannedCardState = null }
                    )
                }

                item {
                    Text(
                        "已保存卡片 (${cards.size})",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontWeight = FontWeight.Bold
                    )
                }

                if (cards.isEmpty()) {
                    item {
                        Text(
                            "暂无保存卡片。先把门禁卡贴到手机背部读取，然后点击“保存卡片”。",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                } else {
                    items(cards, key = { it.uid }) { card ->
                        val active = status.simulationEnabled && card.uid.equals(status.selectedUid, true)
                        CardItem(
                            card = card,
                            isActive = active,
                            onSimulate = {
                                simulateCard(card)
                                status = status.copy(
                                    simulationEnabled = true,
                                    selectedUid = card.uid,
                                    hijackStatus = "WAITING",
                                    hijackResult = null,
                                    hijackUid = null,
                                    hijackError = null
                                )
                            },
                            onStop = {
                                disableSimulation()
                                status = status.copy(
                                    simulationEnabled = false,
                                    hijackStatus = "IDLE",
                                    hijackResult = null,
                                    hijackUid = null,
                                    hijackError = null
                                )
                            },
                            onDelete = {
                                if (active) disableSimulation()
                                val updated = cards.filter { !it.uid.equals(card.uid, true) }
                                saveCards(updated)
                                cards = updated
                                if (active) status = status.copy(simulationEnabled = false, selectedUid = null, hijackStatus = "IDLE")
                            }
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(6.dp))
                    TabRow(selectedTabIndex = selectedSource.ordinal) {
                        LogSource.entries.forEach { source ->
                            Tab(
                                selected = selectedSource == source,
                                onClick = { selectedSource = source },
                                text = { Text(source.name, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .padding(4.dp)
                            .background(Color(0xFF050505), RoundedCornerShape(4.dp))
                            .padding(4.dp)
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
    }

    @Composable
    private fun RuntimeStatusPanel(
        status: RuntimeStatus,
        diagnosticRunning: Boolean,
        reloadRunning: Boolean,
        reloadMessage: String?,
        onReload: () -> Unit,
        onDiagnostic: () -> Unit
    ) {
        val hijackOk = status.hijackStatus == "SUCCESS"
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("运行状态", fontWeight = FontWeight.Bold)
                StatusRow(
                    "LSPosed 范围",
                    status.scopeOk,
                    if (status.scopeOk) "SUCCESS · ${status.scopeProcess ?: "com.android.nfc"}" else "NOT DETECTED"
                )
                StatusRow(
                    "HCE Hook",
                    status.hookInstalled,
                    if (status.hookInstalled) "SUCCESS · ${status.hookClass?.substringAfterLast('.') ?: "setHceTypeAConfig"} · count=${status.hookCount}" else "NOT INSTALLED"
                )
                StatusRow(
                    "模拟配置",
                    status.simulationEnabled,
                    if (status.simulationEnabled) "ENABLED · UID=${status.selectedUid ?: "missing"}" else "IDLE"
                )
                StatusRow(
                    "UID Hijack",
                    hijackOk,
                    when (status.hijackStatus) {
                        "SUCCESS" -> "SUCCESS · UID=${status.hijackUid ?: status.selectedUid} · native=${status.hijackResult ?: "?"}"
                        "FAILED" -> "FAILED · ${status.hijackError ?: status.hijackResult ?: "unknown"}"
                        "APPLYING", "WAITING" -> "WAITING FOR HCE CALL"
                        else -> "IDLE"
                    }
                )

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("重新加载 Hook / 更新状态", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            reloadMessage ?: "无需重启手机；会重启 NFC 系统进程并等待 LSPosed 重新注入",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = reloadRunning,
                        enabled = !reloadRunning,
                        onCheckedChange = { enabled -> if (enabled) onReload() }
                    )
                }

                Button(onClick = onDiagnostic, enabled = !diagnosticRunning && !reloadRunning, modifier = Modifier.fillMaxWidth()) {
                    Text(if (diagnosticRunning) "检测中..." else "一键检测 + 导出")
                }
            }
        }
    }

    @Composable
    private fun ReadCardPanel(card: CardModel?, onSave: (CardModel) -> Unit, onClear: () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("读取卡片", fontWeight = FontWeight.Bold)
                if (card == null) {
                    Text("NFC 读取已开启，请把门禁卡贴到手机背部。", fontSize = 12.sp)
                    Text("读取后会先显示卡片信息，不会自动保存。", fontSize = 11.sp, color = Color.Gray)
                } else {
                    Text("读取成功", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    Text("UID: ${card.uid}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("SAK: ${card.sak}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("ATQA: ${card.atqa}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSave(card) }, modifier = Modifier.weight(1f)) { Text("保存卡片") }
                        OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("重新读取") }
                    }
                }
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
    private fun CardItem(card: CardModel, isActive: Boolean, onSimulate: () -> Unit, onStop: () -> Unit, onDelete: () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(card.name, fontWeight = FontWeight.Bold)
                    Text("UID ${card.uid}", fontSize = 10.sp, color = Color.Gray)
                    Text("SAK ${card.sak} · ATQA ${card.atqa}", fontSize = 9.sp, color = Color.Gray)
                }
                if (isActive) {
                    Button(onClick = onStop, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("停止模拟", fontSize = 10.sp) }
                } else {
                    Button(onClick = onSimulate, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("模拟", fontSize = 10.sp) }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete card", modifier = Modifier.size(18.dp))
                }
            }
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
        val uid = bytesToHex(tag.id).uppercase()
        var sak = "08"
        var atqa = "0400"
        NfcA.get(tag)?.let {
            sak = "%02X".format(it.sak.toInt() and 0xFF)
            atqa = bytesToHex(it.atqa.reversedArray()).uppercase()
        }
        scannedCardState = CardModel("Card ${uid.takeLast(4)}", uid, sak, atqa)
        AppLogger.i("CARD: READ uid=$uid sak=$sak atqa=$atqa")
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
            put(ConfigProvider.KEY_HIJACK_UID, "")
            put(ConfigProvider.KEY_HIJACK_ERROR, "")
        })
        AppLogger.i("CARD: SIM requested uid=${card.uid} sak=${card.sak} atqa=${card.atqa}")
        restartNfcSafely()
    }

    private fun disableSimulation() {
        contentResolver.insert(ConfigProvider.CONTENT_URI, ContentValues().apply {
            put(ConfigProvider.KEY_SIMULATION_ENABLED, false)
            put(ConfigProvider.KEY_HIJACK_STATUS, "IDLE")
            put(ConfigProvider.KEY_HIJACK_RESULT, "")
            put(ConfigProvider.KEY_HIJACK_UID, "")
            put(ConfigProvider.KEY_HIJACK_ERROR, "")
        })
        AppLogger.i("CARD: simulation stopped")
        restartNfcSafely()
    }

    private fun clearRuntimeHookStatus() {
        try {
            contentResolver.insert(ConfigProvider.CONTENT_URI, ContentValues().apply {
                put(ConfigProvider.KEY_SCOPE_OK, false)
                put(ConfigProvider.KEY_SCOPE_PROCESS, "")
                put(ConfigProvider.KEY_HOOK_INSTALLED, false)
                put(ConfigProvider.KEY_HOOK_CLASS, "")
                put(ConfigProvider.KEY_HOOK_COUNT, 0)
                put(ConfigProvider.KEY_HIJACK_STATUS, if (getSimulationEnabled()) "WAITING" else "IDLE")
                put(ConfigProvider.KEY_HIJACK_RESULT, "")
                put(ConfigProvider.KEY_HIJACK_UID, "")
                put(ConfigProvider.KEY_HIJACK_ERROR, "")
            })
        } catch (e: Exception) {
            AppLogger.i("RELOAD: status clear failed ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun getSimulationEnabled(): Boolean {
        return try {
            contentResolver.query(ConfigProvider.CONTENT_URI, null, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(0) == ConfigProvider.KEY_SIMULATION_ENABLED) {
                        return@use c.getString(1).equals("true", true)
                    }
                }
                false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun reloadNfcProcessAndHook(onDone: (RuntimeStatus, String) -> Unit) {
        executor.execute {
            clearRuntimeHookStatus()
            val script = """
                old=$(pidof com.android.nfc 2>/dev/null | awk '{print $1}')
                echo "OLD_PID=${'$'}old"
                svc nfc disable 2>/dev/null || true
                sleep 1
                if [ -n "${'$'}old" ]; then
                  kill -TERM "${'$'}old" 2>/dev/null || true
                  sleep 1
                  kill -0 "${'$'}old" 2>/dev/null && kill -KILL "${'$'}old" 2>/dev/null || true
                fi
                i=0
                new=""
                while [ ${'$'}i -lt 40 ]; do
                  new=$(pidof com.android.nfc 2>/dev/null | awk '{print $1}')
                  if [ -n "${'$'}new" ] && [ "${'$'}new" != "${'$'}old" ]; then break; fi
                  sleep 0.25
                  i=${'$'}((i+1))
                done
                svc nfc enable 2>/dev/null || true
                i=0
                while [ ${'$'}i -lt 40 ]; do
                  state=$(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' | tr 'A-Z' 'a-z')
                  echo "${'$'}state" | grep -Eq 'mstate=3|state_on|state=on|mstate=on| on' && break
                  sleep 0.25
                  i=${'$'}((i+1))
                done
                final=$(pidof com.android.nfc 2>/dev/null | awk '{print $1}')
                echo "NEW_PID=${'$'}final"
                echo "NFC_STATE=$(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' || true)"
            """.trimIndent()

            val result = runRootCmd(script)
            AppLogger.i("RELOAD: process restart result\n$result")

            var status = RuntimeStatus()
            var attempts = 0
            while (attempts < 20) {
                Thread.sleep(250)
                status = readRuntimeStatus()
                if (status.scopeOk && status.hookInstalled) break
                attempts++
            }

            val oldPid = Regex("OLD_PID=(\\d+)").find(result)?.groupValues?.getOrNull(1)
            val newPid = Regex("NEW_PID=(\\d+)").find(result)?.groupValues?.getOrNull(1)
            val pidChanged = !oldPid.isNullOrBlank() && !newPid.isNullOrBlank() && oldPid != newPid
            val message = when {
                !pidChanged -> "更新失败：NFC 进程没有换 PID（old=${oldPid ?: "?"}, new=${newPid ?: "?"}）"
                status.scopeOk && status.hookInstalled -> "更新成功：PID $oldPid → $newPid，Scope + Hook 已重新加载"
                status.scopeOk -> "进程已重载：PID $oldPid → $newPid，但 Hook 尚未确认"
                else -> "进程已重载：PID $oldPid → $newPid，等待 LSPosed 注入"
            }
            AppLogger.i("RELOAD: $message")
            runOnUiThread { onDone(status, message) }
        }
    }

    private fun readRuntimeStatus(): RuntimeStatus {
        val map = mutableMapOf<String, String>()
        try {
            contentResolver.query(ConfigProvider.CONTENT_URI, null, null, null, null)?.use { c ->
                while (c.moveToNext()) map[c.getString(0)] = c.getString(1)
            }
        } catch (_: Exception) {}

        val lsp = runRootCmd("pid=\$(pidof com.android.nfc 2>/dev/null | awk '{print \$1}'); if [ -n \"\$pid\" ]; then grep -h -E 'com.example.nfcdoorcard|SCOPE: SUCCESS|HOOK: (SUCCESS|INSTALLED)|HIJACK: (SUCCESS|FAILED)' /data/adb/lspd/log/modules* 2>/dev/null | grep \": \$pid:\" | tail -n 200; fi")
        val scopeByLog = lsp.contains("SCOPE: SUCCESS package=com.android.nfc")
        val hookByLog = lsp.contains("HOOK: SUCCESS") || lsp.contains("HOOK: INSTALLED")

        return RuntimeStatus(
            scopeOk = map[ConfigProvider.KEY_SCOPE_OK] == "true" || scopeByLog,
            scopeProcess = map[ConfigProvider.KEY_SCOPE_PROCESS]?.takeIf { it.isNotBlank() } ?: if (scopeByLog) "com.android.nfc" else null,
            hookInstalled = map[ConfigProvider.KEY_HOOK_INSTALLED] == "true" || hookByLog,
            hookClass = map[ConfigProvider.KEY_HOOK_CLASS]?.takeIf { it.isNotBlank() },
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
            LogSource.LSPosed -> "grep -h -E 'com.example.nfcdoorcard|NfcUIDSim|SCOPE:|HOOK:|HIJACK:|RELOAD:|com.android.nfc' /data/adb/lspd/log/modules* 2>/dev/null | tail -n 500"
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
                val file = File(cacheDir, "nfc_fullcheck_v11.txt")
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
        append("=== NFC FULL CHECK V11 ===\nGenerated: ").append(System.currentTimeMillis()).append("\n\n")
        append("--- RUNTIME STATUS ---\n").append(buildStatusSummary(status)).append("\n\n")
        append("--- SAVED CARDS ---\ncount=").append(loadCards().size).append("\n")
        loadCards().forEach { append("card uid=${it.uid} sak=${it.sak} atqa=${it.atqa}\n") }
        append("\n--- APP / APK ---\n").append(inspectOwnApk()).append("\n")
        append("--- ROOT ---\n").append(runRootCmd("id; su -v 2>/dev/null || true")).append("\n")
        append("--- NFC PROCESS ---\n").append(runRootCmd("pm path com.android.nfc; pidof com.android.nfc; ps -A | grep -i '[n]fc' || true")).append("\n")
        append("--- NFC SERVICE ---\n").append(runRootCmd("dumpsys nfc 2>/dev/null | head -n 220")).append("\n")
        append("--- LSPOSED MODULE ---\n").append(runRootCmd("grep -h -n -E 'com.example.nfcdoorcard|NfcUIDSim|SCOPE:|HOOK:|HIJACK:|RELOAD:|com.android.nfc' /data/adb/lspd/log/modules* 2>/dev/null | tail -n 1200")).append("\n")
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
        } catch (e: Exception) {
            out.append("APK_INSPECTION_ERROR=${e.javaClass.simpleName}:${e.message}\n")
        }
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
    } catch (e: Exception) {
        "Error: ${e.javaClass.simpleName}: ${e.message}\n"
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
