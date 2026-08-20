package com.kgr.systemtoolbox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import com.kgr.systemtoolbox.BuildConfig
import com.kgr.systemtoolbox.settings.SettingsScreen
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class AppTab(val label: String) { Info("Info"), System("System"), Settings("Settings") }

val systemScreens = listOf(
    Screen.AdBlock,
    Screen.DenylistManager,
    Screen.WirelessAdb,
    Screen.PlayStoreTagger,
    Screen.Zram
)

@Composable
fun HomeScreen() {
    var tab by remember { mutableStateOf(AppTab.Info) }
    var detail by remember { mutableStateOf<Screen?>(null) }

    BackHandler(enabled = detail != null) { detail = null }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == AppTab.Info && detail == null,
                    onClick = { tab = AppTab.Info; detail = null },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(AppTab.Info.label) }
                )
                NavigationBarItem(
                    selected = tab == AppTab.System,
                    onClick = { tab = AppTab.System; detail = null },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                    label = { Text(AppTab.System.label) }
                )
                NavigationBarItem(
                    selected = tab == AppTab.Settings,
                    onClick = { tab = AppTab.Settings; detail = null },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(AppTab.Settings.label) }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val current = detail
            if (current != null) {
                DetailHost(current) { detail = null }
            } else when (tab) {
                AppTab.Info -> InfoScreen()
                AppTab.System -> CategoryMenu("System", systemScreens) { detail = it }
                AppTab.Settings -> SettingsScreen(currentVersionName = BuildConfig.VERSION_NAME)
            }
        }
    }
}

@Composable
private fun DetailHost(screen: Screen, onBack: () -> Unit) {
    when (screen) {
        Screen.Zram -> ZramScreen(onBack)
        Screen.WirelessAdb -> WirelessAdbScreen(onBack)
        Screen.PlayStoreTagger -> PlayStoreTaggerScreen(onBack)
        Screen.AdBlock -> AdBlockScreen(onBack)
        Screen.DenylistManager -> DenylistScreen(onBack)
        Screen.Home -> Unit
    }
}

@Composable
private fun CategoryMenu(title: String, screens: List<Screen>, onNavigate: (Screen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        screens.forEach { screen ->
            MenuEntry(screen) { onNavigate(screen) }
        }
    }
}

@Composable
private fun MenuEntry(screen: Screen, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(screen.title, style = MaterialTheme.typography.titleMedium)
            if (screen.subtitle.isNotEmpty() || screen.access.isNotEmpty()) {
                Row {
                    if (screen.subtitle.isNotEmpty()) {
                        Text(
                            screen.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (screen.access.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            screen.access.joinToString(" ") { "[${it.label}]" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}
