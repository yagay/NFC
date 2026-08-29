package com.example.nfcdoorcard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class ConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.example.nfcdoorcard.config"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/settings")

        const val KEY_SIMULATION_ENABLED = "simulation_enabled"
        const val KEY_UID = "uid"
        const val KEY_SAK = "sak"
        const val KEY_ATQA = "atqa"

        // Runtime diagnostics. These are event/status values, not a heartbeat.
        const val KEY_SCOPE_OK = "scope_ok"
        const val KEY_SCOPE_PROCESS = "scope_process"
        const val KEY_HOOK_INSTALLED = "hook_installed"
        const val KEY_HOOK_CLASS = "hook_class"
        const val KEY_HOOK_COUNT = "hook_count"
        const val KEY_HIJACK_STATUS = "hijack_status"
        const val KEY_HIJACK_RESULT = "hijack_result"
        const val KEY_HIJACK_UID = "hijack_uid"
        const val KEY_HIJACK_ERROR = "hijack_error"

        const val PREFS_NAME = "nfc_sim_prefs"
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate(): Boolean {
        val directBootContext = context?.createDeviceProtectedStorageContext() ?: context
        prefs = directBootContext!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("key", "value"))
        prefs.all.forEach { (key, value) -> cursor.addRow(arrayOf(key, value.toString())) }
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (values == null) return null
        val editor = prefs.edit()
        values.keySet().forEach { key ->
            when (val value = values.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
            }
        }
        editor.apply()
        context?.contentResolver?.notifyChange(uri, null)
        return uri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        prefs.edit().clear().apply()
        context?.contentResolver?.notifyChange(uri, null)
        return 1
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        insert(uri, values)
        return 1
    }
}
