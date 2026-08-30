package com.yagay.nfcdoorcard

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.util.concurrent.Executors

/** Owns diagnostic report generation and public Downloads persistence off the main thread. */
internal class DiagnosticExporter(
    context: Context,
    private val collector: DiagnosticsCollector
) : AutoCloseable {
    private val resolver = context.applicationContext.contentResolver
    private val executor = Executors.newSingleThreadExecutor()

    fun export(statusSummary: (RuntimeStatus) -> String, onDone: (Result<String>) -> Unit) {
        executor.execute {
            var createdUri: Uri? = null
            val result = runCatching {
                val fileName = "nfc_fullcheck_${BuildConfig.VERSION_NAME}_${System.currentTimeMillis()}.txt"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                createdUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法在 Download 创建日志文件")
                resolver.openOutputStream(createdUri!!, "w")?.bufferedWriter()?.use {
                    it.write(collector.buildFullReport(statusSummary))
                } ?: error("无法写入日志文件")
                resolver.update(
                    createdUri!!,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                AppLogger.i("Diagnostics saved to public Downloads: $fileName uri=$createdUri")
                fileName
            }.onFailure { error ->
                createdUri?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
                AppLogger.i("Diagnostics failed ${error.javaClass.simpleName}: ${error.message}")
            }
            onDone(result)
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
