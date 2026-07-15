package com.telegramproxy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.telegramproxy.ui.screens.AddSubscriptionScreen
import com.telegramproxy.ui.screens.ServerListScreen
import com.telegramproxy.ui.theme.BackgroundDark
import com.telegramproxy.ui.theme.OnSurfaceMuted
import com.telegramproxy.ui.theme.SurfaceDark
import com.telegramproxy.ui.theme.TelegramBlue
import com.telegramproxy.ui.theme.TelegramProxyTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ProxyViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ProxyViewModel(application) as T
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — service still works on older APIs */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        // Handle vless:// deep link
        intent?.data?.let { uri ->
            if (uri.scheme.equals("vless", ignoreCase = true)) {
                viewModel.addVless("Импорт", uri.toString())
            }
        }

        setContent {
            TelegramProxyTheme {
                TelegramProxyApp(
                    viewModel = viewModel
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

private data class TabItem(val title: String, val icon: ImageVector)

@Composable
fun TelegramProxyApp(viewModel: ProxyViewModel) {
    val tabs = listOf(
        TabItem("Серверы", Icons.Default.List),
        TabItem("Подписки", Icons.Default.VpnKey)
    )
    var selectedTab by remember { mutableIntStateOf(0) }

    val servers by viewModel.servers.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val selectedId by viewModel.selectedServerId.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        containerColor = BackgroundDark,
        bottomBar = {
            NavigationBar(containerColor = SurfaceDark) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TelegramBlue,
                            selectedTextColor = TelegramBlue,
                            indicatorColor = TelegramBlue.copy(alpha = 0.15f),
                            unselectedIconColor = OnSurfaceMuted,
                            unselectedTextColor = OnSurfaceMuted
                        )
                    )
                }
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> ServerListScreen(
                servers = servers,
                selectedServerId = selectedId,
                connectionState = connectionState,
                statusMessage = statusMessage,
                onSelectServer = viewModel::selectServer,
                onConnectToggle = viewModel::toggleConnection,
                onDeleteServer = viewModel::removeServer,
                modifier = Modifier.padding(padding)
            )
            1 -> AddSubscriptionScreen(
                subscriptions = subscriptions,
                isLoading = isLoading,
                onAddVless = { name, url -> viewModel.addVless(name, url) },
                onImportUrl = { name, url -> viewModel.importSubscriptionSuspend(name, url) },
                onRemoveSubscription = viewModel::removeSubscription,
                modifier = Modifier.padding(padding)
            )
        }
    }
}
