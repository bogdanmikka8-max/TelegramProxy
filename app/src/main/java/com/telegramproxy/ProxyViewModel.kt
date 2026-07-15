package com.telegramproxy

import android.app.Application
import android.widget.Toast
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProxyService.ConnectionState.Disconnected)

    val statusMessage = xray.statusMessage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Отключено")

    val xrayDownloaderState = XrayDownloader.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), XrayDownloader.State.IDLE)

    val xrayProgress = XrayDownloader.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    val xrayStatusText = XrayDownloader.statusText
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val xrayError = XrayDownloader.errorMessage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val xrayLogLines = xray.logLines
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val xrayCoreState = xray.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), XrayCore.State.STOPPED)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        XrayDownloader.ensureReadyAsync(getApplication())
    }

    fun ensureXrayReady() {
        XrayDownloader.ensureReadyAsync(getApplication())
    }

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
            return
        }

        if (!XrayDownloader.isReady(ctx)) {
            Toast.makeText(ctx, "Xray загружается… Подождите", Toast.LENGTH_SHORT).show()
            ensureXrayReady()
            return
        }

        val id = manager.selectedServerId.value
        if (id == null) {
            Toast.makeText(ctx, "Выберите сервер из списка", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(ctx, "Подключение…", Toast.LENGTH_SHORT).show()
        ProxyService.connect(ctx, id)
    }

    fun addVless(name: String, url: String): Result<VlessServer> {
        if (url.isBlank()) return Result.failure(IllegalArgumentException("Введите VLESS URL"))
        return manager.addManualVless(name.ifBlank { "Сервер" }, url)
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
}
