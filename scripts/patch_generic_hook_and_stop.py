from pathlib import Path

# 1) Register GenericNxpAdapter after Oplus-specific adapter.
p = Path('app/src/main/java/com/example/nfcdoorcard/xposed/NfcInjectionModule.java')
s = p.read_text()
if 'import com.example.nfcdoorcard.xposed.adapter.GenericNxpAdapter;' not in s:
    s = s.replace('import com.example.nfcdoorcard.xposed.adapter.NfcStackAdapter;\n',
                  'import com.example.nfcdoorcard.xposed.adapter.NfcStackAdapter;\nimport com.example.nfcdoorcard.xposed.adapter.GenericNxpAdapter;\n')
s = s.replace('    private final NfcStackAdapter[] adapters = new NfcStackAdapter[]{\n            new OplusNxpAdapter()\n    };',
'''    private final NfcStackAdapter[] adapters = new NfcStackAdapter[]{
            // Prefer the proven vendor-specific implementation, then fall back to
            // a strictly validated raw CORE_SET_CONFIG NXP implementation.
            new OplusNxpAdapter(),
            new GenericNxpAdapter()
    };''')
p.write_text(s)

# 2) Make stopSimulation always restart NFC after disabling injection and share mode.
p = Path('app/src/main/java/com/example/nfcdoorcard/MainActivity.kt')
s = p.read_text()
start = s.index('    private fun stopSimulation(')
end = s.index('    private fun writeSimulationConfig(', start)
new = '''    private fun stopSimulation(onDone: (RuntimeStatus, String) -> Unit) {
        stopReadMode("simulation_stop")
        // Disable injection first so every RF config loaded during recovery remains stock.
        contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
            put(ConfigProvider.KEY_SIMULATION_ENABLED, false)
            put(ConfigProvider.KEY_RF_STATUS, "STOPPING")
            put(ConfigProvider.KEY_RF_UID, "")
            put(ConfigProvider.KEY_RF_RESULT, "")
            put(ConfigProvider.KEY_RF_ERROR, "")
            put(ConfigProvider.KEY_FULL_DIAG_STAGE, "STOPPING")
            put(ConfigProvider.KEY_FULL_DIAG_SUMMARY, "Disabling share mode, then restarting NFC to restore stock RF")
        })
        AppLogger.i("SIMULATION: DIRECT_BINDER stop requested; stock RF restart required")
        executor.execute {
            val binderResult = vendorNfcController.setShareMode(false)
            AppLogger.i("SIMULATION: VENDOR_BINDER disable success=${binderResult.success} stage=${binderResult.stage} detail=${binderResult.detail}")

            // A successful tx15(false) does not guarantee that LA_NFCID1 is rewritten.
            // Restart NFC unconditionally while simulation_enabled=false so the OEM stack
            // reloads its original RF configuration without UID injection.
            val restart = restartNfcProcessKeepingEnabled("stop_restore_stock_rf")
            AppLogger.i("SIMULATION: mandatory stock RF restart\\n$restart")
            val state = waitForHookOnly(12_000)

            contentResolver.insert(ConfigProvider.URI, ContentValues().apply {
                put(ConfigProvider.KEY_RF_STATUS, "IDLE")
                put(ConfigProvider.KEY_RF_UID, "")
                put(ConfigProvider.KEY_RF_SOURCE, "")
                put(ConfigProvider.KEY_RF_RESULT, "")
                put(ConfigProvider.KEY_RF_ERROR, "")
                put(ConfigProvider.KEY_RF_PID, 0)
                put(ConfigProvider.KEY_FULL_DIAG_STAGE, if (state.hookInstalled) "IDLE" else "RESTORE_RESTARTED_HOOK_WAIT")
                put(ConfigProvider.KEY_FULL_DIAG_SUMMARY,
                    if (state.hookInstalled) "Stock RF restored after mandatory NFC restart"
                    else "NFC restarted for stock RF; hook status not yet confirmed")
            })
            val finalState = readRuntimeStatus()
            val message = if (state.hookInstalled) {
                "模拟已停止 · NFC 已重启并恢复原厂 RF"
            } else {
                "模拟已停止 · NFC 已重启，Hook 正在重新就绪"
            }
            onDone(finalState, message)
        }
    }

'''
s = s[:start] + new + s[end:]
p.write_text(s)
