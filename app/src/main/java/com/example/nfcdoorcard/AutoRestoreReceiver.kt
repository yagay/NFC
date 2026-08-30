package com.example.nfcdoorcard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Re-applies the persisted desired simulation state after a phone reboot or an NFC process restart.
 * The receiver runs in the app process, so Vendor Binder calls keep the app's caller UID.
 *
 * Retry policy intentionally stays small: VendorNfcController already retries stale Binder handles
 * and waits for authoritative RF_UID_APPLIED evidence, so multiplying retries here only causes
 * redundant NFC traffic and very long restore jobs.
 */
class AutoRestoreReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_NFC_HOOK_READY = "com.example.nfcdoorcard.action.NFC_HOOK_READY"
        private const val TAG = "NfcAutoRestore"
        private const val MAX_RESTORE_ATTEMPTS = 2
        private val restoreRunning = AtomicBoolean(false)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        if (!restoreRunning.compareAndSet(false, true)) {
            Log.i(TAG, "restore already running; merge action=${intent?.action}")
            pending.finish()
            return
        }

        Thread {
            try {
                val initial = readDesiredState(context)
                if (!initial.enabled || initial.uid.isNullOrBlank()) {
                    Log.i(TAG, "skip restore action=${intent?.action} desired=false")
                    return@Thread
                }

                val desiredUid = initial.uid
                Log.i(TAG, "restore requested action=${intent?.action} uid=$desiredUid")
                var last: VendorNfcController.Result? = null

                repeat(MAX_RESTORE_ATTEMPTS) { attempt ->
                    val current = readDesiredState(context)
                    if (!current.enabled || !current.uid.equals(desiredUid, true)) {
                        Log.i(TAG, "restore cancelled: desired state changed")
                        return@Thread
                    }

                    if (attempt > 0) Thread.sleep(600L)
                    last = VendorNfcController().setShareMode(true)
                    if (last?.success == true) {
                        Log.i(TAG, "restore trigger accepted attempt=${attempt + 1} stage=${last?.stage} uid=$desiredUid")
                        if (waitForRfApplied(context, desiredUid, 4_000L)) {
                            Log.i(TAG, "restore confirmed RF_UID_APPLIED uid=$desiredUid attempt=${attempt + 1}")
                            return@Thread
                        }
                        Log.w(TAG, "restore trigger returned success but RF UID not confirmed; retrying uid=$desiredUid")
                    } else {
                        Log.i(TAG, "restore retry attempt=${attempt + 1} stage=${last?.stage} detail=${last?.detail}")
                    }
                }
                Log.e(TAG, "restore failed uid=$desiredUid stage=${last?.stage} detail=${last?.detail}")
            } catch (t: Throwable) {
                Log.e(TAG, "restore exception ${t.javaClass.simpleName}: ${t.message}", t)
            } finally {
                restoreRunning.set(false)
                pending.finish()
            }
        }.apply {
            name = "NfcAutoRestore"
            isDaemon = true
            start()
        }
    }

    private fun waitForRfApplied(context: Context, uid: String, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val map = readProviderMap(context)
            if (!map[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean()) return false
            val applied = map[ConfigProvider.KEY_RF_STATUS] == "RF_UID_APPLIED" &&
                map[ConfigProvider.KEY_RF_UID].equals(uid, true) &&
                map[ConfigProvider.KEY_RF_RESULT] == "0"
            if (applied) return true
            Thread.sleep(150L)
        }
        return false
    }

    private fun readDesiredState(context: Context): DesiredState {
        val map = readProviderMap(context)
        return DesiredState(
            enabled = map[ConfigProvider.KEY_SIMULATION_ENABLED].toBoolean(),
            uid = map[ConfigProvider.KEY_UID]?.takeIf { it.isNotBlank() }
        )
    }

    private fun readProviderMap(context: Context): Map<String, String> {
        val map = mutableMapOf<String, String>()
        context.contentResolver.query(ConfigProvider.URI, null, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) map[cursor.getString(0)] = cursor.getString(1)
        }
        return map
    }

    private data class DesiredState(val enabled: Boolean, val uid: String?)
}
