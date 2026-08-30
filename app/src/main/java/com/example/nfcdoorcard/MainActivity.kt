package com.example.nfcdoorcard

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class LogSource(val label: String) {
    STATUS("状态"),
    LSPOSED("LSPosed"),
    KERNEL_SU("KernelSU"),
    SYSTEM("系统"),
    NFC("NFC"),
    HAL("HAL"),
    PROVIDER("Provider"),
    APP("App")
}

data class RuntimeStatus(
    val appBuild: Int = 0,
    val hookBuild: Int = 0,
    val currentPid: Int = 0,
    val scopePid: Int = 0,
    val hookPid: Int = 0,
    val scopeOk: Boolean = false,
    val hookInstalled: Boolean = false,
    val simulationEnabled: Boolean = false,
    val selectedUid: String? = null,
    val commandGeneration: Long = 0,
    val handledGeneration: Long = Long.MIN_VALUE,
    val commandAction: String = "",
    val commandStatus: String = "IDLE",
    val commandDetail: String? = null,
    val commandPid: Int = 0,
    val rfStatus: String = "IDLE",
    val rfUid: String? = null,
    val rfSource: String? = null,
    val rfResult: String? = null,
    val rfError: String? = null,
    val rfPid: Int = 0,
    val rfGeneration: Long = 0,
    val fullDiagStage: String? = null,
    val fullDiagSummary: String? = null
)

class MainActivity : ComponentActivity() {
    companion object { private const val EXPECTED_HOOK_BUILD = BuildConfig.HOOK_BUILD }

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private val gson = Gson()
    private val operationExecutor = Executors.newSingleThreadExecutor()
    private val diagnosticExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var rootAvailableCache: Boolean? = null
    @Volatile private var lastRootToastAt: Long = 0L
    private var scannedCardState by mutableStateOf<CardModel?>(null)
    private var savedCardsState by mutableStateOf<List<CardModel>>(emptyList())
    private var readModeEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        savedCardsState = loadCards()
        AppLogger.i("NFC controller started; LSPosed in-process command engine enabled")
        contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
            put(ConfigProvider.KEY_DIAGNOSTIC_LOGGING_ENABLED, false)
        })
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { NfcAppContent() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (readModeEnabled) handleIntent(intent)
    }

    override fun onResume() { super.onResume(); if (readModeEnabled) enableReadDispatch() }
    override fun onPause() { disableReadDispatch(); super.onPause() }
    override fun onDestroy() {
        disableReadDispatch()
        runCatching {
            contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
                put(ConfigProvider.KEY_DIAGNOSTIC_LOGGING_ENABLED, false)
            })
        }
        operationExecutor.shutdownNow()
        diagnosticExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun enableReadDispatch() {
        if (!readModeEnabled) return
        runCatching { nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null) }
            .onSuccess { AppLogger.i("READ_MODE: foreground dispatch enabled") }
            .onFailure { AppLogger.i("READ_MODE: enable dispatch failed ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun disableReadDispatch() { runCatching { nfcAdapter?.disableForegroundDispatch(this) } }

    private fun startReadMode() {
        if (getSimulationEnabled()) {
            Toast.makeText(this, "请先停止模拟，再进入读卡模式", Toast.LENGTH_SHORT).show()
            return
        }
        scannedCardState = null
        readModeEnabled = true
        enableReadDispatch()
        AppLogger.i("READ_MODE: STARTED")
    }

    private fun stopReadMode(reason: String = "manual") {
        if (readModeEnabled) AppLogger.i("READ_MODE: STOPPED reason=$reason")
        readModeEnabled = false
        disableReadDispatch()
    }

    private fun handleIntent(intent: Intent?) {
        if (!readModeEnabled || getSimulationEnabled()) return
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
        stopReadMode("card_read_complete")
    }

    private fun saveScannedCard(card: CardModel) {
        if (savedCardsState.any { it.uid.equals(card.uid, true) }) {
            Toast.makeText(this, "该卡片已经保存", Toast.LENGTH_SHORT).show()
            return
        }
        savedCardsState = savedCardsState + card
        saveCards(savedCardsState)
        AppLogger.i("CARD: SAVED uid=${card.uid} sak=${card.sak} atqa=${card.atqa}")
        Toast.makeText(this, "卡片已保存", Toast.LENGTH_SHORT).show()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NfcAppContent() {
        val cards = savedCardsState
        var status by remember { mutableStateOf(RuntimeStatus()) }
        var logText by remember { mutableStateOf("") }
        var selectedSource by remember { mutableStateOf(LogSource.STATUS) }
        var diagnosticRunning by remember { mutableStateOf(false) }
        var logsEnabled by remember { mutableStateOf(false) }
        var expandedUid by remember { mutableStateOf<String?>(null) }
        var operationMessage by remember { mutableStateOf<String?>(null) }
        val logListState = rememberLazyListState()

        LaunchedEffect(selectedSource, logsEnabled) {
            while (true) {
                val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val newStatus = readRuntimeStatus(includeRootPid = logsEnabled)
                    val logs = if (logsEnabled) fetchLogsSync(selectedSource) else ""
                    newStatus to logs
                }
                status = snapshot.first
                logText = if (logsEnabled) {
                    if (selectedSource == LogSource.STATUS) buildStatusSummary(snapshot.first) + "\n\n" + snapshot.second else snapshot.second
                } else {
                    ""
                }
                kotlinx.coroutines.delay(if (logsEnabled) 2000 else 4000)
            }
        }

        Scaffold(topBar = {
            TopAppBar(title = { Text("NFC Expert Pro ${BuildConfig.VERSION_NAME}") })
        }) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                item { RuntimeStatusPanel(status, operationMessage) }
                item {
                    ReadCardPanel(
                        scannedCardState, readModeEnabled, status.simulationEnabled,
                        { startReadMode() }, { stopReadMode() }, { saveScannedCard(it) }, { scannedCardState = null }
                    )
                }
                item { Text("已保存卡片 (${cards.size})", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.Bold) }
                if (cards.isEmpty()) {
                    item { Text("暂无保存卡片。进入读卡模式后贴卡，确认信息无误再保存。", Modifier.padding(12.dp), fontSize = 12.sp, color = Color.Gray) }
                } else {
                    items(cards, key = { it.uid }) { card ->
                        val active = status.simulationEnabled && card.uid.equals(status.selectedUid, true)
                        CardItem(
                            card, active, expandedUid?.equals(card.uid, true) == true,
                            { expandedUid = if (expandedUid?.equals(card.uid, true) == true) null else card.uid },
                            {
                                operationMessage = "正在通过 NFC 进程应用 UID ${card.uid}..."
                                simulateCard(card) { newStatus, message -> runOnUiThread { status = newStatus; operationMessage = message } }
                                status = status.copy(simulationEnabled = true, selectedUid = card.uid, rfStatus = "WAITING")
                            },
                            {
                                operationMessage = "正在恢复原厂 RF..."
                                stopSimulation { newStatus, message -> runOnUiThread { status = newStatus; operationMessage = message } }
                                status = status.copy(simulationEnabled = false, rfStatus = "STOPPING")
                            },
                            {
                                if (active) stopSimulation { _, _ -> }
                                savedCardsState = savedCardsState.filterNot { it.uid.equals(card.uid, true) }
                                if (expandedUid?.equals(card.uid, true) == true) expandedUid = null
                                saveCards(savedCardsState)
                            }
                        )
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("日志显示", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    if (logsEnabled) "已开启 · 正在抓取日志" else "已关闭 · 不抓取日志，减少性能影响",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                            Switch(
                                checked = logsEnabled,
                                onCheckedChange = { enabled ->
                                    logsEnabled = enabled
                                    contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
                                        put(ConfigProvider.KEY_DIAGNOSTIC_LOGGING_ENABLED, enabled)
                                    })
                                    if (!enabled) logText = ""
                                }
                            )
                        }
                    }
                }
                if (logsEnabled) {
                    item {
                        Spacer(Modifier.height(6.dp))
                        ScrollableTabRow(selectedTabIndex = selectedSource.ordinal, edgePadding = 4.dp) {
                            LogSource.entries.forEach { source ->
                                Tab(selected = selectedSource == source, onClick = { selectedSource = source }, text = { Text(source.label, fontSize = 11.sp) })
                            }
                        }
                    }
                    item {
                        Box(
                            Modifier.fillMaxWidth().height(340.dp).padding(6.dp)
                                .background(Color(0xFF050505), RoundedCornerShape(4.dp)).padding(6.dp)
                        ) {
                            val lines = logText.split("\n")
                            LazyColumn(state = logListState, modifier = Modifier.fillMaxSize()) {
                                items(lines) { line ->
                                    Text(
                                        line,
                                        color = when {
                                            line.contains("SUCCESS") || line.contains("APPLIED") || line.contains("ACCEPTED") || line.contains("READY") -> Color.Cyan
                                            line.contains("FAILED") || line.contains("ERROR") || line.contains("STALE") || line.contains("FATAL") -> Color.Red
                                            line.contains("WAITING") || line.contains("IDLE") || line.contains("RUNNING") || line.contains("TRIGGERED") -> Color.Yellow
                                            line.contains("RF") || line.contains("NFCID1") || line.contains("NfcUIDSim") || line.contains("COMMAND") -> Color.Green
                                            else -> Color(0xFFD4D4D4)
                                        },
                                        fontSize = 9.sp, lineHeight = 11.sp, fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            LaunchedEffect(selectedSource, lines.size) {
                                if (lines.isNotEmpty()) logListState.scrollToItem((lines.size - 1).coerceAtLeast(0))
                            }
                        }
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (!diagnosticRunning) {
                                        diagnosticRunning = true
                                        saveDiagnosticWithoutSharing { diagnosticRunning = false }
                                    }
                                },
                                enabled = !diagnosticRunning,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (diagnosticRunning) "保存中" else "导出日志") }
                            OutlinedButton(
                                onClick = { AppLogger.clear(); logText = "" },
                                modifier = Modifier.weight(1f)
                            ) { Text("清空日志") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RuntimeStatusPanel(status: RuntimeStatus, operationMessage: String?) {
        Card(Modifier.fillMaxWidth().padding(8.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("运行状态", fontWeight = FontWeight.Bold)
                StatusRow("NFC / Hook", status.currentPid > 0 && status.hookInstalled, "pid=${status.currentPid} · hookBuild=${status.hookBuild} · hookPid=${status.hookPid}")
                StatusRow("模拟配置", status.simulationEnabled, if (status.simulationEnabled) "UID=${status.selectedUid ?: "-"}" else "IDLE")
                StatusRow(
                    "命令",
                    status.commandStatus == "SUCCESS",
                    "gen=${status.commandGeneration}/${status.handledGeneration} · ${status.commandAction} · ${status.commandStatus} · pid=${status.commandPid}"
                )
                StatusRow(
                    "RF UID",
                    status.rfStatus == "RF_UID_APPLIED" && status.rfResult == "0" || status.rfStatus.startsWith("RF_STOCK_RESTORED"),
                    "${status.rfStatus} · gen=${status.rfGeneration} · uid=${status.rfUid ?: "-"} · result=${status.rfResult ?: "-"}"
                )
                Text("触发方式: LSPosed · com.android.nfc 进程内控制", fontSize = 11.sp)
                Text("读卡模式: ${if (readModeEnabled) "开启" else "关闭"}", fontSize = 11.sp)
                operationMessage?.let { Text(it, fontSize = 11.sp, color = Color.Gray) }
                status.commandDetail?.takeIf { it.isNotBlank() }?.let { Text("Command: $it", fontSize = 10.sp, color = Color.Gray) }
                status.rfError?.takeIf { it.isNotBlank() }?.let { Text("RF error: $it", fontSize = 10.sp, color = Color.Red) }
            }
        }
    }

    @Composable
    private fun ReadCardPanel(card: CardModel?, readMode: Boolean, simulationActive: Boolean, onStartRead: () -> Unit, onStopRead: () -> Unit, onSave: (CardModel) -> Unit, onClear: () -> Unit) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("读卡模式", fontWeight = FontWeight.Bold)
                when {
                    simulationActive -> {
                        Text("当前正在模拟。读卡功能保持关闭。", fontSize = 12.sp)
                        Button({}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("模拟中 · 读卡已关闭") }
                    }
                    readMode -> {
                        Text("读卡已开启，请把门禁卡贴到手机背部。", fontSize = 12.sp)
                        OutlinedButton(onStopRead, Modifier.fillMaxWidth()) { Text("退出读卡模式") }
                    }
                    card == null -> {
                        Text("默认不读取卡片。需要添加新卡时再进入读卡模式。", fontSize = 12.sp)
                        Button(onStartRead, Modifier.fillMaxWidth()) { Text("进入读卡模式") }
                    }
                    else -> {
                        Text("读取成功", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                        CardDetails(card)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ onSave(card) }, Modifier.weight(1f)) { Text("保存卡片") }
                            OutlinedButton({ onClear(); onStartRead() }, Modifier.weight(1f)) { Text("重新读取") }
                        }
                        TextButton(onClear, Modifier.fillMaxWidth()) { Text("关闭读取结果") }
                    }
                }
            }
        }
    }

    @Composable
    private fun CardDetails(card: CardModel) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("名称: ${card.name}", fontSize = 12.sp)
            Text("UID: ${card.uid}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("SAK: ${card.sak}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("ATQA: ${card.atqa}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("UID 长度: ${card.uid.replace(Regex("[^0-9A-Fa-f]"), "").length / 2} bytes", fontSize = 11.sp, color = Color.Gray)
            Text("类型: ISO/IEC 14443 Type A", fontSize = 11.sp, color = Color.Gray)
        }
    }

    @Composable
    private fun StatusRow(label: String, ok: Boolean, detail: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ok) "●" else "○", color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828), fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Column { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text(detail, fontSize = 11.sp) }
        }
    }

    @Composable
    private fun CardItem(card: CardModel, isActive: Boolean, expanded: Boolean, onToggleDetails: () -> Unit, onSimulate: () -> Unit, onStop: () -> Unit, onDelete: () -> Unit) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp).clickable { onToggleDetails() }) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(card.name, fontWeight = FontWeight.Bold)
                        Text("UID ${card.uid}", fontSize = 10.sp, color = Color.Gray)
                        Text(if (expanded) "点击收起详情" else "点击查看详情", fontSize = 9.sp, color = Color.Gray)
                    }
                    if (isActive) Button(onStop, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("停止模拟", fontSize = 10.sp) }
                    else Button(onSimulate, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("模拟", fontSize = 10.sp) }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("删除", fontSize = 10.sp) }
                }
                if (expanded) { HorizontalDivider(); CardDetails(card) }
            }
        }
    }

    private fun simulateCard(card: CardModel, onDone: (RuntimeStatus, String) -> Unit) {
        stopReadMode("simulation_start")
        val generation = publishCommand(enabled = true, card = card)
        AppLogger.i("SIMULATION: COMMAND APPLY published generation=$generation uid=${card.uid} sak=${card.sak} atqa=${card.atqa}")

        operationExecutor.execute {
            var state = readRuntimeStatus(includeRootPid = true)
            if (!state.hookInstalled || state.hookBuild != EXPECTED_HOOK_BUILD) {
                val restart = restartNfcProcessKeepingEnabled("load_hook_build_$EXPECTED_HOOK_BUILD")
                AppLogger.i("SIMULATION: restarting stale/unavailable hook for command generation=$generation\n$restart")
                state = waitForHookOnly(12_000)
            }

            state = waitForCommandCompletion(generation, card.uid, apply = true, timeoutMs = 12_000)
            val applied = isApplySuccess(state, generation, card.uid)
            val message = when {
                applied -> "模拟成功 · UID=${card.uid} · NFC进程内确认"
                !state.hookInstalled -> "模拟请求已保存，但 Hook 未就绪"
                state.commandGeneration != generation -> "模拟请求被更新的命令替代"
                state.commandStatus == "FAILED" || state.commandStatus == "TRIGGER_FAILED" ->
                    "模拟失败 · ${state.commandStatus}: ${state.commandDetail ?: state.rfError ?: "unknown"}"
                else -> "模拟请求已发送 · 等待 RF UID 确认"
            }
            AppLogger.i("SIMULATION: COMMAND result generation=$generation message=$message\n${buildStatusSummary(state)}")
            onDone(state, message)
        }
    }

    private fun stopSimulation(onDone: (RuntimeStatus, String) -> Unit) {
        stopReadMode("simulation_stop")
        val generation = publishCommand(enabled = false, card = null)
        AppLogger.i("SIMULATION: COMMAND STOP published generation=$generation")

        operationExecutor.execute {
            var state = waitForCommandCompletion(generation, null, apply = false, timeoutMs = 6_000)
            if (isStopSuccess(state, generation)) {
                val message = "模拟已停止 · 原厂 RF 已由 NFC 进程恢复"
                AppLogger.i("SIMULATION: STOP success without restart generation=$generation\n${buildStatusSummary(state)}")
                onDone(state, message)
                return@execute
            }

            // Last-resort recovery only. Normal STOP should complete through the in-process
            // share-mode trigger. A restart while simulation_enabled=false guarantees that
            // the new NFC process cannot inject the previous UID.
            val restart = restartNfcProcessKeepingEnabled("stop_command_fallback_generation_$generation")
            AppLogger.i("SIMULATION: STOP fallback NFC restart generation=$generation\n$restart")
            waitForHookOnly(12_000)
            state = waitForCommandCompletion(generation, null, apply = false, timeoutMs = 6_000)

            if (!isStopSuccess(state, generation)) {
                val currentPid = currentNfcPid().toIntOrNull() ?: state.currentPid
                contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
                    put(ConfigProvider.KEY_COMMAND_HANDLED_GENERATION, generation)
                    put(ConfigProvider.KEY_COMMAND_ACTION, "STOP")
                    put(ConfigProvider.KEY_COMMAND_STATUS, "SUCCESS")
                    put(ConfigProvider.KEY_COMMAND_DETAIL, "Stock RF restored by NFC process restart fallback")
                    put(ConfigProvider.KEY_COMMAND_PID, currentPid)
                    put(ConfigProvider.KEY_RF_STATUS, "RF_STOCK_RESTORED_BY_RESTART")
                    put(ConfigProvider.KEY_RF_UID, "")
                    put(ConfigProvider.KEY_RF_SOURCE, "process-restart")
                    put(ConfigProvider.KEY_RF_RESULT, "0")
                    put(ConfigProvider.KEY_RF_ERROR, "")
                    put(ConfigProvider.KEY_RF_PID, currentPid)
                    put(ConfigProvider.KEY_RF_GENERATION, generation)
                    put(ConfigProvider.KEY_FULL_DIAG_STAGE, "RF_STOCK_RESTORED_BY_RESTART")
                    put(ConfigProvider.KEY_FULL_DIAG_SUMMARY, "Simulation disabled before NFC restart; stock RF reloaded")
                })
                state = readRuntimeStatus(includeRootPid = true)
            }

            val message = if (isStopSuccess(state, generation)) {
                "模拟已停止 · 已恢复原厂 RF"
            } else {
                "模拟已停止 · NFC 已重启，但状态确认未完成"
            }
            onDone(state, message)
        }
    }

    private fun publishCommand(enabled: Boolean, card: CardModel?): Long {
        val currentMap = readProviderMap()
        val previous = currentMap[ConfigProvider.KEY_COMMAND_GENERATION]?.toLongOrNull() ?: 0L
        val generation = maxOf(previous + 1L, System.currentTimeMillis())
        val action = if (enabled) "APPLY" else "STOP"

        contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
            put(ConfigProvider.KEY_APP_BUILD, ConfigProvider.APP_BUILD)
            put(ConfigProvider.KEY_SIMULATION_ENABLED, enabled)
            if (card != null) {
                put(ConfigProvider.KEY_UID, card.uid)
                put(ConfigProvider.KEY_SAK, card.sak)
                put(ConfigProvider.KEY_ATQA, card.atqa)
            }
            put(ConfigProvider.KEY_COMMAND_GENERATION, generation)
            put(ConfigProvider.KEY_COMMAND_ACTION, action)
            put(ConfigProvider.KEY_COMMAND_STATUS, "PENDING")
            put(ConfigProvider.KEY_COMMAND_DETAIL, "Waiting for LSPosed NFC process command engine")
            put(ConfigProvider.KEY_COMMAND_PID, 0)
            put(ConfigProvider.KEY_RF_STATUS, if (enabled) "WAITING" else "STOPPING")
            put(ConfigProvider.KEY_RF_UID, "")
            put(ConfigProvider.KEY_RF_SOURCE, "")
            put(ConfigProvider.KEY_RF_RESULT, "")
            put(ConfigProvider.KEY_RF_ERROR, "")
            put(ConfigProvider.KEY_RF_PID, 0)
            put(ConfigProvider.KEY_RF_GENERATION, generation)
            put(ConfigProvider.KEY_FULL_DIAG_STAGE, "COMMAND_PENDING")
            put(ConfigProvider.KEY_FULL_DIAG_SUMMARY, "$action generation=$generation published by app")
        })
        return generation
    }

    private fun restartNfcProcessKeepingEnabled(reason: String): String {
        val script = """
            old=${'$'}(pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}')
            before=${'$'}(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' || true)
            echo "REASON=$reason"
            echo "OLD_PID=${'$'}old"
            echo "BEFORE_STATE=${'$'}before"
            if [ -n "${'$'}old" ]; then
              kill -TERM "${'$'}old" 2>/dev/null || true
              sleep 0.5
              kill -0 "${'$'}old" 2>/dev/null && kill -KILL "${'$'}old" 2>/dev/null || true
            fi
            i=0
            new=""
            while [ ${'$'}i -lt 60 ]; do
              new=${'$'}(pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}')
              if [ -n "${'$'}new" ] && [ "${'$'}new" != "${'$'}old" ]; then break; fi
              sleep 0.2
              i=${'$'}((i+1))
            done
            state=${'$'}(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' || true)
            if ! echo "${'$'}state" | grep -Eqi 'mState=on|state=on|STATE_ON|mState=3'; then svc nfc enable 2>/dev/null || true; fi
            j=0
            while [ ${'$'}j -lt 60 ]; do
              state=${'$'}(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' || true)
              echo "${'$'}state" | grep -Eqi 'mState=on|state=on|STATE_ON|mState=3' && break
              if [ ${'$'}((j % 10)) -eq 0 ]; then svc nfc enable 2>/dev/null || true; fi
              sleep 0.25
              j=${'$'}((j+1))
            done
            new=${'$'}(pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}')
            echo "NEW_PID=${'$'}new"
            echo "NFC_STATE=${'$'}state"
        """.trimIndent()
        return runRootCmd(script)
    }

    private fun waitForCommandCompletion(generation: Long, uid: String?, apply: Boolean, timeoutMs: Long): RuntimeStatus {
        val end = System.currentTimeMillis() + timeoutMs
        var state = RuntimeStatus()
        while (System.currentTimeMillis() < end) {
            state = readRuntimeStatus(includeRootPid = true)
            if (if (apply) isApplySuccess(state, generation, uid.orEmpty()) else isStopSuccess(state, generation)) return state
            if (state.commandGeneration == generation && state.handledGeneration == generation && state.commandStatus == "FAILED") return state
            Thread.sleep(100)
        }
        return state
    }

    private fun isApplySuccess(state: RuntimeStatus, generation: Long, uid: String): Boolean {
        return state.commandGeneration == generation &&
            state.handledGeneration == generation &&
            state.commandStatus == "SUCCESS" &&
            state.commandPid > 0 && state.commandPid == state.currentPid &&
            state.rfGeneration == generation &&
            state.rfPid > 0 && state.rfPid == state.currentPid &&
            state.rfStatus == "RF_UID_APPLIED" &&
            state.rfUid.equals(uid, ignoreCase = true) &&
            state.rfResult == "0"
    }

    private fun isStopSuccess(state: RuntimeStatus, generation: Long): Boolean {
        return state.commandGeneration == generation &&
            state.handledGeneration == generation &&
            state.commandStatus == "SUCCESS" &&
            state.rfGeneration == generation &&
            state.rfResult == "0" &&
            state.rfStatus.startsWith("RF_STOCK_RESTORED")
    }

    private fun waitForHookOnly(timeoutMs: Long): RuntimeStatus {
        val end = System.currentTimeMillis() + timeoutMs
        var state = RuntimeStatus()
        while (System.currentTimeMillis() < end) {
            state = readRuntimeStatus(includeRootPid = true)
            if (state.hookInstalled && state.hookBuild == EXPECTED_HOOK_BUILD) return state
            Thread.sleep(200)
        }
        return state
    }

    private fun getSimulationEnabled(): Boolean = readProviderMap()[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()

    private fun readProviderMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        runCatching {
            contentResolver.query(ConfigProvider.URI, null, null, null, null)?.use { c ->
                while (c.moveToNext()) map[c.getString(0)] = c.getString(1)
            }
        }
        return map
    }

    private fun readRuntimeStatus(includeRootPid: Boolean = false): RuntimeStatus {
        val map = readProviderMap()
        val scopePid = map[ConfigProvider.KEY_SCOPE_PID]?.toIntOrNull() ?: 0
        val hookPid = map[ConfigProvider.KEY_HOOK_PID]?.toIntOrNull() ?: 0
        val rfPidFromProvider = map[ConfigProvider.KEY_RF_PID]?.toIntOrNull() ?: 0
        val commandPid = map[ConfigProvider.KEY_COMMAND_PID]?.toIntOrNull() ?: 0
        val currentPid = if (includeRootPid) {
            currentNfcPid().toIntOrNull() ?: 0
        } else {
            hookPid.takeIf { it > 0 } ?: commandPid.takeIf { it > 0 } ?: scopePid.takeIf { it > 0 } ?: rfPidFromProvider
        }
        val hookBuild = map[ConfigProvider.KEY_HOOK_BUILD]?.toIntOrNull() ?: 0
        val hookFresh = currentPid > 0 && hookPid == currentPid && map[ConfigProvider.KEY_HOOK_INSTALLED].toBoolean()
        val scopeFresh = currentPid > 0 && scopePid == currentPid && map[ConfigProvider.KEY_SCOPE_OK].toBoolean()
        val rfPid = rfPidFromProvider
        return RuntimeStatus(
            appBuild = map[ConfigProvider.KEY_APP_BUILD]?.toIntOrNull() ?: 0,
            hookBuild = hookBuild,
            currentPid = currentPid,
            scopePid = scopePid,
            hookPid = hookPid,
            scopeOk = scopeFresh,
            hookInstalled = hookFresh,
            simulationEnabled = map[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean(),
            selectedUid = map[ConfigProvider.KEY_UID]?.takeIf { it.isNotBlank() },
            commandGeneration = map[ConfigProvider.KEY_COMMAND_GENERATION]?.toLongOrNull() ?: 0L,
            handledGeneration = map[ConfigProvider.KEY_COMMAND_HANDLED_GENERATION]?.toLongOrNull() ?: Long.MIN_VALUE,
            commandAction = map[ConfigProvider.KEY_COMMAND_ACTION].orEmpty(),
            commandStatus = map[ConfigProvider.KEY_COMMAND_STATUS] ?: "IDLE",
            commandDetail = map[ConfigProvider.KEY_COMMAND_DETAIL]?.takeIf { it.isNotBlank() },
            commandPid = commandPid,
            rfStatus = if (rfPid == currentPid && currentPid > 0) map[ConfigProvider.KEY_RF_STATUS] ?: "WAITING" else if (map[ConfigProvider.KEY_RF_STATUS] == "IDLE") "IDLE" else map[ConfigProvider.KEY_RF_STATUS] ?: "STALE",
            rfUid = if (rfPid == currentPid) map[ConfigProvider.KEY_RF_UID]?.takeIf { it.isNotBlank() } else null,
            rfSource = if (rfPid == currentPid) map[ConfigProvider.KEY_RF_SOURCE]?.takeIf { it.isNotBlank() } else null,
            rfResult = if (rfPid == currentPid) map[ConfigProvider.KEY_RF_RESULT]?.takeIf { it.isNotBlank() } else null,
            rfError = if (rfPid == currentPid) map[ConfigProvider.KEY_RF_ERROR]?.takeIf { it.isNotBlank() } else null,
            rfPid = rfPid,
            rfGeneration = map[ConfigProvider.KEY_RF_GENERATION]?.toLongOrNull() ?: 0L,
            fullDiagStage = map[ConfigProvider.KEY_FULL_DIAG_STAGE]?.takeIf { it.isNotBlank() },
            fullDiagSummary = map[ConfigProvider.KEY_FULL_DIAG_SUMMARY]?.takeIf { it.isNotBlank() }
        )
    }

    private fun buildStatusSummary(s: RuntimeStatus): String = buildString {
        appendLine("=== CURRENT STATUS ===")
        appendLine("BUILD: app=${s.appBuild} hook=${s.hookBuild} expectedHook=$EXPECTED_HOOK_BUILD")
        appendLine("PID: current=${s.currentPid} scope=${s.scopePid} hook=${s.hookPid} command=${s.commandPid} rf=${s.rfPid}")
        appendLine("SCOPE: ${if (s.scopeOk) "SUCCESS" else "NOT DETECTED/STALE"}")
        appendLine("HOOK: ${if (s.hookInstalled) "SUCCESS" else "NOT INSTALLED/STALE"}")
        appendLine("CONFIG: ${if (s.simulationEnabled) "ENABLED" else "IDLE"} uid=${s.selectedUid}")
        appendLine("COMMAND: generation=${s.commandGeneration} handled=${s.handledGeneration} action=${s.commandAction} status=${s.commandStatus} detail=${s.commandDetail}")
        appendLine("READ_MODE: ${if (readModeEnabled) "ENABLED" else "IDLE"}")
        appendLine("RF: generation=${s.rfGeneration} ${s.rfStatus} uid=${s.rfUid} source=${s.rfSource} result=${s.rfResult} error=${s.rfError}")
        append("FINAL: stage=${s.fullDiagStage} summary=${s.fullDiagSummary}")
    }

    private fun fetchLogsSync(source: LogSource): String {
        val pid = currentNfcPid()
        val output = when (source) {
            LogSource.STATUS -> runRootCmd("logcat -d -t 350 -v threadtime -s NfcDoorCard NfcUIDSim 2>/dev/null")
            LogSource.LSPOSED -> runRootCmd("""
                {
                  grep -R -h -E 'NfcUIDSim|com.example.nfcdoorcard|PROD MODULE|PROD HOOK|RFPROBE|NFCID1|COMMAND|LSPosed' /data/adb/lspd/log 2>/dev/null || true
                  logcat -b all -d -v threadtime 2>/dev/null | grep -E 'NfcUIDSim|PROD MODULE|PROD HOOK|RFPROBE|NFCID1|COMMAND' || true
                } | tail -n 1500
            """.trimIndent())
            LogSource.KERNEL_SU -> runRootCmd("for f in ${'$'}(ls -t /data/adb/ksu/log/sulog* 2>/dev/null | head -n 3); do echo === ${'$'}f ===; tail -n 300 ${'$'}f; done")
            LogSource.SYSTEM -> runRootCmd("logcat -b all -d -v threadtime 2>/dev/null | tail -n 1800")
            LogSource.NFC -> {
                val filter = "NfcUIDSim|NfcService|NxpNfcService|NfcChipDeviceImpl|NFCID1|COMMAND|changeRfParamsByConfig|setRfConfig|VendorNfcService|enableNfcShareMode"
                val pidFilter = if (pid.isNotBlank()) "${'$'}0 ~ / $pid / || ${'$'}0 ~ /$filter/" else "${'$'}0 ~ /$filter/"
                runRootCmd("logcat -b all -d -v threadtime 2>/dev/null | awk '$pidFilter' | tail -n 1800")
            }
            LogSource.HAL -> runRootCmd("""
                echo '--- NFC PROCESSES ---'
                ps -A | grep -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|com.android.nfc' || true
                echo '--- NFC PROPERTIES ---'
                getprop | grep -i -E 'nfc|nxp|st21|st54|sn100|sn220|oplus' | head -n 300 || true
                echo '--- HAL LOGCAT ---'
                logcat -b all -d -v threadtime 2>/dev/null | grep -i -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|NxpNfc|NfcHal|libnfc|nfc-service|NFC HAL|STNfc|sn100|sn220' | tail -n 1200 || true
            """.trimIndent())
            LogSource.PROVIDER -> buildString {
                appendLine("=== PROVIDER STATE ===")
                readProviderMap().toSortedMap().forEach { (k, v) -> appendLine("$k=$v") }
                appendLine("current_nfc_pid=${currentNfcPid()}")
            }
            LogSource.APP -> AppLogger.readAll().ifBlank { "No app logs" }
        }
        return output.ifBlank { "No matching logs found for ${source.label}" }
    }

    private fun currentNfcPid(): String = runRootCmd("pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}'")
        .lineSequence().firstOrNull { it.trim().matches(Regex("\\d+")) }?.trim().orEmpty()

    private fun loadCards(): List<CardModel> {
        val prefs = getSharedPreferences("cards", 0)
        var json = prefs.getString("list", null)
        if (json == null) {
            val old = getSharedPreferences("saved_cards", 0).getString("cards_list", null)
            if (!old.isNullOrBlank()) { json = old; prefs.edit().putString("list", old).apply() }
        }
        return if (json.isNullOrBlank()) emptyList() else runCatching { gson.fromJson<List<CardModel>>(json, object : TypeToken<List<CardModel>>() {}.type) ?: emptyList() }.getOrDefault(emptyList())
    }

    private fun saveCards(cards: List<CardModel>) {
        getSharedPreferences("cards", 0).edit().putString("list", gson.toJson(cards)).apply()
    }

    private fun saveDiagnosticWithoutSharing(onDone: () -> Unit) {
        stopReadMode("diagnostic_save")
        diagnosticExecutor.execute {
            var createdUri: android.net.Uri? = null
            try {
                val fileName = "nfc_fullcheck_${BuildConfig.VERSION_NAME}_${System.currentTimeMillis()}.txt"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                createdUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法在 Download 创建日志文件")

                contentResolver.openOutputStream(createdUri, "w")?.bufferedWriter()?.use { writer ->
                    writer.write(buildFullDiagnosticReport())
                } ?: error("无法写入日志文件")

                contentResolver.update(createdUri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)

                AppLogger.i("Diagnostics saved to public Downloads: $fileName uri=$createdUri")
                runOnUiThread {
                    onDone()
                    Toast.makeText(this@MainActivity, "日志已保存到 Download/$fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                createdUri?.let { runCatching { contentResolver.delete(it, null, null) } }
                AppLogger.i("Diagnostics failed ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread { onDone(); Toast.makeText(this@MainActivity, "检测失败: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun buildFullDiagnosticReport(): String = buildString {
        val s = readRuntimeStatus(includeRootPid = true)
        appendLine("=== NFC FULL CHECK ${BuildConfig.VERSION_NAME} ===")
        appendLine("Generated: ${System.currentTimeMillis()}")
        appendLine("Trigger: LSPosed in-process NFC command engine; vendor transaction 6 -> 15 is executed inside com.android.nfc")
        appendLine("--- FINAL SUMMARY ---")
        appendLine(buildStatusSummary(s))
        appendLine()
        appendLine("--- SAVED CARDS ---")
        val cards = loadCards()
        appendLine("count=${cards.size}")
        cards.forEach { appendLine("card uid=${it.uid} sak=${it.sak} atqa=${it.atqa}") }
        appendLine("--- APP / APK ---")
        appendLine(runRootCmd("dumpsys package $packageName 2>/dev/null | grep -E 'versionName=|versionCode=|path:' | head -n 30"))
        appendLine("--- ROOT ---")
        appendLine(runRootCmd("id; su -v 2>/dev/null || true"))
        appendLine("--- NFC PROCESS / HAL ---")
        appendLine(runRootCmd("pm path com.android.nfc; pidof com.android.nfc; ps -A | grep -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|com.android.nfc|$packageName'"))
        appendLine("--- NFC SERVICE FULL ---")
        appendLine(runRootCmd("dumpsys nfc 2>/dev/null | head -n 1000"))
        LogSource.entries.forEach { source ->
            appendLine()
            appendLine("=== LOG SOURCE: ${source.name} / ${source.label} ===")
            appendLine(fetchLogsSync(source))
        }
    }

    private fun ensureRootAccess(showToast: Boolean = true): Boolean {
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
        rootAvailableCache = if (ok) true else null
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

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }
}
