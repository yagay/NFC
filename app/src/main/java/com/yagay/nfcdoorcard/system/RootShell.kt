package com.yagay.nfcdoorcard.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.TimeUnit

class RootShell(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var rootAvailableCache: Boolean? = null
    @Volatile private var lastRootToastAt: Long = 0L

    fun run(command: String, timeoutSeconds: Long = 20, maxChars: Int = 1_000_000, showToast: Boolean = true): String {
        if (!ensureRootAccess(showToast)) return "ROOT_UNAVAILABLE"
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = Thread({
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (output.length < maxChars) {
                                val remaining = maxChars - output.length
                                val piece = if (line.length + 1 <= remaining) line + "\n" else line.take(remaining)
                                output.append(piece)
                            }
                        }
                    }
                }
            }, "NfcDoorCard-RootReader").apply { isDaemon = true; start() }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            reader.join(1500)
            if (!finished) output.appendLine("[timeout=${timeoutSeconds}s]")
            else output.appendLine("[exit=${process.exitValue()}]")
            output.toString()
        } catch (t: Throwable) {
            rootAvailableCache = null
            if (showToast) notifyRootUnavailable()
            "ERROR ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun ensureRootAccess(showToast: Boolean): Boolean {
        rootAvailableCache?.let { if (it) return true }
        val ok = try {
            val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            val finished = process.waitFor(4, TimeUnit.SECONDS)
            val output = if (finished) process.inputStream.bufferedReader().readText().trim() else ""
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0 && output.lineSequence().any { it.trim() == "0" }
        } catch (_: Throwable) { false }
        rootAvailableCache = if (ok) true else null
        if (!ok && showToast) notifyRootUnavailable()
        return ok
    }

    private fun notifyRootUnavailable() {
        val now = System.currentTimeMillis()
        if (now - lastRootToastAt < 3000L) return
        lastRootToastAt = now
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "Root 获取失败，请在 Root 管理器中授予本应用权限", Toast.LENGTH_LONG).show()
        }
    }
}
