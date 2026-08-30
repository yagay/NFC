package com.example.nfcdoorcard.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import com.example.nfcdoorcard.CardModel

class NfcForegroundDispatcher(private val activity: Activity) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val pendingIntent: PendingIntent = PendingIntent.getActivity(
        activity,
        0,
        Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun enable(): Result<Unit> = runCatching {
        adapter?.enableForegroundDispatch(activity, pendingIntent, null, null)
    }

    fun disable() {
        runCatching { adapter?.disableForegroundDispatch(activity) }
    }

    @Suppress("DEPRECATION")
    fun parse(intent: Intent?): CardModel? {
        val tag: Tag = intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return null
        val uid = tag.id.joinToString("") { "%02X".format(it) }.uppercase()
        var sak = "08"
        var atqa = "0400"
        runCatching {
            NfcA.get(tag)?.let {
                sak = "%02X".format(it.sak.toInt() and 0xFF)
                atqa = it.atqa.reversedArray().joinToString("") { b -> "%02X".format(b) }.uppercase()
            }
        }
        return CardModel("Card ${uid.takeLast(4)}", uid, sak, atqa)
    }
}
