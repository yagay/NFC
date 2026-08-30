package com.example.nfcdoorcard

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
        const val AUTHORITY = "com.example.nfcdoorcard.config"
        const val PATH_SETTINGS = "settings"
        private const val PREFS_NAME = "nfc_config"
        const val STATE_SCHEMA_VERSION = 2
        const val PROFILE_SCHEMA_VERSION = 2
        val APP_BUILD: Int = BuildConfig.VERSION_CODE

        const val KEY_STATE_SCHEMA = "state_schema"
        const val KEY_APP_BUILD = "app_build"
        const val KEY_HOOK_BUILD = "hook_build"
        const val KEY_SIMULATION_ENABLED = "simulation_enabled"
        const val KEY_DIAGNOSTIC_LOGGING_ENABLED = "diagnostic_logging_enabled"
        const val KEY_UID = "uid"
        const val KEY_SAK = "sak"
        const val KEY_ATQA = "atqa"

        const val KEY_COMMAND_GENERATION = "command_generation"
        const val KEY_COMMAND_HANDLED_GENERATION = "command_handled_generation"
        const val KEY_COMMAND_ACTION = "command_action"
        const val KEY_COMMAND_STATUS = "command_status"
        const val KEY_COMMAND_DETAIL = "command_detail"
        const val KEY_COMMAND_PID = "command_pid"

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

        const val KEY_RF_STATUS = "rf_status"
        const val KEY_RF_UID = "rf_uid"
        const val KEY_RF_SOURCE = "rf_source"
        const val KEY_RF_RESULT = "rf_result"
        const val KEY_RF_ERROR = "rf_error"
        const val KEY_RF_PID = "rf_pid"
        const val KEY_RF_GENERATION = "rf_generation"
        const val KEY_RF_VERIFICATION = "rf_verification"

        const val KEY_FULL_DIAG_STAGE = "full_diag_stage"
        const val KEY_FULL_DIAG_SUMMARY = "full_diag_summary"

        val URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_SETTINGS")

        private val LEGACY_PREFIXES = listOf(
            "adapter_", "heytap_", "hijack_", "trace_", "config_block_", "nci_frame_",
            "nfcid1_", "rf_refresh_", "vendor_observed_"
        )
        private val LEGACY_KEYS = setOf(
            "last_native_result", "hce_get_uid", "rf_field_count", "text_config_length",
            "text_config_seen", "text_config_source"
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
            if (key in LEGACY_KEYS || LEGACY_PREFIXES.any { prefix -> key.startsWith(prefix) }) {
                editor.remove(key)
            }
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
            .putInt(KEY_STATE_SCHEMA, STATE_SCHEMA_VERSION)
            .apply()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        if (!isTrustedCaller()) {
            Log.w("NfcConfigProvider", "Rejected config read from uid=${Binder.getCallingUid()}")
            return MatrixCursor(arrayOf("key", "value"))
        }
        val cursor = MatrixCursor(arrayOf("key", "value"))
        prefs().all.forEach { (key, value) -> cursor.addRow(arrayOf(key, value?.toString() ?: "")) }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        if (values == null) return uri
        if (!isTrustedCaller()) {
            Log.w("NfcConfigProvider", "Rejected config write from uid=${Binder.getCallingUid()}")
            return uri
        }
        val editor = prefs().edit()
        values.keySet().forEach { key ->
            when (val value = values.get(key)) {
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

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        if (values == null || !isTrustedCaller()) return 0
        insert(uri, values)
        return values.size()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (!isTrustedCaller()) return 0
        prefs().edit().clear()
            .putInt(KEY_STATE_SCHEMA, STATE_SCHEMA_VERSION)
            .putInt(KEY_APP_BUILD, APP_BUILD)
            .apply()
        context?.contentResolver?.notifyChange(uri, null)
        return 1
    }

    private fun prefs(): SharedPreferences = context!!
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, 0)

    private fun isTrustedCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid()) return true
        val packages = runCatching { context?.packageManager?.getPackagesForUid(callingUid) }.getOrNull()
        return packages?.any { it == "com.android.nfc" } == true
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.$AUTHORITY.settings"
}
