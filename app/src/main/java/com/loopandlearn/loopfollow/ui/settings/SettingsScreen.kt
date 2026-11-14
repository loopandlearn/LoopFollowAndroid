package com.loopandlearn.loopfollow.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.NavStack
import com.loopandlearn.loopfollow.ui.Route
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.components.NavigationRow
import com.loopandlearn.loopfollow.ui.components.formSection

@Composable
fun SettingsScreen(nav: NavStack) {
    val url by Storage.url.flow.collectAsStateWithLifecycle()
    NavScreen("Settings", onBack = { nav.pop() }) {
        FormList {
            formSection(header = "Data Settings") {
                NavigationRow("Nightscout", Icons.Outlined.Explore) { nav.push(Route.NightscoutSettings()) }
                FormDivider()
                NavigationRow("Dexcom", Icons.Outlined.Sensors) { nav.push(Route.DexcomSettings()) }
            }
            formSection(header = "Display Settings") {
                NavigationRow("General", Icons.Outlined.Settings) { nav.push(Route.GeneralSettings) }
                FormDivider()
                NavigationRow("Graph", Icons.AutoMirrored.Outlined.ShowChart) { nav.push(Route.GraphSettings) }
                if (url.isNotEmpty()) {
                    FormDivider()
                    NavigationRow("Information Display", Icons.Outlined.Info) { nav.push(Route.InfoDisplaySettings) }
                }
                FormDivider()
                NavigationRow("Units and Metrics", Icons.Outlined.Scale) { nav.push(Route.UnitsSettings) }
                FormDivider()
                NavigationRow("Tabs", Icons.Outlined.ViewWeek) { nav.push(Route.TabSettings) }
            }
            formSection(header = "App Settings") {
                NavigationRow("Background Refresh", Icons.Outlined.Refresh) { nav.push(Route.BackgroundRefresh) }
                FormDivider()
                NavigationRow("Import/Export", Icons.Outlined.Download) { nav.push(Route.ImportExportSettings) }
                FormDivider()
                NavigationRow("Live Activity", Icons.Outlined.WifiTethering) { nav.push(Route.LiveActivitySettings) }
                if (url.isNotEmpty()) {
                    FormDivider()
                    NavigationRow("Remote", Icons.Outlined.SettingsInputAntenna) { nav.push(Route.RemoteSettings) }
                }
            }
            formSection(header = "Alarms") {
                NavigationRow("Alarms", Icons.Outlined.NotificationsActive) { nav.push(Route.AlarmSettings) }
            }
            formSection(header = "Advanced Settings") {
                NavigationRow("Advanced", Icons.Outlined.Shield) { nav.push(Route.AdvancedSettings) }
            }
        }
    }
}
