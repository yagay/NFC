package com.yagay.nfcdoorcard.ui

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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yagay.nfcdoorcard.*

/** Stateless Activity boundary for the complete NFC screen; operation state stays screen-local. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcAppScreen(
    cards: List<CardModel>,
    scannedCard: CardModel?,
    readModeEnabled: Boolean,
    initialLogsEnabled: Boolean,
    diagnosticsCollector: DiagnosticsCollector,
    readRuntimeStatus: () -> RuntimeStatus,
    buildStatusSummary: (RuntimeStatus) -> String,
    onLoggingChanged: (Boolean) -> Unit,
    onStartRead: () -> Unit,
    onStopRead: () -> Unit,
    onSaveCard: (CardModel) -> Unit,
    onClearScanned: () -> Unit,
    onSimulate: (CardModel, (String) -> Unit) -> Unit,
    onStopSimulation: ((String) -> Unit) -> Unit,
    onDeleteCard: (CardModel, Boolean) -> Unit,
    onExportLogs: (() -> Unit) -> Unit
) {
    val runtimeViewModel: RuntimeStatusViewModel = viewModel()
    val status by runtimeViewModel.status.collectAsState()
    val logLines = remember { mutableStateListOf<String>() }
    var selectedSource by remember { mutableStateOf(LogSource.STATUS) }
    var diagnosticRunning by remember { mutableStateOf(false) }
    var logsEnabled by remember { mutableStateOf(initialLogsEnabled) }
    var expandedUid by remember { mutableStateOf<String?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    val logListState = rememberLazyListState()

    // Logs remain independent from runtime status observation and only poll while visible.
    LaunchedEffect(selectedSource, logsEnabled) {
        logLines.clear()
        if (!logsEnabled) return@LaunchedEffect
        while (true) {
            val incoming = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val logStatus = readRuntimeStatus()
                val raw = diagnosticsCollector.fetchLogs(selectedSource)
                val text = if (selectedSource == LogSource.STATUS) {
                    buildStatusSummary(logStatus) + "\n\n" + raw
                } else raw
                RuntimeText.boundedLines(text)
            }
            RuntimeText.updateWindow(logLines, incoming)
            kotlinx.coroutines.delay(2000)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("NFC Expert Pro ${BuildConfig.VERSION_NAME}") },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            item { RuntimeStatusPanel(status, operationMessage, readModeEnabled) }
            item {
                ReadCardPanel(
                    scannedCard,
                    readModeEnabled,
                    status.simulationEnabled,
                    onStartRead,
                    onStopRead,
                    onSaveCard,
                    onClearScanned
                )
            }
            item {
                Text(
                    "已保存卡片 (${cards.size})",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontWeight = FontWeight.Bold
                )
            }
            if (cards.isEmpty()) {
                item {
                    Text(
                        "暂无保存卡片。进入读卡模式后贴卡，确认信息无误再保存。",
                        Modifier.padding(12.dp),
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
                            operationMessage = "正在通过 NFC 进程应用 UID ${card.uid}..."
                            onSimulate(card) { operationMessage = it }
                        },
                        onStop = {
                            operationMessage = "正在恢复原厂 RF..."
                            onStopSimulation { operationMessage = it }
                        },
                        onDelete = {
                            onDeleteCard(card, active)
                            if (expandedUid?.equals(card.uid, true) == true) expandedUid = null
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
                                onLoggingChanged(enabled)
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
                            Tab(
                                selected = selectedSource == source,
                                onClick = { selectedSource = source },
                                text = { Text(source.label, fontSize = 11.sp) }
                            )
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
                                    color = logLineColor(line),
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        LaunchedEffect(selectedSource, logLines.size) {
                            if (logLines.isNotEmpty()) {
                                logListState.scrollToItem((logLines.size - 1).coerceAtLeast(0))
                            }
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
                                    onExportLogs { diagnosticRunning = false }
                                }
                            },
                            enabled = !diagnosticRunning,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (diagnosticRunning) "保存中" else "导出日志") }
                        OutlinedButton(
                            onClick = { AppLogger.clear(); logLines.clear() },
                            modifier = Modifier.weight(1f)
                        ) { Text("清空日志") }
                    }
                }
            }
        }
    }
}

private fun logLineColor(line: String): Color = when {
    line.contains("SUCCESS") || line.contains("APPLIED") || line.contains("ACCEPTED") ||
        line.contains("READY") -> Color.Cyan
    line.contains("FAILED") || line.contains("ERROR") || line.contains("STALE") ||
        line.contains("FATAL") -> Color.Red
    line.contains("WAITING") || line.contains("IDLE") || line.contains("RUNNING") ||
        line.contains("TRIGGERED") -> Color.Yellow
    line.contains("RF") || line.contains("NFCID1") || line.contains("NfcUIDSim") ||
        line.contains("COMMAND") -> Color.Green
    else -> Color(0xFFD4D4D4)
}
