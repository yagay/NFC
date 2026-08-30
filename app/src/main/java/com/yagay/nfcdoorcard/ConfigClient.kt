package com.yagay.nfcdoorcard

import android.content.ContentResolver
import android.os.Bundle

/** Typed app-side API for durable commands owned by ConfigProvider. */
class ConfigClient(private val resolver: ContentResolver) {
    fun publishCommand(enabled: Boolean, card: CardModel?): Long {
        val extras = Bundle().apply {
            putBoolean(ConfigProvider.EXTRA_ENABLED, enabled)
            card?.let {
                putString(ConfigProvider.EXTRA_UID, it.uid)
                putString(ConfigProvider.EXTRA_SAK, it.sak)
                putString(ConfigProvider.EXTRA_ATQA, it.atqa)
            }
        }
        return resolver.call(
            ConfigProvider.URI,
            ConfigProvider.METHOD_PUBLISH_COMMAND,
            null,
            extras
        )?.getLong(ConfigProvider.RESULT_GENERATION, 0L) ?: 0L
    }

    fun setDiagnosticLogging(enabled: Boolean): Boolean =
        resolver.call(
            ConfigProvider.URI,
            ConfigProvider.METHOD_SET_DIAGNOSTIC_LOGGING,
            null,
            Bundle().apply { putBoolean(ConfigProvider.EXTRA_ENABLED, enabled) }
        )?.getBoolean(ConfigProvider.RESULT_SUCCESS, false) == true

    fun confirmStockRestart(generation: Long, pid: Int): Boolean =
        resolver.call(
            ConfigProvider.URI,
            ConfigProvider.METHOD_CONFIRM_STOCK_RESTART,
            null,
            Bundle().apply {
                putLong(ConfigProvider.EXTRA_GENERATION, generation)
                putInt(ConfigProvider.EXTRA_PID, pid)
            }
        )?.getBoolean(ConfigProvider.RESULT_SUCCESS, false) == true
}
