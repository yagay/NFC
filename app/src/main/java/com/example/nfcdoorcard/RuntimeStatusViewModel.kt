package com.example.nfcdoorcard

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Activity-independent holder for runtime status and ConfigProvider change notifications.
 *
 * NFC/root operations remain owned by MainActivity for now; this ViewModel removes the
 * ContentObserver and status lifetime from the Composable so recomposition/activity recreation
 * does not recreate the provider subscription or lose the last semantic state.
 */
class RuntimeStatusViewModel(application: Application) : AndroidViewModel(application) {
    private val resolver = application.contentResolver
    private val _status = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    private val _providerRevision = MutableStateFlow(0L)
    val providerRevision: StateFlow<Long> = _providerRevision.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = signalProviderChanged()
        override fun onChange(selfChange: Boolean, uri: Uri?) = signalProviderChanged()
    }

    init {
        resolver.registerContentObserver(ConfigProvider.URI, true, observer)
        signalProviderChanged()
    }

    fun update(value: RuntimeStatus) {
        _status.value = value
    }

    private fun signalProviderChanged() {
        _providerRevision.value = _providerRevision.value + 1L
    }

    override fun onCleared() {
        runCatching { resolver.unregisterContentObserver(observer) }
        super.onCleared()
    }
}
