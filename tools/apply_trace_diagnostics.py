from pathlib import Path

main = Path('app/src/main/java/com/yagay/nfcdoorcard/MainActivity.kt')
text = main.read_text()

anchor = '''        appendLine("--- NFC SERVICE FULL ---")
        appendLine(runRootCmd("dumpsys nfc 2>/dev/null", 25, 300_000))
        LogSource.entries.forEach { source ->
'''
replacement = '''        appendLine("--- NFC SERVICE FULL ---")
        appendLine(runRootCmd("dumpsys nfc 2>/dev/null", 25, 300_000))
        appendLine("--- NFC OVERWRITE TRACE / CORRELATED TIMELINE ---")
        appendLine(collectNfcOverwriteTrace())
        LogSource.entries.forEach { source ->
'''
if anchor not in text:
    raise SystemExit('buildFullDiagnosticReport anchor not found')
text = text.replace(anchor, replacement, 1)

insert_anchor = '''    private fun collectNfcConfigSnapshot(): String = nfcSystemService.collectNfcConfigSnapshot()

    private fun runRootCmd(command: String, timeoutSeconds: Long = 20, maxChars: Int = 1_000_000): String =
'''
helper = r'''    private fun collectNfcConfigSnapshot(): String = nfcSystemService.collectNfcConfigSnapshot()

    /**
     * High-retention trace used to correlate a known-good simulated UID with the first later
     * OEM/NXP runtime reconfiguration that can restore stock/random NFCID1. This intentionally
     * does not change NFC behaviour; it only preserves evidence that the normal UI log windows
     * can otherwise tail away.
     */
    private fun collectNfcOverwriteTrace(): String = buildString {
        appendLine("TRACE_CAPTURE_EPOCH_MS=${System.currentTimeMillis()}")
        appendLine("TRACE_CURRENT_NFC_PID=${currentNfcPid()}")
        appendLine("TRACE_PROVIDER_BEGIN")
        readProviderMap().toSortedMap().forEach { (k, v) -> appendLine("$k=$v") }
        appendLine("TRACE_PROVIDER_END")

        appendLine("--- LOGCAT BUFFER INFO ---")
        appendLine(runRootCmd("logcat -g 2>/dev/null || true", 15, 300_000))

        appendLine("--- NCI TX/RX FULL TIMELINE (epoch) ---")
        appendLine(runRootCmd("""
            logcat -b all -d -v epoch 2>/dev/null | \
              grep -E 'NxpNciX|NxpNciR|NfcAdaptation::HalWrite|android\.hardware\.nfc-service\.nxp: write|NxpHal|phNxp|nfc_ncif_send|CORE_SET_CONFIG' || true
        """.trimIndent(), 35, 4_000_000))

        appendLine("--- NCI SET-CONFIG / NFCID1 CANDIDATES ---")
        appendLine(runRootCmd("""
            logcat -b all -d -v epoch 2>/dev/null | \
              grep -E 'NxpNciX|NxpNciR' | \
              grep -E -i '20[ :_-]*02|2002|33[ :_-]*00|3300|C1[ :_-]*B0[ :_-]*BC[ :_-]*1B|NFCID1' || true
        """.trimIndent(), 25, 2_000_000))

        appendLine("--- OPLUS RUNTIME CARD / SUPER-CARD TIMELINE (epoch) ---")
        appendLine(runRootCmd("""
            logcat -b all -d -v epoch 2>/dev/null | \
              grep -E 'NfcSwitchCardDispatcher|RealTimeSwitchCardManager|HceAccessCard|StrProfileMatch|LxDebugProfileCompare|TapToShareEvent|NfcRfEventStateMachine|VendorNfcService|loadListenTechMask|RESTORE_SUPERCARD|SUPER.?CARD|RF_FIELD|onRfFieldDetected|onLxDebugConfigData' || true
        """.trimIndent(), 30, 3_000_000))

        appendLine("--- BOOT / ROUTING / TAP-SHARE NFC TIMELINE (epoch) ---")
        appendLine(runRootCmd("""
            logcat -b all -d -v epoch 2>/dev/null | \
              grep -E 'ACTION_OPLUS_BOOT_COMPLETED|BOOT_COMPLETED|RoutingTableParser|NfcServiceRegister|accept-tap_share|startAdvertise|NfcChipDeviceImpl|setRfConfig|changeRfParamsByConfig|enableNfcShareMode|NfcUIDSim' || true
        """.trimIndent(), 30, 3_000_000))

        appendLine("--- NFC PROCESS / HAL STATE AT EXPORT ---")
        appendLine(runRootCmd("""
            echo '[processes]'; ps -A | grep -E 'com.android.nfc|android.hardware.nfc|vendor.oplus.hardware.nfc' || true
            echo '[properties]'; getprop | grep -i -E 'nfc|nxp|nfcuidsim|initialized' || true
            echo '[dumpsys summary]'; dumpsys nfc 2>/dev/null | grep -E -i 'state|screen|routing|discovery|reader|secure|listen|poll|host|aid' | head -n 600 || true
        """.trimIndent(), 25, 1_500_000))
    }

    private fun runRootCmd(command: String, timeoutSeconds: Long = 20, maxChars: Int = 1_000_000): String =
'''
if insert_anchor not in text:
    raise SystemExit('helper insertion anchor not found')
text = text.replace(insert_anchor, helper, 1)

old_hal = '''                echo '--- HAL LOGCAT ---'; logcat -b all -d -v threadtime 2>/dev/null | grep -i -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|NxpNfc|NfcHal|libnfc|nfc-service|NFC HAL|STNfc|sn100|sn220' | tail -n 1200 || true
'''
new_hal = '''                echo '--- HAL LOGCAT ---'; logcat -b all -d -v threadtime 2>/dev/null | grep -i -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|NxpNfc|NfcHal|NxpNciX|NxpNciR|NfcAdaptation|oplus_nfc|libnfc|nfc-service|NFC HAL|STNfc|sn100|sn220' | tail -n 5000 || true
'''
if old_hal not in text:
    raise SystemExit('HAL log anchor not found')
text = text.replace(old_hal, new_hal, 1)

old_nfc = '''                val filter = "NfcUIDSim|NfcService|NxpNfcService|NfcChipDeviceImpl|NFCID1|COMMAND|changeRfParamsByConfig|setRfConfig|VendorNfcService|enableNfcShareMode"
'''
new_nfc = '''                val filter = "NfcUIDSim|NfcService|NxpNfcService|NfcChipDeviceImpl|NFCID1|COMMAND|changeRfParamsByConfig|setRfConfig|VendorNfcService|enableNfcShareMode|NfcSwitchCardDispatcher|RealTimeSwitchCardManager|HceAccessCard|LxDebugProfileCompare|StrProfileMatch|TapToShareEvent|NfcRfEventStateMachine|RoutingTableParser|NxpNciX|NxpNciR|NfcAdaptation"
'''
if old_nfc not in text:
    raise SystemExit('NFC filter anchor not found')
text = text.replace(old_nfc, new_nfc, 1)
main.write_text(text)

build = Path('app/build.gradle.kts')
b = build.read_text()
b = b.replace('versionCode = 47', 'versionCode = 48', 1)
b = b.replace('versionName = "1.0.46"', 'versionName = "1.0.47"', 1)
b = b.replace(
    '// Runtime protocol v7; hook build 34; RF-sequence quiet-period settling + strictly-new final reapply after NFC lifecycle, with generic Java capability discovery, controller epochs, bounded OPLUS rewrite and 4/7/10-byte NFCID1 support.',
    '// Runtime protocol v7; hook build 34; diagnostic-only 1.0.47 adds high-retention correlated NCI TX/RX + Oplus SuperCard/routing/boot timelines without changing NFC injection behaviour.'
)
build.write_text(b)

print('Applied 1.0.47 overwrite-trace diagnostics')
