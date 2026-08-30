from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/yagay/nfcdoorcard/MainActivity.kt"
GRADLE = ROOT / "app/build.gradle.kts"


def one(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


def between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    a = text.find(start)
    b = text.find(end, a + len(start)) if a >= 0 else -1
    if a < 0 or b < 0:
        raise SystemExit(f"{label}: boundaries missing")
    return text[:a] + replacement + text[b:]


def main():
    text = MAIN.read_text()

    text = one(text,
        "    private lateinit var nfcReader: NfcReaderController\n"
        "    private lateinit var cardRepository: CardRepository\n"
        "    private lateinit var rootShell: RootShell\n"
        "    private lateinit var nfcSystemService: NfcSystemService\n"
        "    private lateinit var runtimeRepository: RuntimeStatusRepository\n"
        "    private lateinit var configClient: ConfigClient\n",
        "    private lateinit var nfcReader: NfcReaderController\n"
        "    private lateinit var cardRepository: CardRepository\n"
        "    private lateinit var nfcSystemService: NfcSystemService\n"
        "    private lateinit var runtimeRepository: RuntimeStatusRepository\n"
        "    private lateinit var configClient: ConfigClient\n"
        "    private lateinit var diagnosticsCollector: DiagnosticsCollector\n",
        "replace root field with diagnostics collector")

    text = one(text,
        "        nfcReader = NfcReaderController(this)\n"
        "        cardRepository = CardRepository(this)\n"
        "        rootShell = RootShell(this)\n"
        "        nfcSystemService = NfcSystemService(rootShell)\n"
        "        runtimeRepository = RuntimeStatusRepository(this, nfcSystemService)\n"
        "        configClient = ConfigClient(contentResolver)\n",
        "        nfcReader = NfcReaderController(this)\n"
        "        cardRepository = CardRepository(this)\n"
        "        val rootShell = RootShell(this)\n"
        "        nfcSystemService = NfcSystemService(rootShell)\n"
        "        runtimeRepository = RuntimeStatusRepository(this, nfcSystemService)\n"
        "        configClient = ConfigClient(contentResolver)\n"
        "        diagnosticsCollector = DiagnosticsCollector(this, rootShell, nfcSystemService, runtimeRepository, cardRepository)\n",
        "initialize diagnostics collector")

    text = one(text,
        "                    val raw = fetchLogsSync(selectedSource)\n",
        "                    val raw = diagnosticsCollector.fetchLogs(selectedSource)\n",
        "route UI log collection")

    text = one(text,
        "                contentResolver.openOutputStream(createdUri, \"w\")?.bufferedWriter()?.use { it.write(buildFullDiagnosticReport()) } ?: error(\"无法写入日志文件\")\n",
        "                contentResolver.openOutputStream(createdUri, \"w\")?.bufferedWriter()?.use {\n"
        "                    it.write(diagnosticsCollector.buildFullReport(::buildStatusSummary))\n"
        "                } ?: error(\"无法写入日志文件\")\n",
        "route full check report")

    text = one(text,
        "    private fun decodeRuntimeStatus(map: Map<String, String>, rootPid: Int?): RuntimeStatus =\n"
        "        runtimeRepository.decode(map, rootPid)\n\n",
        "",
        "remove activity decoder")

    text = between(text,
        "    private fun fetchLogsSync(source: LogSource): String {",
        "    private fun currentNfcPid(): String = nfcSystemService.currentNfcPid()",
        "",
        "remove activity log collector")

    report_start = text.find("    private fun buildFullDiagnosticReport(): String = buildString {")
    root_start = text.find("    private fun runRootCmd(command: String, timeoutSeconds: Long = 20, maxChars: Int = 1_000_000): String =", report_start)
    if report_start < 0 or root_start < 0:
        raise SystemExit("diagnostic report/root helper boundaries missing")
    root_body = "    private fun runRootCmd(command: String, timeoutSeconds: Long = 20, maxChars: Int = 1_000_000): String =\n        rootShell.run(command, timeoutSeconds, maxChars)\n"
    if not text.startswith(root_body, root_start):
        raise SystemExit("unexpected root helper body")
    tail = text[root_start + len(root_body):]
    if tail.strip() != "}":
        raise SystemExit("unexpected content after root helper")
    text = text[:report_start] + "}\n"
    MAIN.write_text(text)

    gradle = GRADLE.read_text()
    gradle = one(gradle,
        '        versionCode = 55\n        versionName = "1.0.54"\n',
        '        versionCode = 56\n        versionName = "1.0.55"\n',
        "bump final version")
    old_comment = "        // Runtime protocol v7; hook build 38; 1.0.51 extracts pure recovery policy, defers full trigger discovery until fallback is needed, and keeps exact replay as the controller-ready primary path.\n"
    if old_comment in gradle:
        gradle = gradle.replace(old_comment,
            "        // Runtime protocol v7; hook build 39; 1.0.55 finalizes recovery/replay separation, typed Provider commands, ReaderMode, and isolated diagnostics collection.\n",
            1)
    GRADLE.write_text(gradle)


if __name__ == "__main__":
    main()
