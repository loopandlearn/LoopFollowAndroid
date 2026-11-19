package com.loopandlearn.loopfollow.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.storage.TabItem
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.alarms.AddAlarmScreen
import com.loopandlearn.loopfollow.ui.alarms.AlarmEditorScreen
import com.loopandlearn.loopfollow.ui.alarms.AlarmListScreen
import com.loopandlearn.loopfollow.ui.alarms.AlarmSettingsScreen
import com.loopandlearn.loopfollow.ui.alarms.SnoozerScreen
import com.loopandlearn.loopfollow.ui.home.HomeScreen
import com.loopandlearn.loopfollow.ui.menu.MenuScreen
import com.loopandlearn.loopfollow.ui.settings.DexcomSettingsScreen
import com.loopandlearn.loopfollow.ui.settings.NightscoutSettingsScreen
import com.loopandlearn.loopfollow.ui.settings.SettingsScreen
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

/** A screen that can be pushed on a tab's navigation stack. */
sealed class Route {
    data object Settings : Route()
    data class NightscoutSettings(val fromSetup: Boolean = false) : Route()
    data class DexcomSettings(val fromSetup: Boolean = false) : Route()
    data class Feature(val item: TabItem) : Route()
    data object AlarmSettings : Route()
    data object AddAlarm : Route()
    data class EditAlarm(val id: String, val isNew: Boolean) : Route()
    data object GeneralSettings : Route()
    data object TelemetryPreview : Route()
    data object TelemetryPrivacy : Route()
    data object GraphSettings : Route()
    data object UnitsSettings : Route()
    data object RangeMode : Route()
    data object TabSettings : Route()
    data object InfoDisplaySettings : Route()
    data class InfoRow(val type: com.loopandlearn.loopfollow.info.InfoType) : Route()
    data object AdvancedSettings : Route()
    data object BackgroundRefresh : Route()
    data object ImportExportSettings : Route()
    data object LiveActivitySettings : Route()
    data object Log : Route()
    data object ShareLogs : Route()
    data object TreatmentDetail : Route()
    data class TrioMealEdit(val meal: com.loopandlearn.loopfollow.engine.TrioMealTreatment) : Route()
    data class LoopCarbEdit(val carb: com.loopandlearn.loopfollow.engine.LoopCarbTreatment) : Route()
    data object RemoteSettings : Route()
    data object TrioBolus : Route()
    data object TrioMeal : Route()
    data object TrioOverride : Route()
    data object TrioTempTarget : Route()
    data object LoopBolus : Route()
    data object LoopCarbs : Route()
    data object LoopOverrides : Route()
    data object LoopProfiles : Route()
    data class LoopProfilePreview(val name: String) : Route()
}

class NavStack {
    val stack = mutableStateListOf<Route>()
    val canPop: Boolean get() = stack.isNotEmpty()
    fun push(route: Route) {
        stack.add(route)
    }
    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
    }
    fun popToRoot() = stack.clear()
}

val TabItem.icon: ImageVector
    get() = when (this) {
        TabItem.HOME -> Icons.Outlined.Home
        TabItem.ALARMS -> Icons.Filled.Alarm
        TabItem.REMOTE -> Icons.Filled.SettingsInputAntenna
        TabItem.NIGHTSCOUT -> Icons.Filled.Explore
        TabItem.SNOOZER -> Icons.Filled.Snooze
        TabItem.TREATMENTS -> Icons.Filled.MedicalServices
        TabItem.STATS -> Icons.Filled.BarChart
    }

@Composable
fun TabRoot(item: TabItem, nav: NavStack) {
    StackHost(nav) { FeatureRoot(item, nav, isRoot = true) }
}

@Composable
fun MenuRoot(nav: NavStack) {
    StackHost(nav) { MenuScreen(nav) }
}

@Composable
private fun StackHost(nav: NavStack, root: @Composable () -> Unit) {
    val top = nav.stack.lastOrNull()
    AnimatedContent(
        targetState = top to nav.stack.size,
        transitionSpec = {
            val forward = targetState.second > initialState.second
            if (forward) {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 }
            } else {
                slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }
            }
        },
        label = "nav",
    ) { (route, _) ->
        Box(Modifier.fillMaxSize()) {
            if (route == null) root() else RouteContent(route, nav)
        }
    }
}

@Composable
private fun RouteContent(route: Route, nav: NavStack) {
    when (route) {
        Route.Settings -> SettingsScreen(nav)
        is Route.NightscoutSettings -> NightscoutSettingsScreen(onBack = { nav.pop() }, onDone = {
            nav.pop()
            if (route.fromSetup) MainEngine.startInitialLoad()
        }, onImport = { nav.push(Route.ImportExportSettings) }, closeLabel = if (route.fromSetup) "Close" else null)
        is Route.DexcomSettings -> DexcomSettingsScreen(onBack = { nav.pop() }, onDone = {
            nav.pop()
            if (route.fromSetup) MainEngine.startInitialLoad()
        }, onImport = { nav.push(Route.ImportExportSettings) }, closeLabel = if (route.fromSetup) "Close" else null)
        is Route.Feature -> FeatureRoot(route.item, nav, isRoot = false)
        Route.AlarmSettings -> AlarmSettingsScreen(onBack = { nav.pop() })
        Route.AddAlarm -> AddAlarmScreen(nav)
        is Route.EditAlarm -> AlarmEditorScreen(nav, route.id, route.isNew)
        Route.GeneralSettings -> com.loopandlearn.loopfollow.ui.settings.GeneralSettingsScreen(nav, onBack = { nav.pop() })
        Route.TelemetryPreview -> com.loopandlearn.loopfollow.ui.settings.TelemetryPreviewScreen(onBack = { nav.pop() })
        Route.TelemetryPrivacy -> com.loopandlearn.loopfollow.ui.settings.TelemetryPrivacyScreen(onBack = { nav.pop() })
        Route.GraphSettings -> com.loopandlearn.loopfollow.ui.settings.GraphSettingsScreen(onBack = { nav.pop() })
        Route.UnitsSettings -> com.loopandlearn.loopfollow.ui.settings.UnitsSettingsScreen(nav, onBack = { nav.pop() })
        Route.RangeMode -> com.loopandlearn.loopfollow.ui.settings.RangeModeScreen(onBack = { nav.pop() })
        Route.TabSettings -> com.loopandlearn.loopfollow.ui.settings.TabSettingsScreen(onBack = { nav.pop() })
        Route.InfoDisplaySettings -> com.loopandlearn.loopfollow.ui.settings.InfoDisplaySettingsScreen(nav, onBack = { nav.pop() })
        is Route.InfoRow -> com.loopandlearn.loopfollow.ui.settings.InfoRowSettingsScreen(route.type, onBack = { nav.pop() })
        Route.AdvancedSettings -> com.loopandlearn.loopfollow.ui.settings.AdvancedSettingsScreen(onBack = { nav.pop() })
        Route.BackgroundRefresh -> com.loopandlearn.loopfollow.ui.settings.BackgroundRefreshSettingsScreen(onBack = { nav.pop() })
        Route.ImportExportSettings -> com.loopandlearn.loopfollow.ui.settings.importexport.ImportExportSettingsScreen(nav, onBack = { nav.pop() })
        Route.LiveActivitySettings -> com.loopandlearn.loopfollow.ui.settings.LiveActivitySettingsScreen(onBack = { nav.pop() })
        Route.Log -> com.loopandlearn.loopfollow.ui.menu.LogScreen(onBack = { nav.pop() })
        Route.ShareLogs -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            com.loopandlearn.loopfollow.ui.menu.ShareLogNoticeScreen(
                onCancel = { nav.pop() },
                onShare = { text ->
                    scope.launch {
                        com.loopandlearn.loopfollow.ui.menu.shareLogs(context, text)
                        nav.pop()
                    }
                },
            )
        }
        Route.TreatmentDetail -> com.loopandlearn.loopfollow.ui.treatments.TreatmentDetailScreen(nav, onBack = { nav.pop() })
        // A sent edit returns to the treatment list, past the detail screen it was opened from.
        is Route.TrioMealEdit -> com.loopandlearn.loopfollow.ui.remote.TrioMealEditScreen(route.meal, onBack = { nav.pop() }, onSent = {
            nav.pop()
            nav.pop()
        })
        is Route.LoopCarbEdit -> com.loopandlearn.loopfollow.ui.remote.LoopCarbEditScreen(route.carb, onBack = { nav.pop() }, onSent = {
            nav.pop()
            nav.pop()
        })
        Route.RemoteSettings -> com.loopandlearn.loopfollow.ui.remote.RemoteSettingsScreen(onBack = { nav.pop() }, onImportExport = { nav.push(Route.ImportExportSettings) })
        Route.TrioBolus -> com.loopandlearn.loopfollow.ui.remote.TrioBolusScreen(onBack = { nav.pop() })
        Route.TrioMeal -> com.loopandlearn.loopfollow.ui.remote.TrioMealScreen(onBack = { nav.pop() })
        Route.TrioOverride -> com.loopandlearn.loopfollow.ui.remote.TrioOverrideScreen(onBack = { nav.pop() })
        Route.TrioTempTarget -> com.loopandlearn.loopfollow.ui.remote.TrioTempTargetScreen(onBack = { nav.pop() })
        Route.LoopBolus -> com.loopandlearn.loopfollow.ui.remote.LoopBolusScreen(onBack = { nav.pop() })
        Route.LoopCarbs -> com.loopandlearn.loopfollow.ui.remote.LoopCarbsScreen(onBack = { nav.pop() })
        Route.LoopOverrides -> com.loopandlearn.loopfollow.ui.remote.LoopOverridePresetsScreen(onBack = { nav.pop() })
        Route.LoopProfiles -> com.loopandlearn.loopfollow.ui.remote.LoopProfilesScreen(nav, onBack = { nav.pop() })
        is Route.LoopProfilePreview -> com.loopandlearn.loopfollow.ui.remote.LoopProfilePreviewScreen(route.name, onBack = { nav.pop() })
    }
}

@Composable
private fun FeatureRoot(item: TabItem, nav: NavStack, isRoot: Boolean) {
    val back: (() -> Unit)? = if (isRoot) null else ({ nav.pop() })
    when (item) {
        TabItem.HOME -> HomeScreen(
            onSetupNightscout = { nav.push(Route.NightscoutSettings(fromSetup = true)) },
            onSetupDexcom = { nav.push(Route.DexcomSettings(fromSetup = true)) },
            onStatsTap = { nav.push(Route.Feature(TabItem.STATS)) },
        )
        TabItem.ALARMS -> AlarmListScreen(nav, back)
        TabItem.SNOOZER -> SnoozerScreen()
        TabItem.TREATMENTS -> com.loopandlearn.loopfollow.ui.treatments.TreatmentsScreen(nav, back)
        TabItem.STATS -> com.loopandlearn.loopfollow.ui.stats.StatisticsScreen(back)
        TabItem.REMOTE -> com.loopandlearn.loopfollow.ui.remote.RemoteScreen(nav, back)
        TabItem.NIGHTSCOUT -> if (isRoot) NightscoutScreen() else NavScreen("Nightscout", onBack = back) { NightscoutScreen() }
    }
}
