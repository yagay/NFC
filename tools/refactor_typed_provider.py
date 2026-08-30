from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROVIDER = ROOT / "app/src/main/java/com/yagay/nfcdoorcard/ConfigProvider.kt"
MAIN = ROOT / "app/src/main/java/com/yagay/nfcdoorcard/MainActivity.kt"
GRADLE = ROOT / "app/build.gradle.kts"


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
    provider = PROVIDER.read_text()
    provider = provider.replace("import android.os.Binder\n", "import android.os.Binder\nimport android.os.Bundle\n")

    provider = one(provider,
        '        val APP_BUILD: Int = BuildConfig.VERSION_CODE\n',
        '        val APP_BUILD: Int = BuildConfig.VERSION_CODE\n\n'
        '        const val METHOD_PUBLISH_COMMAND = "publish_command"\n'
        '        const val METHOD_SET_DIAGNOSTIC_LOGGING = "set_diagnostic_logging"\n'
        '        const val METHOD_CONFIRM_STOCK_RESTART = "confirm_stock_restart"\n'
        '        const val EXTRA_ENABLED = "enabled"\n'
        '        const val EXTRA_UID = "uid"\n'
        '        const val EXTRA_SAK = "sak"\n'
        '        const val EXTRA_ATQA = "atqa"\n'
        '        const val EXTRA_GENERATION = "generation"\n'
        '        const val EXTRA_PID = "pid"\n'
        '        const val RESULT_GENERATION = "generation"\n'
        '        const val RESULT_SUCCESS = "success"\n',
        "add typed API constants")

    provider = one(provider,
        "class ConfigProvider : ContentProvider() {\n",
        "class ConfigProvider : ContentProvider() {\n    private lateinit var devicePrefs: SharedPreferences\n",
        "add prefs field")

    provider = one(provider,
        "        val prefs = deviceContext.getSharedPreferences(PREFS_NAME, 0)\n"
        "        migrateStateIfNeeded(prefs)\n"
        "        prefs.edit()\n",
        "        devicePrefs = deviceContext.getSharedPreferences(PREFS_NAME, 0)\n"
        "        migrateStateIfNeeded(devicePrefs)\n"
        "        devicePrefs.edit()\n",
        "cache device prefs")

    call_method = '''
    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!isTrustedCaller()) {
            Log.w("NfcConfigProvider", "Rejected call=$method from uid=${Binder.getCallingUid()}")
            return Bundle().apply { putBoolean(RESULT_SUCCESS, false) }
        }
        return when (method) {
            METHOD_PUBLISH_COMMAND -> {
                val enabled = extras?.getBoolean(EXTRA_ENABLED, false) == true
                val uid = extras?.getString(EXTRA_UID)
                val sak = extras?.getString(EXTRA_SAK)
                val atqa = extras?.getString(EXTRA_ATQA)
                val previous = (prefs().all[KEY_COMMAND_GENERATION] as? Number)?.toLong() ?: 0L
                val generation = maxOf(previous + 1L, System.currentTimeMillis())
                val action = if (enabled) "APPLY" else "STOP"
                insert(URI, ContentValues().apply {
                    put(KEY_APP_BUILD, APP_BUILD)
                    put(KEY_STATE_GENERATION, generation)
                    put(KEY_SIMULATION_ENABLED, enabled)
                    if (enabled && !uid.isNullOrBlank()) {
                        put(KEY_UID, uid)
                        put(KEY_SAK, sak.orEmpty())
                        put(KEY_ATQA, atqa.orEmpty())
                    }
                    put(KEY_COMMAND_GENERATION, generation)
                    put(KEY_COMMAND_ACTION, action)
                    put(KEY_COMMAND_STATUS, "PENDING")
                    put(KEY_COMMAND_DETAIL, "Waiting for LSPosed NFC process command engine")
                    put(KEY_COMMAND_PID, 0)
                    put(KEY_OPERATION_STATE, if (enabled) "APPLYING" else "STOPPING")
                    put(KEY_EFFECTIVE_STATE, "UNKNOWN")
                    put(KEY_VERIFICATION_CONFIDENCE, "PENDING")
                    put(KEY_RF_ACCEPTED, false)
                    put(KEY_RF_NATIVE_RESULT, "")
                    put(KEY_RF_NATIVE_RESULT_TYPE, "")
                    put(KEY_RF_STATUS, if (enabled) "WAITING" else "STOPPING")
                    put(KEY_RF_UID, "")
                    put(KEY_RF_SOURCE, "")
                    put(KEY_RF_RESULT, "")
                    put(KEY_RF_ERROR, "")
                    put(KEY_RF_PID, 0)
                    put(KEY_RF_GENERATION, generation)
                    put(KEY_RF_VERIFICATION, "")
                    put(KEY_FULL_DIAG_STAGE, "COMMAND_PENDING")
                    put(KEY_FULL_DIAG_SUMMARY, "$action generation=$generation published by app")
                })
                Bundle().apply {
                    putBoolean(RESULT_SUCCESS, true)
                    putLong(RESULT_GENERATION, generation)
                }
            }
            METHOD_SET_DIAGNOSTIC_LOGGING -> {
                val enabled = extras?.getBoolean(EXTRA_ENABLED, false) == true
                insert(URI, ContentValues().apply { put(KEY_DIAGNOSTIC_LOGGING_ENABLED, enabled) })
                Bundle().apply { putBoolean(RESULT_SUCCESS, true) }
            }
            METHOD_CONFIRM_STOCK_RESTART -> {
                val generation = extras?.getLong(EXTRA_GENERATION, 0L) ?: 0L
                val pid = extras?.getInt(EXTRA_PID, 0) ?: 0
                val currentGeneration = (prefs().all[KEY_COMMAND_GENERATION] as? Number)?.toLong() ?: 0L
                if (generation <= 0L || generation != currentGeneration || pid <= 0) {
                    Bundle().apply { putBoolean(RESULT_SUCCESS, false) }
                } else {
                    val previousEpoch = (prefs().all[KEY_CONTROLLER_EPOCH] as? Number)?.toLong() ?: 0L
                    val epoch = maxOf(previousEpoch + 1L, System.currentTimeMillis())
                    insert(URI, ContentValues().apply {
                        put(KEY_STATE_GENERATION, generation)
                        put(KEY_COMMAND_CONSUMED_GENERATION, generation)
                        put(KEY_COMMAND_HANDLED_GENERATION, generation)
                        put(KEY_COMMAND_ACTION, "STOP")
                        put(KEY_COMMAND_STATUS, "SUCCESS")
                        put(KEY_COMMAND_DETAIL, "Stock RF restored by NFC process restart fallback")
                        put(KEY_COMMAND_PID, pid)
                        put(KEY_OPERATION_STATE, "IDLE")
                        put(KEY_EFFECTIVE_STATE, "STOCK")
                        put(KEY_VERIFICATION_CONFIDENCE, "VERIFIED")
                        put(KEY_RF_ACCEPTED, true)
                        put(KEY_RF_NATIVE_RESULT, "process-restart")
                        put(KEY_RF_NATIVE_RESULT_TYPE, "lifecycle")
                        put(KEY_RUNTIME_PID, pid)
                        put(KEY_RF_STATUS, "RF_STOCK_RESTORED_BY_RESTART")
                        put(KEY_RF_UID, "")
                        put(KEY_RF_SOURCE, "process-restart")
                        put(KEY_RF_RESULT, "0")
                        put(KEY_RF_ERROR, "")
                        put(KEY_RF_PID, pid)
                        put(KEY_RF_GENERATION, generation)
                        put(KEY_CONTROLLER_EPOCH, epoch)
                        put(KEY_RF_CONTROLLER_EPOCH, epoch)
                        put(KEY_RF_VERIFICATION, "PROCESS_RESTART")
                        put(KEY_FULL_DIAG_STAGE, "RF_STOCK_RESTORED_BY_RESTART")
                        put(KEY_FULL_DIAG_SUMMARY, "Simulation disabled before NFC restart; stock RF reloaded by lifecycle reset")
                    })
                    Bundle().apply { putBoolean(RESULT_SUCCESS, true) }
                }
            }
            else -> super.call(method, arg, extras) ?: Bundle()
        }
    }

'''
    marker = "    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {\n"
    if marker not in provider:
        raise SystemExit("provider query marker missing")
    provider = provider.replace(marker, call_method + marker, 1)

    provider = one(provider,
        "    private fun prefs(): SharedPreferences =\n"
        "        context!!.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, 0)\n",
        "    private fun prefs(): SharedPreferences = devicePrefs\n",
        "use cached prefs")
    PROVIDER.write_text(provider)

    main = MAIN.read_text()
    main = one(main,
        "    private lateinit var runtimeRepository: RuntimeStatusRepository\n",
        "    private lateinit var runtimeRepository: RuntimeStatusRepository\n"
        "    private lateinit var configClient: ConfigClient\n",
        "add config client field")
    main = one(main,
        "        runtimeRepository = RuntimeStatusRepository(this, nfcSystemService)\n"
        "        savedCardsState = cardRepository.load()\n",
        "        runtimeRepository = RuntimeStatusRepository(this, nfcSystemService)\n"
        "        configClient = ConfigClient(contentResolver)\n"
        "        savedCardsState = cardRepository.load()\n",
        "init config client")

    main = one(main,
        "                                    contentResolver.insert(ConfigProvider.URI, ContentValues().apply {\n"
        "                                        put(ConfigProvider.KEY_DIAGNOSTIC_LOGGING_ENABLED, enabled)\n"
        "                                    })\n",
        "                                    configClient.setDiagnosticLogging(enabled)\n",
        "typed diagnostic toggle")

    # Replace restart fallback confirmation block.
    start = "                val providerBeforeStockConfirm = readProviderMap()\n"
    end = "                state = readRuntimeStatus(includeRootPid = true)\n"
    a = main.find(start)
    b = main.find(end, a)
    if a < 0 or b < 0:
        raise SystemExit("stock confirmation block missing")
    main = main[:a] + "                configClient.confirmStockRestart(generation, currentPid)\n" + main[b:]

    # Replace command publication implementation.
    main = between(main,
        "    private fun publishCommand(enabled: Boolean, card: CardModel?): Long {",
        "    private fun restartNfcProcessKeepingEnabled(reason: String): String =",
        "    private fun publishCommand(enabled: Boolean, card: CardModel?): Long =\n"
        "        configClient.publishCommand(enabled, card)\n\n",
        "replace publishCommand")
    MAIN.write_text(main)

    gradle = GRADLE.read_text()
    gradle = one(gradle,
        '        versionCode = 53\n        versionName = "1.0.52"\n',
        '        versionCode = 54\n        versionName = "1.0.53"\n',
        "bump app version")
    GRADLE.write_text(gradle)

if __name__ == "__main__":
    main()
