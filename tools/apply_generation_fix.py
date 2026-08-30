from pathlib import Path


def rep(s, old, new, label):
    if old not in s:
        raise SystemExit(f"patch target not found: {label}")
    return s.replace(old, new, 1)

p = Path("app/src/main/java/com/example/nfcdoorcard/xposed/NfcInjectionModule.java")
s = p.read_text()
old = '''        if (!cfg.active && controllerReinitRequired) {
            NfcProcessVendorController.Result stopTrigger = vendorController.setShareMode(false);
            writeRfProgress(cfg, "STOPPING", "",
                    stopTrigger.detail + "; reinitializing NFC controller because LA_NFCID1 was appended", "controller-lifecycle");
            writeSemanticState("RESETTING_CONTROLLER", "UNKNOWN", "LIFECYCLE_PENDING", false, null);
            NfcProcessVendorController.Result reset = vendorController.reinitializeController();
            if (reset.success) {
                clearRestoreState();
                completeControllerReinit(cfg, reset.detail);
            } else {
                failCommand(cfg, "RF_CONTROLLER_RESET_FAILED", "", reset.stage + ": " + reset.detail, NativeOutcome.notInvoked());
            }
            return;
        }
'''
new = '''        if (!cfg.active && controllerReinitRequired) {
            NfcProcessVendorController.Result stopTrigger = vendorController.setShareMode(false);
            writeRfProgress(cfg, "STOPPING", "",
                    stopTrigger.detail + "; appended LA_NFCID1 requires NFC process/controller restart", "controller-lifecycle");
            // OxygenOS 16 rejects raw INfcAdapter disable/enable Binder calls from UID 1027 when
            // generated attribution/package identity is absent. Use the proven process lifecycle reset.
            failCommand(cfg, "RF_CONTROLLER_RESTART_REQUIRED", "",
                    "CONTROLLER_RESTART_REQUIRED: appended LA_NFCID1 cannot be deleted in-place; restart com.android.nfc to reinitialize controller",
                    NativeOutcome.notInvoked());
            return;
        }
'''
s = rep(s, old, new, "controller branch")
s = rep(s, '        v.put("rf_status", state);\n', '        v.put("state_generation", cfg.generation);\n        v.put("rf_status", state);\n', "rf progress")
marker = '        ContentValues v = baseHookState();\n        v.put("rf_status", rfState);\n'
s = rep(s, marker, '        ContentValues v = baseHookState();\n        v.put("state_generation", cfg.generation);\n        v.put("rf_status", rfState);\n', "complete generation")
s = rep(s, marker, '        ContentValues v = baseHookState();\n        v.put("state_generation", cfg.generation);\n        v.put("rf_status", rfState);\n', "fail generation")
s = rep(s, '        ContentValues v = baseHookState();\n        v.put("command_status", status);\n', '        ContentValues v = baseHookState();\n        if (generation > 0L) v.put("state_generation", generation);\n        v.put("command_status", status);\n', "simple generation")
s = rep(s, '        ContentValues v = baseHookState();\n        v.put("operation_state", operation);\n', '        ContentValues v = baseHookState();\n        SimConfig cfg = cachedConfig;\n        if (cfg.initialized && cfg.generation > 0L) v.put("state_generation", cfg.generation);\n        v.put("operation_state", operation);\n', "semantic generation")
s = rep(s, '    private void persistRestoreState() {\n        ContentValues v = new ContentValues();\n', '    private void persistRestoreState() {\n        ContentValues v = new ContentValues();\n        SimConfig cfg = cachedConfig;\n        if (cfg.initialized && cfg.generation > 0L) v.put("state_generation", cfg.generation);\n', "restore generation")
p.write_text(s)

p = Path("app/src/main/java/com/example/nfcdoorcard/MainActivity.kt")
s = p.read_text()
s = rep(s, '        contentResolver.insert(ConfigProvider.URI, ContentValues().apply {\n            put(ConfigProvider.KEY_APP_BUILD, ConfigProvider.APP_BUILD)\n            put(ConfigProvider.KEY_SIMULATION_ENABLED, enabled)\n', '        contentResolver.insert(ConfigProvider.URI, ContentValues().apply {\n            put(ConfigProvider.KEY_APP_BUILD, ConfigProvider.APP_BUILD)\n            put(ConfigProvider.KEY_STATE_GENERATION, generation)\n            put(ConfigProvider.KEY_SIMULATION_ENABLED, enabled)\n', "publish generation")
s = rep(s, '            AppLogger.i("SIMULATION: STOP timeout snapshot before fallback generation=$generation\\n${buildStatusSummary(state)}\\nPROVIDER=${readProviderMap().toSortedMap()}")\n            val restart = restartNfcProcessKeepingEnabled("stop_command_fallback_generation_$generation")\n', '            AppLogger.i("SIMULATION: STOP timeout snapshot before fallback generation=$generation\\n${buildStatusSummary(state)}\\nPROVIDER=${readProviderMap().toSortedMap()}")\n            if (!isCurrentCommandGeneration(generation)) {\n                state = readRuntimeStatus(includeRootPid = true)\n                AppLogger.i("SIMULATION: STOP fallback cancelled because generation=$generation is no longer current")\n                onDone(state, "停止请求已被更新的命令替代")\n                return@execute\n            }\n            val restart = restartNfcProcessKeepingEnabled("stop_command_fallback_generation_$generation")\n', "restart guard")
s = rep(s, '            if (!isStopSuccess(state, generation)) {\n                val currentPid = currentNfcPid().toIntOrNull() ?: state.currentPid\n                contentResolver.insert(ConfigProvider.URI, ContentValues().apply {\n                    put(ConfigProvider.KEY_COMMAND_HANDLED_GENERATION, generation)\n', '            if (!isStopSuccess(state, generation) && isCurrentCommandGeneration(generation)) {\n                val currentPid = currentNfcPid().toIntOrNull() ?: state.currentPid\n                contentResolver.insert(ConfigProvider.URI, ContentValues().apply {\n                    put(ConfigProvider.KEY_STATE_GENERATION, generation)\n                    put(ConfigProvider.KEY_COMMAND_HANDLED_GENERATION, generation)\n', "fallback generation")
s = rep(s, '    private fun getSimulationEnabled(): Boolean = readProviderMap()[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()\n', '    private fun isCurrentCommandGeneration(generation: Long): Boolean =\n        readProviderMap()[ConfigProvider.KEY_COMMAND_GENERATION]?.toLongOrNull() == generation\n\n    private fun getSimulationEnabled(): Boolean = readProviderMap()[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()\n', "generation helper")
p.write_text(s)

p = Path("app/build.gradle.kts")
s = p.read_text()
s = s.replace('versionCode = 25', 'versionCode = 26', 1)
s = s.replace('versionName = "1.0.24"', 'versionName = "1.0.25"', 1)
s = s.replace('buildConfigField("int", "HOOK_BUILD", "19")', 'buildConfigField("int", "HOOK_BUILD", "20")', 1)
p.write_text(s)
