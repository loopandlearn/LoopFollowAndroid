package com.loopandlearn.loopfollow.ui.alarms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.alarm.AlarmManager
import com.loopandlearn.loopfollow.alarm.TimeUnit
import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.storage.GlucoseDisplayUnit
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.components.AutoSizeText
import com.loopandlearn.loopfollow.ui.components.StepperButtons
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SnoozerScreen() {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val bgText by Observable.bgText.flow.collectAsStateWithLifecycle()
    val bgColor by Observable.bgTextColor.flow.collectAsStateWithLifecycle()
    val stale by Observable.bgStale.flow.collectAsStateWithLifecycle()
    val direction by Observable.directionText.flow.collectAsStateWithLifecycle()
    val delta by Observable.deltaText.flow.collectAsStateWithLifecycle()
    val minAgo by Observable.minAgoText.flow.collectAsStateWithLifecycle()
    val sensorState by Observable.cgmSensorState.flow.collectAsStateWithLifecycle()
    val bg by Observable.bg.flow.collectAsStateWithLifecycle()
    val emoji by Storage.snoozerEmoji.flow.collectAsStateWithLifecycle()
    val cfg by Storage.alarmConfiguration.flow.collectAsStateWithLifecycle()
    val currentAlarmId by Observable.currentAlarm.flow.collectAsStateWithLifecycle()
    val titleOverride by Observable.currentAlarmTitleOverride.flow.collectAsStateWithLifecycle()
    val alarms by Storage.alarms.flow.collectAsStateWithLifecycle()
    val activeAlarm = alarms.firstOrNull { it.id == currentAlarmId }

    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = System.currentTimeMillis()
            delay(1000)
        }
    }
    val globalSnooze = (cfg.snoozeUntil ?: 0.0) > clock / 1000.0

    var showBar by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    LaunchedEffect(interaction, globalSnooze, activeAlarm?.id) {
        if (globalSnooze || activeAlarm != null) {
            showBar = true
        } else {
            delay(5000)
            showBar = false
        }
    }

    var snoozeUnits by remember(activeAlarm?.id) { mutableIntStateOf(activeAlarm?.snoozeDuration ?: 5) }

    Box(Modifier.fillMaxSize().background(Color.Black).clickable(indication = null, interactionSource = null) {
        showBar = true
        interaction++
    }) {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(showBar || globalSnooze, enter = slideInVertically() + fadeIn(), exit = slideOutVertically() + fadeOut()) {
                SnoozerBar(globalSnooze, cfg.snoozeUntil, isNightNow(), onActivate = {
                    if (activeAlarm != null) AlarmManager.performSnooze(snoozeUnits)
                    Storage.alarmConfiguration.value = Storage.alarmConfiguration.value.copy(snoozeUntil = nowSeconds() + 3600)
                    interaction++
                }, onAdjust = { minutes ->
                    val current = Storage.alarmConfiguration.value.snoozeUntil ?: return@SnoozerBar
                    val next = current + minutes * 60
                    Storage.alarmConfiguration.value = Storage.alarmConfiguration.value.copy(snoozeUntil = if (next <= nowSeconds()) null else next)
                }, onEnd = {
                    Storage.alarmConfiguration.value = Storage.alarmConfiguration.value.copy(snoozeUntil = null)
                    interaction++
                }, onPick = {
                    pickDateTime(context, cfg.snoozeUntil ?: (nowSeconds() + 3600)) { t ->
                        Storage.alarmConfiguration.value = Storage.alarmConfiguration.value.copy(snoozeUntil = t)
                    }
                })
            }

            val barShowing = showBar || globalSnooze
            val bgDisplayColor = colors.resolve(bgColor).let { if (it == colors.primaryText) Color.White else it }
            val clockText = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(clock))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val landscape = maxWidth > maxHeight
                if (landscape) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = if (barShowing) 0.dp else 16.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            AutoSizeText(
                                bgText,
                                maxFontSize = if (barShowing) 100.sp else 120.sp,
                                fontWeight = FontWeight.Black,
                                color = bgDisplayColor,
                                textDecoration = if (stale) TextDecoration.LineThrough else null,
                                modifier = Modifier.fillMaxWidth(),
                                minScale = 0.5f,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                com.loopandlearn.loopfollow.ui.home.DirectionArrows(direction, Color.White, if (barShowing) 44.dp else 52.dp, if (barShowing) 40.sp else 48.sp)
                                Text(delta, fontSize = if (barShowing) 40.sp else 48.sp, color = Color.White, maxLines = 1)
                            }
                            AutoSizeText(minAgo, maxFontSize = if (barShowing) 28.sp else 34.sp, color = Color.White, modifier = Modifier.fillMaxWidth(), minScale = 0.5f)
                            sensorState?.let {
                                Text("CGM: ${it.summary}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.orange, textAlign = TextAlign.Center, maxLines = 2)
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            if (emoji) Text(bgEmoji(bg, stale, bgText), fontSize = 96.sp)
                            AutoSizeText(clockText, maxFontSize = 70.sp, color = Color.White, modifier = Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = if (barShowing) 0.dp else 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AutoSizeText(
                            bgText,
                            maxFontSize = if (barShowing) 170.sp else 200.sp,
                            fontWeight = FontWeight.Black,
                            color = bgDisplayColor,
                            textDecoration = if (stale) TextDecoration.LineThrough else null,
                            modifier = Modifier.fillMaxWidth(),
                            minScale = 0.3f,
                        )
                        com.loopandlearn.loopfollow.ui.home.DirectionArrows(direction, Color.White, if (barShowing) 90.dp else 120.dp, if (barShowing) 80.sp else 100.sp)
                        AutoSizeText(delta, maxFontSize = if (barShowing) 70.sp else 90.sp, color = Color.White, modifier = Modifier.fillMaxWidth(), minScale = 0.3f)
                        AutoSizeText(minAgo, maxFontSize = if (barShowing) 50.sp else 60.sp, color = Color.White, modifier = Modifier.fillMaxWidth(), minScale = 0.3f)
                        sensorState?.let {
                            Text("CGM: ${it.summary}", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = colors.orange, textAlign = TextAlign.Center, maxLines = 2)
                        }
                        Spacer(Modifier.weight(1f))
                        if (emoji) Text(bgEmoji(bg, stale, bgText), fontSize = 96.sp)
                        AutoSizeText(clockText, maxFontSize = 90.sp, color = Color.White, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }

        if (activeAlarm != null) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(Color(0xE61C1C1E)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AutoSizeText(titleOverride ?: activeAlarm.name, maxFontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(top = 20.dp, start = 16.dp, end = 16.dp).fillMaxWidth())
                HorizontalDivider(color = colors.separator)
                if (activeAlarm.type.snoozeTimeUnit != TimeUnit.NONE) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Snooze for", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text("$snoozeUnits ${activeAlarm.type.snoozeTimeUnit.label}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        val range = activeAlarm.type.snoozeRange
                        val step = activeAlarm.type.snoozeStep
                        StepperButtons(
                            canDecrement = snoozeUnits - step >= range.first,
                            canIncrement = snoozeUnits + step <= range.last,
                            onDecrement = { snoozeUnits -= step },
                            onIncrement = { snoozeUnits += step },
                        )
                    }
                }
                Box(
                    Modifier
                        .padding(start = 24.dp, end = 24.dp, bottom = 20.dp)
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clip(CircleShape)
                        .background(colors.orange)
                        .clickable { AlarmManager.performSnooze(snoozeUnits) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (snoozeUnits == 0) "Acknowledge" else "Snooze", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

private fun isNightNow(): Boolean = AlarmManager.isNight(Storage.alarmConfiguration.value, nowSeconds())

@Composable
private fun SnoozerBar(
    active: Boolean,
    until: Double?,
    isNight: Boolean,
    onActivate: () -> Unit,
    onAdjust: (Int) -> Unit,
    onEnd: () -> Unit,
    onPick: () -> Unit,
) {
    val colors = LocalAppColors.current
    val phaseIcon = if (isNight) Icons.Outlined.DarkMode else Icons.Outlined.LightMode
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (active) {
            val target = until ?: (nowSeconds() + 3600)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.NotificationsOff, null, tint = colors.red, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("All alerts snoozed", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("📅 " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date((target * 1000).toLong())), onPick)
                        Chip("🕑 " + DateTimeUtils.shortTime(target), onPick)
                    }
                }
                Icon(phaseIcon, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("− 30m", Color.White.copy(alpha = 0.12f)) { onAdjust(-30) }
                Pill("+ 30m", Color.White.copy(alpha = 0.12f)) { onAdjust(30) }
                Pill("End now", colors.red.copy(alpha = 0.6f), onEnd)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .widthIn(min = 230.dp)
                        .heightIn(min = 54.dp)
                        .clip(CircleShape)
                        .background(colors.orange)
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        .clickable(onClick = onActivate)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Outlined.NotificationsOff, null, tint = Color.White)
                    Spacer(Modifier.size(10.dp))
                    Text("Snooze all · 1h", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Icon(phaseIcon, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 15.sp,
        color = Color.White.copy(alpha = 0.9f),
        modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun Pill(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 44.dp).widthIn(min = 90.dp).clip(CircleShape).background(color).clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

private fun bgEmoji(bg: Int?, stale: Boolean, bgText: String): String {
    if (bg == null || stale) return "🤷"
    if (UnitSettingsStore.glucoseUnit == GlucoseDisplayUnit.MMOLL && Localizer.removePeriodAndCommaForBadge(bgText) == "55") return "🦄"
    if (UnitSettingsStore.glucoseUnit == GlucoseDisplayUnit.MGDL && bg == 100) return "🦄"
    val table = listOf(
        40 to "❌", 55 to "🥶", 73 to "😱", 98 to "😊", 102 to "🥇", 109 to "😎", 127 to "🥳", 145 to "🤔",
        163 to "😳", 181 to "😵‍💫", 199 to "🎃", 217 to "🙀", 235 to "🔥", 253 to "😬", 271 to "😡", 289 to "🤬",
        307 to "🥵", 325 to "🫣", 343 to "😩", 361 to "🤯",
    )
    return table.firstOrNull { bg < it.first }?.second ?: "👿"
}
