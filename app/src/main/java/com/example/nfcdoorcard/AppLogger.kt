package com.example.nfcdoorcard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    @Synchronized
    fun i(message: String) {
        val line = "[${format.format(Date())}] APP: $message"
        lines.addLast(line)
        while (lines.size > 1000) lines.removeFirst()
        android.util.Log.i("NfcDoorCard", message)
    }

    @Synchronized
    fun readAll(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
    }
}
