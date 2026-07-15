package com.telegramproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps Xray SOCKS5 proxy alive in background.
 */
class ProxyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        instance = this
        _serviceRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
                connect(serverId)
            }
            ACTION_DISCONNECT -> {
                disconnect()
                stopForegroundCompat()
                stopSelf()
            }
            ACTION_STOP -> {
                disconnect()
                stopForegroundCompat()
                stopSelf()
            }
            else -> {
                // Restart recovery
                val selected = SubscriptionManager.get(this).getSelectedServer()
                if (selected != null && XrayCore.get(this).isRunning) {
                    startAsForeground(selected)
                }
            }
        }
        return START_STICKY
    }

    private fun connect(serverId: String?) {
        val manager = SubscriptionManager.get(this)
        val server = when {
            serverId != null -> manager.servers.value.find { it.id == serverId }
            else -> manager.getSelectedServer()
        }
        if (server == null) {
            Log.e(TAG, "No server to connect")
            _connectionState.value = ConnectionState.Error("Сервер не выбран")
            stopSelf()
            return
        }

        manager.selectServer(server.id)
        startAsForeground(server)
        _connectionState.value = ConnectionState.Connecting(server)

        val result = XrayCore.get(this).start(server)
        if (result.isSuccess) {
            _connectionState.value = ConnectionState.Connected(server)
            updateNotification(server, connected = true)
            collectJob?.cancel()
            collectJob = scope.launch {
                XrayCore.get(this@ProxyService).statusMessage.collect { msg ->
                    // keep notification in sync
                    if (XrayCore.get(this@ProxyService).isRunning) {
                        updateNotification(server, connected = true, subtitle = msg)
                    }
                }
            }
        } else {
            val err = result.exceptionOrNull()?.message ?: "Ошибка запуска"
            _connectionState.value = ConnectionState.Error(err)
            XrayCore.get(this).stop()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun disconnect() {
        collectJob?.cancel()
        XrayCore.get(this).stop()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun startAsForeground(server: VlessServer) {
        val notification = buildNotification(server, connected = false, subtitle = "Подключение…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(
        server: VlessServer,
        connected: Boolean,
        subtitle: String? = null
    ) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(server, connected, subtitle))
    }

    private fun buildNotification(
        server: VlessServer,
        connected: Boolean,
        subtitle: String?
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (connected) getString(R.string.notification_title) else "Подключение…"
        val text = subtitle
            ?: "${server.name} · 127.0.0.1:${VlessConfig.LOCAL_SOCKS_PORT}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Отключить", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        instance = null
        _serviceRunning.value = false
        super.onDestroy()
    }

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data class Connecting(val server: VlessServer) : ConnectionState()
        data class Connected(val server: VlessServer) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    companion object {
        private const val TAG = "ProxyService"
        private const val CHANNEL_ID = "telegram_proxy_channel"
        private const val NOTIFICATION_ID = 1008

        const val ACTION_CONNECT = "com.telegramproxy.CONNECT"
        const val ACTION_DISCONNECT = "com.telegramproxy.DISCONNECT"
        const val ACTION_STOP = "com.telegramproxy.STOP"
        const val EXTRA_SERVER_ID = "server_id"

        @Volatile
        private var instance: ProxyService? = null

        private val _connectionState =
            MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        private val _serviceRunning = MutableStateFlow(false)
        val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

        fun connect(context: Context, serverId: String) {
            val intent = Intent(context, ProxyService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_SERVER_ID, serverId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, ProxyService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                XrayCore.get(context).stop()
                _connectionState.value = ConnectionState.Disconnected
            }
        }

        fun isConnected(): Boolean =
            _connectionState.value is ConnectionState.Connected
    }
}
