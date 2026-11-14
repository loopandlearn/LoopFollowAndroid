package com.loopandlearn.loopfollow.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.loopandlearn.loopfollow.alarm.AlarmPermissions
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.engine.TaskID
import com.loopandlearn.loopfollow.engine.TaskScheduler
import com.loopandlearn.loopfollow.engine.updateOpenAPSPredictionDisplay
import com.loopandlearn.loopfollow.helpers.GlycemicMetricMode
import com.loopandlearn.loopfollow.helpers.GlycemicOutputUnit
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.TelemetryClient
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.helpers.VariabilityMetricMode
import com.loopandlearn.loopfollow.info.InfoColorDirection
import com.loopandlearn.loopfollow.info.InfoType
import com.loopandlearn.loopfollow.service.LoopFollowService
import com.loopandlearn.loopfollow.storage.AppearanceMode
import com.loopandlearn.loopfollow.storage.BackgroundRefreshType
import com.loopandlearn.loopfollow.storage.GlucoseConversion
import com.loopandlearn.loopfollow.storage.GlucoseDisplayUnit
import com.loopandlearn.loopfollow.storage.HistoryCurvePlacement
import com.loopandlearn.loopfollow.storage.PredictionDisplayType
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.storage.StorageValue
import com.loopandlearn.loopfollow.storage.TabItem
import com.loopandlearn.loopfollow.storage.TabPosition
import com.loopandlearn.loopfollow.storage.TimeInRangeDisplayMode
import com.loopandlearn.loopfollow.ui.NavStack
import com.loopandlearn.loopfollow.ui.Route
import com.loopandlearn.loopfollow.ui.components.BGPickerRow
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormSection
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.components.MenuPickerRow
import com.loopandlearn.loopfollow.ui.components.NavigationRow
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.components.SegmentedPicker
import com.loopandlearn.loopfollow.ui.components.StepperRow
import com.loopandlearn.loopfollow.ui.components.ToggleRow
import com.loopandlearn.loopfollow.ui.components.clickableNoRipple
import com.loopandlearn.loopfollow.ui.components.formSection
import com.loopandlearn.loopfollow.ui.icon
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import java.util.TimeZone
import kotlin.math.abs

@Composable
private fun <T> StorageValue<T>.state(): T = flow.collectAsStateWithLifecycle().value

@Composable
private fun StoredToggle(title: String, value: StorageValue<Boolean>) {
    ToggleRow(title, value.state()) { value.value = it }
}

/** Re-routes predictions and reloads BG so a changed graph setting shows at once. */
private fun applyGraphSettings() {
    MainEngine.updateOpenAPSPredictionDisplay()
    MainEngine.chartChanged()
    TaskScheduler.rescheduleTask(TaskID.FETCH_BG, System.currentTimeMillis())
}

@Composable
fun GeneralSettingsScreen(nav: NavStack, onBack: () -> Unit) {
    val speakBG = Storage.speakBG.state()
    val always = Storage.speakBGAlways.state()
    val speakLow = Storage.speakLowBG.state()
    val proactive = Storage.speakProactiveLowBG.state()
    val speakHigh = Storage.speakHighBG.state()
    val showStats = Storage.showStats.state()
    val tzEnabled = Storage.graphTimeZoneEnabled.state()
    val zones = remember {
        TimeZone.getAvailableIDs().map { TimeZone.getTimeZone(it) }.sortedBy { it.rawOffset }
    }

    NavScreen("General Settings", onBack = onBack) {
        FormList {
            formSection(header = "App Settings") {
                StoredToggle("Persistent Notification", Storage.persistentNotification)
            }
            formSection(header = "Display") {
                MenuPickerRow("Appearance", Storage.appearanceMode.state(), AppearanceMode.entries, { it.displayName }) { Storage.appearanceMode.value = it }
                FormDivider()
                StoredToggle("Display Small Graph", Storage.showSmallGraph)
                FormDivider()
                StoredToggle("Color BG Text", Storage.colorBGText)
                FormDivider()
                StoredToggle("Keep Screen Active", Storage.screenlockSwitchState)
                FormDivider()
                StoredToggle("Show Display Name", Storage.showDisplayName)
                FormDivider()
                StoredToggle("Snoozer emoji", Storage.snoozerEmoji)
                FormDivider()
                StoredToggle("Force portrait mode", Storage.forcePortraitMode)
            }
            formSection(
                header = "Statistics",
                footer = "The statistics box and the Time in Range band both cover this period. Today starts at midnight in your time zone, or in the Time Zone Override below when it is on.",
            ) {
                StoredToggle("Display Stats", Storage.showStats)
                if (showStats) {
                    FormDivider()
                    MenuPickerRow("Stats Style", Storage.showTIRBand.state(), listOf(false, true), { if (it) "Time in Range Band" else "Statistics Box" }) { Storage.showTIRBand.value = it }
                    FormDivider()
                    MenuPickerRow("Stats Period", Storage.statsSinceMidnight.state(), listOf(false, true), { if (it) "Today (since midnight)" else "Last 24 Hours" }) { Storage.statsSinceMidnight.value = it }
                }
            }
            formSection(header = "Time Zone") {
                ToggleRow("Time Zone Override", tzEnabled) {
                    Storage.graphTimeZoneEnabled.value = it
                    MainEngine.chartChanged()
                }
                if (tzEnabled) {
                    FormDivider()
                    val current = zones.firstOrNull { it.id == Storage.graphTimeZoneIdentifier.state() } ?: TimeZone.getDefault()
                    MenuPickerRow("Time Zone", current, zones, ::timeZoneLabel) {
                        Storage.graphTimeZoneIdentifier.value = it.id
                        MainEngine.chartChanged()
                    }
                }
            }
            formSection(header = "Speak BG") {
                StoredToggle("Speak BG", Storage.speakBG)
                if (speakBG) {
                    FormDivider()
                    val languages = listOf("en" to "English", "fr" to "French", "it" to "Italian", "sk" to "Slovak", "sv" to "Swedish")
                    val lang = Storage.speakLanguage.state()
                    MenuPickerRow("Language", languages.firstOrNull { it.first == lang } ?: languages[0], languages, { it.second }) { Storage.speakLanguage.value = it.first }
                    FormDivider()
                    StoredToggle("Always", Storage.speakBGAlways)
                    if (!always) {
                        FormDivider()
                        ToggleRow("Low", speakLow) {
                            Storage.speakLowBG.value = it
                            if (it) Storage.speakProactiveLowBG.value = false
                        }
                        FormDivider()
                        ToggleRow("Proactive Low", proactive) {
                            Storage.speakProactiveLowBG.value = it
                            if (it) Storage.speakLowBG.value = false
                        }
                        if (speakLow || proactive) {
                            FormDivider()
                            BGPickerRow("Low BG Limit", 40.0..108.0, Storage.speakLowBGLimit.state()) { Storage.speakLowBGLimit.value = it }
                        }
                        if (proactive) {
                            FormDivider()
                            BGPickerRow("Fast Drop Delta", 3.0..20.0, Storage.speakFastDropDelta.state()) { Storage.speakFastDropDelta.value = it }
                        }
                        FormDivider()
                        StoredToggle("High", Storage.speakHighBG)
                        if (speakHigh) {
                            FormDivider()
                            BGPickerRow("High BG Limit", 140.0..300.0, Storage.speakHighBGLimit.state()) { Storage.speakHighBGLimit.value = it }
                        }
                    }
                }
            }
            formSection(header = "Diagnostics") {
                ToggleRow("Send anonymous usage stats", Storage.telemetryEnabled.state()) {
                    Storage.telemetryEnabled.value = it
                    if (it) TelemetryClient.scheduleRecurring()
                }
                FormDivider()
                NavigationRow("What's sent") { nav.push(Route.TelemetryPreview) }
                FormDivider()
                NavigationRow("Privacy") { nav.push(Route.TelemetryPrivacy) }
            }
        }
    }
}

private fun timeZoneLabel(tz: TimeZone): String {
    val offsetMinutes = tz.getOffset(System.currentTimeMillis()) / 60000
    val sign = if (offsetMinutes >= 0) "+" else "-"
    return String.format("(UTC%s%02d:%02d) %s", sign, abs(offsetMinutes) / 60, abs(offsetMinutes) % 60, tz.id)
}

@Composable
fun GraphSettingsScreen(onBack: () -> Unit) {
    val nightscout = Storage.url.state().isNotEmpty()
    val device = Storage.device.state()
    val iob = Storage.showIOBGraph.state()
    val cob = Storage.showCOBGraph.state()
    val sens = Storage.showSensitivityRatioGraph.state()
    DisposableEffect(Unit) { onDispose { applyGraphSettings() } }

    NavScreen("Graph Settings", onBack = onBack) {
        FormList {
            formSection(header = "Graph Display") {
                StoredToggle("Display Dots", Storage.showDots)
                FormDivider()
                StoredToggle("Display Lines", Storage.showLines)
                if (nightscout) {
                    FormDivider()
                    StoredToggle("Show DIA Lines", Storage.showDIALines)
                    FormDivider()
                    StoredToggle("Show −30 min Line", Storage.show30MinLine)
                    FormDivider()
                    StoredToggle("Show −90 min Line", Storage.show90MinLine)
                    FormDivider()
                    StoredToggle("Show Yesterday's BG", Storage.showYesterdayLine)
                }
                FormDivider()
                StoredToggle("Show Midnight Lines", Storage.showMidnightLines)
                FormDivider()
                StoredToggle("Show Prior Day Time Lines", Storage.showPriorDayTimeLines)
            }
            if (nightscout) {
                formSection(header = "Treatments") {
                    StoredToggle("Show Carb/Bolus Values", Storage.showValues)
                    FormDivider()
                    StoredToggle("Show Carb Absorption", Storage.showAbsorption)
                    FormDivider()
                    StoredToggle("Treatments on Small Graph", Storage.smallGraphTreatments)
                }
                formSection(header = "History Graphs") {
                    StoredToggle("Show IOB Graph", Storage.showIOBGraph)
                    FormDivider()
                    StoredToggle("Show COB Graph", Storage.showCOBGraph)
                    if (device != "Loop") {
                        FormDivider()
                        StoredToggle("Show Smoothed BG", Storage.showSmoothedBG)
                        FormDivider()
                        StoredToggle("Show Sensitivity Ratio Graph", Storage.showSensitivityRatioGraph)
                    }
                    if (iob || cob || sens) {
                        FormDivider()
                        MenuPickerRow("Curve Placement", Storage.historyCurvePlacement.state(), HistoryCurvePlacement.entries, { it.displayName }) { Storage.historyCurvePlacement.value = it }
                    }
                }
            }
            formSection(header = "Small Graph") {
                val h = Storage.smallGraphHeight.state()
                StepperRow("Height", h.toDouble(), 40.0..80.0, 5.0, "$h pt") { Storage.smallGraphHeight.value = it.toInt() }
            }
            if (nightscout) {
                formSection(header = "Prediction") {
                    val hours = Storage.predictionToLoad.state()
                    StepperRow("Hours of Prediction", hours, 0.0..6.0, 0.25, Localizer.formatToLocalizedString(hours, 2) + " h") { Storage.predictionToLoad.value = it }
                    if (device != "Loop") {
                        FormDivider()
                        MenuPickerRow("Prediction Style", Storage.predictionDisplayType.state(), PredictionDisplayType.entries, { it.displayName }) { Storage.predictionDisplayType.value = it }
                    }
                }
                formSection(header = "Basal / BG Scale") {
                    val minBasal = Storage.minBasalScale.state()
                    StepperRow("Min Basal", minBasal, 0.5..20.0, 0.5, Localizer.formatToLocalizedString(minBasal, 1) + " U/h") { Storage.minBasalScale.value = it }
                    FormDivider()
                    BGPickerRow("Min BG Scale", 40.0..400.0, Storage.minBGScale.state()) { Storage.minBGScale.value = it }
                }
                formSection(header = "History") {
                    val days = Storage.downloadDays.state()
                    StepperRow("Show Days Back", days.toDouble(), 1.0..4.0, 1.0, "$days d") { Storage.downloadDays.value = it.toInt() }
                }
            }
        }
    }
}

@Composable
fun UnitsSettingsScreen(nav: NavStack, onBack: () -> Unit) {
    subscribeUnitsState()
    NavScreen("Units and Metrics", onBack = onBack) {
        FormList {
            unitsGlucoseSection()
            unitsStatisticsSections(onRangeTap = { nav.push(Route.RangeMode) })
        }
    }
}

/** Recomposes the caller when any unit or metric setting changes. */
@Composable
fun subscribeUnitsState() {
    Storage.units.state()
    Storage.timeInRangeModeRaw.state()
    Storage.showGMI.state()
    Storage.useIFCC.state()
    Storage.showStdDev.state()
    Storage.lowLine.state()
    Storage.highLine.state()
}

fun LazyListScope.unitsGlucoseSection() {
    formSection(header = "Glucose") {
        FormRow {
            SegmentedPicker(GlucoseDisplayUnit.entries, UnitSettingsStore.glucoseUnit, { it.raw }, { UnitSettingsStore.glucoseUnit = it }, Modifier.fillMaxWidth())
        }
    }
}

fun LazyListScope.unitsStatisticsSections(onRangeTap: () -> Unit) {
    val unit = UnitSettingsStore.glucoseUnit
    formSection(header = "Range", footer = "Cut-offs in ${unit.raw}.") {
        val colors = LocalAppColors.current
        val mode = UnitSettingsStore.timeInRangeMode
        FormRow(onClick = onRangeTap) {
            FormText(mode.title)
            Spacer(Modifier.weight(1f))
            FormText(rangeBounds(mode), color = colors.secondaryText)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.gray)
        }
    }
    formSection(
        header = "Glycemic Metrics",
        footer = "eHbA1c — an A1c estimate from your average glucose. GMI — Glucose Management Indicator, another A1c estimate from average glucose. % and mmol/mol (IFCC) are two scales for the result.",
    ) {
        FormRow { SegmentedPicker(GlycemicMetricMode.entries, UnitSettingsStore.glycemicMetricMode, { it.raw }, { UnitSettingsStore.glycemicMetricMode = it }, Modifier.fillMaxWidth()) }
        FormRow { SegmentedPicker(GlycemicOutputUnit.entries, UnitSettingsStore.glycemicOutputUnit, { it.raw }, { UnitSettingsStore.glycemicOutputUnit = it }, Modifier.fillMaxWidth()) }
    }
    formSection(
        header = "Variability",
        footer = "Std Dev — Standard Deviation, how much glucose swings around the average. CV — Coefficient of Variation, that swing relative to the average (Std Dev ÷ mean).",
    ) {
        FormRow {
            SegmentedPicker(VariabilityMetricMode.entries, UnitSettingsStore.variabilityMetricMode, { if (it == VariabilityMetricMode.STD_DEVIATION) "Std Dev" else "CV" }, { UnitSettingsStore.variabilityMetricMode = it }, Modifier.fillMaxWidth())
        }
    }
}

private fun rangeBounds(mode: TimeInRangeDisplayMode): String {
    val (low, high) = mode.fixedThresholds ?: (Storage.lowLine.value to Storage.highLine.value)
    val unit = UnitSettingsStore.glucoseUnit
    val factor = if (unit == GlucoseDisplayUnit.MMOLL) GlucoseConversion.MGDL_TO_MMOL else 1.0
    val d = unit.fractionDigits
    return Localizer.formatToLocalizedString(low * factor, d, d) + "–" + Localizer.formatToLocalizedString(high * factor, d, d)
}

@Composable
fun RangeModeScreen(onBack: () -> Unit) {
    subscribeUnitsState()
    NavScreen("Range", onBack = onBack) {
        FormList { rangeModeSections(onBack) }
    }
}

/** Range mode choices; [onPicked] runs after choosing a fixed range. */
fun LazyListScope.rangeModeSections(onPicked: () -> Unit) {
    val selected = UnitSettingsStore.timeInRangeMode
    val low = Storage.lowLine.value
    val high = Storage.highLine.value
    formSection(footer = "Cut-offs in ${UnitSettingsStore.glucoseUnit.raw}.") {
        val colors = LocalAppColors.current
        TimeInRangeDisplayMode.entries.forEachIndexed { i, mode ->
            if (i > 0) FormDivider()
            FormRow(onClick = {
                UnitSettingsStore.timeInRangeMode = mode
                if (mode != TimeInRangeDisplayMode.CUSTOM) onPicked()
            }) {
                FormText(mode.title)
                Spacer(Modifier.weight(1f))
                FormText(rangeBounds(mode), color = colors.secondaryText)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.Check, null, tint = if (mode == selected) colors.blue else androidx.compose.ui.graphics.Color.Transparent)
            }
        }
    }
    if (selected == TimeInRangeDisplayMode.CUSTOM) {
        formSection(header = "Custom Range") {
            BGPickerRow("Low", 40.0..120.0, low) { Storage.lowLine.value = it }
            FormDivider()
            BGPickerRow("High", 120.0..400.0, high) { Storage.highLine.value = it }
        }
    }
}

@Composable
fun TabSettingsScreen(onBack: () -> Unit) {
    subscribeTabState()
    NavScreen("Tabs", onBack = onBack) {
        FormList { tabOrderSections() }
    }
}

@Composable
fun subscribeTabState() {
    TabItem.entries.forEach { Storage.positionValue(it).state() }
}

fun LazyListScope.tabOrderSections() {
    val tabBar = Storage.orderedTabBarItems()
    val order = tabBar + TabItem.featureOrder.filter { it !in tabBar }

    fun persist(items: List<TabItem>) {
        items.forEachIndexed { index, item ->
            val position = when (index) {
                0 -> TabPosition.POSITION1
                1 -> TabPosition.POSITION2
                2 -> TabPosition.POSITION3
                3 -> TabPosition.POSITION4
                else -> TabPosition.MENU
            }
            Storage.setPosition(position, item)
        }
    }

    formSection {
        val colors = LocalAppColors.current
        Column(Modifier.padding(16.dp)) {
            Text("Reorder with the arrows", fontSize = 15.sp, color = colors.primaryText)
            Text("The top 4 items appear in the tab bar. The Menu can always open every feature.", fontSize = 12.sp, color = colors.secondaryText)
        }
    }
    formSection(header = "Tab Order") {
        val colors = LocalAppColors.current
        order.forEachIndexed { index, item ->
            if (index > 0) FormDivider()
            if (index == 4) {
                FormRow {
                    Icon(Icons.Filled.Menu, null, tint = colors.secondaryText, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    FormText("Menu", color = colors.secondaryText, modifier = Modifier.weight(1f))
                    Badge("Tab 5")
                }
                FormDivider()
            }
            FormRow {
                Icon(item.icon, null, tint = if (index < 4) colors.blue else colors.secondaryText, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                FormText(item.displayName, modifier = Modifier.weight(1f))
                if (index < 4) Badge("Tab ${index + 1}", colors.blue) else Text("In Menu", fontSize = 12.sp, color = colors.secondaryText)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.KeyboardArrowUp, "Move up", tint = if (index > 0) colors.blue else colors.gray, modifier = Modifier.size(28.dp).clip(CircleShape).let { m ->
                    if (index > 0) m.clickableNoRipple { persist(order.toMutableList().apply { add(index - 1, removeAt(index)) }) } else m
                })
                Icon(Icons.Filled.KeyboardArrowDown, "Move down", tint = if (index < order.size - 1) colors.blue else colors.gray, modifier = Modifier.size(28.dp).clip(CircleShape).let { m ->
                    if (index < order.size - 1) m.clickableNoRipple { persist(order.toMutableList().apply { add(index + 1, removeAt(index)) }) } else m
                })
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color = LocalAppColors.current.gray) {
    Text(
        text,
        fontSize = 12.sp,
        color = androidx.compose.ui.graphics.Color.White,
        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).background(color).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun InfoDisplaySettingsScreen(nav: NavStack, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val items = Storage.infoDisplayItems.state()
    DisposableEffect(Unit) { onDispose { MainEngine.refresh() } }
    NavScreen("Information Display Settings", onBack = onBack) {
        FormList {
            formSection(header = "General") {
                StoredToggle("Hide Information Table", Storage.hideInfoTable)
            }
            formSection(header = "Information Display Settings", footer = "Use the arrows to reorder. Tap a row to set its visibility and colors.") {
                items.forEachIndexed { index, item ->
                    if (index > 0) FormDivider()
                    FormRow(onClick = { nav.push(Route.InfoRow(item.type)) }) {
                        FormText(item.type.displayName, modifier = Modifier.weight(1f))
                        if (item.type.isColorable && item.coloring.enabled) {
                            Spacer(Modifier.size(8.dp).clip(CircleShape).background(colors.orange))
                            Spacer(Modifier.width(8.dp))
                        }
                        FormText(if (item.isVisible) "On" else "Off", color = colors.secondaryText)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.KeyboardArrowUp, "Move up", tint = if (index > 0) colors.blue else colors.gray, modifier = Modifier.size(28.dp).let { m ->
                            if (index > 0) m.clickableNoRipple { Storage.infoDisplayItems.value = items.toMutableList().apply { add(index - 1, removeAt(index)) } } else m
                        })
                        Icon(Icons.Filled.KeyboardArrowDown, "Move down", tint = if (index < items.size - 1) colors.blue else colors.gray, modifier = Modifier.size(28.dp).let { m ->
                            if (index < items.size - 1) m.clickableNoRipple { Storage.infoDisplayItems.value = items.toMutableList().apply { add(index + 1, removeAt(index)) } } else m
                        })
                    }
                }
            }
        }
    }
}

@Composable
fun InfoRowSettingsScreen(type: InfoType, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val items = Storage.infoDisplayItems.state()
    val item = items.firstOrNull { it.type == type } ?: return
    fun update(transform: (com.loopandlearn.loopfollow.info.InfoDisplayItem) -> com.loopandlearn.loopfollow.info.InfoDisplayItem) {
        Storage.infoDisplayItems.value = Storage.infoDisplayItems.value.map { if (it.type == type) transform(it) else it }
    }
    NavScreen(type.displayName, onBack = onBack) {
        FormList {
            formSection {
                ToggleRow("Show in Info Display", item.isVisible) { v -> update { it.copy(isVisible = v) } }
            }
            val config = type.colorConfig
            if (config != null) {
                val footer = if (config.direction == InfoColorDirection.ABOVE) {
                    "The value turns yellow at or above the yellow level and red at or above the red level. In range it shows green. A visual cue only — it never triggers an alarm."
                } else {
                    "The value turns yellow at or below the yellow level and red at or below the red level. In range it shows green. A visual cue only — it never triggers an alarm."
                }
                formSection(header = "Color", footer = footer) {
                    ToggleRow("Enable coloring", item.coloring.enabled) { on ->
                        update {
                            it.copy(coloring = it.coloring.copy(
                                enabled = on,
                                warning = if (on) it.coloring.warning ?: config.defaultWarning else it.coloring.warning,
                                urgent = if (on) it.coloring.urgent ?: config.defaultUrgent else it.coloring.urgent,
                            ))
                        }
                    }
                    if (item.coloring.enabled) {
                        fun fmt(v: Double) = Localizer.formatToLocalizedString(v, config.fractionDigits, config.fractionDigits) + " " + config.unit
                        val warning = item.coloring.warning ?: config.defaultWarning
                        val urgent = item.coloring.urgent ?: config.defaultUrgent
                        FormDivider()
                        StepperRow("Yellow at", warning, config.range, config.step, fmt(warning)) { v -> update { it.copy(coloring = it.coloring.copy(warning = v)) } }
                        FormDivider()
                        StepperRow("Red at", urgent, config.range, config.step, fmt(urgent)) { v -> update { it.copy(coloring = it.coloring.copy(urgent = v)) } }
                        val problem = when (config.direction) {
                            InfoColorDirection.ABOVE -> if (urgent < warning) "Red should be at or above yellow." else null
                            InfoColorDirection.BELOW -> if (urgent > warning) "Red should be at or below yellow." else null
                        }
                        if (problem != null) {
                            FormDivider()
                            FormRow {
                                Icon(Icons.Filled.Warning, null, tint = colors.orange, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(problem, fontSize = 13.sp, color = colors.orange)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdvancedSettingsScreen(onBack: () -> Unit) {
    val delay = Storage.bgUpdateDelay.state()
    DisposableEffect(Unit) { onDispose { applyGraphSettings() } }
    NavScreen("Advanced Settings", onBack = onBack) {
        FormList {
            formSection(header = "Advanced Settings") {
                StoredToggle("Download Treatments", Storage.downloadTreatments)
                FormDivider()
                StoredToggle("Graph Basal", Storage.graphBasal)
                FormDivider()
                StoredToggle("Graph Bolus", Storage.graphBolus)
                FormDivider()
                StoredToggle("Graph Carbs", Storage.graphCarbs)
                FormDivider()
                StoredToggle("Graph Other Treatments", Storage.graphOtherTreatments)
                FormDivider()
                StepperRow("BG Update Delay (Sec)", delay.toDouble(), 1.0..30.0, 1.0, "$delay") { Storage.bgUpdateDelay.value = it.toInt() }
            }
            formSection(header = "Logging Options") {
                StoredToggle("Debug Log Level", Storage.debugLogLevel)
            }
        }
    }
}

/** The alarm permissions, re-read each time the screen resumes (the user may come back from system settings). */
@Composable
private fun rememberAlarmPermissions(): AlarmPermissions.State {
    val context = LocalContext.current
    var state by remember { mutableStateOf(AlarmPermissions.state(context)) }
    LifecycleResumeEffect(Unit) {
        state = AlarmPermissions.state(context)
        AlarmPermissions.updateBanner(context)
        onPauseOrDispose {}
    }
    return state
}

/** Notification and full-screen alert rows for alarms; with [onlyWhenDenied], shown only while one is denied. */
fun LazyListScope.alarmPermissionSection(onlyWhenDenied: Boolean) {
    item {
        val context = LocalContext.current
        val colors = LocalAppColors.current
        val state = rememberAlarmPermissions()
        if (onlyWhenDenied && state.allGranted) return@item
        FormSection(
            header = "Alarm Notifications",
            footer = "Alarm sounds play either way. Notifications show the alarm with its Snooze button, and full-screen alerts open the Snoozer over the lock screen.",
        ) {
            @Composable
            fun row(title: String, allowed: Boolean, intent: () -> Intent) {
                FormRow(onClick = if (allowed) null else { { context.startActivity(intent()) } }) {
                    if (!allowed) {
                        Icon(Icons.Filled.Warning, null, tint = colors.orange, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    FormText(title)
                    Spacer(Modifier.weight(1f))
                    FormText(if (allowed) "Allowed" else "Tap to allow", color = if (allowed) colors.green else colors.blue)
                }
            }
            row("Notifications", state.notifications) { AlarmPermissions.notificationSettingsIntent(context) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                FormDivider()
                row("Full-screen alerts", state.fullScreen) { AlarmPermissions.fullScreenSettingsIntent(context) }
            }
        }
    }
}

@Composable
fun BackgroundRefreshSettingsScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val type = Storage.backgroundRefreshType.state()
    val pm = context.getSystemService(PowerManager::class.java)
    val unrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)
    NavScreen("Background Refresh", onBack = onBack) {
        FormList {
            formSection(
                header = "Background Refresh",
                footer = "With the background service on, LoopFollow keeps downloading and checking alarms while the app is closed, and shows the latest glucose in an ongoing notification. Without it, alarms only work while the app is open.",
            ) {
                MenuPickerRow("Mode", type, BackgroundRefreshType.entries, { if (it == BackgroundRefreshType.NONE) "None" else "Background Service" }) {
                    Storage.backgroundRefreshType.value = it
                    if (it == BackgroundRefreshType.NONE) LoopFollowService.stop(context) else LoopFollowService.start(context)
                }
            }
            formSection(
                header = "Battery Optimization",
                footer = "Android may pause apps to save battery. Allow LoopFollow to run unrestricted so background downloads and alarms are not delayed.",
            ) {
                FormRow(onClick = {
                    if (!unrestricted) {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                    }
                }) {
                    FormText("Unrestricted battery use")
                    Spacer(Modifier.weight(1f))
                    FormText(if (unrestricted) "Allowed" else "Tap to allow", color = if (unrestricted) colors.green else colors.blue)
                }
            }
            alarmPermissionSection(onlyWhenDenied = false)
        }
    }
}

