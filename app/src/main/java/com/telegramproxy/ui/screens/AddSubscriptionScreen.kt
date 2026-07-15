package com.telegramproxy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.telegramproxy.Subscription
import com.telegramproxy.ui.components.SectionHeader
import com.telegramproxy.ui.components.SubscriptionCard
import com.telegramproxy.ui.theme.OnSurfaceMuted
import com.telegramproxy.ui.theme.SurfaceVariantDark
import com.telegramproxy.ui.theme.TelegramBlue
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSubscriptionScreen(
    subscriptions: List<Subscription>,
    isLoading: Boolean,
    onAddVless: (name: String, vlessUrl: String) -> Result<*>,
    onImportUrl: suspend (name: String, url: String) -> Result<*>,
    onRemoveSubscription: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf("") }
    var vlessUrl by remember { mutableStateOf("") }
    var subUrl by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = TelegramBlue,
        unfocusedBorderColor = OnSurfaceMuted.copy(alpha = 0.35f),
        focusedLabelColor = TelegramBlue,
        cursorColor = TelegramBlue,
        focusedContainerColor = SurfaceVariantDark.copy(alpha = 0.35f),
        unfocusedContainerColor = SurfaceVariantDark.copy(alpha = 0.2f)
    )

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Добавить сервер / подписку")
                Text(
                    "Вставьте VLESS URL или ссылку подписки (base64-список серверов).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted
                )
                Spacer(Modifier.height(8.dp))
            }

            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя подписки / сервера") },
                    placeholder = { Text("Мой VPN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors
                )
            }

            item {
                OutlinedTextField(
                    value = vlessUrl,
                    onValueChange = { vlessUrl = it },
                    label = { Text("VLESS URL") },
                    placeholder = { Text("vless://uuid@host:443?…") },
                    leadingIcon = { Icon(Icons.Default.Link, null, tint = TelegramBlue) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors
                )
            }

            item {
                Button(
                    onClick = {
                        val result = onAddVless(name.trim(), vlessUrl.trim())
                        scope.launch {
                            if (result.isSuccess) {
                                snackbar.showSnackbar("Сервер добавлен")
                                vlessUrl = ""
                            } else {
                                snackbar.showSnackbar(
                                    result.exceptionOrNull()?.message ?: "Ошибка добавления"
                                )
                            }
                        }
                    },
                    enabled = vlessUrl.trim().startsWith("vless://", ignoreCase = true) && !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("Добавить VLESS")
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Или импорт подписки по URL",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                OutlinedTextField(
                    value = subUrl,
                    onValueChange = { subUrl = it },
                    label = { Text("Ссылка подписки (https://…)") },
                    placeholder = { Text("https://example.com/sub") },
                    leadingIcon = { Icon(Icons.Default.CloudDownload, null, tint = TelegramBlue) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors
                )
            }

            item {
                Button(
                    onClick = {
                        scope.launch {
                            val result = onImportUrl(
                                name.trim().ifBlank { "Подписка" },
                                subUrl.trim()
                            )
                            if (result.isSuccess) {
                                snackbar.showSnackbar("Подписка импортирована")
                                subUrl = ""
                            } else {
                                snackbar.showSnackbar(
                                    result.exceptionOrNull()?.message ?: "Ошибка импорта"
                                )
                            }
                        }
                    },
                    enabled = subUrl.trim().startsWith("http", ignoreCase = true) && !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.padding(6.dp))
                    } else {
                        Icon(Icons.Default.CloudDownload, null)
                        Spacer(Modifier.padding(4.dp))
                    }
                    Text("Импортировать подписку")
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader("Список подписок")
            }

            if (subscriptions.isEmpty()) {
                item {
                    Text(
                        "Пока нет подписок. Добавьте VLESS или URL.",
                        color = OnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else {
                items(subscriptions, key = { it.id }) { sub ->
                    SubscriptionCard(
                        name = sub.name,
                        serverCount = sub.servers.size,
                        sourceUrl = sub.sourceUrl,
                        onDelete = { onRemoveSubscription(sub.id) }
                    )
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}
