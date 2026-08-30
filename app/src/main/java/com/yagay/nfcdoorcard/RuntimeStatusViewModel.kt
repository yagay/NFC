package com.yagay.nfcdoorcard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yagay.nfcdoorcard.system.NfcSystemService
import com.yagay.nfcdoorcard.system.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RuntimeStatusViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RuntimeStatusRepository(
        application,
        NfcSystemService(RootShell(application))
    )
    private val _status = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeChanges().collectLatest {
                _status.value = withContext(Dispatchers.IO) { repository.read(includeRootPid = false) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                _status.value = withContext(Dispatchers.IO) { repository.read(includeRootPid = true) }
                delay(20_000)
            }
        }
    }

    fun update(value: RuntimeStatus) {
        _status.value = value
    }
}
