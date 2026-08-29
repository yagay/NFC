package com.example.nfcdoorcard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-applies the persisted desired simulation state after a phone reboot or an NFC process restart.
 * The receiver runs in the app process, so Vendor Binder calls keep the app's caller UID.
 */
class AutoRestoreReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_NFC_HOOK_READY = "com.example.nfcdoorcard.action.NFC_HOOK_READY"
        private const val TAG = "NfcAutoRestore"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        Thread {
            try {
                val state = readDesiredState(context)
                if (!state.enabled || state.uid.isNullOrBlank()) {
                    Log.i(TAG, "skip restore action=${intent?.action} desired=false")
                    return@Thread
                }

                Log.i(TAG, "restore requested action=${intent?.action} uid=${state.uid}")
                var last: VendorNfcController.Result? = null
                repeat(15) { attempt ->
                    // Give the NFC framework/vendor service time to finish initialization.
                    if (attempt > 0) Thread.sleep(1000L)
                    last = VendorNfcController().setShareMode(true)
                    if (last?.success == true) {
                        Log.i(TAG, "restore accepted attempt=${attempt + 1} uid=${state.uid}")
                        return@Thread
                    }
                    Log.i(TAG, "restore retry attempt=${attempt + 1} stage=${last?.stage} detail=${last?.detail}")
                }
                Log.e(TAG, "restore failed uid=${state.uid} stage=${last?.stage} detail=${last?.detail}")
            } catch (t: Throwable) {
                Log.e(TAG, "restore exception ${t.javaClass.simpleName}: ${t.message}", t)
            } finally {
                pending.finish()
            }
        }.apply {
            name = "NfcAutoRestore"
            isDaemon = true
            start()
        }
    }

    private fun readDesiredState(context: Context): DesiredState {
        var enabled = false
        var uid: String? = null
        context.contentResolver.query(ConfigProvider.URI, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                when (cursor.getString(0)) {
                    ConfigProvider.KEY_SIMULATION_ENABLED -> enabled = cursor.getString(1).toBoolean()
                    ConfigProvider.KEY_UID -> uid = cursor.getString(1)?.takeIf { it.isNotBlank() }
                }
            }
        }
        return DesiredState(enabled, uid)
    }

    private data class DesiredState(val enabled: Boolean, val uid: String?)
}
