package com.example.nfcdoorcard

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Bundle
import android.provider.Settings
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
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.Executors

enum class LogSource { HIJACK, LSPosed, KernelSU }

data class ModuleStatus(val active: Boolean, val process: String?)

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private val gson = Gson()
    private val PREFS_CARDS = "saved_cards"
    private val KEY_CARDS_LIST = "cards_list"
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        AppLogger.i("Diagnostics V7 started")

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NfcAppContent()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun NfcAppContent() {
        var cards by remember { mutableStateOf(loadCards()) }
        var activeSimUid by remember { mutableStateOf(getSimulatedUid()) }
        var moduleStatus by remember { mutableStateOf(getModuleStatus()) }
        var logText by remember { mutableStateOf("") }
        var selectedSource by remember { mutableStateOf(LogSource.HIJACK) }
        val logListState = rememberLazyListState()

        LaunchedEffect(selectedSource) {
            while (true) {
                moduleStatus = getModuleStatus()
                fetchLogs(selectedSource) { result ->
                    runOnUiThread {
                        logText = if (selectedSource == LogSource.HIJACK) {
                            AppLogger.getAllLogs() + "\n--- BOTTOM TRACE ---\n" + result
                        } else {
                            result
                        }
                    }
                }
                kotlinx.coroutines.delay(4000)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("NFC Expert Pro") },
                    actions = {
                        IconButton(onClick = { exportAllLogs() }) {
                            Icon(Icons.Default.Share, contentDescription = null)
                        }
                        IconButton(onClick = {
                            AppLogger.clear()
                            logText = ""
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (moduleStatus.active) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                    )
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (moduleStatus.active) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (moduleStatus.active) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (moduleStatus.active) "Module Active" else "Module NOT LOADED",
                                fontWeight = FontWeight.Bold,
                                color = if (moduleStatus.active) Color(0xFF2E7D32) else Color(0xFFC62828)
                            )
                            Text(
                                text = if (moduleStatus.active) {
                                    "Loaded in ${moduleStatus.process ?: "NFC process"} this boot"
                                } else {
                                    "Enable com.android.nfc scope in LSPosed, then restart NFC/reboot"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text(
                        text = if (activeSimUid != null) "LOCKED: $activeSimUid" else "STATUS: IDLE",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                LazyColumn(modifier = Modifier.height(160.dp).fillMaxWidth()) {
                    items(cards) { card ->
                        CardItem(card, card.uid == activeSimUid, {
                            simulateCard(card)
                            activeSimUid = card.uid
                        }, {
                            cards = cards.filter { it.uid != card.uid }
                            saveCards(cards)
                            if (activeSimUid == card.uid) {
                                disableSimulation()
                                activeSimUid = null
                            }
                        })
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
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
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
                                    line.contains("SUCCESS") || line.contains("Injecting") || line.contains("MODULE:") -> Color.Cyan
                                    line.contains("reset") || line.contains("fail", ignoreCase = true) -> Color.Red
                                    line.contains("APP:") -> Color.Gray
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
    fun CardItem(card: CardModel, isActive: Boolean, onSimulate: () -> Unit, onDelete: () -> Unit) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = card.name, fontWeight = FontWeight.Bold)
                Text(text = card.uid, fontSize = 10.sp, color = Color.Gray)
            }
            Button(onClick = onSimulate, enabled = !isActive, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(if (isActive) "ACTIVE" else "SIM", fontSize = 10.sp)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
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
        val uid = bytesToHex(tag.id)
        var sak = "08"
        var atqa = "0400"
        NfcA.get(tag)?.let {
            sak = "%02x".format(it.sak.toInt() and 0xFF)
            atqa = bytesToHex(it.atqa).reversed()
        }
        val currentCards = loadCards().toMutableList()
        if (currentCards.none { it.uid == uid }) {
            currentCards.add(CardModel("Card ${uid.takeLast(4)}", uid, sak, atqa))
            saveCards(currentCards)
            recreate()
        }
    }

    private fun loadCards(): List<CardModel> {
        val prefs = getSharedPreferences(PREFS_CARDS, MODE_PRIVATE)
        val json = prefs.getString(KEY_CARDS_LIST, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<CardModel>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveCards(cards: List<CardModel>) {
        getSharedPreferences(PREFS_CARDS, MODE_PRIVATE)
            .edit()
            .putString(KEY_CARDS_LIST, gson.toJson(cards))
            .apply()
    }

    private fun simulateCard(card: CardModel) {
        val values = ContentValues().apply {
            put(ConfigProvider.KEY_SIMULATION_ENABLED, true)
            put(ConfigProvider.KEY_UID, card.uid)
            put(ConfigProvider.KEY_SAK, card.sak)
            put(ConfigProvider.KEY_ATQA, card.atqa)
        }
        contentResolver.insert(ConfigProvider.CONTENT_URI, values)
        toggleNfc()
    }

    private fun disableSimulation() {
        contentResolver.insert(
            ConfigProvider.CONTENT_URI,
            ContentValues().apply { put(ConfigProvider.KEY_SIMULATION_ENABLED, false) }
        )
        toggleNfc()
    }

    private fun getSimulatedUid(): String? {
        return try {
            contentResolver.query(ConfigProvider.CONTENT_URI, null, null, null, null)?.use {
                var enabled = false
                var uid: String? = null
                while (it.moveToNext()) {
                    if (it.getString(0) == ConfigProvider.KEY_SIMULATION_ENABLED) enabled = it.getString(1) == "true"
                    if (it.getString(0) == ConfigProvider.KEY_UID) uid = it.getString(1)
                }
                if (enabled) uid else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getModuleStatus(): ModuleStatus {
        return try {
            val currentBoot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
            contentResolver.query(ConfigProvider.CONTENT_URI, null, null, null, null)?.use { cursor ->
                var active = false
                var process: String? = null
                var storedBoot = -2
                while (cursor.moveToNext()) {
                    when (cursor.getString(0)) {
                        ConfigProvider.KEY_MODULE_ACTIVE -> active = cursor.getString(1) == "true"
                        ConfigProvider.KEY_MODULE_PROCESS -> process = cursor.getString(1)
                        ConfigProvider.KEY_MODULE_BOOT_COUNT -> storedBoot = cursor.getString(1).toIntOrNull() ?: -2
                    }
                }
                ModuleStatus(active && storedBoot == currentBoot, process)
            } ?: ModuleStatus(false, null)
        } catch (e: Exception) {
            ModuleStatus(false, null)
        }
    }

    private fun fetchLogs(source: LogSource, callback: (String) -> Unit) {
        executor.execute {
            val cmd = when (source) {
                LogSource.HIJACK -> "su -c logcat -d -t 300 -s NfcUIDSim"
                LogSource.LSPosed -> "su -c 'ls -t /data/adb/lspd/log/modules* 2>/dev/null | head -n 1 | xargs -r cat | tail -n 300'"
                LogSource.KernelSU -> "su -c 'ls -t /data/adb/ksu/log/sulog* 2>/dev/null | head -n 1 | xargs -r cat | tail -n 300'"
            }
            try {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) output.append(line).append("\n")
                p.waitFor()
                callback(if (output.isEmpty()) "No logs found for $source" else output.toString())
            } catch (e: Exception) {
                callback("Error: ${e.message}")
            }
        }
    }

    private fun toggleNfc() {
        executor.execute {
            try {
                val p = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(p.outputStream)
                os.writeBytes("svc nfc disable\nsleep 0.5\nsvc nfc enable\nexit\n")
                os.flush()
                p.waitFor()
                AppLogger.i("NFC Toggle sent")
            } catch (e: Exception) {
                AppLogger.i("Toggle fail: ${e.message}")
            }
        }
    }

    private fun exportAllLogs() {
        executor.execute {
            try {
                val export = StringBuilder("=== COMPREHENSIVE DIAGNOSTIC ===\n\n")
                export.append("--- HIJACK ---\n")
                    .append(runRootCmd("logcat -d -t 1000 -s NfcUIDSim"))
                    .append("\n")
                export.append("--- LSPosed ---\n")
                    .append(runRootCmd("ls -t /data/adb/lspd/log/modules* 2>/dev/null | head -n 1 | xargs -r cat"))
                    .append("\n")
                export.append("--- KernelSU ---\n")
                    .append(runRootCmd("ls -t /data/adb/ksu/log/sulog* 2>/dev/null | head -n 1 | xargs -r cat"))
                    .append("\n")
                val file = File(cacheDir, "nfc_diag_v7.txt")
                file.writeText(export.toString())
                runOnUiThread {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_STREAM,
                            FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share Diagnostic"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Export fail", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun runRootCmd(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) output.append(line).append("\n")
            p.waitFor()
            output.toString()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
