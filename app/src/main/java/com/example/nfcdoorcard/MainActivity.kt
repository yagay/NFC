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
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.zip.ZipFile

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

        AppLogger.i("Diagnostics V8 started")

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
        var diagnosticRunning by remember { mutableStateOf(false) }
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
                        IconButton(onClick = {
                            if (!diagnosticRunning) {
                                diagnosticRunning = true
                                runOneTapDiagnosticAndShare { diagnosticRunning = false }
                            }
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "One tap diagnostic")
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
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (moduleStatus.active) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (moduleStatus.active) Color(0xFF4CAF50) else Color(0xFFF44336)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (moduleStatus.active) "Module heartbeat detected" else "Module heartbeat not detected",
                                    fontWeight = FontWeight.Bold,
                                    color = if (moduleStatus.active) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                                Text(
                                    text = if (moduleStatus.active) {
                                        "Seen in ${moduleStatus.process ?: "NFC process"} this boot"
                                    } else {
                                        "Use ONE-TAP CHECK for exact cause"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (!diagnosticRunning) {
                                    diagnosticRunning = true
                                    runOneTapDiagnosticAndShare { diagnosticRunning = false }
                                }
                            },
                            enabled = !diagnosticRunning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (diagnosticRunning) "CHECKING..." else "ONE-TAP CHECK + EXPORT")
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text(
                        text = if (activeSimUid != null) "SELECTED: $activeSimUid" else "STATUS: IDLE",
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
                                    line.contains("SUCCESS") || line.contains("APPLY") || line.contains("MODULE:") -> Color.Cyan
                                    line.contains("SAFE-SKIP") || line.contains("fail", ignoreCase = true) -> Color.Red
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
                Text(if (isActive) "SELECTED" else "SIM", fontSize = 10.sp)
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
            atqa = bytesToHex(it.atqa.reversedArray())
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
        } catch (_: Exception) {
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
        restartNfcSafely()
    }

    private fun disableSimulation() {
        contentResolver.insert(
            ConfigProvider.CONTENT_URI,
            ContentValues().apply { put(ConfigProvider.KEY_SIMULATION_ENABLED, false) }
        )
        restartNfcSafely()
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
            ModuleStatus(false, null)
        }
    }

    private fun fetchLogs(source: LogSource, callback: (String) -> Unit) {
        executor.execute {
            val cmd = when (source) {
                LogSource.HIJACK -> "logcat -d -t 500 -s NfcUIDSim"
                LogSource.LSPosed -> "grep -h -E 'com.example.nfcdoorcard|NfcUIDSim|XposedEntry|com.android.nfc' /data/adb/lspd/log/modules* 2>/dev/null | tail -n 300"
                LogSource.KernelSU -> "ls -t /data/adb/ksu/log/sulog* 2>/dev/null | head -n 1 | xargs -r cat | tail -n 300"
            }
            val output = runRootCmd(cmd)
            callback(if (output.isBlank()) "No matching logs found for $source" else output)
        }
    }

    private fun restartNfcSafely() {
        executor.execute {
            val script = """
                svc nfc disable
                i=0
                while [ ${'$'}i -lt 20 ]; do
                  s=$(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' | tr 'A-Z' 'a-z')
                  echo "${'$'}s" | grep -Eq 'mstate=1|state_off|state=off| off' && break
                  sleep 0.25
                  i=$((i+1))
                done
                svc nfc enable
                i=0
                while [ ${'$'}i -lt 24 ]; do
                  s=$(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' | tr 'A-Z' 'a-z')
                  echo "${'$'}s" | grep -Eq 'mstate=3|state_on|state=on| on' && break
                  sleep 0.25
                  i=$((i+1))
                done
                echo "FINAL_STATE:"; dumpsys nfc 2>/dev/null | grep -m3 -E 'mState=|state=' || true
            """.trimIndent()
            val result = runRootCmd(script)
            AppLogger.i("NFC restart completed: ${result.lineSequence().lastOrNull().orEmpty()}")
        }
    }

    private fun runOneTapDiagnosticAndShare(onDone: () -> Unit) {
        executor.execute {
            try {
                val report = buildFullDiagnosticReport()
                val file = File(cacheDir, "nfc_fullcheck_v8.txt")
                file.writeText(report)
                runOnUiThread {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_STREAM,
                            FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    onDone()
                    startActivity(Intent.createChooser(intent, "Share NFC Full Check"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    onDone()
                    Toast.makeText(this@MainActivity, "Diagnostic failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildFullDiagnosticReport(): String {
        val report = StringBuilder()
        report.append("=== NFC FULL CHECK V8 ===\n")
        report.append("Generated: ").append(System.currentTimeMillis()).append("\n\n")

        report.append("--- APP / APK ---\n")
        report.append(inspectOwnApk()).append("\n")

        report.append("--- APP CONFIG ---\n")
        report.append("boot_count=").append(Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)).append("\n")
        report.append("module_status=").append(getModuleStatus()).append("\n")
        report.append("selected_uid=").append(getSimulatedUid()).append("\n\n")

        report.append("--- ROOT ---\n")
        report.append(runRootCmd("id; su -v 2>/dev/null || true")).append("\n")

        report.append("--- NFC PACKAGE / PROCESS ---\n")
        report.append(runRootCmd("pm path com.android.nfc; pidof com.android.nfc; ps -A | grep -i '[n]fc' || true")).append("\n")

        report.append("--- NFC SERVICE ---\n")
        report.append(runRootCmd("dumpsys nfc 2>/dev/null | head -n 220")).append("\n")

        report.append("--- LSPOSED INSTALLATION ---\n")
        report.append(runRootCmd("ls -ld /data/adb/lspd /data/adb/lspd/log 2>&1; ls -lt /data/adb/lspd/log/modules* 2>/dev/null | head -n 20")).append("\n")

        report.append("--- LSPOSED MODULE MATCHES (ALL LOG FILES) ---\n")
        report.append(
            runRootCmd(
                "grep -h -n -E 'com.example.nfcdoorcard|NfcUIDSim|XposedEntry|com.android.nfc' /data/adb/lspd/log/modules* 2>/dev/null | tail -n 1000"
            )
        ).append("\n")

        report.append("--- LSPOSED RECENT ERRORS ---\n")
        report.append(runRootCmd("grep -h -E 'Failed to load module|Cannot load module|java_init.list|ClassNotFoundException|VerifyError|NoSuchMethodError' /data/adb/lspd/log/modules* 2>/dev/null | tail -n 400")).append("\n")

        report.append("--- HIJACK LOGCAT ---\n")
        report.append(runRootCmd("logcat -d -t 1500 -s NfcUIDSim")).append("\n")

        report.append("--- PACKAGE DETAILS ---\n")
        report.append(runRootCmd("dumpsys package com.example.nfcdoorcard | head -n 160")).append("\n")

        report.append("--- LSPOSED FILE MAP ---\n")
        report.append(runRootCmd("find /data/adb/lspd -maxdepth 2 -type f 2>/dev/null | sort | head -n 300")).append("\n")

        report.append("--- KERNELSU RELATED ---\n")
        report.append(runRootCmd("ls -t /data/adb/ksu/log/sulog* 2>/dev/null | head -n 1 | xargs -r cat | tail -n 600")).append("\n")

        report.append("--- APP LOG ---\n")
        report.append(AppLogger.getAllLogs()).append("\n")

        return report.toString()
    }

    private fun inspectOwnApk(): String {
        val out = StringBuilder()
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val source = applicationInfo.sourceDir
        out.append("package=").append(packageName).append("\n")
        out.append("versionName=").append(packageInfo.versionName).append("\n")
        out.append("versionCode=").append(packageInfo.longVersionCode).append("\n")
        out.append("sourceDir=").append(source).append("\n")

        val expected = listOf(
            "META-INF/xposed/java_init.list",
            "META-INF/xposed/scope.list",
            "META-INF/xposed/module.prop"
        )
        try {
            ZipFile(source).use { zip ->
                expected.forEach { name ->
                    val entry = zip.getEntry(name)
                    out.append(name).append('=').append(if (entry != null) "OK" else "MISSING").append("\n")
                    if (entry != null) {
                        zip.getInputStream(entry).bufferedReader().use { reader ->
                            out.append(reader.readText().trim()).append("\n")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            out.append("APK_INSPECTION_ERROR=").append(e.javaClass.simpleName).append(':').append(e.message).append("\n")
        }
        return out.toString()
    }

    private fun runRootCmd(cmd: String): String {
        return try {
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
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
