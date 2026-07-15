package com.telegramproxy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProxyViewModel(app: Application) : AndroidViewModel(app) {

    private val manager = SubscriptionManager.get(app)
    private val xray = XrayCore.get(app)

    val subscriptions = manager.subscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val servers = manager.servers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedServerId = manager.selectedServerId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val connectionState = ProxyService.connectionState
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            ProxyService.ConnectionState.Disconnected
        )

    val statusMessage = xray.statusMessage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Отключено")

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun selectServer(id: String) {
        manager.selectServer(id)
    }

    fun toggleConnection() {
        val ctx = getApplication<Application>()
        val state = ProxyService.connectionState.value
        if (state is ProxyService.ConnectionState.Connected ||
            state is ProxyService.ConnectionState.Connecting
        ) {
            ProxyService.disconnect(ctx)
        } else {
            val id = manager.selectedServerId.value
            if (id == null) {
                _message.value = "Сначала выберите сервер"
                return
            }
            ProxyService.connect(ctx, id)
        }
    }

    fun addVless(name: String, url: String): Result<VlessServer> {
        if (url.isBlank()) return Result.failure(IllegalArgumentException("Введите VLESS URL"))
        return manager.addManualVless(name.ifBlank { "Сервер" }, url)
    }

    fun importSubscription(name: String, url: String, onDone: (Result<Subscription>) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = manager.importFromUrl(name, url)
            _isLoading.value = false
            onDone(result)
        }
    }

    suspend fun importSubscriptionSuspend(name: String, url: String): Result<Subscription> {
        _isLoading.value = true
        return try {
            manager.importFromUrl(name, url)
        } finally {
            _isLoading.value = false
        }
    }

    fun removeSubscription(id: String) {
        manager.removeSubscription(id)
    }

    fun removeServer(id: String) {
        val connected = ProxyService.connectionState.value
        if (connected is ProxyService.ConnectionState.Connected && connected.server.id == id) {
            ProxyService.disconnect(getApplication())
        }
        manager.removeServer(id)
    }

    fun clearMessage() {
        _message.value = null
    }
}
