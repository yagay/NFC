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

        const val KEY_SIMULATION_ENABLED = "simulation_enabled"
        const val KEY_UID = "uid"
        const val KEY_SAK = "sak"
        const val KEY_ATQA = "atqa"

        const val KEY_SCOPE_OK = "scope_ok"
        const val KEY_SCOPE_PROCESS = "scope_process"
        const val KEY_HOOK_INSTALLED = "hook_installed"
        const val KEY_HOOK_CLASS = "hook_class"
        const val KEY_HOOK_COUNT = "hook_count"
        const val KEY_HIJACK_STATUS = "hijack_status"
        const val KEY_HIJACK_RESULT = "hijack_result"
        const val KEY_HIJACK_UID = "hijack_uid"
        const val KEY_HIJACK_ERROR = "hijack_error"

        const val KEY_RF_STATUS = "rf_status"
        const val KEY_RF_UID = "rf_uid"
        const val KEY_RF_SOURCE = "rf_source"
        const val KEY_RF_RESULT = "rf_result"
        const val KEY_RF_ERROR = "rf_error"

        val URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_SETTINGS")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
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
        return uri
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (values == null) return 0
        insert(uri, values)
        return values.size()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        context!!.getSharedPreferences("nfc_config", 0).edit().clear().apply()
        return 1
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.$AUTHORITY.settings"
}
