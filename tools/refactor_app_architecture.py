from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/yagay/nfcdoorcard/MainActivity.kt"
GRADLE = ROOT / "app/build.gradle.kts"
OLD_READER = ROOT / "app/src/main/java/com/yagay/nfcdoorcard/nfc/NfcForegroundDispatcher.kt"


def one(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1, found {count}")
    return text.replace(old, new, 1)


def between(text, start, end, replacement, label):
    a = text.find(start)
    b = text.find(end, a + len(start)) if a >= 0 else -1
    if a < 0 or b < 0:
        raise SystemExit(f"{label}: boundaries missing")
    return text[:a] + replacement + text[b:]


def main():
    text = MAIN.read_text()
    text = text.replace("import android.content.Intent\n", "")
    text = text.replace("import com.yagay.nfcdoorcard.nfc.NfcForegroundDispatcher\n", "import com.yagay.nfcdoorcard.nfc.NfcReaderController\n")
    text = text.replace("import com.google.gson.Gson\n", "")
    text = text.replace("import com.google.gson.reflect.TypeToken\n", "")

    text = one(text,
        "    private lateinit var nfcDispatcher: NfcForegroundDispatcher\n"
        "    private lateinit var rootShell: RootShell\n"
        "    private lateinit var nfcSystemService: NfcSystemService\n"
        "    private lateinit var runtimeRepository: RuntimeStatusRepository\n"
        "    private val gson = Gson()\n",
        "    private lateinit var nfcReader: NfcReaderController\n"
        "    private lateinit var cardRepository: CardRepository\n"
        "    private lateinit var rootShell: RootShell\n"
        "    private lateinit var nfcSystemService: NfcSystemService\n"
        "    private lateinit var runtimeRepository: RuntimeStatusRepository\n",
        "replace app fields")

    text = one(text,
        "        nfcDispatcher = NfcForegroundDispatcher(this)\n"
        "        rootShell = RootShell(this)\n"
        "        nfcSystemService = NfcSystemService(rootShell)\n"
        "        runtimeRepository = RuntimeStatusRepository(this, nfcSystemService)\n"
        "        savedCardsState = loadCards()\n",
        "        nfcReader = NfcReaderController(this)\n"
        "        cardRepository = CardRepository(this)\n"
        "        rootShell = RootShell(this)\n"
        "        nfcSystemService = NfcSystemService(rootShell)\n"
        "        runtimeRepository = RuntimeStatusRepository(this, nfcSystemService)\n"
        "        savedCardsState = cardRepository.load()\n",
        "initialize repositories")

    text = between(text,
        "    override fun onNewIntent(intent: Intent) {",
        "    override fun onResume()",
        "",
        "remove intent reader flow")

    text = one(text,
        "    private fun enableReadDispatch() {\n"
        "        if (!readModeEnabled) return\n"
        "        nfcDispatcher.enable()\n"
        "            .onSuccess { AppLogger.i(\"READ_MODE: foreground dispatch enabled\") }\n"
        "            .onFailure { AppLogger.i(\"READ_MODE: enable dispatch failed ${it.javaClass.simpleName}: ${it.message}\") }\n"
        "    }\n\n"
        "    private fun disableReadDispatch() { nfcDispatcher.disable() }\n",
        "    private fun enableReadDispatch() {\n"
        "        if (!readModeEnabled) return\n"
        "        nfcReader.enable { card ->\n"
        "            if (!readModeEnabled || getSimulationEnabled()) return@enable\n"
        "            scannedCardState = card\n"
        "            AppLogger.i(\"CARD: READ uid=${card.uid} sak=${card.sak} atqa=${card.atqa}\")\n"
        "            stopReadMode(\"card_read_complete\")\n"
        "        }\n"
        "            .onSuccess { AppLogger.i(\"READ_MODE: reader mode enabled\") }\n"
        "            .onFailure { AppLogger.i(\"READ_MODE: enable reader mode failed ${it.javaClass.simpleName}: ${it.message}\") }\n"
        "    }\n\n"
        "    private fun disableReadDispatch() { nfcReader.disable() }\n",
        "switch to ReaderMode")

    text = text.replace("saveCards(savedCardsState)", "cardRepository.save(savedCardsState)")

    # Logs may read a fresh snapshot for their own text, but must not mutate RuntimeStatusViewModel.
    text = one(text,
        "                val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {\n"
        "                    val newStatus = readRuntimeStatus(includeRootPid = false)\n"
        "                    val raw = fetchLogsSync(selectedSource)\n"
        "                    val text = if (selectedSource == LogSource.STATUS) buildStatusSummary(newStatus) + \"\\n\\n\" + raw else raw\n"
        "                    newStatus to boundedLogLines(text)\n"
        "                }\n"
        "                runtimeViewModel.update(snapshot.first)\n"
        "                updateLogWindow(logLines, snapshot.second)\n",
        "                val incoming = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {\n"
        "                    val logStatus = readRuntimeStatus(includeRootPid = false)\n"
        "                    val raw = fetchLogsSync(selectedSource)\n"
        "                    val text = if (selectedSource == LogSource.STATUS) buildStatusSummary(logStatus) + \"\\n\\n\" + raw else raw\n"
        "                    boundedLogLines(text)\n"
        "                }\n"
        "                updateLogWindow(logLines, incoming)\n",
        "separate logs from runtime state")

    # UI never fabricates RuntimeStatus. Provider observation is the normal status authority.
    text = one(text,
        "                                simulateCard(card) { newStatus, message -> runOnUiThread { runtimeViewModel.update(newStatus); operationMessage = message } }\n"
        "                                runtimeViewModel.update(status.copy(\n"
        "                                    simulationEnabled = true, selectedUid = card.uid, rfStatus = \"WAITING\",\n"
        "                                    operationState = \"APPLYING\", effectiveState = \"UNKNOWN\", verificationConfidence = \"PENDING\", rfAccepted = false\n"
        "                                ))\n",
        "                                simulateCard(card) { _, message -> runOnUiThread { operationMessage = message } }\n",
        "remove optimistic apply status")
    text = one(text,
        "                                stopSimulation { newStatus, message -> runOnUiThread { runtimeViewModel.update(newStatus); operationMessage = message } }\n"
        "                                runtimeViewModel.update(status.copy(\n"
        "                                    simulationEnabled = false, rfStatus = \"STOPPING\",\n"
        "                                    operationState = \"STOPPING\", effectiveState = \"UNKNOWN\", verificationConfidence = \"PENDING\", rfAccepted = false\n"
        "                                ))\n",
        "                                stopSimulation { _, message -> runOnUiThread { operationMessage = message } }\n",
        "remove optimistic stop status")

    # Remove Activity-owned persistence implementation.
    text = between(text,
        "    private fun loadCards(): List<CardModel> {",
        "    private fun saveDiagnosticWithoutSharing(onDone: () -> Unit) {",
        "    private fun saveDiagnosticWithoutSharing(onDone: () -> Unit) {",
        "remove Activity card persistence")
    text = text.replace("        val cards = loadCards(); appendLine(\"count=${cards.size}\"); cards.forEach { appendLine(\"card uid=${it.uid} sak=${it.sak} atqa=${it.atqa}\") }",
                        "        val cards = cardRepository.load(); appendLine(\"count=${cards.size}\"); cards.forEach { appendLine(\"card uid=${it.uid} sak=${it.sak} atqa=${it.atqa}\") }")

    MAIN.write_text(text)
    OLD_READER.unlink(missing_ok=True)

    gradle = GRADLE.read_text()
    gradle = one(gradle,
        '        versionCode = 52\n        versionName = "1.0.51"\n',
        '        versionCode = 53\n        versionName = "1.0.52"\n',
        "bump app version")
    GRADLE.write_text(gradle)

if __name__ == "__main__":
    main()
