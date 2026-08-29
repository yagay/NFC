package com.example.nfcdoorcard

import android.content.ContentValues
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.Executors

class NfcControlActivity : ComponentActivity() {
    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Content() } } }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Content() {
        val cards = remember { loadCards() }
        var provider by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var message by remember { mutableStateOf("等待 HeyTap Hook 状态") }

        LaunchedEffect(Unit) {
            while (true) {
                executor.execute {
                    val map = readProviderMap()
                    runOnUiThread { provider = map }
                }
                kotlinx.coroutines.delay(500)
            }
        }

        val activeUid = provider[ConfigProvider.KEY_UID].orEmpty()
        val simulationEnabled = provider[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()
        val bridgeReady = provider["heytap_bridge_ready"].toBoolean()
        val bridgeStage = provider["heytap_bridge_stage"].orEmpty()
        val bridgeDetail = provider["heytap_bridge_detail"].orEmpty()
        val rfStatus = provider[ConfigProvider.KEY_RF_STATUS].orEmpty()
        val rfUid = provider[ConfigProvider.KEY_RF_UID].orEmpty()
        val rfResult = provider[ConfigProvider.KEY_RF_RESULT].orEmpty()

        Scaffold(topBar = { TopAppBar(title = { Text("NFC Expert Pro · HeyTap Bridge") }) }) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("运行状态", style = MaterialTheme.typography.titleMedium)
                            Text("HeyTap Hook: ${if (bridgeReady) "READY" else "WAITING"}")
                            Text("Bridge: ${bridgeStage.ifBlank { "-" }}")
                            if (bridgeDetail.isNotBlank()) Text(bridgeDetail, fontSize = 11.sp)
                            Text("模拟: ${if (simulationEnabled) activeUid else "IDLE"}", fontFamily = FontFamily.Monospace)
                            Text("RF: ${rfStatus.ifBlank { "-" }} uid=${rfUid.ifBlank { "-" }} result=${rfResult.ifBlank { "-" }}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            Text(message, fontSize = 11.sp)
                        }
                    }
                }

                item { Text("已保存卡片 (${cards.size})", style = MaterialTheme.typography.titleMedium) }

                if (cards.isEmpty()) {
                    item { Text("暂无已保存卡片。可以先从旧主界面读取并保存卡片。") }
                } else {
                    items(cards, key = { it.uid }) { card ->
                        val active = simulationEnabled && activeUid.equals(card.uid, true)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(card.name, style = MaterialTheme.typography.titleSmall)
                                Text("UID ${card.uid}", fontFamily = FontFamily.Monospace)
                                Text("SAK ${card.sak} · ATQA ${card.atqa}", fontSize = 11.sp)
                                if (active) {
                                    Button(
                                        onClick = {
                                            stopSimulation()
                                            message = "已发送停止请求，等待 HeyTap 恢复默认 RF"
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("停止模拟") }
                                } else {
                                    Button(
                                        onClick = {
                                            startSimulation(card)
                                            message = "已发送模拟请求，等待 HeyTap Hook"
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("模拟") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startSimulation(card: CardModel) {
        contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
            put(ConfigProvider.KEY_APP_BUILD, ConfigProvider.APP_BUILD)
            put(ConfigProvider.KEY_SIMULATION_ENABLED, true)
            put(ConfigProvider.KEY_UID, card.uid)
            put(ConfigProvider.KEY_SAK, card.sak)
            put(ConfigProvider.KEY_ATQA, card.atqa)
            put(ConfigProvider.KEY_RF_STATUS, "WAITING_HEYTAP")
            put(ConfigProvider.KEY_RF_UID, "")
            put(ConfigProvider.KEY_RF_SOURCE, "")
            put(ConfigProvider.KEY_RF_RESULT, "")
            put(ConfigProvider.KEY_RF_ERROR, "")
            put(ConfigProvider.KEY_RF_PID, 0)
            put(ConfigProvider.KEY_FULL_DIAG_STAGE, "WAITING_HEYTAP")
            put(ConfigProvider.KEY_FULL_DIAG_SUMMARY, "Waiting for HeyTap LSPosed bridge")
        })
        AppLogger.i("SIMULATION: HEYTAP_BRIDGE_REQUEST uid=${card.uid}")
    }

    private fun stopSimulation() {
        contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
            put(ConfigProvider.KEY_SIMULATION_ENABLED, false)
            put(ConfigProvider.KEY_RF_STATUS, "STOPPING_HEYTAP")
            put(ConfigProvider.KEY_FULL_DIAG_STAGE, "STOPPING_HEYTAP")
            put(ConfigProvider.KEY_FULL_DIAG_SUMMARY, "Waiting for HeyTap bridge to disable share mode")
        })
        AppLogger.i("SIMULATION: HEYTAP_BRIDGE_STOP_REQUEST")
    }

    private fun readProviderMap(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        runCatching {
            contentResolver.query(ConfigProvider.URI, null, null, null, null)?.use { c ->
                while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
            }
        }
        return out
    }

    private fun loadCards(): List<CardModel> {
        val prefs = getSharedPreferences("cards", 0)
        var json = prefs.getString("list", null)
        if (json == null) {
            val old = getSharedPreferences("saved_cards", 0).getString("cards_list", null)
            if (!old.isNullOrBlank()) json = old
        }
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<CardModel>>(json, object : TypeToken<List<CardModel>>() {}.type) ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
