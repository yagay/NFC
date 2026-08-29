package com.example.nfcdoorcard

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class ConfigProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.example.nfcdoorcard.config"
        const val PATH_SETTINGS = "settings"
        const val APP_BUILD = 14

        const val KEY_APP_BUILD = "app_build"
        const val KEY_HOOK_BUILD = "hook_build"
        const val KEY_SIMULATION_ENABLED = "simulation_enabled"
        const val KEY_UID = "uid"
        const val KEY_SAK = "sak"
        const val KEY_ATQA = "atqa"

        const val KEY_SCOPE_OK = "scope_ok"
        const val KEY_SCOPE_PROCESS = "scope_process"
        const val KEY_SCOPE_PID = "scope_pid"
        const val KEY_HOOK_INSTALLED = "hook_installed"
        const val KEY_HOOK_CLASS = "hook_class"
        const val KEY_HOOK_COUNT = "hook_count"
        const val KEY_HOOK_PID = "hook_pid"
        const val KEY_HIJACK_STATUS = "hijack_status"
        const val KEY_HIJACK_RESULT = "hijack_result"
        const val KEY_HIJACK_UID = "hijack_uid"
        const val KEY_HIJACK_ERROR = "hijack_error"
        const val KEY_HIJACK_PID = "hijack_pid"

        const val KEY_RF_STATUS = "rf_status"
        const val KEY_RF_UID = "rf_uid"
        const val KEY_RF_SOURCE = "rf_source"
        const val KEY_RF_RESULT = "rf_result"
        const val KEY_RF_ERROR = "rf_error"
        const val KEY_RF_PID = "rf_pid"
        const val KEY_TRACE_STAGE = "trace_stage"
        const val KEY_TRACE_SOURCE = "trace_source"
        const val KEY_TRACE_PID = "trace_pid"

        const val KEY_TEXT_CONFIG_SEEN = "text_config_seen"
        const val KEY_TEXT_CONFIG_SOURCE = "text_config_source"
        const val KEY_TEXT_CONFIG_LENGTH = "text_config_length"
        const val KEY_CONFIG_BLOCK_COUNT = "config_block_count"
        const val KEY_NCI_FRAME_COUNT = "nci_frame_count"
        const val KEY_NFCID1_COUNT = "nfcid1_count"
        const val KEY_HCE_GET_UID = "hce_get_uid"
        const val KEY_RF_FIELD_COUNT = "rf_field_count"
        const val KEY_LAST_NATIVE_RESULT = "last_native_result"
        const val KEY_FULL_DIAG_STAGE = "full_diag_stage"
        const val KEY_FULL_DIAG_SUMMARY = "full_diag_summary"

        val URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_SETTINGS")
    }

    override fun onCreate(): Boolean {
        context!!.getSharedPreferences("nfc_config", 0).edit().putInt(KEY_APP_BUILD, APP_BUILD).apply()
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val prefs = context!!.getSharedPreferences("nfc_config", 0)
        val cursor = MatrixCursor(arrayOf("key", "value"))
        prefs.all.forEach { (key, value) -> cursor.addRow(arrayOf(key, value?.toString() ?: "")) }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        if (values == null) return uri
        val editor = context!!.getSharedPreferences("nfc_config", 0).edit()
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

        if (values.containsKey(KEY_SIMULATION_ENABLED)) scheduleNfcEnableRecovery()
        return uri
    }

    private fun scheduleNfcEnableRecovery() {
        Thread {
            runCatching {
                Thread.sleep(2500)
                val command = """
                    i=0
                    while [ ${'$'}i -lt 8 ]; do
                      state=${'$'}(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' || true)
                      echo "${'$'}state" | grep -Eqi 'mState=on|state=on|STATE_ON|mState=3' && exit 0
                      svc nfc enable 2>/dev/null || true
                      sleep 2
                      i=${'$'}((i+1))
                    done
                    svc nfc enable 2>/dev/null || true
                """.trimIndent()
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                process.inputStream.close()
                process.errorStream.close()
                process.waitFor()
            }
        }.apply {
            name = "NfcEnableRecovery"
            isDaemon = true
            start()
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (values == null) return 0
        insert(uri, values)
        return values.size()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        context!!.getSharedPreferences("nfc_config", 0).edit().clear().putInt(KEY_APP_BUILD, APP_BUILD).apply()
        return 1
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.$AUTHORITY.settings"
}
