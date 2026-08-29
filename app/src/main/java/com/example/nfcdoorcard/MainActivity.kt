package com.example.nfcdoorcard

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()
    private var scannedCardState by mutableStateOf<CardModel?>(null)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.i("Diagnostics V12 started")
        handleIntent(intent)
        setContent { AppUi() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val adapter = NfcAdapter.getDefaultAdapter(this) ?: return
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        adapter.enableForegroundDispatch(this, pending, null, null)
    }

    override fun onPause() {
        super.onPause()
        runCatching { NfcAdapter.getDefaultAdapter(this)?.disableForegroundDispatch(this) }
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
    private fun AppUi() {
        MaterialTheme {
            var status by remember { mutableStateOf(readRuntimeStatus()) }
            var reloadRunning by remember { mutableStateOf(false) }
            var reloadMessage by remember { mutableStateOf("") }
            var cards by remember { mutableStateOf(loadCards()) }
            val scanned = scannedCardState

            Scaffold(topBar = { TopAppBar(title = { Text("NFC 门禁诊断 V12") }) }) { padding ->
                LazyColumn(
                    modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("运行状态", style = MaterialTheme.typography.titleMedium)
                                Text("LSPosed 范围: ${if (status.scopeOk) "SUCCESS" else "FAILED"}${status.scopeProcess?.let { " · $it" } ?: ""}")
                                Text("Hook: ${if (status.hookInstalled) "SUCCESS" else "NOT INSTALLED"} · count=${status.hookCount}")
                                Text("模拟配置: ${if (status.simulationEnabled) "ENABLED" else "IDLE"}${status.selectedUid?.let { " · $it" } ?: ""}")
                                Text("HCE Native: ${status.hijackStatus}${status.hijackUid?.let { " · $it" } ?: ""}")
                                Text("RF NFCID1: ${status.rfStatus}${status.rfUid?.let { " · $it" } ?: ""}")
                                status.rfSource?.let { Text("RF source: $it") }
                                status.rfResult?.let { Text("RF result: $it") }
                                status.rfError?.takeIf { it.isNotBlank() }?.let { Text("RF error: $it") }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("重新加载 Hook / 更新状态", modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = reloadRunning,
                                        onCheckedChange = { checked ->
                                            if (checked && !reloadRunning) {
                                                reloadRunning = true
                                                reloadMessage = "正在重新加载 com.android.nfc..."
                                                reloadNfcProcessAndHook { newStatus, msg ->
                                                    runOnUiThread {
                                                        status = newStatus
                                                        reloadMessage = msg
                                                        reloadRunning = false
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                                if (reloadMessage.isNotBlank()) Text(reloadMessage)
                                Button(onClick = { status = readRuntimeStatus() }) { Text("刷新状态") }
                            }
                        }
                    }

                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("读取卡片", style = MaterialTheme.typography.titleMedium)
                                if (scanned == null) {
                                    Text("把实体卡贴到手机背面读取 UID / SAK / ATQA")
                                } else {
                                    Text("UID: ${scanned.uid}")
                                    Text("SAK: ${scanned.sak}   ATQA: ${scanned.atqa}")
                                    Button(onClick = {
                                        if (cards.none { it.uid.equals(scanned.uid, true) }) {
                                            val next = cards + scanned
                                            saveCards(next)
                                            cards = next
                                            AppLogger.i("CARD: SAVED uid=${scanned.uid}")
                                        }
                                        scannedCardState = null
                                    }) { Text("保存卡片") }
                                }
                            }
                        }
                    }

                    item { Text("已保存卡片", style = MaterialTheme.typography.titleMedium) }
                    items(cards, key = { it.uid }) { card ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(card.name)
                                Text("UID: ${card.uid}")
                                Text("SAK: ${card.sak}   ATQA: ${card.atqa}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val active = status.simulationEnabled && status.selectedUid.equals(card.uid, true)
                                    Button(onClick = {
                                        if (active) disableSimulation() else simulateCard(card)
                                        status = readRuntimeStatus()
                                    }) { Text(if (active) "停止模拟" else "模拟") }
                                    OutlinedButton(onClick = {
                                        if (active) disableSimulation()
                                        val next = cards.filterNot { it.uid.equals(card.uid, true) }
                                        saveCards(next)
                                        cards = next
                                    }) { Text("删除") }
                                }
                            }
                        }
                    }

                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("诊断", style = MaterialTheme.typography.titleMedium)
                                Text("V12 会区分 HCE native 接受与 RF LA_NFCID1 真正出现/被改写。")
                                Button(onClick = { exportDiagnostics() }) { Text("导出完整诊断 V12") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun simulateCard(card: CardModel) {
        val values = ContentValues().apply {
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
        }
        contentResolver.insert(ConfigProvider.URI, values)
        AppLogger.i("CARD: SIM requested uid=${card.uid} sak=${card.sak} atqa=${card.atqa}")
        restartNfcSafely()
    }

    private fun disableSimulation() {
        val values = ContentValues().apply {
            put(ConfigProvider.KEY_SIMULATION_ENABLED, false)
            put(ConfigProvider.KEY_HIJACK_STATUS, "IDLE")
            put(ConfigProvider.KEY_RF_STATUS, "IDLE")
            put(ConfigProvider.KEY_RF_UID, "")
            put(ConfigProvider.KEY_RF_SOURCE, "")
            put(ConfigProvider.KEY_RF_RESULT, "")
            put(ConfigProvider.KEY_RF_ERROR, "")
        }
        contentResolver.insert(ConfigProvider.URI, values)
        AppLogger.i("CARD: simulation stopped")
        restartNfcSafely()
    }

    private fun restartNfcSafely() {
        executor.execute {
            val result = runRootCmd("svc nfc disable; sleep 1; svc nfc enable; sleep 2; dumpsys nfc 2>/dev/null | grep -m1 'mState=' || true")
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
                  state=${'$'}(dumpsys nfc 2>/dev/null | grep -m1 'mState=' || true)
                  echo "${'$'}state" | grep -q 'mState=on' && break
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
            }
            val oldPid = Regex("OLD_PID=(\\d+)").find(result)?.groupValues?.getOrNull(1)
            val newPid = Regex("NEW_PID=(\\d+)").find(result)?.groupValues?.getOrNull(1)
            val msg = when {
                oldPid != null && newPid != null && oldPid != newPid && state.scopeOk && state.hookInstalled ->
                    "更新成功：PID $oldPid → $newPid，Scope + Hook 已重新加载"
                oldPid != null && newPid != null && oldPid != newPid ->
                    "进程已重启：PID $oldPid → $newPid，等待 LSPosed 注入"
                else -> "重新加载失败：com.android.nfc PID 未变化"
            }
            AppLogger.i("RELOAD: $msg")
            onDone(state, msg)
        }
    }

    private fun clearRuntimeHookStatus() {
        val values = ContentValues().apply {
            put(ConfigProvider.KEY_SCOPE_OK, false)
            put(ConfigProvider.KEY_SCOPE_PROCESS, "")
            put(ConfigProvider.KEY_HOOK_INSTALLED, false)
            put(ConfigProvider.KEY_HOOK_CLASS, "")
            put(ConfigProvider.KEY_HOOK_COUNT, 0)
            put(ConfigProvider.KEY_HIJACK_STATUS, "IDLE")
            put(ConfigProvider.KEY_HIJACK_RESULT, "")
            put(ConfigProvider.KEY_HIJACK_UID, "")
            put(ConfigProvider.KEY_HIJACK_ERROR, "")
            put(ConfigProvider.KEY_RF_STATUS, "WAITING")
            put(ConfigProvider.KEY_RF_UID, "")
            put(ConfigProvider.KEY_RF_SOURCE, "")
            put(ConfigProvider.KEY_RF_RESULT, "")
            put(ConfigProvider.KEY_RF_ERROR, "")
        }
        contentResolver.insert(ConfigProvider.URI, values)
    }

    private fun readRuntimeStatus(): RuntimeStatus {
        val map = mutableMapOf<String, String>()
        runCatching {
            contentResolver.query(ConfigProvider.URI, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) map[cursor.getString(0)] = cursor.getString(1)
            }
        }

        var scopeOk = map[ConfigProvider.KEY_SCOPE_OK].toBoolean()
        var hookInstalled = map[ConfigProvider.KEY_HOOK_INSTALLED].toBoolean()
        val currentPid = runRootCmd("pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}'").trim()
        if (currentPid.isNotBlank()) {
            val lsp = runRootCmd(
                "grep -h -E 'SCOPE: SUCCESS|HOOK: (SUCCESS|INSTALLED)|RF: NFCID1|RF: CONFIG|HIJACK:' /data/adb/lspd/log/modules* 2>/dev/null | grep ': $currentPid:' | tail -n 300"
            )
            scopeOk = scopeOk || lsp.contains("SCOPE: SUCCESS package=com.android.nfc")
            hookInstalled = hookInstalled || lsp.contains("HOOK: SUCCESS") || lsp.contains("HOOK: INSTALLED")
        }

        return RuntimeStatus(
            scopeOk = scopeOk,
            scopeProcess = map[ConfigProvider.KEY_SCOPE_PROCESS]?.takeIf { it.isNotBlank() },
            hookInstalled = hookInstalled,
            hookClass = map[ConfigProvider.KEY_HOOK_CLASS]?.takeIf { it.isNotBlank() },
            hookCount = map[ConfigProvider.KEY_HOOK_COUNT]?.toIntOrNull() ?: 0,
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
    }

    private fun loadCards(): List<CardModel> {
        val json = getSharedPreferences("cards", 0).getString("list", null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<CardModel>>(json, object : TypeToken<List<CardModel>>() {}.type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun saveCards(cards: List<CardModel>) {
        getSharedPreferences("cards", 0).edit().putString("list", gson.toJson(cards)).apply()
    }

    private fun exportDiagnostics() {
        executor.execute {
            val report = buildString {
                appendLine("=== NFC FULL CHECK V12 ===")
                appendLine("Generated: ${System.currentTimeMillis()}")
                appendLine()
                appendLine("--- RUNTIME STATUS ---")
                val s = readRuntimeStatus()
                appendLine("SCOPE: ${if (s.scopeOk) "SUCCESS" else "FAILED"} process=${s.scopeProcess}")
                appendLine("HOOK: ${if (s.hookInstalled) "SUCCESS" else "NOT INSTALLED"} class=${s.hookClass} count=${s.hookCount}")
                appendLine("CONFIG: ${if (s.simulationEnabled) "ENABLED" else "IDLE"} uid=${s.selectedUid}")
                appendLine("HCE_NATIVE: ${s.hijackStatus} uid=${s.hijackUid} result=${s.hijackResult} error=${s.hijackError}")
                appendLine("RF_NFCID1: ${s.rfStatus} uid=${s.rfUid} source=${s.rfSource} result=${s.rfResult} error=${s.rfError}")
                appendLine()
                appendLine("--- SAVED CARDS ---")
                val cards = loadCards()
                appendLine("count=${cards.size}")
                cards.forEach { appendLine("card uid=${it.uid} sak=${it.sak} atqa=${it.atqa}") }
                appendLine()
                appendLine("--- APP / APK ---")
                appendLine(runRootCmd("dumpsys package $packageName 2>/dev/null | grep -E 'versionName=|versionCode=|path:' | head -n 20"))
                appendLine()
                appendLine("--- ROOT ---")
                appendLine(runRootCmd("id; su -v 2>/dev/null || true"))
                appendLine()
                appendLine("--- NFC PROCESS ---")
                appendLine(runRootCmd("pm path com.android.nfc; pidof com.android.nfc; ps -A | grep -E 'nfc|$packageName'"))
                appendLine()
                appendLine("--- NFC SERVICE ---")
                appendLine(runRootCmd("dumpsys nfc 2>/dev/null | grep -E 'mState=|mScreenState=|listenTech=|pollTech=|mEnableHostRouting=|mTechMask' | head -n 100"))
                appendLine()
                appendLine("--- LSPOSED / RF ---")
                appendLine(runRootCmd("grep -h -E 'NfcUIDSim|SCOPE:|HOOK:|HIJACK:|HCE:|RF:' /data/adb/lspd/log/modules* 2>/dev/null | tail -n 700"))
                appendLine()
                appendLine("--- LOGCAT RF/NFC ---")
                appendLine(runRootCmd("logcat -d -v threadtime 2>/dev/null | grep -E 'NfcUIDSim|NFCID1|LA_NFCID1|CORE_SET_CONFIG|setHceTypeAConfig' | tail -n 700"))
                appendLine()
                appendLine("--- APP LOG ---")
                appendLine(AppLogger.readAll())
            }
            val file = File(getExternalFilesDir(null), "nfc_fullcheck_v12.txt")
            file.writeText(report)
            AppLogger.i("Diagnostics exported: ${file.absolutePath}")
        }
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
