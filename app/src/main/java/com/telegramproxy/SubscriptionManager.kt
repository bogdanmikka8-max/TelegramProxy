package com.telegramproxy

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Persists subscriptions & servers, imports subscription URLs (base64 VLESS lists).
 */
class SubscriptionManager private constructor(private val context: Context) {

    private val gson = Gson()
    private val storeFile = File(context.filesDir, "subscriptions.json")
    private val selectedFile = File(context.filesDir, "selected_server.txt")

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val _servers = MutableStateFlow<List<VlessServer>>(emptyList())
    val servers: StateFlow<List<VlessServer>> = _servers.asStateFlow()

    private val _selectedServerId = MutableStateFlow<String?>(null)
    val selectedServerId: StateFlow<String?> = _selectedServerId.asStateFlow()

    init {
        load()
    }

    fun getSelectedServer(): VlessServer? {
        val id = _selectedServerId.value ?: return null
        return _servers.value.find { it.id == id }
    }

    fun selectServer(serverId: String?) {
        _selectedServerId.value = serverId
        selectedFile.writeText(serverId.orEmpty())
    }

    fun addManualVless(name: String, vlessUrl: String): Result<VlessServer> {
        val subId = "manual"
        val server = VlessConfig.parseVlessUrl(vlessUrl, subId, name)
            ?: return Result.failure(IllegalArgumentException("Некорректный VLESS URL"))
        val named = server.copy(name = name.ifBlank { server.name })

        // Ensure manual subscription bucket exists
        val subs = _subscriptions.value.toMutableList()
        val manualIdx = subs.indexOfFirst { it.id == subId }
        if (manualIdx >= 0) {
            val old = subs[manualIdx]
            subs[manualIdx] = old.copy(servers = old.servers + named)
        } else {
            subs.add(
                Subscription(
                    id = subId,
                    name = "Ручные",
                    sourceUrl = "",
                    servers = listOf(named)
                )
            )
        }
        _subscriptions.value = subs
        rebuildServers()
        persist()
        return Result.success(named)
    }

    fun addSubscriptionEntry(name: String, url: String = "", servers: List<VlessServer>): Subscription {
        val id = java.util.UUID.randomUUID().toString()
        val withIds = servers.map { it.copy(subscriptionId = id) }
        val sub = Subscription(id = id, name = name, sourceUrl = url, servers = withIds)
        _subscriptions.value = _subscriptions.value + sub
        rebuildServers()
        persist()
        return sub
    }

    fun removeSubscription(subscriptionId: String) {
        _subscriptions.value = _subscriptions.value.filter { it.id != subscriptionId }
        if (_selectedServerId.value != null) {
            val still = _servers.value.any {
                it.id == _selectedServerId.value && it.subscriptionId != subscriptionId
            }
            // recompute after rebuild
        }
        rebuildServers()
        val sel = _selectedServerId.value
        if (sel != null && _servers.value.none { it.id == sel }) {
            selectServer(null)
        }
        persist()
    }

    fun removeServer(serverId: String) {
        _subscriptions.value = _subscriptions.value.map { sub ->
            sub.copy(servers = sub.servers.filter { it.id != serverId })
        }.filter { it.servers.isNotEmpty() || it.sourceUrl.isNotBlank() || it.id == "manual" }
        rebuildServers()
        if (_selectedServerId.value == serverId) selectServer(null)
        persist()
    }

    /**
     * Download subscription URL, decode base64 list of servers, add all.
     */
    suspend fun importFromUrl(name: String, url: String): Result<Subscription> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url.trim())
                .header("User-Agent", "TelegramProxy/1.0")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${response.code}: не удалось загрузить подписку")
                    )
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Пустой ответ подписки"))
                }
                val tempId = java.util.UUID.randomUUID().toString()
                val servers = VlessConfig.parseSubscriptionBody(body, tempId, name)
                if (servers.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalStateException("В подписке не найдено VLESS-серверов")
                    )
                }
                val sub = addSubscriptionEntry(name.ifBlank { "Подписка" }, url.trim(), servers)
                Log.i(TAG, "Imported ${servers.size} servers from $url")
                Result.success(sub)
            }
        } catch (e: Exception) {
            Log.e(TAG, "importFromUrl failed", e)
            Result.failure(e)
        }
    }

    /**
     * Import from pasted base64 / plain multi-line body without network.
     */
    fun importFromBody(name: String, body: String): Result<Subscription> {
        return try {
            val tempId = java.util.UUID.randomUUID().toString()
            val servers = VlessConfig.parseSubscriptionBody(body, tempId, name)
            if (servers.isEmpty()) {
                Result.failure(IllegalStateException("Не найдено VLESS-серверов"))
            } else {
                Result.success(addSubscriptionEntry(name.ifBlank { "Импорт" }, "", servers))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun rebuildServers() {
        _servers.value = _subscriptions.value.flatMap { it.servers }
    }

    private fun persist() {
        try {
            storeFile.writeText(gson.toJson(_subscriptions.value))
        } catch (e: Exception) {
            Log.e(TAG, "persist failed", e)
        }
    }

    private fun load() {
        try {
            if (storeFile.exists()) {
                val type = object : TypeToken<List<Subscription>>() {}.type
                val list: List<Subscription> = gson.fromJson(storeFile.readText(), type) ?: emptyList()
                _subscriptions.value = list
                rebuildServers()
            }
            if (selectedFile.exists()) {
                val id = selectedFile.readText().trim()
                if (id.isNotEmpty()) _selectedServerId.value = id
            }
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
            _subscriptions.value = emptyList()
            _servers.value = emptyList()
        }
    }

    companion object {
        private const val TAG = "SubscriptionManager"

        @Volatile
        private var instance: SubscriptionManager? = null

        fun get(context: Context): SubscriptionManager {
            return instance ?: synchronized(this) {
                instance ?: SubscriptionManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
