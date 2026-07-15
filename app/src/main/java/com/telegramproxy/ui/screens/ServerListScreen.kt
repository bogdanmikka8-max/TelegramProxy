package com.telegramproxy.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.telegramproxy.ProxyService
import com.telegramproxy.VlessConfig
import com.telegramproxy.VlessServer
import com.telegramproxy.ui.components.ConnectionStatusBanner
import com.telegramproxy.ui.components.SectionHeader
import com.telegramproxy.ui.components.ServerCard
import com.telegramproxy.ui.theme.CardDark
import com.telegramproxy.ui.theme.ErrorRed
import com.telegramproxy.ui.theme.OnSurfaceMuted
import com.telegramproxy.ui.theme.TelegramBlue

@Composable
fun ServerListScreen(
    servers: List<VlessServer>,
    selectedServerId: String?,
    connectionState: ProxyService.ConnectionState,
    statusMessage: String,
    message: String?,
    onClearMessage: () -> Unit,
    onSelectServer: (String) -> Unit,
    onConnectToggle: () -> Unit,
    onDeleteServer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isConnected = connectionState is ProxyService.ConnectionState.Connected
    val isConnecting = connectionState is ProxyService.ConnectionState.Connecting
    val connectedId = (connectionState as? ProxyService.ConnectionState.Connected)?.server?.id

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            ConnectionStatusBanner(
                state = connectionState,
                statusMessage = statusMessage
            )
        }

        item {
            Button(
                onClick = onConnectToggle,
                enabled = selectedServerId != null && !isConnecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) ErrorRed else TelegramBlue
                )
            ) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        isConnecting -> "Подключение…"
                        isConnected -> "Отключить"
                        else -> "Подключить"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        item {
            TelegramProxySetupCard(
                onOpenTelegramProxy = {
                    openTelegramProxy(context)
                }
            )
        }

        item {
            SectionHeader("Серверы (${servers.size})")
            if (servers.isEmpty()) {
                Text(
                    "Нет серверов. Добавьте подписку на вкладке «Подписки».",
                    color = OnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        items(servers, key = { it.id }) { server ->
            ServerCard(
                server = server,
                selected = server.id == selectedServerId,
                connected = server.id == connectedId,
                onClick = { onSelectServer(server.id) },
                onDelete = { onDeleteServer(server.id) }
            )
        }

        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun TelegramProxySetupCard(onOpenTelegramProxy: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Send, contentDescription = null, tint = TelegramBlue)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Прокси для Telegram",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "SOCKS5: 127.0.0.1:${VlessConfig.LOCAL_SOCKS_PORT}\n" +
                    "Настройки → Данные и память → Прокси → SOCKS5",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenTelegramProxy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Настроить прокси в Telegram")
            }
        }
    }
}

private fun openTelegramProxy(context: android.content.Context) {
    val uri = Uri.parse(
        "tg://proxy?server=127.0.0.1&port=${VlessConfig.LOCAL_SOCKS_PORT}"
    )
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: Exception) {
        try {
            val web = Uri.parse(
                "https://t.me/proxy?server=127.0.0.1&port=${VlessConfig.LOCAL_SOCKS_PORT}"
            )
            context.startActivity(Intent(Intent.ACTION_VIEW, web))
        } catch (_: Exception) {
            Toast.makeText(
                context,
                "Установите Telegram. Прокси: 127.0.0.1:${VlessConfig.LOCAL_SOCKS_PORT}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
