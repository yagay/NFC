package com.yagay.nfcdoorcard.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import com.yagay.nfcdoorcard.CardModel

/** Purpose-built NFC-A reader mode for the app's explicit one-card scan flow. */
class NfcReaderController(private val activity: Activity) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    @Volatile private var readerEnabled = false

    @Synchronized
    fun enable(onCard: (CardModel) -> Unit): Result<Unit> = runCatching {
        if (readerEnabled) return@runCatching
        val nfc = adapter ?: error("NFC adapter unavailable")
        nfc.enableReaderMode(
            activity,
            { tag ->
                parse(tag)?.let { card -> activity.runOnUiThread { onCard(card) } }
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
        readerEnabled = true
    }

    @Synchronized
    fun disable() {
        if (!readerEnabled) return
        runCatching { adapter?.disableReaderMode(activity) }
        readerEnabled = false
    }

    private fun parse(tag: Tag): CardModel? {
        val uid = tag.id?.joinToString("") { "%02X".format(it) }?.uppercase().orEmpty()
        if (uid.isBlank()) return null
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
