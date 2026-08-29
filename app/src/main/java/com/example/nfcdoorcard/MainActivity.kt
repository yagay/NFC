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
import java.io.File
import java.util.concurrent.Executors

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
    val hijackError: String? = null,
    val rfStatus: String = "WAITING",
    val rfUid: String? = null,
    val rfSource: String? = null,
    val rfResult: String? = null,
    val rfError: String? = null
)

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()
    private var scannedCardState by mutableStateOf<CardModel?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        AppLogger.i("Diagnostics V12 full UI started")
        handleIntent(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { NfcAppContent() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        runCatching { nfcAdapter?.disableForegroundDispatch(this) }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val tag: Tag = intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return
        val uid = bytesToHex(tag.id).uppercase()
        var sak = "08"
        var atqa = "0400"
        runCatching {
            NfcA.get(tag)?.let {
                sak = "%02X".format(it.sak.toInt() and 0xFF)
                atqa = bytesToHex(it.atqa.reversedArray()).uppercase()
            }
        }
        scannedCardState = CardModel("Card ${uid.takeLast(4)}", uid, sak, atqa)
        AppLogger.i("CARD: READ uid=$uid sak=$sak atqa=$atqa")
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
                    title = { Text("NFC Expert Pro V12") },
                    actions = {
                        TextButton(onClick = {
                            if (!diagnosticRunning) {
                                diagnosticRunning = true
                                runOneTapDiagnosticAndShare { diagnosticRunning = false }
                            }
                        }) { Text("导出") }
                        TextButton(onClick = {
                            AppLogger.clear()
                            logText = ""
                        }) { Text("清空") }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                item {
                    RuntimeStatusPanel(
                        status = status,
                        diagnosticRunning = diagnosticRunning,
                        reloadRunning = reloadRunning,
                        reloadMessage = reloadMessage,
                        onRefresh = { status = readRuntimeStatus() },
                        onReload = {
                            if (!reloadRunning) {
                                reloadRunning = true
                                reloadMessage = "正在重新加载 com.android.nfc..."
                                reloadNfcProcessAndHook { newStatus, message ->
                                    runOnUiThread {
                                        status = newStatus
                                        reloadMessage = message
                                        reloadRunning = false
                                    }
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
                                AppLogger.i("CARD: SAVED uid=${card.uid}")
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
                                    hijackError = null,
                                    rfStatus = "WAITING",
                                    rfUid = null,
                                    rfSource = null,
                                    rfResult = null,
                                    rfError = null
                                )
                            },
                            onStop = {
                                disableSimulation()
                                status = status.copy(
                                    simulationEnabled = false,
                                    hijackStatus = "IDLE",
                                    rfStatus = "IDLE"
                                )
                            },
                            onDelete = {
                                if (active) disableSimulation()
                                val updated = cards.filterNot { it.uid.equals(card.uid, true) }
                                saveCards(updated)
                                cards = updated
                                if (active) status = readRuntimeStatus()
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
                            .height(280.dp)
                            .padding(6.dp)
                            .background(Color(0xFF050505), RoundedCornerShape(4.dp))
                            .padding(6.dp)
                    ) {
                        val lines = logText.split("\n")
                        LazyColumn(state = logListState, modifier = Modifier.fillMaxSize()) {
                            items(lines) { line ->
                                Text(
                                    text = line,
                                    color = when {
                                        line.contains("SUCCESS") || line.contains("ACCEPTED") || line.contains("INSTALLED") -> Color.Cyan
                                        line.contains("FAILED") || line.contains("ERROR") -> Color.Red
                                        line.contains("WAITING") || line.contains("IDLE") -> Color.Yellow
                                        line.contains("RF:") || line.contains("NFCID1") -> Color.Green
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
        onRefresh: () -> Unit,
        onReload: () -> Unit,
        onDiagnostic: () -> Unit
    ) {
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("运行状态", fontWeight = FontWeight.Bold)
                StatusRow("LSPosed 范围", status.scopeOk, if (status.scopeOk) "SUCCESS · ${status.scopeProcess ?: "com.android.nfc"}" else "NOT DETECTED")
                StatusRow("HCE Hook", status.hookInstalled, if (status.hookInstalled) "SUCCESS · ${status.hookClass?.substringAfterLast('.') ?: "hook"} · count=${status.hookCount}" else "NOT INSTALLED")
                StatusRow("模拟配置", status.simulationEnabled, if (status.simulationEnabled) "ENABLED · UID=${status.selectedUid ?: "missing"}" else "IDLE")
                StatusRow(
                    "HCE Native",
                    status.hijackStatus == "SUCCESS" || status.hijackStatus == "NATIVE_ACCEPTED",
                    when (status.hijackStatus) {
                        "SUCCESS", "NATIVE_ACCEPTED" -> "NATIVE ACCEPTED · UID=${status.hijackUid ?: status.selectedUid} · result=${status.hijackResult ?: "?"}"
                        "FAILED" -> "FAILED · ${status.hijackError ?: status.hijackResult ?: "unknown"}"
                        "APPLYING", "WAITING" -> "WAITING FOR HCE CALL"
                        else -> status.hijackStatus
                    }
                )
                val rfOk = status.rfStatus.contains("ACCEPTED", true) || status.rfStatus.contains("APPLIED", true) || status.rfStatus.contains("REWRITE", true)
                StatusRow(
                    "RF NFCID1",
                    rfOk,
                    buildString {
                        append(status.rfStatus)
                        status.rfUid?.let { append(" · UID=$it") }
                        status.rfResult?.let { append(" · $it") }
                    }
                )
                status.rfSource?.let { Text("RF source: $it", fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                status.rfError?.takeIf { it.isNotBlank() }?.let { Text("RF error: $it", fontSize = 10.sp, color = Color.Red) }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("重新加载 Hook / 更新状态", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            reloadMessage ?: "无需重启手机；重启 NFC 系统进程并等待 LSPosed 重新注入",
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f), enabled = !reloadRunning) {
                        Text("刷新状态")
                    }
                    Button(onClick = onDiagnostic, modifier = Modifier.weight(1f), enabled = !diagnosticRunning && !reloadRunning) {
                        Text(if (diagnosticRunning) "检测中..." else "一键检测 + 导出")
                    }
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
                    Text("读取后先显示 UID / SAK / ATQA，不会自动保存。", fontSize = 11.sp, color = Color.Gray)
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
            Text(if (ok) "●" else "○", color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828), fontSize = 18.sp)
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
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("删除", fontSize = 10.sp) }
            }
        }
    }

    private fun simulateCard(card: CardModel) {
        contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
            put(ConfigProvider.KEY_SIMULATION_ENABLED, true)
            put(ConfigProvider.KEY_UID, card.uid)
            put(ConfigProvider.KEY_SAK, card.sak)
            put(ConfigProvider.KEY_ATQA, card.atqa)
            put(ConfigProvider.KEY_HIJACK_STATUS, "WAITING")
            put(ConfigProvider.KEY_HIJACK_UID, "")
            put(ConfigProvider.KEY_HIJACK_RESULT, "")
            put(ConfigProvider.KEY_HIJACK_ERROR, "")
            put(ConfigProvider.KEY_RF_STATUS, "WAITING")
            put(ConfigProvider.KEY_RF_UID, "")
            put(ConfigProvider.KEY_RF_SOURCE, "")
            put(ConfigProvider.KEY_RF_RESULT, "")
            put(ConfigProvider.KEY_RF_ERROR, "")
        })
        AppLogger.i("CARD: SIM requested uid=${card.uid} sak=${card.sak} atqa=${card.atqa}")
        restartNfcSafely()
    }

    private fun disableSimulation() {
        contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
            put(ConfigProvider.KEY_SIMULATION_ENABLED, false)
            put(ConfigProvider.KEY_HIJACK_STATUS, "IDLE")
            put(ConfigProvider.KEY_HIJACK_RESULT, "")
            put(ConfigProvider.KEY_HIJACK_UID, "")
            put(ConfigProvider.KEY_HIJACK_ERROR, "")
            put(ConfigProvider.KEY_RF_STATUS, "IDLE")
            put(ConfigProvider.KEY_RF_UID, "")
            put(ConfigProvider.KEY_RF_SOURCE, "")
            put(ConfigProvider.KEY_RF_RESULT, "")
            put(ConfigProvider.KEY_RF_ERROR, "")
        })
        AppLogger.i("CARD: simulation stopped")
        restartNfcSafely()
    }

    private fun restartNfcSafely() {
        executor.execute {
            val result = runRootCmd("svc nfc disable; sleep 1; svc nfc enable; sleep 2; dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' || true")
            AppLogger.i("NFC restart: ${result.trim()}")
        }
    }

    private fun reloadNfcProcessAndHook(onDone: (RuntimeStatus, String) -> Unit) {
        executor.execute {
            clearRuntimeHookStatus()
            val script = """
                old=${'$'}(pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}')
                echo "OLD_PID=${'$'}old"
                svc nfc disable 2>/dev/null || true
                sleep 1
                if [ -n "${'$'}old" ]; then
                  kill -TERM "${'$'}old" 2>/dev/null || true
                  sleep 1
                  kill -0 "${'$'}old" 2>/dev/null && kill -KILL "${'$'}old" 2>/dev/null || true
                fi
                svc nfc enable 2>/dev/null || true
                i=0
                new=""
                while [ ${'$'}i -lt 40 ]; do
                  new=${'$'}(pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}')
                  if [ -n "${'$'}new" ] && [ "${'$'}new" != "${'$'}old" ]; then break; fi
                  sleep 0.25
                  i=${'$'}((i+1))
                done
                j=0
                state=""
                while [ ${'$'}j -lt 20 ]; do
                  state=${'$'}(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' || true)
                  echo "${'$'}state" | grep -Eqi 'mState=on|state=on|STATE_ON|mState=3' && break
                  sleep 0.25
                  j=${'$'}((j+1))
                done
                final=${'$'}(pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}')
                echo "NEW_PID=${'$'}final"
                echo "NFC_STATE=${'$'}state"
            """.trimIndent()
            val result = runRootCmd(script)
            AppLogger.i("RELOAD: process restart result\n$result")
            var state = RuntimeStatus()
            repeat(20) {
                Thread.sleep(250)
                state = readRuntimeStatus()
                if (state.scopeOk && state.hookInstalled) return@repeat
            }
            val oldPid = Regex("OLD_PID=(\\d+)").find(result)?.groupValues?.getOrNull(1)
            val newPid = Regex("NEW_PID=(\\d+)").find(result)?.groupValues?.getOrNull(1)
            val msg = when {
                oldPid != null && newPid != null && oldPid != newPid && state.scopeOk && state.hookInstalled -> "更新成功：PID $oldPid → $newPid，Scope + Hook 已重新加载"
                oldPid != null && newPid != null && oldPid != newPid -> "进程已重启：PID $oldPid → $newPid，等待 LSPosed 注入"
                else -> "重新加载失败：com.android.nfc PID 未变化"
            }
            AppLogger.i("RELOAD: $msg")
            onDone(state, msg)
        }
    }

    private fun clearRuntimeHookStatus() {
        contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
            put(ConfigProvider.KEY_SCOPE_OK, false)
            put(ConfigProvider.KEY_SCOPE_PROCESS, "")
            put(ConfigProvider.KEY_HOOK_INSTALLED, false)
            put(ConfigProvider.KEY_HOOK_CLASS, "")
            put(ConfigProvider.KEY_HOOK_COUNT, 0)
            put(ConfigProvider.KEY_HIJACK_STATUS, if (getSimulationEnabled()) "WAITING" else "IDLE")
            put(ConfigProvider.KEY_HIJACK_RESULT, "")
            put(ConfigProvider.KEY_HIJACK_UID, "")
            put(ConfigProvider.KEY_HIJACK_ERROR, "")
            put(ConfigProvider.KEY_RF_STATUS, if (getSimulationEnabled()) "WAITING" else "IDLE")
            put(ConfigProvider.KEY_RF_UID, "")
            put(ConfigProvider.KEY_RF_SOURCE, "")
            put(ConfigProvider.KEY_RF_RESULT, "")
            put(ConfigProvider.KEY_RF_ERROR, "")
        })
    }

    private fun getSimulationEnabled(): Boolean = readRuntimeStatusNoLogs().simulationEnabled

    private fun readRuntimeStatusNoLogs(): RuntimeStatus {
        val map = mutableMapOf<String, String>()
        runCatching {
            contentResolver.query(ConfigProvider.URI, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) map[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return runtimeStatusFromMap(map, false, false)
    }

    private fun readRuntimeStatus(): RuntimeStatus {
        val map = mutableMapOf<String, String>()
        runCatching {
            contentResolver.query(ConfigProvider.URI, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) map[cursor.getString(0)] = cursor.getString(1)
            }
        }
        var scopeByLog = false
        var hookByLog = false
        val currentPid = runRootCmd("pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}'").lineSequence().firstOrNull()?.trim().orEmpty()
        if (currentPid.isNotBlank()) {
            val lsp = runRootCmd("grep -h -E 'SCOPE: SUCCESS|HOOK: (SUCCESS|INSTALLED)|RF: NFCID1|RF: CONFIG|HIJACK:' /data/adb/lspd/log/modules* 2>/dev/null | grep ': $currentPid:' | tail -n 300")
            scopeByLog = lsp.contains("SCOPE: SUCCESS package=com.android.nfc")
            hookByLog = lsp.contains("HOOK: SUCCESS") || lsp.contains("HOOK: INSTALLED")
        }
        return runtimeStatusFromMap(map, scopeByLog, hookByLog)
    }

    private fun runtimeStatusFromMap(map: Map<String, String>, scopeByLog: Boolean, hookByLog: Boolean): RuntimeStatus = RuntimeStatus(
        scopeOk = map[ConfigProvider.KEY_SCOPE_OK].toBoolean() || scopeByLog,
        scopeProcess = map[ConfigProvider.KEY_SCOPE_PROCESS]?.takeIf { it.isNotBlank() } ?: if (scopeByLog) "com.android.nfc" else null,
        hookInstalled = map[ConfigProvider.KEY_HOOK_INSTALLED].toBoolean() || hookByLog,
        hookClass = map[ConfigProvider.KEY_HOOK_CLASS]?.takeIf { it.isNotBlank() },
        hookCount = map[ConfigProvider.KEY_HOOK_COUNT]?.toIntOrNull() ?: if (hookByLog) 1 else 0,
        simulationEnabled = map[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean(),
        selectedUid = map[ConfigProvider.KEY_UID]?.takeIf { it.isNotBlank() },
        hijackStatus = map[ConfigProvider.KEY_HIJACK_STATUS] ?: "IDLE",
        hijackResult = map[ConfigProvider.KEY_HIJACK_RESULT]?.takeIf { it.isNotBlank() },
        hijackUid = map[ConfigProvider.KEY_HIJACK_UID]?.takeIf { it.isNotBlank() },
        hijackError = map[ConfigProvider.KEY_HIJACK_ERROR]?.takeIf { it.isNotBlank() },
        rfStatus = map[ConfigProvider.KEY_RF_STATUS] ?: "WAITING",
        rfUid = map[ConfigProvider.KEY_RF_UID]?.takeIf { it.isNotBlank() },
        rfSource = map[ConfigProvider.KEY_RF_SOURCE]?.takeIf { it.isNotBlank() },
        rfResult = map[ConfigProvider.KEY_RF_RESULT]?.takeIf { it.isNotBlank() },
        rfError = map[ConfigProvider.KEY_RF_ERROR]?.takeIf { it.isNotBlank() }
    )

    private fun buildStatusSummary(status: RuntimeStatus): String = buildString {
        appendLine("=== CURRENT STATUS ===")
        appendLine("SCOPE: ${if (status.scopeOk) "SUCCESS" else "NOT DETECTED"} process=${status.scopeProcess}")
        appendLine("HOOK: ${if (status.hookInstalled) "SUCCESS" else "NOT INSTALLED"} class=${status.hookClass} count=${status.hookCount}")
        appendLine("CONFIG: ${if (status.simulationEnabled) "ENABLED" else "IDLE"} uid=${status.selectedUid}")
        appendLine("HCE_NATIVE: ${status.hijackStatus} uid=${status.hijackUid} result=${status.hijackResult} error=${status.hijackError}")
        append("RF_NFCID1: ${status.rfStatus} uid=${status.rfUid} source=${status.rfSource} result=${status.rfResult} error=${status.rfError}")
    }

    private fun fetchLogsSync(source: LogSource): String {
        val cmd = when (source) {
            LogSource.HIJACK -> "grep -h -E 'HIJACK:|HCE:|RF:|NFCID1|NfcUIDSim' /data/adb/lspd/log/modules* 2>/dev/null | tail -n 500; logcat -d -t 300 -s NfcUIDSim 2>/dev/null"
            LogSource.LSPosed -> "grep -h -E 'com.example.nfcdoorcard|NfcUIDSim|SCOPE:|HOOK:|HIJACK:|HCE:|RF:|com.android.nfc' /data/adb/lspd/log/modules* 2>/dev/null | tail -n 600"
            LogSource.KernelSU -> "ls -t /data/adb/ksu/log/sulog* 2>/dev/null | head -n 1 | xargs -r cat | tail -n 300"
        }
        return runRootCmd(cmd).ifBlank { "No matching logs found for $source" }
    }

    private fun loadCards(): List<CardModel> {
        val json = getSharedPreferences("cards", 0).getString("list", null) ?: return emptyList()
        return runCatching { gson.fromJson<List<CardModel>>(json, object : TypeToken<List<CardModel>>() {}.type) ?: emptyList() }.getOrDefault(emptyList())
    }

    private fun saveCards(cards: List<CardModel>) {
        getSharedPreferences("cards", 0).edit().putString("list", gson.toJson(cards)).apply()
    }

    private fun runOneTapDiagnosticAndShare(onDone: () -> Unit) {
        executor.execute {
            try {
                val file = File(cacheDir, "nfc_fullcheck_v12.txt")
                file.writeText(buildFullDiagnosticReport())
                AppLogger.i("Diagnostics V12 exported: ${file.absolutePath}")
                runOnUiThread {
                    onDone()
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share NFC Full Check V12"))
                }
            } catch (e: Exception) {
                AppLogger.i("Diagnostics failed ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread {
                    onDone()
                    Toast.makeText(this@MainActivity, "检测失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildFullDiagnosticReport(): String = buildString {
        val s = readRuntimeStatus()
        appendLine("=== NFC FULL CHECK V12 ===")
        appendLine("Generated: ${System.currentTimeMillis()}")
        appendLine()
        appendLine("--- RUNTIME STATUS ---")
        appendLine(buildStatusSummary(s))
        appendLine()
        appendLine("--- SAVED CARDS ---")
        val cards = loadCards()
        appendLine("count=${cards.size}")
        cards.forEach { appendLine("card uid=${it.uid} sak=${it.sak} atqa=${it.atqa}") }
        appendLine()
        appendLine("--- APP / APK ---")
        appendLine(runRootCmd("dumpsys package $packageName 2>/dev/null | grep -E 'versionName=|versionCode=|path:' | head -n 20"))
        appendLine("--- ROOT ---")
        appendLine(runRootCmd("id; su -v 2>/dev/null || true"))
        appendLine("--- NFC PROCESS ---")
        appendLine(runRootCmd("pm path com.android.nfc; pidof com.android.nfc; ps -A | grep -E 'nfc|$packageName'"))
        appendLine("--- NFC SERVICE ---")
        appendLine(runRootCmd("dumpsys nfc 2>/dev/null | grep -E 'mState=|mScreenState=|listenTech=|pollTech=|mEnableHostRouting=|mTechMask' | head -n 120"))
        appendLine("--- LSPOSED / RF ---")
        appendLine(runRootCmd("grep -h -E 'NfcUIDSim|SCOPE:|HOOK:|HIJACK:|HCE:|RF:' /data/adb/lspd/log/modules* 2>/dev/null | tail -n 900"))
        appendLine("--- LOGCAT RF/NFC ---")
        appendLine(runRootCmd("logcat -d -v threadtime 2>/dev/null | grep -E 'NfcUIDSim|NFCID1|LA_NFCID1|CORE_SET_CONFIG|setHceTypeAConfig' | tail -n 900"))
        appendLine("--- APP LOG ---")
        appendLine(AppLogger.readAll())
    }

    private fun runRootCmd(command: String): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        process.waitFor()
        buildString {
            append(out)
            if (err.isNotBlank()) appendLine(err)
            appendLine("[exit=${process.exitValue()}]")
        }
    } catch (t: Throwable) {
        "ERROR ${t.javaClass.simpleName}: ${t.message}"
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }
}
