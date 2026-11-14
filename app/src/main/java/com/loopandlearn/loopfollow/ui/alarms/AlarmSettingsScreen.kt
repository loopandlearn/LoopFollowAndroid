package com.loopandlearn.loopfollow.ui.alarms

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.alarm.AlarmConfiguration
import com.loopandlearn.loopfollow.alarm.TimeOfDay
import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.components.StepperRow
import com.loopandlearn.loopfollow.ui.components.ToggleRow
import com.loopandlearn.loopfollow.ui.components.formSection
import com.loopandlearn.loopfollow.ui.settings.alarmPermissionSection
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import java.util.Calendar

/** Picks a date, then a time, starting from [initial] (epoch s). */
fun pickDateTime(context: Context, initial: Double, onPicked: (Double) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = (initial * 1000).toLong() }
    DatePickerDialog(context, { _, y, m, d ->
        TimePickerDialog(context, { _, h, min ->
            val result = Calendar.getInstance().apply {
                set(y, m, d, h, min, 0)
                set(Calendar.MILLISECOND, 0)
            }
            onPicked(result.timeInMillis / 1000.0)
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), DateFormat.is24HourFormat(context)).show()
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
}

fun pickTime(context: Context, initial: TimeOfDay, onPicked: (TimeOfDay) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPicked(TimeOfDay(h, m)) }, initial.hour, initial.minute, DateFormat.is24HourFormat(context)).show()
}

fun formatTimeOfDay(context: Context, t: TimeOfDay): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, t.hour)
        set(Calendar.MINUTE, t.minute)
    }
    return DateTimeUtils.shortTime(cal.timeInMillis / 1000.0)
}

@Composable
fun AlarmSettingsScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val cfg by Storage.alarmConfiguration.flow.collectAsStateWithLifecycle()
    fun update(block: (AlarmConfiguration) -> AlarmConfiguration) {
        Storage.alarmConfiguration.value = block(Storage.alarmConfiguration.value)
    }
    val now = nowSeconds()

    NavScreen("Alarm Settings", onBack = onBack) {
        FormList {
            alarmPermissionSection(onlyWhenDenied = true)
            formSection(
                header = "Snooze & Mute Options",
                footer = "“Snooze All” disables every alarm. “Mute All” silences phone sounds but still vibrates and shows notifications.",
            ) {
                val snoozed = (cfg.snoozeUntil ?: 0.0) > now
                ToggleRow("All Alerts Snoozed", snoozed) { on ->
                    update { if (on) it.copy(snoozeUntil = if ((it.snoozeUntil ?: 0.0) <= nowSeconds()) nowSeconds() + 3600 else it.snoozeUntil) else it.copy(snoozeUntil = null) }
                }
                if (snoozed) {
                    FormDivider()
                    FormRow(onClick = { pickDateTime(context, cfg.snoozeUntil ?: now) { t -> update { it.copy(snoozeUntil = t) } } }) {
                        FormText("Until")
                        Spacer(Modifier.weight(1f))
                        Text(DateTimeUtils.formattedDate(cfg.snoozeUntil), fontSize = 17.sp, color = colors.blue)
                    }
                }
                FormDivider()
                val muted = (cfg.muteUntil ?: 0.0) > now
                ToggleRow("All Sounds Muted", muted) { on ->
                    update { if (on) it.copy(muteUntil = if ((it.muteUntil ?: 0.0) <= nowSeconds()) nowSeconds() + 3600 else it.muteUntil) else it.copy(muteUntil = null) }
                }
                if (muted) {
                    FormDivider()
                    FormRow(onClick = { pickDateTime(context, cfg.muteUntil ?: now) { t -> update { it.copy(muteUntil = t) } } }) {
                        FormText("Until")
                        Spacer(Modifier.weight(1f))
                        Text(DateTimeUtils.formattedDate(cfg.muteUntil), fontSize = 17.sp, color = colors.blue)
                    }
                }
            }

            formSection(
                header = "Day / Night Schedule",
                footer = "Pick when your day period begins and when your night period begins. Any time from your Day-starts time up until your Night-starts time will count as day; from Night-starts until the next Day-starts will count as night.",
            ) {
                FormRow(onClick = { pickTime(context, cfg.dayStart) { t -> update { it.copy(dayStart = t) } } }) {
                    FormText("Day starts")
                    Spacer(Modifier.weight(1f))
                    Text(formatTimeOfDay(context, cfg.dayStart), fontSize = 17.sp, color = colors.blue)
                }
                FormDivider()
                FormRow(onClick = { pickTime(context, cfg.nightStart) { t -> update { it.copy(nightStart = t) } } }) {
                    FormText("Night starts")
                    Spacer(Modifier.weight(1f))
                    Text(formatTimeOfDay(context, cfg.nightStart), fontSize = 17.sp, color = colors.blue)
                }
            }

            formSection(header = "Alarm Settings") {
                ToggleRow("Override System Volume", cfg.overrideSystemOutputVolume) { v -> update { it.copy(overrideSystemOutputVolume = v) } }
                if (cfg.overrideSystemOutputVolume) {
                    FormDivider()
                    StepperRow("Volume Level", cfg.forcedOutputVolume.toDouble(), 0.0..1.0, 0.05, "${(cfg.forcedOutputVolume * 100).toInt()}%") { v ->
                        update { it.copy(forcedOutputVolume = v.toFloat()) }
                    }
                }
                FormDivider()
                ToggleRow("Audio During Calls", cfg.audioDuringCalls) { v -> update { it.copy(audioDuringCalls = v) } }
                FormDivider()
                ToggleRow("Ignore Zero BG", cfg.ignoreZeroBG) { v -> update { it.copy(ignoreZeroBG = v) } }
                FormDivider()
                ToggleRow("Auto‑Snooze CGM Start", cfg.autoSnoozeCGMStart) { v -> update { it.copy(autoSnoozeCGMStart = v) } }
                FormDivider()
                ToggleRow("Volume Buttons Snooze Alarms", cfg.enableVolumeButtonSnooze) { v -> update { it.copy(enableVolumeButtonSnooze = v) } }
            }
        }
    }
}
