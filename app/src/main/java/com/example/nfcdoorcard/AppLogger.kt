package com.example.nfcdoorcard

import java.text.SimpleDateFormat
import java.util.*

object AppLogger {
    private val logs = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun i(msg: String) {
        val time = dateFormat.format(Date())
        val line = "[$time] APP: $msg"
        logs.add(line)
        if (logs.size > 1000) logs.removeAt(0)
    }

    fun getAllLogs(): String = logs.joinToString("\n")

    fun clear() {
        logs.clear()
    }
}
