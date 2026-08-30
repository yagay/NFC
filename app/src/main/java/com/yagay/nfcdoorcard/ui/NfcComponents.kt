package com.yagay.nfcdoorcard.ui

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
import com.yagay.nfcdoorcard.BuildConfig
import com.yagay.nfcdoorcard.CardModel
import com.yagay.nfcdoorcard.RuntimeStatus
import com.yagay.nfcdoorcard.StatusTone

@Composable
fun RuntimeStatusPanel(status: RuntimeStatus, operationMessage: String?, readModeEnabled: Boolean) {
    val hookReady = status.currentPid > 0 && status.scopeOk && status.hookInstalled && status.hookBuild == BuildConfig.HOOK_BUILD
    val commandInFlight = status.commandStatus in setOf("PENDING", "RUNNING", "TRIGGERED", "RESTART_REQUIRED") &&
        status.commandGeneration != status.handledGeneration
    val semanticBusy = status.operationState in setOf("APPLYING", "STOPPING", "RESETTING_CONTROLLER")
    val commandFailed = status.commandStatus in setOf("FAILED", "TRIGGER_FAILED", "OBSERVER_FAILED") || status.operationState == "FAILED"
    // The command row is diagnostic/history. Keep the single blue BUSY indicator on the
    // user-facing simulation row so one operation is not shown as three simultaneous blue states.
    val commandTone = if (commandFailed) StatusTone.ERROR else StatusTone.IDLE
    val commandDisplay = when {
        status.operationState == "RESETTING_CONTROLLER" ->
            "已提交 STOP · 正在重新初始化 NFC Controller · gen=${status.commandGeneration} · pid=${status.commandPid}"
        commandInFlight || semanticBusy ->
            "已提交 ${status.commandAction.ifBlank { "UNKNOWN" }} · ${status.operationState} · gen=${status.commandGeneration} consumed=${status.consumedGeneration} completed=${status.handledGeneration} · pid=${status.commandPid}"
        status.commandStatus == "SUCCESS" && status.commandGeneration == status.handledGeneration ->
            "当前空闲 · 最近操作 ${status.commandAction.ifBlank { "UNKNOWN" }} 成功 · gen=${status.commandGeneration} · pid=${status.commandPid}"
        commandFailed ->
            "最近操作 ${status.commandAction.ifBlank { "UNKNOWN" }} 失败 · ${status.commandStatus} · gen=${status.commandGeneration} · pid=${status.commandPid}"
        else -> "当前空闲 · 暂无命令"
    }

    val applyVerified = status.simulationEnabled &&
        status.effectiveState == "ACTIVE" && status.verificationConfidence == "VERIFIED" && status.rfAccepted &&
        status.rfUid.equals(status.selectedUid, ignoreCase = true)
    val stockVerified = !status.simulationEnabled &&
        status.effectiveState == "STOCK" && status.verificationConfidence == "VERIFIED" && status.rfAccepted

    val simulationTone: StatusTone
    val simulationDetail: String
    if (status.simulationEnabled) {
        when {
            applyVerified -> {
                simulationTone = StatusTone.OK
                simulationDetail = "模拟已生效 · UID=${status.selectedUid ?: "-"}"
            }
            commandFailed -> {
                simulationTone = StatusTone.ERROR
                simulationDetail = "模拟失败 · ${status.commandStatus}"
            }
            status.rfStatus.startsWith("STALE") -> {
                simulationTone = StatusTone.WARNING
                simulationDetail = "模拟已请求，但 RF 状态来自旧 NFC 进程"
            }
            else -> {
                simulationTone = StatusTone.BUSY
                simulationDetail = "正在应用 · ${status.operationState} · UID=${status.selectedUid ?: "-"}"
            }
        }
    } else {
        when {
            stockVerified -> {
                simulationTone = StatusTone.STOCK
                simulationDetail = if (status.rfVerification == "PROCESS_RESTART")
                    "模拟已停止 · 原厂 RF 已通过 NFC 生命周期恢复"
                else "模拟已停止 · 原厂 RF 已验证恢复"
            }
            status.operationState in setOf("STOPPING", "RESETTING_CONTROLLER") || (status.commandAction == "STOP" && commandInFlight) -> {
                simulationTone = StatusTone.BUSY
                simulationDetail = if (status.operationState == "RESETTING_CONTROLLER")
                    "正在重新初始化 NFC Controller 并恢复原厂 RF" else "正在停止模拟并恢复原厂 RF"
            }
            commandFailed -> {
                simulationTone = StatusTone.ERROR
                simulationDetail = "停止模拟失败 · ${status.commandDetail ?: status.rfError ?: "unknown"}"
            }
            status.rfStatus.startsWith("STALE") -> {
                simulationTone = StatusTone.WARNING
                simulationDetail = "模拟未启用 · 检测到旧 NFC 进程遗留 RF 状态"
            }
            else -> {
                simulationTone = StatusTone.IDLE
                simulationDetail = "未启用模拟"
            }
        }
    }

    val rfTone = when {
        status.effectiveState == "ACTIVE" && status.verificationConfidence == "VERIFIED" && status.rfAccepted -> StatusTone.OK
        status.effectiveState == "STOCK" && status.verificationConfidence == "VERIFIED" && status.rfAccepted -> StatusTone.STOCK
        status.operationState in setOf("APPLYING", "STOPPING", "RESETTING_CONTROLLER") -> StatusTone.WARNING
        status.rfStatus == "IDLE" && status.operationState == "IDLE" -> StatusTone.IDLE
        status.rfStatus.startsWith("STALE") -> StatusTone.WARNING
        status.operationState == "FAILED" || status.rfStatus.contains("FAILED") || !status.rfError.isNullOrBlank() -> StatusTone.ERROR
        else -> StatusTone.WARNING
    }

    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("运行状态", fontWeight = FontWeight.Bold)
            StatusRow(
                "NFC / Hook",
                if (hookReady) StatusTone.OK else StatusTone.ERROR,
                "pid=${status.currentPid} · runtimePid=${status.runtimePid} · hookBuild=${status.hookBuild}/${BuildConfig.HOOK_BUILD} · hookPid=${status.hookPid}"
            )
            StatusRow("模拟状态", simulationTone, simulationDetail)
            StatusRow("命令", commandTone, commandDisplay)
            StatusRow(
                "RF 状态",
                rfTone,
                "effective=${status.effectiveState} · op=${status.operationState} · confidence=${status.verificationConfidence} · accepted=${status.rfAccepted} · uid=${status.rfUid ?: "-"}"
            )
            Text(
                "底层诊断: ${status.rfStatus} · gen=${status.rfGeneration} · pid=${status.rfPid} · raw=${status.rfNativeResult ?: "-"} (${status.rfNativeResultType ?: "-"}) · verify=${status.rfVerification ?: "-"}",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("触发方式: LSPosed · com.android.nfc 进程内控制", fontSize = 11.sp)
            Text("读卡模式: ${if (readModeEnabled) "开启" else "关闭"}", fontSize = 11.sp)
            operationMessage?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            status.commandDetail?.takeIf { it.isNotBlank() }?.let { Text("最近命令: $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            status.rfError?.takeIf { it.isNotBlank() }?.let { Text("RF error: $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun ReadCardPanel(card: CardModel?, readMode: Boolean, simulationActive: Boolean, onStartRead: () -> Unit, onStopRead: () -> Unit, onSave: (CardModel) -> Unit, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("读卡模式", fontWeight = FontWeight.Bold)
            when {
                simulationActive -> { Text("当前正在模拟。读卡功能保持关闭。", fontSize = 12.sp); Button({}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("模拟中 · 读卡已关闭") } }
                readMode -> { Text("读卡已开启，请把门禁卡贴到手机背部。", fontSize = 12.sp); OutlinedButton(onStopRead, Modifier.fillMaxWidth()) { Text("退出读卡模式") } }
                card == null -> { Text("默认不读取卡片。需要添加新卡时再进入读卡模式。", fontSize = 12.sp); Button(onStartRead, Modifier.fillMaxWidth()) { Text("进入读卡模式") } }
                else -> {
                    Text("读取成功", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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
fun CardDetails(card: CardModel) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("名称: ${card.name}", fontSize = 12.sp)
        Text("UID: ${card.uid}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("SAK: ${card.sak}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("ATQA: ${card.atqa}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("UID 长度: ${card.uid.replace(Regex("[^0-9A-Fa-f]"), "").length / 2} bytes", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("类型: ISO/IEC 14443 Type A", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatusRow(label: String, tone: StatusTone, detail: String) {
    val symbol = when (tone) {
        StatusTone.OK -> "●"
        StatusTone.STOCK -> "●"
        StatusTone.IDLE -> "●"
        StatusTone.BUSY -> "●"
        StatusTone.WARNING -> "▲"
        StatusTone.ERROR -> "●"
    }
    val colors = MaterialTheme.colorScheme
    val color = when (tone) {
        StatusTone.OK -> colors.primary
        StatusTone.STOCK -> colors.tertiary
        StatusTone.IDLE -> colors.onSurfaceVariant
        StatusTone.BUSY -> colors.secondary
        StatusTone.WARNING -> Color(0xFFF9A825)
        StatusTone.ERROR -> colors.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(symbol, color = color, fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Column { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text(detail, fontSize = 11.sp) }
    }
}

@Composable
fun CardItem(card: CardModel, isActive: Boolean, expanded: Boolean, onToggleDetails: () -> Unit, onSimulate: () -> Unit, onStop: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp).clickable { onToggleDetails() }) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.name, fontWeight = FontWeight.Bold)
                    Text("UID ${card.uid}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (expanded) "点击收起详情" else "点击查看详情", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
