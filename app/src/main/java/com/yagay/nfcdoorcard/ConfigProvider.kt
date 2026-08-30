package com.yagay.nfcdoorcard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.util.Log

class ConfigProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.yagay.nfcdoorcard.config"
        const val PATH_SETTINGS = "settings"
        private const val PREFS_NAME = "nfc_config"
        const val STATE_SCHEMA_VERSION = 7
        const val PROFILE_SCHEMA_VERSION = 3
        val APP_BUILD: Int = BuildConfig.VERSION_CODE

        const val KEY_STATE_SCHEMA = "state_schema"
        const val KEY_STATE_GENERATION = "state_generation"
        const val KEY_APP_BUILD = "app_build"
        const val KEY_HOOK_BUILD = "hook_build"
        const val KEY_SIMULATION_ENABLED = "simulation_enabled"
        const val KEY_DIAGNOSTIC_LOGGING_ENABLED = "diagnostic_logging_enabled"
        const val KEY_UID = "uid"
        const val KEY_SAK = "sak"
        const val KEY_ATQA = "atqa"

        const val KEY_COMMAND_GENERATION = "command_generation"
        const val KEY_COMMAND_CONSUMED_GENERATION = "command_consumed_generation"
        const val KEY_COMMAND_HANDLED_GENERATION = "command_handled_generation"
        const val KEY_COMMAND_ACTION = "command_action"
        const val KEY_COMMAND_STATUS = "command_status"
        const val KEY_COMMAND_DETAIL = "command_detail"
        const val KEY_COMMAND_PID = "command_pid"

        const val KEY_OPERATION_STATE = "operation_state"
        const val KEY_EFFECTIVE_STATE = "effective_state"
        const val KEY_VERIFICATION_CONFIDENCE = "verification_confidence"
        const val KEY_RF_ACCEPTED = "rf_accepted"
        const val KEY_RF_NATIVE_RESULT = "rf_native_result"
        const val KEY_RF_NATIVE_RESULT_TYPE = "rf_native_result_type"
        const val KEY_RUNTIME_PID = "runtime_pid"

        const val KEY_SCOPE_OK = "scope_ok"
        const val KEY_SCOPE_PROCESS = "scope_process"
        const val KEY_SCOPE_PID = "scope_pid"
        const val KEY_HOOK_INSTALLED = "hook_installed"
        const val KEY_HOOK_CLASS = "hook_class"
        const val KEY_HOOK_COUNT = "hook_count"
        const val KEY_HOOK_PID = "hook_pid"

        const val KEY_PROFILE_SCHEMA = "profile_schema"
        const val KEY_PROFILE_HOOK_BUILD = "profile_hook_build"
        const val KEY_PROFILE_STATUS = "profile_status"
        const val KEY_PROFILE_SYSTEM_FINGERPRINT = "profile_system_fingerprint"
        const val KEY_PROFILE_NFC_VERSION = "profile_nfc_version"
        const val KEY_RF_HOOK_CLASS = "rf_hook_class"
        const val KEY_RF_HOOK_METHOD = "rf_hook_method"
        const val KEY_RF_HOOK_SIGNATURE = "rf_hook_signature"
        const val KEY_RF_HOOK_SCORE = "rf_hook_score"
        const val KEY_RF_HOOK_SOURCE = "rf_hook_source"
        const val KEY_RF_HOOK_FINGERPRINT = "rf_hook_fingerprint"
        const val KEY_RF_HOOK_CANDIDATES = "rf_hook_candidates"
        const val KEY_RF_HOOK_CANDIDATE_COUNT = "rf_hook_candidate_count"
        const val KEY_RF_HOOK_DISCOVERY_PID = "rf_hook_discovery_pid"
        const val KEY_REFRESH_PROBE_CANDIDATES = "refresh_probe_candidates"
        const val KEY_REFRESH_PROBE_COUNT = "refresh_probe_count"
        const val KEY_REFRESH_PROBE_PID = "refresh_probe_pid"
        const val KEY_REFRESH_TRIGGER_STATUS = "refresh_trigger_status"
        const val KEY_REFRESH_TRIGGER_TARGET = "refresh_trigger_target"
        const val KEY_REFRESH_TRIGGER_SOURCE = "refresh_trigger_source"
        const val KEY_REFRESH_TRIGGER_GENERATION = "refresh_trigger_generation"
        const val KEY_REFRESH_TRIGGER_RF_CONFIRMED = "refresh_trigger_rf_confirmed"

        const val KEY_RF_STATUS = "rf_status"
        const val KEY_RF_UID = "rf_uid"
        const val KEY_RF_SOURCE = "rf_source"
        const val KEY_RF_RESULT = "rf_result"
        const val KEY_RF_ERROR = "rf_error"
        const val KEY_RF_PID = "rf_pid"
        const val KEY_RF_GENERATION = "rf_generation"
        const val KEY_RF_VERIFICATION = "rf_verification"
        const val KEY_CONTROLLER_EPOCH = "controller_epoch"
        const val KEY_RF_CONTROLLER_EPOCH = "rf_controller_epoch"

        const val KEY_FULL_DIAG_STAGE = "full_diag_stage"
        const val KEY_FULL_DIAG_SUMMARY = "full_diag_summary"

        val URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_SETTINGS")

        private val LEGACY_PREFIXES = listOf(
            "adapter_", "heytap_", "hijack_", "trace_", "config_block_", "nci_frame_",
            "nfcid1_", "rf_refresh_", "vendor_"
        )
        private val LEGACY_KEYS = setOf(
            "last_native_result", "hce_get_uid", "rf_field_count", "text_config_length",
            "text_config_seen", "text_config_source"
        )
        private val RUNTIME_KEYS = setOf(
            KEY_STATE_GENERATION,
            KEY_COMMAND_GENERATION, KEY_COMMAND_CONSUMED_GENERATION, KEY_COMMAND_HANDLED_GENERATION, KEY_COMMAND_ACTION,
            KEY_COMMAND_STATUS, KEY_COMMAND_DETAIL, KEY_COMMAND_PID,
            KEY_OPERATION_STATE, KEY_EFFECTIVE_STATE, KEY_VERIFICATION_CONFIDENCE,
            KEY_RF_ACCEPTED, KEY_RF_NATIVE_RESULT, KEY_RF_NATIVE_RESULT_TYPE, KEY_RUNTIME_PID,
            KEY_RF_STATUS, KEY_RF_UID, KEY_RF_SOURCE, KEY_RF_RESULT, KEY_RF_ERROR,
            KEY_RF_PID, KEY_RF_GENERATION, KEY_RF_VERIFICATION, KEY_CONTROLLER_EPOCH, KEY_RF_CONTROLLER_EPOCH,
            KEY_REFRESH_TRIGGER_STATUS, KEY_REFRESH_TRIGGER_TARGET, KEY_REFRESH_TRIGGER_SOURCE,
            KEY_REFRESH_TRIGGER_GENERATION, KEY_REFRESH_TRIGGER_RF_CONFIRMED,
            KEY_FULL_DIAG_STAGE, KEY_FULL_DIAG_SUMMARY
        )
        private val TERMINAL_COMMAND_STATUSES = setOf("SUCCESS", "FAILED")
        private val TERMINAL_OWNED_KEYS = setOf(
            KEY_COMMAND_CONSUMED_GENERATION, KEY_COMMAND_HANDLED_GENERATION, KEY_COMMAND_ACTION,
            KEY_COMMAND_STATUS, KEY_COMMAND_DETAIL, KEY_COMMAND_PID,
            KEY_OPERATION_STATE, KEY_EFFECTIVE_STATE, KEY_VERIFICATION_CONFIDENCE,
            KEY_RF_ACCEPTED, KEY_RF_NATIVE_RESULT, KEY_RF_NATIVE_RESULT_TYPE,
            KEY_RF_STATUS, KEY_RF_UID, KEY_RF_SOURCE, KEY_RF_RESULT, KEY_RF_ERROR,
            KEY_RF_PID, KEY_RF_GENERATION, KEY_RF_VERIFICATION, KEY_RF_CONTROLLER_EPOCH,
            KEY_FULL_DIAG_STAGE, KEY_FULL_DIAG_SUMMARY
        )
    }

    override fun onCreate(): Boolean {
        val base = context!!
        val deviceContext = base.createDeviceProtectedStorageContext()
        runCatching { deviceContext.moveSharedPreferencesFrom(base, PREFS_NAME) }
        val prefs = deviceContext.getSharedPreferences(PREFS_NAME, 0)
        migrateStateIfNeeded(prefs)
        prefs.edit()
            .putInt(KEY_STATE_SCHEMA, STATE_SCHEMA_VERSION)
            .putInt(KEY_APP_BUILD, APP_BUILD)
            .apply()
        return true
    }

    private fun migrateStateIfNeeded(prefs: SharedPreferences) {
        val current = prefs.getInt(KEY_STATE_SCHEMA, 0)
        if (current >= STATE_SCHEMA_VERSION) return
        val editor = prefs.edit()
        prefs.all.keys.forEach { key ->
            if (key in LEGACY_KEYS || key in RUNTIME_KEYS || LEGACY_PREFIXES.any { prefix -> key.startsWith(prefix) }) editor.remove(key)
        }
        editor
            .remove(KEY_PROFILE_STATUS)
            .remove(KEY_PROFILE_SYSTEM_FINGERPRINT)
            .remove(KEY_PROFILE_NFC_VERSION)
            .remove(KEY_RF_HOOK_CLASS)
            .remove(KEY_RF_HOOK_METHOD)
            .remove(KEY_RF_HOOK_SIGNATURE)
            .remove(KEY_RF_HOOK_SCORE)
            .remove(KEY_RF_HOOK_SOURCE)
            .remove(KEY_RF_HOOK_FINGERPRINT)
            .remove(KEY_RF_HOOK_CANDIDATES)
            .remove(KEY_RF_HOOK_CANDIDATE_COUNT)
            .remove(KEY_RF_HOOK_DISCOVERY_PID)
            .remove(KEY_REFRESH_PROBE_CANDIDATES)
            .remove(KEY_REFRESH_PROBE_COUNT)
            .remove(KEY_REFRESH_PROBE_PID)
            .remove("refresh_probe_events")
            .remove("refresh_probe_last")
            .remove(KEY_REFRESH_TRIGGER_STATUS)
            .remove(KEY_REFRESH_TRIGGER_TARGET)
            .remove(KEY_REFRESH_TRIGGER_SOURCE)
            .remove(KEY_REFRESH_TRIGGER_GENERATION)
            .remove(KEY_REFRESH_TRIGGER_RF_CONFIRMED)
            .putInt(KEY_STATE_SCHEMA, STATE_SCHEMA_VERSION)
            .apply()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (!isTrustedCaller()) {
            Log.w("NfcConfigProvider", "Rejected config read from uid=${Binder.getCallingUid()}")
            return MatrixCursor(arrayOf("key", "value"))
        }
        val cursor = MatrixCursor(arrayOf("key", "value"))
        prefs().all.forEach { (key, value) -> cursor.addRow(arrayOf(key, value?.toString() ?: "")) }
        return cursor
    }

    @Synchronized
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        if (values == null) return uri
        if (!isTrustedCaller()) {
            Log.w("NfcConfigProvider", "Rejected config write from uid=${Binder.getCallingUid()}")
            return uri
        }

        val incoming = ContentValues(values)
        val prefs = prefs()
        val currentGeneration = (prefs.all[KEY_COMMAND_GENERATION] as? Number)?.toLong() ?: 0L
        val currentHandledGeneration = (prefs.all[KEY_COMMAND_HANDLED_GENERATION] as? Number)?.toLong() ?: Long.MIN_VALUE
        val currentCommandStatus = prefs.getString(KEY_COMMAND_STATUS, "IDLE") ?: "IDLE"
        val currentCommandAction = prefs.getString(KEY_COMMAND_ACTION, "") ?: ""
        val currentEffective = prefs.getString(KEY_EFFECTIVE_STATE, "UNKNOWN") ?: "UNKNOWN"
        val currentConfidence = prefs.getString(KEY_VERIFICATION_CONFIDENCE, "NONE") ?: "NONE"
        val currentRfAccepted = prefs.getBoolean(KEY_RF_ACCEPTED, false)
        val currentRfPid = (prefs.all[KEY_RF_PID] as? Number)?.toInt() ?: 0
        val simulationEnabled = prefs.getBoolean(KEY_SIMULATION_ENABLED, false)
        val stateGeneration = incoming.getAsLong(KEY_STATE_GENERATION)
        val declaredCommandGeneration = incoming.getAsLong(KEY_COMMAND_GENERATION)

        if (stateGeneration != null && stateGeneration > 0L) {
            if (currentGeneration > 0L && stateGeneration < currentGeneration) {
                Log.w("NfcConfigProvider", "Dropped stale runtime write generation=$stateGeneration current=$currentGeneration uid=${Binder.getCallingUid()}")
                return uri
            }
            if (stateGeneration > currentGeneration && declaredCommandGeneration != stateGeneration) {
                Log.w("NfcConfigProvider", "Dropped future runtime write generation=$stateGeneration current=$currentGeneration without command publication")
                return uri
            }
        }

        val terminalCurrentGeneration = currentGeneration > 0L &&
            currentHandledGeneration == currentGeneration && currentCommandStatus in TERMINAL_COMMAND_STATUSES
        val advancesCommand = declaredCommandGeneration != null && declaredCommandGeneration > currentGeneration
        val expectedEffective = if (simulationEnabled) "ACTIVE" else "STOCK"
        val expectedAction = if (simulationEnabled) "APPLY" else "STOP"
        val incomingEffective = incoming.getAsString(KEY_EFFECTIVE_STATE)
        val incomingOperation = incoming.getAsString(KEY_OPERATION_STATE)
        val incomingConfidence = incoming.getAsString(KEY_VERIFICATION_CONFIDENCE)
        val incomingAccepted = incoming.getAsBoolean(KEY_RF_ACCEPTED)
        val incomingRfGeneration = incoming.getAsLong(KEY_RF_GENERATION)
        val incomingRfPid = incoming.getAsInteger(KEY_RF_PID) ?: 0
        val incomingRuntimePid = incoming.getAsInteger(KEY_RUNTIME_PID) ?: 0
        val incomingCommandStatus = incoming.getAsString(KEY_COMMAND_STATUS)
        val terminalReaffirmation = terminalCurrentGeneration && currentCommandStatus == "SUCCESS" &&
            stateGeneration == currentGeneration && incomingRfGeneration == currentGeneration &&
            incomingOperation == "IDLE" && incomingEffective == expectedEffective &&
            incomingConfidence == "VERIFIED" && incomingAccepted == true && incomingRfPid > 0 &&
            (incomingRuntimePid == 0 || incomingRuntimePid == incomingRfPid) &&
            (incomingCommandStatus == null || incomingCommandStatus == "SUCCESS") &&
            currentCommandAction == expectedAction && currentEffective == expectedEffective &&
            currentConfidence == "VERIFIED" && currentRfAccepted

        if (terminalCurrentGeneration && !advancesCommand && !terminalReaffirmation) {
            val removed = mutableListOf<String>()
            TERMINAL_OWNED_KEYS.forEach { key ->
                if (incoming.containsKey(key)) {
                    incoming.remove(key)
                    removed += key
                }
            }
            if (removed.isNotEmpty()) {
                Log.i(
                    "NfcConfigProvider",
                    "Protected terminal generation=$currentGeneration status=$currentCommandStatus; stripped=${removed.joinToString(",")} uid=${Binder.getCallingUid()}"
                )
            }
        } else if (terminalReaffirmation) {
            listOf(
                KEY_COMMAND_CONSUMED_GENERATION, KEY_COMMAND_HANDLED_GENERATION, KEY_COMMAND_ACTION,
                KEY_COMMAND_STATUS, KEY_COMMAND_DETAIL, KEY_COMMAND_PID
            ).forEach { incoming.remove(it) }
            Log.i(
                "NfcConfigProvider",
                "Accepted terminal lifecycle reaffirmation generation=$currentGeneration effective=$expectedEffective rfPid=$incomingRfPid uid=${Binder.getCallingUid()}"
            )
        }

        val announcedRuntimePid = incoming.getAsInteger(KEY_RUNTIME_PID) ?: 0
        val announcedHookPid = incoming.getAsInteger(KEY_HOOK_PID) ?: 0
        val announcedScopePid = incoming.getAsInteger(KEY_SCOPE_PID) ?: 0
        val announcedHookReady = incoming.getAsBoolean(KEY_HOOK_INSTALLED) == true
        val announcedScopeOk = incoming.getAsBoolean(KEY_SCOPE_OK) == true
        val stockLifecycleAdoption = isNfcCaller() && terminalCurrentGeneration &&
            currentCommandStatus == "SUCCESS" && currentCommandAction == "STOP" && !simulationEnabled &&
            currentEffective == "STOCK" && currentConfidence == "VERIFIED" && currentRfAccepted &&
            currentGeneration > 0L && currentRfPid > 0 && announcedRuntimePid > 0 &&
            announcedRuntimePid != currentRfPid && announcedHookPid == announcedRuntimePid &&
            announcedScopePid == announcedRuntimePid && announcedHookReady && announcedScopeOk
        if (stockLifecycleAdoption) {
            incoming.put(KEY_STATE_GENERATION, currentGeneration)
            incoming.put(KEY_OPERATION_STATE, "IDLE")
            incoming.put(KEY_EFFECTIVE_STATE, "STOCK")
            incoming.put(KEY_VERIFICATION_CONFIDENCE, "VERIFIED")
            incoming.put(KEY_RF_ACCEPTED, true)
            incoming.put(KEY_RF_STATUS, "RF_STOCK_CONFIRMED_AFTER_PROCESS_START")
            incoming.put(KEY_RF_UID, "")
            incoming.put(KEY_RF_SOURCE, "process-start")
            incoming.put(KEY_RF_RESULT, "0")
            incoming.put(KEY_RF_NATIVE_RESULT, "process-start")
            incoming.put(KEY_RF_NATIVE_RESULT_TYPE, "lifecycle")
            incoming.put(KEY_RF_ERROR, "")
            incoming.put(KEY_RF_PID, announcedRuntimePid)
            incoming.put(KEY_RF_GENERATION, currentGeneration)
            incoming.put(KEY_RF_VERIFICATION, "PROCESS_START")
            incoming.put(KEY_FULL_DIAG_STAGE, "RF_STOCK_CONFIRMED_AFTER_PROCESS_START")
            incoming.put(KEY_FULL_DIAG_SUMMARY, "Terminal STOP adopted by new NFC process; stock RF confirmed by process lifecycle")
            Log.i(
                "NfcConfigProvider",
                "Adopted terminal STOCK generation=$currentGeneration oldRfPid=$currentRfPid newRfPid=$announcedRuntimePid"
            )
        }

        val editor = prefs.edit()
        incoming.keySet().forEach { key ->
            when (val value = incoming.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                null -> editor.remove(key)
                else -> editor.putString(key, value.toString())
            }
        }
        editor.apply()
        context?.contentResolver?.notifyChange(uri, null)
        return uri
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (values == null || !isTrustedCaller()) return 0
        insert(uri, values)
        return values.size()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (!isTrustedCaller()) return 0
        prefs().edit().clear().putInt(KEY_STATE_SCHEMA, STATE_SCHEMA_VERSION).putInt(KEY_APP_BUILD, APP_BUILD).apply()
        context?.contentResolver?.notifyChange(uri, null)
        return 1
    }

    private fun prefs(): SharedPreferences = context!!.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, 0)

    private fun isNfcCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        val packages = runCatching { context?.packageManager?.getPackagesForUid(callingUid) }.getOrNull()
        return packages?.any { it == "com.android.nfc" } == true
    }

    private fun isTrustedCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid()) return true
        return isNfcCaller()
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.$AUTHORITY.settings"
}
