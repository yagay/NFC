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
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.Executors

enum class LogSource { STATUS, LSPosed, KernelSU }

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
    val rfStatus: String = "IDLE",
    val rfUid: String? = null,
    val rfSource: String? = null,
    val rfResult: String? = null,
    val rfError: String? = null,
    val rfPid: Int = 0,
    val fullDiagStage: String? = null,
    val fullDiagSummary: String? = null
)

class MainActivity : ComponentActivity() {
    companion object {
        private const val EXPECTED_HOOK_BUILD = 9
    }

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()

    private var scannedCardState by mutableStateOf<CardModel?>(null)
    private var savedCardsState by mutableStateOf<List<CardModel>>(emptyList())
    private var readModeEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        savedCardsState = loadCards()
        AppLogger.i("NFC mode controller started")
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NfcAppContent()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (readModeEnabled) handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applyReadDispatchState()
    }

    override fun onPause() {
        disableReadDispatch()
        super.onPause()
    }

    override fun onDestroy() {
        disableReadDispatch()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun applyReadDispatchState() {
        if (readModeEnabled && !getSimulationEnabled()) enableReadDispatch() else disableReadDispatch()
    }

    private fun enableReadDispatch() {
        if (!readModeEnabled || getSimulationEnabled()) return
        runCatching { nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null) }
            .onSuccess { AppLogger.i("READ_MODE: foreground dispatch enabled") }
            .onFailure { AppLogger.i("READ_MODE: enable dispatch failed ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun disableReadDispatch() {
        runCatching { nfcAdapter?.disableForegroundDispatch(this) }
    }

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
        var status by remember { mutableStateOf(readRuntimeStatus()) }
        var logText by remember { mutableStateOf("") }
        var selectedSource by remember { mutableStateOf(LogSource.STATUS) }
        var diagnosticRunning by remember { mutableStateOf(false) }
        var expandedUid by remember { mutableStateOf<String?>(null) }
        var operationMessage by remember { mutableStateOf<String?>(null) }
        val logListState = rememberLazyListState()

        LaunchedEffect(selectedSource) {
            while (true) {
                executor.execute {
                    val newStatus = readRuntimeStatus()
                    val logs = fetchLogsSync(selectedSource)
                    runOnUiThread {
                        status = newStatus
                        logText = if (selectedSource == LogSource.STATUS) {
                            buildStatusSummary(newStatus) + "\n\n" + logs
                        } else logs
                    }
                }
                kotlinx.coroutines.delay(2000)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("NFC Expert Pro 1.0.15") },
                    actions = {
                        TextButton(onClick = {
                            if (!diagnosticRunning) {
                                diagnosticRunning = true
                                runOneTapDiagnosticAndShare { diagnosticRunning = false }
                            }
                        }) { Text("导出") }
                        TextButton(onClick = { AppLogger.clear(); logText = "" }) { Text("清空") }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                item {
                    RuntimeStatusPanel(status, operationMessage)
                }

                item {
                    ReadCardPanel(
                        card = scannedCardState,
                        readMode = readModeEnabled,
                        simulationActive = status.simulationEnabled,
                        onStartRead = { startReadMode() },
                        onStopRead = { stopReadMode() },
                        onSave = { saveScannedCard(it) },
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
                            "暂无保存卡片。进入读卡模式后贴卡，确认信息无误再保存。",
                            modifier = Modifier.padding(12.dp),
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
                            expanded = expandedUid?.equals(card.uid, true) == true,
                            onToggleDetails = {
                                expandedUid = if (expandedUid?.equals(card.uid, true) == true) null else card.uid
                            },
                            onSimulate = {
                                operationMessage = "正在应用 UID ${card.uid}，NFC 保持开启..."
                                simulateCard(card) { newStatus, message ->
                                    runOnUiThread {
                                        status = newStatus
                                        operationMessage = message
                                    }
                                }
                                status = status.copy(
                                    simulationEnabled = true,
                                    selectedUid = card.uid,
                                    rfStatus = "WAITING"
                                )
                            },
                            onStop = {
                                operationMessage = "正在停止模拟并恢复原始 NFC 配置..."
                                stopSimulation { newStatus, message ->
                                    runOnUiThread {
                                        status = newStatus
                                        operationMessage = message
                                    }
                                }
                                status = status.copy(simulationEnabled = false, rfStatus = "IDLE")
                            },
                            onDelete = {
                                if (active) {
                                    stopSimulation { _, _ -> }
                                }
                                savedCardsState = savedCardsState.filterNot { it.uid.equals(card.uid, true) }
                                if (expandedUid?.equals(card.uid, true) == true) expandedUid = null
                                saveCards(savedCardsState)
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
                            .height(300.dp)
                            .padding(6.dp)
                            .background(Color(0xFF050505), RoundedCornerShape(4.dp))
                            .padding(6.dp)
                    ) {
                        val lines = logText.split("\n")
                        LazyColumn(state = logListState, modifier = Modifier.fillMaxSize()) {
                            items(lines) { line ->
                                Text(
                                    line,
                                    color = when {
                                        line.contains("SUCCESS") || line.contains("APPLIED") || line.contains("ACCEPTED") || line.contains("READY") -> Color.Cyan
                                        line.contains("FAILED") || line.contains("ERROR") || line.contains("STALE") -> Color.Red
                                        line.contains("WAITING") || line.contains("IDLE") -> Color.Yellow
                                        line.contains("RF") || line.contains("NFCID1") -> Color.Green
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
    private fun RuntimeStatusPanel(status: RuntimeStatus, operationMessage: String?) {
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("运行状态", fontWeight = FontWeight.Bold)
                StatusRow(
                    "NFC / Hook",
                    status.currentPid > 0 && status.hookInstalled,
                    "pid=${status.currentPid} · hookBuild=${status.hookBuild} · hookPid=${status.hookPid}"
                )
                StatusRow(
                    "模拟配置",
                    status.simulationEnabled,
                    if (status.simulationEnabled) "UID=${status.selectedUid ?: "-"}" else "IDLE"
                )
                StatusRow(
                    "RF UID",
                    status.rfStatus == "RF_UID_APPLIED",
                    "${status.rfStatus} · uid=${status.rfUid ?: "-"} · result=${status.rfResult ?: "-"}"
                )
                Text("读卡模式: ${if (readModeEnabled) "开启" else "关闭"}", fontSize = 11.sp)
                operationMessage?.let { Text(it, fontSize = 11.sp, color = Color.Gray) }
                status.rfError?.takeIf { it.isNotBlank() }?.let {
                    Text("RF error: $it", fontSize = 10.sp, color = Color.Red)
                }
            }
        }
    }

    @Composable
    private fun ReadCardPanel(
        card: CardModel?,
        readMode: Boolean,
        simulationActive: Boolean,
        onStartRead: () -> Unit,
        onStopRead: () -> Unit,
        onSave: (CardModel) -> Unit,
        onClear: () -> Unit
    ) {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("读卡模式", fontWeight = FontWeight.Bold)
                when {
                    simulationActive -> {
                        Text("当前正在模拟。读卡功能保持关闭。", fontSize = 12.sp)
                        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                            Text("模拟中 · 读卡已关闭")
                        }
                    }
                    readMode -> {
                        Text("读卡已开启，请把门禁卡贴到手机背部。", fontSize = 12.sp)
                        OutlinedButton(onClick = onStopRead, modifier = Modifier.fillMaxWidth()) {
                            Text("退出读卡模式")
                        }
                    }
                    card == null -> {
                        Text("默认不读取卡片。需要添加新卡时再进入读卡模式。", fontSize = 12.sp)
                        Button(onClick = onStartRead, modifier = Modifier.fillMaxWidth()) {
                            Text("进入读卡模式")
                        }
                    }
                    else -> {
                        Text("读取成功", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                        CardDetails(card)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onSave(card) }, modifier = Modifier.weight(1f)) {
                                Text("保存卡片")
                            }
                            OutlinedButton(onClick = { onClear(); onStartRead() }, modifier = Modifier.weight(1f)) {
                                Text("重新读取")
                            }
                        }
                        TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                            Text("关闭读取结果")
                        }
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
            Text(
                "UID 长度: ${card.uid.replace(Regex("[^0-9A-Fa-f]"), "").length / 2} bytes",
                fontSize = 11.sp,
                color = Color.Gray
            )
            Text("类型: ISO/IEC 14443 Type A", fontSize = 11.sp, color = Color.Gray)
        }
    }

    @Composable
    private fun StatusRow(label: String, ok: Boolean, detail: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ok) "●" else "○",
                color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828),
                fontSize = 18.sp
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(detail, fontSize = 11.sp)
            }
        }
    }

    @Composable
    private fun CardItem(
        card: CardModel,
        isActive: Boolean,
        expanded: Boolean,
        onToggleDetails: () -> Unit,
        onSimulate: () -> Unit,
        onStop: () -> Unit,
        onDelete: () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .clickable { onToggleDetails() }
        ) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(card.name, fontWeight = FontWeight.Bold)
                        Text("UID ${card.uid}", fontSize = 10.sp, color = Color.Gray)
                        Text(if (expanded) "点击收起详情" else "点击查看详情", fontSize = 9.sp, color = Color.Gray)
                    }
                    if (isActive) {
                        Button(onClick = onStop, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text("停止模拟", fontSize = 10.sp)
                        }
                    } else {
                        Button(onClick = onSimulate, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text("模拟", fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("删除", fontSize = 10.sp)
                    }
                }
                if (expanded) {
                    HorizontalDivider()
                    CardDetails(card)
                }
            }
        }
    }

    private fun simulateCard(card: CardModel, onDone: (RuntimeStatus, String) -> Unit) {
        stopReadMode("simulation_start")
        writeSimulationConfig(card)
        AppLogger.i("SIMULATION: requested uid=${card.uid} sak=${card.sak} atqa=${card.atqa}")
        executor.execute {
            val restart = restartNfcProcessKeepingEnabled("simulation")
            AppLogger.i("SIMULATION: process restart\n$restart")
            var state = waitForHookAndRf(card.uid, 14_000)
            val message = when {
                state.rfStatus == "RF_UID_APPLIED" && state.rfUid.equals(card.uid, true) ->
                    "模拟已应用 · UID=${card.uid}"
                !state.hookInstalled ->
                    "NFC 已保持开启，但 Hook 尚未就绪"
                else ->
                    "Hook 已加载，等待 RF UID 应用"
            }
            AppLogger.i("SIMULATION: result $message\n${buildStatusSummary(state)}")
            onDone(state, message)
        }
    }

    private fun stopSimulation(onDone: (RuntimeStatus, String) -> Unit) {
        stopReadMode("simulation_stop")
        contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
            put(ConfigProvider.KEY_SIMULATION_ENABLED, false)
            put(ConfigProvider.KEY_RF_STATUS, "IDLE")
            put(ConfigProvider.KEY_RF_UID, "")
            put(ConfigProvider.KEY_RF_RESULT, "")
            put(ConfigProvider.KEY_RF_ERROR, "")
            put(ConfigProvider.KEY_FULL_DIAG_STAGE, "STOPPING")
            put(ConfigProvider.KEY_FULL_DIAG_SUMMARY, "Restoring stock NFC config without disabling NFC")
        })
        AppLogger.i("SIMULATION: stop requested")
        executor.execute {
            val restart = restartNfcProcessKeepingEnabled("stop_simulation")
            AppLogger.i("SIMULATION: stop restart\n$restart")
            val state = waitForHookOnly(10_000)
            onDone(state, "模拟已停止，NFC 保持开启")
        }
    }

    private fun writeSimulationConfig(card: CardModel) {
        contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
            put(ConfigProvider.KEY_APP_BUILD, ConfigProvider.APP_BUILD)
            put(ConfigProvider.KEY_SIMULATION_ENABLED, true)
            put(ConfigProvider.KEY_UID, card.uid)
            put(ConfigProvider.KEY_SAK, card.sak)
            put(ConfigProvider.KEY_ATQA, card.atqa)
            put(ConfigProvider.KEY_SCOPE_OK, false)
            put(ConfigProvider.KEY_SCOPE_PROCESS, "")
            put(ConfigProvider.KEY_SCOPE_PID, 0)
            put(ConfigProvider.KEY_HOOK_INSTALLED, false)
            put(ConfigProvider.KEY_HOOK_CLASS, "")
            put(ConfigProvider.KEY_HOOK_COUNT, 0)
            put(ConfigProvider.KEY_HOOK_PID, 0)
            put(ConfigProvider.KEY_RF_STATUS, "WAITING")
            put(ConfigProvider.KEY_RF_UID, "")
            put(ConfigProvider.KEY_RF_SOURCE, "")
            put(ConfigProvider.KEY_RF_RESULT, "")
            put(ConfigProvider.KEY_RF_ERROR, "")
            put(ConfigProvider.KEY_RF_PID, 0)
            put(ConfigProvider.KEY_FULL_DIAG_STAGE, "APP_CONFIG_READY")
            put(ConfigProvider.KEY_FULL_DIAG_SUMMARY, "UID saved; restarting NFC process without disabling NFC")
        })
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
            if ! echo "${'$'}state" | grep -Eqi 'mState=on|state=on|STATE_ON|mState=3'; then
              svc nfc enable 2>/dev/null || true
            fi

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

    private fun waitForHookAndRf(uid: String, timeoutMs: Long): RuntimeStatus {
        val end = System.currentTimeMillis() + timeoutMs
        var state = RuntimeStatus()
        while (System.currentTimeMillis() < end) {
            state = readRuntimeStatus()
            if (
                state.hookInstalled &&
                state.hookBuild == EXPECTED_HOOK_BUILD &&
                state.rfStatus == "RF_UID_APPLIED" &&
                state.rfUid.equals(uid, true)
            ) return state
            Thread.sleep(250)
        }
        return state
    }

    private fun waitForHookOnly(timeoutMs: Long): RuntimeStatus {
        val end = System.currentTimeMillis() + timeoutMs
        var state = RuntimeStatus()
        while (System.currentTimeMillis() < end) {
            state = readRuntimeStatus()
            if (state.hookInstalled && state.hookBuild == EXPECTED_HOOK_BUILD) return state
            Thread.sleep(250)
        }
        return state
    }

    private fun getSimulationEnabled(): Boolean =
        readProviderMap()[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()

    private fun readProviderMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        runCatching {
            contentResolver.query(ConfigProvider.URI, null, null, null, null)?.use { c ->
                while (c.moveToNext()) map[c.getString(0)] = c.getString(1)
            }
        }
        return map
    }

    private fun readRuntimeStatus(): RuntimeStatus {
        val map = readProviderMap()
        val currentPid = runRootCmd("pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}'")
            .lineSequence()
            .firstOrNull { it.trim().matches(Regex("\\d+")) }
            ?.trim()
            ?.toIntOrNull() ?: 0

        val scopePid = map[ConfigProvider.KEY_SCOPE_PID]?.toIntOrNull() ?: 0
        val hookPid = map[ConfigProvider.KEY_HOOK_PID]?.toIntOrNull() ?: 0
        val hookBuild = map[ConfigProvider.KEY_HOOK_BUILD]?.toIntOrNull() ?: 0
        val hookFresh = currentPid > 0 && hookPid == currentPid && hookBuild == EXPECTED_HOOK_BUILD &&
            map[ConfigProvider.KEY_HOOK_INSTALLED].toBoolean()
        val scopeFresh = currentPid > 0 && scopePid == currentPid && hookBuild == EXPECTED_HOOK_BUILD &&
            map[ConfigProvider.KEY_SCOPE_OK].toBoolean()
        val rfPid = map[ConfigProvider.KEY_RF_PID]?.toIntOrNull() ?: 0

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
            rfStatus = if (rfPid == currentPid && currentPid > 0) {
                map[ConfigProvider.KEY_RF_STATUS] ?: "WAITING"
            } else if (map[ConfigProvider.KEY_RF_STATUS] == "IDLE") {
                "IDLE"
            } else {
                "STALE"
            },
            rfUid = if (rfPid == currentPid) map[ConfigProvider.KEY_RF_UID]?.takeIf { it.isNotBlank() } else null,
            rfSource = if (rfPid == currentPid) map[ConfigProvider.KEY_RF_SOURCE]?.takeIf { it.isNotBlank() } else null,
            rfResult = if (rfPid == currentPid) map[ConfigProvider.KEY_RF_RESULT]?.takeIf { it.isNotBlank() } else null,
            rfError = if (rfPid == currentPid) map[ConfigProvider.KEY_RF_ERROR]?.takeIf { it.isNotBlank() } else null,
            rfPid = rfPid,
            fullDiagStage = map[ConfigProvider.KEY_FULL_DIAG_STAGE]?.takeIf { it.isNotBlank() },
            fullDiagSummary = map[ConfigProvider.KEY_FULL_DIAG_SUMMARY]?.takeIf { it.isNotBlank() }
        )
    }

    private fun buildStatusSummary(s: RuntimeStatus): String = buildString {
        appendLine("=== CURRENT STATUS ===")
        appendLine("BUILD: app=${s.appBuild} hook=${s.hookBuild} expectedHook=$EXPECTED_HOOK_BUILD")
        appendLine("PID: current=${s.currentPid} scope=${s.scopePid} hook=${s.hookPid} rf=${s.rfPid}")
        appendLine("SCOPE: ${if (s.scopeOk) "SUCCESS" else "NOT DETECTED/STALE"}")
        appendLine("HOOK: ${if (s.hookInstalled) "SUCCESS" else "NOT INSTALLED/STALE"}")
        appendLine("CONFIG: ${if (s.simulationEnabled) "ENABLED" else "IDLE"} uid=${s.selectedUid}")
        appendLine("READ_MODE: ${if (readModeEnabled) "ENABLED" else "IDLE"}")
        appendLine("RF_NFCID1: ${s.rfStatus} uid=${s.rfUid} source=${s.rfSource} result=${s.rfResult} error=${s.rfError}")
        append("FINAL: stage=${s.fullDiagStage} summary=${s.fullDiagSummary}")
    }

    private fun fetchLogsSync(source: LogSource): String {
        val pid = runRootCmd("pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}'")
            .lineSequence().firstOrNull { it.trim().matches(Regex("\\d+")) }?.trim().orEmpty()
        val pidFilter = if (pid.isNotBlank()) " | grep ': $pid:'" else ""
        val cmd = when (source) {
            LogSource.STATUS -> "logcat -d -t 250 -s NfcDoorCard NfcUIDSim 2>/dev/null"
            LogSource.LSPosed -> "grep -h -E 'com.example.nfcdoorcard|NfcUIDSim|PROD MODULE|PROD HOOK|NFCID1' /data/adb/lspd/log/modules* 2>/dev/null$pidFilter | tail -n 600"
            LogSource.KernelSU -> "ls -t /data/adb/ksu/log/sulog* 2>/dev/null | head -n 1 | xargs -r cat | tail -n 250"
        }
        return runRootCmd(cmd).ifBlank { "No matching logs found for $source" }
    }

    private fun loadCards(): List<CardModel> {
        val prefs = getSharedPreferences("cards", 0)
        var json = prefs.getString("list", null)
        if (json == null) {
            val old = getSharedPreferences("saved_cards", 0).getString("cards_list", null)
            if (!old.isNullOrBlank()) {
                json = old
                prefs.edit().putString("list", old).apply()
            }
        }
        return if (json.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                gson.fromJson<List<CardModel>>(
                    json,
                    object : TypeToken<List<CardModel>>() {}.type
                ) ?: emptyList()
            }.getOrDefault(emptyList())
        }
    }

    private fun saveCards(cards: List<CardModel>) {
        getSharedPreferences("cards", 0).edit().putString("list", gson.toJson(cards)).apply()
    }

    private fun runOneTapDiagnosticAndShare(onDone: () -> Unit) {
        stopReadMode("diagnostic_share")
        executor.execute {
            try {
                val file = File(cacheDir, "nfc_fullcheck_1.0.15.txt")
                file.writeText(buildFullDiagnosticReport())
                AppLogger.i("Diagnostics exported: ${file.absolutePath}")
                runOnUiThread {
                    onDone()
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            "Share NFC Full Check"
                        )
                    )
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
        val pid = s.currentPid
        appendLine("=== NFC FULL CHECK 1.0.15 ===")
        appendLine("Generated: ${System.currentTimeMillis()}")
        appendLine("--- FINAL SUMMARY ---")
        appendLine(buildStatusSummary(s))
        appendLine()
        appendLine("--- SAVED CARDS ---")
        val cards = loadCards()
        appendLine("count=${cards.size}")
        cards.forEach { appendLine("card uid=${it.uid} sak=${it.sak} atqa=${it.atqa}") }
        appendLine("--- APP / APK ---")
        appendLine(runRootCmd("dumpsys package $packageName 2>/dev/null | grep -E 'versionName=|versionCode=|path:' | head -n 20"))
        appendLine("--- ROOT ---")
        appendLine(runRootCmd("id; su -v 2>/dev/null || true"))
        appendLine("--- NFC PROCESS / HAL ---")
        appendLine(runRootCmd("pm path com.android.nfc; pidof com.android.nfc; ps -A | grep -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|com.android.nfc|$packageName'"))
        appendLine("--- NFC SERVICE ---")
        appendLine(runRootCmd("dumpsys nfc 2>/dev/null | grep -E 'mState=|mScreenState=|listenTech=|pollTech=|mEnableHostRouting=|mTechMask' | head -n 160"))
        appendLine("--- CURRENT PID LSPOSED ---")
        val filter = if (pid > 0) " | grep ': $pid:'" else ""
        appendLine(runRootCmd("grep -h -E 'NfcUIDSim|PROD MODULE|PROD HOOK|NFCID1' /data/adb/lspd/log/modules* 2>/dev/null$filter | tail -n 1000"))
        appendLine("--- LOGCAT RF/NFC ---")
        appendLine(runRootCmd("logcat -d -v threadtime 2>/dev/null | grep -E 'NfcUIDSim|NFCID1|LA_NFCID1|CORE_SET_CONFIG|changeRfParamsByConfig|applyPreRfConfig' | tail -n 1000"))
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

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}
