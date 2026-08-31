package com.yagay.nfcdoorcard

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.yagay.nfcdoorcard.nfc.NfcReaderController
import com.yagay.nfcdoorcard.system.NfcSystemService
import com.yagay.nfcdoorcard.system.RootShell
import com.yagay.nfcdoorcard.ui.NfcAppScreen

class MainActivity : ComponentActivity() {
    companion object { private const val EXPECTED_HOOK_BUILD = BuildConfig.HOOK_BUILD }

    private lateinit var nfcReader: NfcReaderController
    private lateinit var cardRepository: CardRepository
    private lateinit var nfcSystemService: NfcSystemService
    private lateinit var runtimeRepository: RuntimeStatusRepository
    private lateinit var configClient: ConfigClient
    private lateinit var diagnosticsCollector: DiagnosticsCollector
    private lateinit var diagnosticExporter: DiagnosticExporter
    private lateinit var simulationCoordinator: SimulationCoordinator
    private var scannedCardState by mutableStateOf<CardModel?>(null)
    private var savedCardsState by mutableStateOf<List<CardModel>>(emptyList())
    private var readModeEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcReader = NfcReaderController(this)
        cardRepository = CardRepository(this)
        val rootShell = RootShell(this)
        nfcSystemService = NfcSystemService(rootShell)
        runtimeRepository = RuntimeStatusRepository(this, nfcSystemService)
        configClient = ConfigClient(contentResolver)
        diagnosticsCollector = DiagnosticsCollector(this, rootShell, nfcSystemService, runtimeRepository, cardRepository)
        diagnosticExporter = DiagnosticExporter(this, diagnosticsCollector)
        simulationCoordinator = SimulationCoordinator(configClient, runtimeRepository, nfcSystemService, EXPECTED_HOOK_BUILD)
        savedCardsState = cardRepository.load()
        AppLogger.i("NFC controller started; LSPosed in-process command engine enabled")
        enableEdgeToEdge()
        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NfcAppContent()
                }
            }
        }
    }

    override fun onResume() { super.onResume(); if (readModeEnabled) enableReadDispatch() }
    override fun onPause() { disableReadDispatch(); super.onPause() }
    override fun onDestroy() {
        disableReadDispatch()
        simulationCoordinator.close()
        diagnosticExporter.close()
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

    @Composable
    private fun NfcAppContent() {
        val initialLogsEnabled = remember { getDiagnosticLoggingEnabled() }
        NfcAppScreen(
            cards = savedCardsState,
            scannedCard = scannedCardState,
            readModeEnabled = readModeEnabled,
            initialLogsEnabled = initialLogsEnabled,
            diagnosticsCollector = diagnosticsCollector,
            readRuntimeStatus = { readRuntimeStatus(includeRootPid = false) },
            buildStatusSummary = ::buildStatusSummary,
            onLoggingChanged = configClient::setDiagnosticLogging,
            onStartRead = ::startReadMode,
            onStopRead = { stopReadMode() },
            onSaveCard = ::saveScannedCard,
            onClearScanned = { scannedCardState = null },
            onSimulate = { card, onMessage ->
                simulateCard(card) { _, message -> runOnUiThread { onMessage(message) } }
            },
            onStopSimulation = { onMessage ->
                stopSimulation { _, message -> runOnUiThread { onMessage(message) } }
            },
            onDeleteCard = { card, active ->
                if (active) stopSimulation { _, _ -> }
                savedCardsState = savedCardsState.filterNot { it.uid.equals(card.uid, true) }
                cardRepository.save(savedCardsState)
            },
            onExportLogs = ::saveDiagnosticWithoutSharing
        )
    }

    private fun simulateCard(card: CardModel, onDone: (RuntimeStatus, String) -> Unit) {
        stopReadMode("simulation_start")
        simulationCoordinator.simulate(card, onDone)
    }

    private fun stopSimulation(onDone: (RuntimeStatus, String) -> Unit) {
        stopReadMode("simulation_stop")
        simulationCoordinator.stop(onDone)
    }

    private fun getSimulationEnabled(): Boolean = runtimeRepository.simulationEnabled()

    private fun getDiagnosticLoggingEnabled(): Boolean =
        readProviderMap()[ConfigProvider.KEY_DIAGNOSTIC_LOGGING_ENABLED]?.toBooleanStrictOrNull() ?: false

    private fun readProviderMap(): Map<String, String> = runtimeRepository.readProviderMap()

    private fun readRuntimeStatus(includeRootPid: Boolean = false): RuntimeStatus =
        runtimeRepository.read(includeRootPid)

    private fun buildStatusSummary(s: RuntimeStatus): String =
        RuntimeText.statusSummary(s, EXPECTED_HOOK_BUILD, readModeEnabled)

    private fun saveDiagnosticWithoutSharing(onDone: () -> Unit) {
        stopReadMode("diagnostic_save")
        diagnosticExporter.export(::buildStatusSummary) { result ->
            runOnUiThread {
                onDone()
                result.onSuccess { fileName ->
                    Toast.makeText(this, "日志已保存到 Download/$fileName", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    Toast.makeText(this, "检测失败: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

}
