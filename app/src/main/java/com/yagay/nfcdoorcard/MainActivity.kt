package com.yagay.nfcdoorcard

import android.content.ContentValues
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yagay.nfcdoorcard.nfc.NfcReaderController
import com.yagay.nfcdoorcard.system.NfcSystemService
import com.yagay.nfcdoorcard.system.RootShell
import com.yagay.nfcdoorcard.ui.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    companion object { private const val EXPECTED_HOOK_BUILD = BuildConfig.HOOK_BUILD }

    private lateinit var nfcReader: NfcReaderController
    private lateinit var cardRepository: CardRepository
    private lateinit var rootShell: RootShell
    private lateinit var nfcSystemService: NfcSystemService
    private lateinit var runtimeRepository: RuntimeStatusRepository
    private lateinit var configClient: ConfigClient
    private val operationExecutor = Executors.newSingleThreadExecutor()
    private val diagnosticExecutor = Executors.newSingleThreadExecutor()
    private var scannedCardState by mutableStateOf<CardModel?>(null)
    private var savedCardsState by mutableStateOf<List<CardModel>>(emptyList())
    private var readModeEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcReader = NfcReaderController(this)
        cardRepository = CardRepository(this)
        rootShell = RootShell(this)
        nfcSystemService = NfcSystemService(rootShell)
        runtimeRepository = RuntimeStatusRepository(this, nfcSystemService)
        configClient = ConfigClient(contentResolver)
        savedCardsState = cardRepository.load()
        AppLogger.i("NFC controller started; LSPosed in-process command engine enabled")
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { NfcAppContent() } } }
    }

    override fun onResume() { super.onResume(); if (readModeEnabled) enableReadDispatch() }
    override fun onPause() { disableReadDispatch(); super.onPause() }
    override fun onDestroy() {
        disableReadDispatch()
        operationExecutor.shutdownNow()
        diagnosticExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun enableReadDispatch() {
        if (!readModeEnabled) return
        nfcReader.enable { card ->
            if (!readModeEnabled || getSimulationEnabled()) return@enable
            scannedCardState = card
            AppLogger.i("CARD: READ uid=${card.uid} sak=${card.sak} atqa=${card.atqa}")
            stopReadMode("card_read_complete")
        }
            .onSuccess { AppLogger.i("READ_MODE: reader mode enabled") }
            .onFailure { AppLogger.i("READ_MODE: enable reader mode failed ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun disableReadDispatch() { nfcReader.disable() }

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

    private fun saveScannedCard(card: CardModel) {
        if (savedCardsState.any { it.uid.equals(card.uid, true) }) {
            Toast.makeText(this, "该卡片已经保存", Toast.LENGTH_SHORT).show()
            return
        }
        savedCardsState = savedCardsState + card
        cardRepository.save(savedCardsState)
        AppLogger.i("CARD: SAVED uid=${card.uid} sak=${card.sak} atqa=${card.atqa}")
        Toast.makeText(this, "卡片已保存", Toast.LENGTH_SHORT).show()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NfcAppContent() {
        val cards = savedCardsState
        val runtimeViewModel: RuntimeStatusViewModel = viewModel()
        val status by runtimeViewModel.status.collectAsState()
        val logLines = remember { mutableStateListOf<String>() }
        var selectedSource by remember { mutableStateOf(LogSource.STATUS) }
        var diagnosticRunning by remember { mutableStateOf(false) }
        var logsEnabled by remember { mutableStateOf(getDiagnosticLoggingEnabled()) }
        var expandedUid by remember { mutableStateOf<String?>(null) }
        var operationMessage by remember { mutableStateOf<String?>(null) }
        val logListState = rememberLazyListState()

        // Logs are independent from runtime state. Only poll them while the log panel is open.
        LaunchedEffect(selectedSource, logsEnabled) {
            logLines.clear()
            if (!logsEnabled) return@LaunchedEffect
            while (true) {
                val incoming = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val logStatus = readRuntimeStatus(includeRootPid = false)
                    val raw = fetchLogsSync(selectedSource)
                    val text = if (selectedSource == LogSource.STATUS) buildStatusSummary(logStatus) + "\n\n" + raw else raw
                    boundedLogLines(text)
                }
                updateLogWindow(logLines, incoming)
                kotlinx.coroutines.delay(2000)
            }
        }

        Scaffold(topBar = { TopAppBar(title = { Text("NFC Expert Pro ${BuildConfig.VERSION_NAME}") }) }) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                item { RuntimeStatusPanel(status, operationMessage, readModeEnabled) }
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
                                simulateCard(card) { _, message -> runOnUiThread { operationMessage = message } }
                            },
                            {
                                operationMessage = "正在恢复原厂 RF..."
                                stopSimulation { _, message -> runOnUiThread { operationMessage = message } }
                            },
                            {
                                if (active) stopSimulation { _, _ -> }
                                savedCardsState = savedCardsState.filterNot { it.uid.equals(card.uid, true) }
                                if (expandedUid?.equals(card.uid, true) == true) expandedUid = null
                                cardRepository.save(savedCardsState)
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
                                Text(if (logsEnabled) "已开启 · 正在抓取日志" else "已关闭 · 不抓取日志，减少性能影响", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = logsEnabled,
                                onCheckedChange = { enabled ->
                                    logsEnabled = enabled
                                    configClient.setDiagnosticLogging(enabled)
                                    if (!enabled) logLines.clear()
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
                            LazyColumn(state = logListState, modifier = Modifier.fillMaxSize()) {
                                items(logLines.size) { index ->
                                    val line = logLines[index]
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
                            LaunchedEffect(selectedSource, logLines.size) {
                                if (logLines.isNotEmpty()) logListState.scrollToItem((logLines.size - 1).coerceAtLeast(0))
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
                            OutlinedButton(onClick = { AppLogger.clear(); logLines.clear() }, modifier = Modifier.weight(1f)) { Text("清空日志") }
                        }
                    }
                }
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
                state.commandStatus == "FAILED" || state.commandStatus == "TRIGGER_FAILED" -> "模拟失败 · ${state.commandStatus}: ${state.commandDetail ?: state.rfError ?: "unknown"}"
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
                AppLogger.i("SIMULATION: STOP success without restart generation=$generation\n${buildStatusSummary(state)}")
                onDone(state, "模拟已停止 · 原厂 RF 已由 NFC 进程恢复")
                return@execute
            }

            AppLogger.i("SIMULATION: STOP handoff snapshot before fallback generation=$generation\n${buildStatusSummary(state)}\nPROVIDER=${readProviderMap().toSortedMap()}")
            if (!isCurrentCommandGeneration(generation)) {
                state = readRuntimeStatus(includeRootPid = true)
                AppLogger.i("SIMULATION: STOP fallback cancelled because generation=$generation is no longer current")
                onDone(state, "停止请求已被更新的命令替代")
                return@execute
            }
            val restart = restartNfcProcessKeepingEnabled("stop_command_fallback_generation_$generation")
            AppLogger.i("SIMULATION: STOP fallback NFC restart generation=$generation\n$restart")
            waitForHookOnly(12_000)
            state = waitForCommandCompletion(generation, null, apply = false, timeoutMs = 6_000)

            if (!isStopSuccess(state, generation) && isCurrentCommandGeneration(generation)) {
                val currentPid = currentNfcPid().toIntOrNull() ?: state.currentPid
                configClient.confirmStockRestart(generation, currentPid)
                state = readRuntimeStatus(includeRootPid = true)
            }

            val message = if (isStopSuccess(state, generation)) "模拟已停止 · 已恢复原厂 RF" else "模拟已停止 · NFC 已重启，但状态确认未完成"
            onDone(state, message)
        }
    }

    private fun publishCommand(enabled: Boolean, card: CardModel?): Long =
        configClient.publishCommand(enabled, card)

    private fun restartNfcProcessKeepingEnabled(reason: String): String =
        nfcSystemService.restartNfcProcessKeepingEnabled(reason)

    private fun waitForCommandCompletion(generation: Long, uid: String?, apply: Boolean, timeoutMs: Long): RuntimeStatus {
        val end = System.currentTimeMillis() + timeoutMs
        var state = RuntimeStatus()
        while (System.currentTimeMillis() < end) {
            state = readRuntimeStatus(includeRootPid = true)
            if (if (apply) isApplySuccess(state, generation, uid.orEmpty()) else isStopSuccess(state, generation)) return state
            if (state.commandGeneration == generation && state.commandStatus == "RESTART_REQUIRED" && state.consumedGeneration == generation) return state
            if (state.commandGeneration == generation && state.handledGeneration == generation && state.commandStatus == "FAILED") return state
            Thread.sleep(100)
        }
        return state
    }

    private fun isApplySuccess(state: RuntimeStatus, generation: Long, uid: String): Boolean =
        state.commandGeneration == generation && state.handledGeneration == generation && state.commandStatus == "SUCCESS" &&
            state.currentPid > 0 && state.commandPid == state.currentPid && state.rfGeneration == generation &&
            state.rfPid == state.currentPid && state.operationState == "IDLE" && state.effectiveState == "ACTIVE" &&
            state.verificationConfidence == "VERIFIED" && state.rfAccepted && state.rfUid.equals(uid, ignoreCase = true)

    private fun isStopSuccess(state: RuntimeStatus, generation: Long): Boolean {
        val common = state.commandGeneration == generation && state.handledGeneration == generation &&
            state.commandStatus == "SUCCESS" && state.currentPid > 0 && state.commandPid == state.currentPid &&
            state.rfGeneration == generation && state.rfPid == state.currentPid
        return common && state.operationState == "IDLE" && state.effectiveState == "STOCK" &&
            state.verificationConfidence == "VERIFIED" && state.rfAccepted
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

    private fun isCurrentCommandGeneration(generation: Long): Boolean = runtimeRepository.isCurrentCommandGeneration(generation)

    private fun getSimulationEnabled(): Boolean = runtimeRepository.simulationEnabled()

    private fun getDiagnosticLoggingEnabled(): Boolean =
        readProviderMap()[ConfigProvider.KEY_DIAGNOSTIC_LOGGING_ENABLED]?.toBooleanStrictOrNull() ?: false

    private fun readProviderMap(): Map<String, String> = runtimeRepository.readProviderMap()

    private fun readRuntimeStatus(includeRootPid: Boolean = false): RuntimeStatus =
        runtimeRepository.read(includeRootPid)

    private fun decodeRuntimeStatus(map: Map<String, String>, rootPid: Int?): RuntimeStatus =
        runtimeRepository.decode(map, rootPid)

    private fun buildStatusSummary(s: RuntimeStatus): String = buildString {
        appendLine("=== CURRENT STATUS ===")
        appendLine("BUILD: app=${s.appBuild} hook=${s.hookBuild} expectedHook=$EXPECTED_HOOK_BUILD")
        appendLine("PID: current=${s.currentPid} runtime=${s.runtimePid} scope=${s.scopePid} hook=${s.hookPid} command=${s.commandPid} rf=${s.rfPid}")
        appendLine("SCOPE: ${if (s.scopeOk) "SUCCESS" else "NOT DETECTED/STALE"}")
        appendLine("HOOK: ${if (s.hookInstalled) "SUCCESS" else "NOT INSTALLED/STALE"}")
        appendLine("CONFIG: ${if (s.simulationEnabled) "ENABLED" else "IDLE"} uid=${s.selectedUid}")
        appendLine("SEMANTIC: operation=${s.operationState} effective=${s.effectiveState} confidence=${s.verificationConfidence} accepted=${s.rfAccepted}")
        appendLine("COMMAND: generation=${s.commandGeneration} consumed=${s.consumedGeneration} completed=${s.handledGeneration} action=${s.commandAction} status=${s.commandStatus} detail=${s.commandDetail}")
        appendLine("READ_MODE: ${if (readModeEnabled) "ENABLED" else "IDLE"}")
        appendLine("RF: generation=${s.rfGeneration} ${s.rfStatus} uid=${s.rfUid} source=${s.rfSource} result=${s.rfResult} raw=${s.rfNativeResult}/${s.rfNativeResultType} verification=${s.rfVerification} error=${s.rfError}")
        append("FINAL: stage=${s.fullDiagStage} summary=${s.fullDiagSummary}")
    }

    private fun fetchLogsSync(source: LogSource): String {
        val pid = currentNfcPid()
        val output = when (source) {
            LogSource.STATUS -> runRootCmd("logcat -d -t 350 -v threadtime -s NfcDoorCard NfcUIDSim 2>/dev/null")
            LogSource.LSPOSED -> runRootCmd("""
                { grep -R -h -E 'NfcUIDSim|com.yagay.nfcdoorcard|PROD MODULE|PROD HOOK|RFPROBE|NFCID1|COMMAND|LSPosed' /data/adb/lspd/log 2>/dev/null || true
                  logcat -b all -d -v threadtime 2>/dev/null | grep -E 'NfcUIDSim|PROD MODULE|PROD HOOK|RFPROBE|NFCID1|COMMAND' || true; } | tail -n 1500
            """.trimIndent())
            LogSource.KERNEL_SU -> runRootCmd("for f in ${'$'}(ls -t /data/adb/ksu/log/sulog* 2>/dev/null | head -n 3); do echo === ${'$'}f ===; tail -n 300 ${'$'}f; done")
            LogSource.SYSTEM -> runRootCmd("logcat -b all -d -v threadtime 2>/dev/null | tail -n 1800")
            LogSource.NFC -> {
                val filter = "NfcUIDSim|NfcService|NxpNfcService|NfcChipDeviceImpl|NFCID1|COMMAND|changeRfParamsByConfig|setRfConfig|VendorNfcService|enableNfcShareMode|NfcSwitchCardDispatcher|RealTimeSwitchCardManager|HceAccessCard|LxDebugProfileCompare|StrProfileMatch|TapToShareEvent|NfcRfEventStateMachine|RoutingTableParser|NxpNciX|NxpNciR|NfcAdaptation"
                val pidFilter = if (pid.isNotBlank()) "${'$'}0 ~ / $pid / || ${'$'}0 ~ /$filter/" else "${'$'}0 ~ /$filter/"
                runRootCmd("logcat -b all -d -v threadtime 2>/dev/null | awk '$pidFilter' | tail -n 1800")
            }
            LogSource.HAL -> runRootCmd("""
                echo '--- NFC PROCESSES ---'; ps -A | grep -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|com.android.nfc' || true
                echo '--- NFC PROPERTIES ---'; getprop | grep -i -E 'nfc|nxp|st21|st54|sn100|sn220|oplus' | head -n 300 || true
                echo '--- HAL LOGCAT ---'; logcat -b all -d -v threadtime 2>/dev/null | grep -i -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|NxpNfc|NfcHal|NxpNciX|NxpNciR|NfcAdaptation|oplus_nfc|libnfc|nfc-service|NFC HAL|STNfc|sn100|sn220' | tail -n 5000 || true
            """.trimIndent())
            LogSource.PROVIDER -> buildString {
                appendLine("=== PROVIDER STATE ==="); readProviderMap().toSortedMap().forEach { (k, v) -> appendLine("$k=$v") }; appendLine("current_nfc_pid=${currentNfcPid()}")
            }
            LogSource.APP -> AppLogger.readAll().ifBlank { "No app logs" }
        }
        return output.ifBlank { "No matching logs found for ${source.label}" }
    }

    private fun currentNfcPid(): String = nfcSystemService.currentNfcPid()

    private fun boundedLogLines(text: String, maxLines: Int = 1800): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.lineSequence().toList()
        return if (lines.size <= maxLines) lines else lines.takeLast(maxLines)
    }

    /**
     * Applies the smallest prefix/suffix diff possible to the Compose state list instead of
     * replacing/splitting the entire log inside composition every refresh. The window is bounded
     * by boundedLogLines(), so memory and recomposition cost stay predictable.
     */
    private fun updateLogWindow(target: MutableList<String>, incoming: List<String>) {
        var prefix = 0
        val commonLimit = minOf(target.size, incoming.size)
        while (prefix < commonLimit && target[prefix] == incoming[prefix]) prefix++

        var suffix = 0
        while (suffix < commonLimit - prefix &&
            target[target.size - 1 - suffix] == incoming[incoming.size - 1 - suffix]) {
            suffix++
        }

        val removeUntil = target.size - suffix
        for (i in removeUntil - 1 downTo prefix) target.removeAt(i)
        val addUntil = incoming.size - suffix
        if (prefix < addUntil) target.addAll(prefix, incoming.subList(prefix, addUntil))
    }

    private fun saveDiagnosticWithoutSharing(onDone: () -> Unit) {
        stopReadMode("diagnostic_save")
        diagnosticExecutor.execute {
            var createdUri: android.net.Uri? = null
            try {
                val fileName = "nfc_fullcheck_${BuildConfig.VERSION_NAME}_${System.currentTimeMillis()}.txt"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName); put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS); put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                createdUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法在 Download 创建日志文件")
                contentResolver.openOutputStream(createdUri, "w")?.bufferedWriter()?.use { it.write(buildFullDiagnosticReport()) } ?: error("无法写入日志文件")
                contentResolver.update(createdUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                AppLogger.i("Diagnostics saved to public Downloads: $fileName uri=$createdUri")
                runOnUiThread { onDone(); Toast.makeText(this@MainActivity, "日志已保存到 Download/$fileName", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                createdUri?.let { runCatching { contentResolver.delete(it, null, null) } }
                AppLogger.i("Diagnostics failed ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread { onDone(); Toast.makeText(this@MainActivity, "检测失败: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun buildFullDiagnosticReport(): String = buildString {
        val snapshotAt = System.currentTimeMillis()
        val snapshotMap = readProviderMap().toMap()
        val snapshotPid = currentNfcPid().toIntOrNull()
        val s = decodeRuntimeStatus(snapshotMap, snapshotPid)
        appendLine("=== NFC FULL CHECK ${BuildConfig.VERSION_NAME} ===")
        appendLine("Generated: $snapshotAt")
        appendLine("Snapshot: frozen provider + NFC PID; generation=${s.commandGeneration} pid=${s.currentPid}")
        appendLine("Trigger: LSPosed in-process NFC command engine; AIDL transaction IDs are reflected with compatibility fallbacks")
        appendLine("--- FINAL SUMMARY ---"); appendLine(buildStatusSummary(s)); appendLine()
        appendLine("--- SAVED CARDS ---")
        val cards = cardRepository.load(); appendLine("count=${cards.size}"); cards.forEach { appendLine("card uid=${it.uid} sak=${it.sak} atqa=${it.atqa}") }
        appendLine("--- APP / APK ---"); appendLine(runRootCmd("dumpsys package $packageName 2>/dev/null | grep -E 'versionName=|versionCode=|path:'"))
        appendLine("--- ROOT ---"); appendLine(runRootCmd("id; su -v 2>/dev/null || true"))
        appendLine("--- NFC PROCESS / HAL ---"); appendLine(runRootCmd("pm path com.android.nfc; pidof com.android.nfc; ps -A | grep -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|com.android.nfc|$packageName'"))
        appendLine("--- NFC CONFIG SNAPSHOT ---")
        appendLine(collectNfcConfigSnapshot())
        appendLine("--- NFC SERVICE FULL ---")
        appendLine(runRootCmd("dumpsys nfc 2>/dev/null", 25, 300_000))
        appendLine("--- NFC OVERWRITE TRACE / CORRELATED TIMELINE ---")
        appendLine(collectNfcOverwriteTrace())
        LogSource.entries.forEach { source ->
            appendLine(); appendLine("=== LOG SOURCE: ${source.name} / ${source.label} ===")
            if (source == LogSource.PROVIDER) {
                appendLine("=== PROVIDER STATE (FROZEN SNAPSHOT) ===")
                snapshotMap.toSortedMap().forEach { (k, v) -> appendLine("$k=$v") }
                appendLine("current_nfc_pid=${snapshotPid ?: 0}")
            } else {
                appendLine(fetchLogsSync(source))
            }
        }
    }

    private fun collectNfcConfigSnapshot(): String = nfcSystemService.collectNfcConfigSnapshot()

    /**
     * High-retention trace used to correlate a known-good simulated UID with the first later
     * OEM/NXP runtime reconfiguration that can restore stock/random NFCID1. This intentionally
     * does not change NFC behaviour; it only preserves evidence that the normal UI log windows
     * can otherwise tail away.
     */
    private fun collectNfcOverwriteTrace(): String = buildString {
        appendLine("TRACE_CAPTURE_EPOCH_MS=${System.currentTimeMillis()}")
        appendLine("TRACE_CURRENT_NFC_PID=${currentNfcPid()}")
        appendLine("TRACE_PROVIDER_BEGIN")
        readProviderMap().toSortedMap().forEach { (k, v) -> appendLine("$k=$v") }
        appendLine("TRACE_PROVIDER_END")

        appendLine("--- LOGCAT BUFFER INFO ---")
        appendLine(runRootCmd("logcat -g 2>/dev/null || true", 15, 300_000))

        appendLine("--- NCI TX/RX FULL TIMELINE (epoch) ---")
        appendLine(runRootCmd("""
            logcat -b all -d -v epoch 2>/dev/null | \
              grep -E 'NxpNciX|NxpNciR|NfcAdaptation::HalWrite|android\.hardware\.nfc-service\.nxp: write|NxpHal|phNxp|nfc_ncif_send|CORE_SET_CONFIG' || true
        """.trimIndent(), 35, 4_000_000))

        appendLine("--- NCI SET-CONFIG / NFCID1 CANDIDATES ---")
        appendLine(runRootCmd("""
            logcat -b all -d -v epoch 2>/dev/null | \
              grep -E 'NxpNciX|NxpNciR' | \
              grep -E -i '20[ :_-]*02|2002|33[ :_-]*00|3300|C1[ :_-]*B0[ :_-]*BC[ :_-]*1B|NFCID1' || true
        """.trimIndent(), 25, 2_000_000))

        appendLine("--- OPLUS RUNTIME CARD / SUPER-CARD TIMELINE (epoch) ---")
        appendLine(runRootCmd("""
            logcat -b all -d -v epoch 2>/dev/null | \
              grep -E 'NfcSwitchCardDispatcher|RealTimeSwitchCardManager|HceAccessCard|StrProfileMatch|LxDebugProfileCompare|TapToShareEvent|NfcRfEventStateMachine|VendorNfcService|loadListenTechMask|RESTORE_SUPERCARD|SUPER.?CARD|RF_FIELD|onRfFieldDetected|onLxDebugConfigData' || true
        """.trimIndent(), 30, 3_000_000))

        appendLine("--- BOOT / ROUTING / TAP-SHARE NFC TIMELINE (epoch) ---")
        appendLine(runRootCmd("""
            logcat -b all -d -v epoch 2>/dev/null | \
              grep -E 'ACTION_OPLUS_BOOT_COMPLETED|BOOT_COMPLETED|RoutingTableParser|NfcServiceRegister|accept-tap_share|startAdvertise|NfcChipDeviceImpl|setRfConfig|changeRfParamsByConfig|enableNfcShareMode|NfcUIDSim' || true
        """.trimIndent(), 30, 3_000_000))

        appendLine("--- NFC PROCESS / HAL STATE AT EXPORT ---")
        appendLine(runRootCmd("""
            echo '[processes]'; ps -A | grep -E 'com.android.nfc|android.hardware.nfc|vendor.oplus.hardware.nfc' || true
            echo '[properties]'; getprop | grep -i -E 'nfc|nxp|nfcuidsim|initialized' || true
            echo '[dumpsys summary]'; dumpsys nfc 2>/dev/null | grep -E -i 'state|screen|routing|discovery|reader|secure|listen|poll|host|aid' | head -n 600 || true
        """.trimIndent(), 25, 1_500_000))
    }

    private fun runRootCmd(command: String, timeoutSeconds: Long = 20, maxChars: Int = 1_000_000): String =
        rootShell.run(command, timeoutSeconds, maxChars)

}
