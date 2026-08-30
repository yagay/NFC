package com.yagay.nfcdoorcard

import android.content.Context
import com.yagay.nfcdoorcard.system.NfcSystemService
import com.yagay.nfcdoorcard.system.RootShell

/**
 * Owns diagnostic collection and Full Check report generation.
 *
 * Keeping shell/log collection out of MainActivity makes diagnostics independently evolvable and
 * ensures UI recomposition never contains the expensive root/logcat work itself.
 */
class DiagnosticsCollector(
    context: Context,
    private val rootShell: RootShell,
    private val nfcSystemService: NfcSystemService,
    private val runtimeRepository: RuntimeStatusRepository,
    private val cardRepository: CardRepository
) {
    private val packageName = context.packageName

    fun fetchLogs(source: LogSource): String {
        val pid = currentNfcPid()
        val output = when (source) {
            LogSource.STATUS -> runRoot("logcat -d -t 350 -v threadtime -s NfcDoorCard NfcUIDSim 2>/dev/null")
            LogSource.LSPOSED -> runRoot("""
                { grep -R -h -E 'NfcUIDSim|com.yagay.nfcdoorcard|PROD MODULE|PROD HOOK|RFPROBE|NFCID1|COMMAND|LSPosed' /data/adb/lspd/log 2>/dev/null || true
                  logcat -b all -d -v threadtime 2>/dev/null | grep -E 'NfcUIDSim|PROD MODULE|PROD HOOK|RFPROBE|NFCID1|COMMAND' || true; } | tail -n 1500
            """.trimIndent())
            LogSource.KERNEL_SU -> runRoot("for f in ${'$'}(ls -t /data/adb/ksu/log/sulog* 2>/dev/null | head -n 3); do echo === ${'$'}f ===; tail -n 300 ${'$'}f; done")
            LogSource.SYSTEM -> runRoot("logcat -b all -d -v threadtime 2>/dev/null | tail -n 1800")
            LogSource.NFC -> {
                val filter = "NfcUIDSim|NfcService|NxpNfcService|NfcChipDeviceImpl|NFCID1|COMMAND|changeRfParamsByConfig|setRfConfig|VendorNfcService|enableNfcShareMode|NfcSwitchCardDispatcher|RealTimeSwitchCardManager|HceAccessCard|LxDebugProfileCompare|StrProfileMatch|TapToShareEvent|NfcRfEventStateMachine|RoutingTableParser|NxpNciX|NxpNciR|NfcAdaptation"
                val pidFilter = if (pid.isNotBlank()) "${'$'}0 ~ / $pid / || ${'$'}0 ~ /$filter/" else "${'$'}0 ~ /$filter/"
                runRoot("logcat -b all -d -v threadtime 2>/dev/null | awk '$pidFilter' | tail -n 1800")
            }
            LogSource.HAL -> runRoot("""
                echo '--- NFC PROCESSES ---'; ps -A | grep -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|com.android.nfc' || true
                echo '--- NFC PROPERTIES ---'; getprop | grep -i -E 'nfc|nxp|st21|st54|sn100|sn220|oplus' | head -n 300 || true
                echo '--- HAL LOGCAT ---'; logcat -b all -d -v threadtime 2>/dev/null | grep -i -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|NxpNfc|NfcHal|NxpNciX|NxpNciR|NfcAdaptation|oplus_nfc|libnfc|nfc-service|NFC HAL|STNfc|sn100|sn220' | tail -n 5000 || true
            """.trimIndent())
            LogSource.PROVIDER -> buildString {
                appendLine("=== PROVIDER STATE ===")
                runtimeRepository.readProviderMap().toSortedMap().forEach { (k, v) -> appendLine("$k=$v") }
                appendLine("current_nfc_pid=${currentNfcPid()}")
            }
            LogSource.APP -> AppLogger.readAll().ifBlank { "No app logs" }
        }
        return output.ifBlank { "No matching logs found for ${source.label}" }
    }

    fun buildFullReport(statusSummary: (RuntimeStatus) -> String): String = buildString {
        val snapshotAt = System.currentTimeMillis()
        val snapshotMap = runtimeRepository.readProviderMap().toMap()
        val snapshotPid = currentNfcPid().toIntOrNull()
        val status = runtimeRepository.decode(snapshotMap, snapshotPid)

        appendLine("=== NFC FULL CHECK ${BuildConfig.VERSION_NAME} ===")
        appendLine("Generated: $snapshotAt")
        appendLine("Snapshot: frozen provider + NFC PID; generation=${status.commandGeneration} pid=${status.currentPid}")
        appendLine("Trigger: LSPosed in-process NFC command engine; AIDL transaction IDs are reflected with compatibility fallbacks")
        appendLine("--- FINAL SUMMARY ---")
        appendLine(statusSummary(status))
        appendLine()
        appendLine("--- SAVED CARDS ---")
        val cards = cardRepository.load()
        appendLine("count=${cards.size}")
        cards.forEach { appendLine("card uid=${it.uid} sak=${it.sak} atqa=${it.atqa}") }
        appendLine("--- APP / APK ---")
        appendLine(runRoot("dumpsys package $packageName 2>/dev/null | grep -E 'versionName=|versionCode=|path:'"))
        appendLine("--- ROOT ---")
        appendLine(runRoot("id; su -v 2>/dev/null || true"))
        appendLine("--- NFC PROCESS / HAL ---")
        appendLine(runRoot("pm path com.android.nfc; pidof com.android.nfc; ps -A | grep -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|com.android.nfc|$packageName'"))
        appendLine("--- NFC CONFIG SNAPSHOT ---")
        appendLine(nfcSystemService.collectNfcConfigSnapshot())
        appendLine("--- NFC SERVICE FULL ---")
        appendLine(runRoot("dumpsys nfc 2>/dev/null", 25, 300_000))
        appendLine("--- NFC OVERWRITE TRACE / CORRELATED TIMELINE ---")
        appendLine(collectNfcOverwriteTrace())

        LogSource.entries.forEach { source ->
            appendLine()
            appendLine("=== LOG SOURCE: ${source.name} / ${source.label} ===")
            if (source == LogSource.PROVIDER) {
                appendLine("=== PROVIDER STATE (FROZEN SNAPSHOT) ===")
                snapshotMap.toSortedMap().forEach { (k, v) -> appendLine("$k=$v") }
                appendLine("current_nfc_pid=${snapshotPid ?: 0}")
            } else {
                appendLine(fetchLogs(source))
            }
        }
    }

    private fun currentNfcPid(): String = nfcSystemService.currentNfcPid()

    /** High-retention timeline used for the OEM/NXP NFCID1 overwrite investigation. */
    private fun collectNfcOverwriteTrace(): String = buildString {
        appendLine("TRACE_CAPTURE_EPOCH_MS=${System.currentTimeMillis()}")
        appendLine("TRACE_CURRENT_NFC_PID=${currentNfcPid()}")
        appendLine("TRACE_PROVIDER_BEGIN")
        runtimeRepository.readProviderMap().toSortedMap().forEach { (k, v) -> appendLine("$k=$v") }
        appendLine("TRACE_PROVIDER_END")

        appendLine("--- LOGCAT BUFFER INFO ---")
        appendLine(runRoot("logcat -g 2>/dev/null || true", 15, 300_000))

        appendLine("--- NCI TX/RX FULL TIMELINE (epoch) ---")
        appendLine(runRoot("""
            logcat -b all -d -v epoch 2>/dev/null | \
              grep -E 'NxpNciX|NxpNciR|NfcAdaptation::HalWrite|android\.hardware\.nfc-service\.nxp: write|NxpHal|phNxp|nfc_ncif_send|CORE_SET_CONFIG' || true
        """.trimIndent(), 35, 4_000_000))

        appendLine("--- NCI SET-CONFIG / NFCID1 CANDIDATES ---")
        appendLine(runRoot("""
            logcat -b all -d -v epoch 2>/dev/null | \
              grep -E 'NxpNciX|NxpNciR' | \
              grep -E -i '20[ :_-]*02|2002|33[ :_-]*00|3300|C1[ :_-]*B0[ :_-]*BC[ :_-]*1B|NFCID1' || true
        """.trimIndent(), 25, 2_000_000))

        appendLine("--- OPLUS RUNTIME CARD / SUPER-CARD TIMELINE (epoch) ---")
        appendLine(runRoot("""
            logcat -b all -d -v epoch 2>/dev/null | \
              grep -E 'NfcSwitchCardDispatcher|RealTimeSwitchCardManager|HceAccessCard|StrProfileMatch|LxDebugProfileCompare|TapToShareEvent|NfcRfEventStateMachine|VendorNfcService|loadListenTechMask|RESTORE_SUPERCARD|SUPER.?CARD|RF_FIELD|onRfFieldDetected|onLxDebugConfigData' || true
        """.trimIndent(), 30, 3_000_000))

        appendLine("--- BOOT / ROUTING / TAP-SHARE NFC TIMELINE (epoch) ---")
        appendLine(runRoot("""
            logcat -b all -d -v epoch 2>/dev/null | \
              grep -E 'ACTION_OPLUS_BOOT_COMPLETED|BOOT_COMPLETED|RoutingTableParser|NfcServiceRegister|accept-tap_share|startAdvertise|NfcChipDeviceImpl|setRfConfig|changeRfParamsByConfig|enableNfcShareMode|NfcUIDSim' || true
        """.trimIndent(), 30, 3_000_000))

        appendLine("--- NFC PROCESS / HAL STATE AT EXPORT ---")
        appendLine(runRoot("""
            echo '[processes]'; ps -A | grep -E 'com.android.nfc|android.hardware.nfc|vendor.oplus.hardware.nfc' || true
            echo '[properties]'; getprop | grep -i -E 'nfc|nxp|nfcuidsim|initialized' || true
            echo '[dumpsys summary]'; dumpsys nfc 2>/dev/null | grep -E -i 'state|screen|routing|discovery|reader|secure|listen|poll|host|aid' | head -n 600 || true
        """.trimIndent(), 25, 1_500_000))
    }

    private fun runRoot(command: String, timeoutSeconds: Long = 20, maxChars: Int = 1_000_000): String =
        rootShell.run(command, timeoutSeconds, maxChars)
}
