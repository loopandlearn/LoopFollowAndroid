package com.loopandlearn.loopfollow.ui.alarms

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.alarm.Alarm
import com.loopandlearn.loopfollow.alarm.AlarmSound
import com.loopandlearn.loopfollow.alarm.AlarmType
import com.loopandlearn.loopfollow.alarm.CustomSoundStore
import com.loopandlearn.loopfollow.alarm.DayNightOption
import com.loopandlearn.loopfollow.alarm.SoundCatalog
import com.loopandlearn.loopfollow.alarm.SoundFile
import com.loopandlearn.loopfollow.engine.cFormat
import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.NavStack
import com.loopandlearn.loopfollow.ui.Route
import com.loopandlearn.loopfollow.ui.components.BGPickerRow
import com.loopandlearn.loopfollow.ui.components.ButtonRow
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.components.InfoBanner
import com.loopandlearn.loopfollow.ui.components.MenuPickerRow
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.components.StepperRow
import com.loopandlearn.loopfollow.ui.components.TextFieldRow
import com.loopandlearn.loopfollow.ui.components.ToggleRow
import com.loopandlearn.loopfollow.ui.components.clickableNoRipple
import com.loopandlearn.loopfollow.ui.components.formSection
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors

@Composable
fun AlarmListScreen(nav: NavStack, onBack: (() -> Unit)?) {
    val colors = LocalAppColors.current
    val alarms by Storage.alarms.flow.collectAsStateWithLifecycle()
    val now = remember(alarms) { nowSeconds() }

    val snoozed = alarms.filter { (it.snoozedUntil ?: 0.0) > now && it.isEnabled }.sortedWith(Alarm.byPriorityThenSpec)
    val active = alarms.filter { it.isEnabled && (it.snoozedUntil ?: 0.0) <= now }.sortedWith(Alarm.byPriorityThenSpec)
    val inactive = alarms.filter { !it.isEnabled }.sortedWith(Alarm.byPriorityThenSpec)

    NavScreen("Alarms", onBack = onBack, actions = {
        Icon(Icons.Outlined.Settings, "Alarm Settings", tint = colors.blue, modifier = Modifier.padding(6.dp).size(24.dp).clickableNoRipple { nav.push(Route.AlarmSettings) })
        Icon(Icons.Filled.Add, "Add Alarm", tint = colors.blue, modifier = Modifier.padding(6.dp).size(26.dp).clickableNoRipple { nav.push(Route.AddAlarm) })
    }) {
        FormList {
            for ((title, list) in listOf("Snoozed" to snoozed, "Active" to active, "Inactive" to inactive)) {
                if (list.isEmpty()) continue
                formSection(header = title) {
                    list.forEachIndexed { index, alarm ->
                        if (index > 0) FormDivider()
                        AlarmRow(alarm, now, dimmed = title == "Inactive") { nav.push(Route.EditAlarm(alarm.id, isNew = false)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlarmRow(alarm: Alarm, now: Double, dimmed: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val alpha = if (dimmed) 0.6f else 1f
    FormRow(onClick = onClick) {
        Icon(alarm.type.icon, null, tint = colors.primaryText.copy(alpha = alpha), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            FormText(alarm.name, color = colors.primaryText.copy(alpha = alpha))
            val until = alarm.snoozedUntil
            if (until != null && until > now) {
                Text("💤 Snoozed until ${DateTimeUtils.shortTime(until)}", fontSize = 12.sp, color = colors.secondaryText)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.gray)
    }
}

@Composable
fun AddAlarmScreen(nav: NavStack) {
    val colors = LocalAppColors.current
    NavScreen("Add Alarm", onBack = { nav.pop() }) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.fillMaxSize().background(colors.groupedBackground).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            for (group in AlarmType.Group.entries) {
                val types = AlarmType.entries.filter { it.group == group }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(group.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
                }
                items(types) { type ->
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.cell)
                            .clickable {
                                val alarm = Alarm.create(type)
                                Storage.alarms.value = Storage.alarms.value + alarm
                                nav.pop()
                                nav.push(Route.EditAlarm(alarm.id, isNew = true))
                            }
                            .heightIn(min = 110.dp)
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(type.icon, null, tint = colors.blue, modifier = Modifier.size(28.dp))
                        Text(type.raw, fontSize = 15.sp, color = colors.primaryText, textAlign = TextAlign.Center, maxLines = 2)
                        Text(type.blurb, fontSize = 11.sp, color = colors.secondaryText, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
fun AlarmEditorScreen(nav: NavStack, alarmId: String, isNew: Boolean) {
    val colors = LocalAppColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val original = remember(alarmId) { Storage.alarms.value.firstOrNull { it.id == alarmId } }
    if (original == null) {
        NavScreen("Alarm", onBack = { nav.pop() }) { Text("Alarm not found", Modifier.padding(16.dp)) }
        return
    }
    var alarm by remember(alarmId) { mutableStateOf(original) }
    var askDelete by remember { mutableStateOf(false) }
    var pickingTone by remember { mutableStateOf(false) }
    val stored by Storage.alarms.flow.collectAsStateWithLifecycle()

    fun save() {
        Storage.alarms.value = Storage.alarms.value.map { if (it.id == alarm.id) alarm else it }
    }

    // Every change is stored at once, so leaving the editor any way keeps it; a change made elsewhere while
    // the editor is open (a snooze, an alarm disabling itself) shows here.
    LaunchedEffect(alarm) {
        if (Storage.alarms.value.firstOrNull { it.id == alarm.id } != alarm) save()
    }
    LaunchedEffect(stored) {
        stored.firstOrNull { it.id == alarmId }?.let { if (it != alarm) alarm = it }
    }
    fun remove() {
        Storage.alarms.value = Storage.alarms.value.filterNot { it.id == alarmId }
    }

    if (pickingTone) {
        TonePickerScreen(alarm.soundFile, onSelect = { alarm = alarm.copy(soundFile = it) }, onDone = {
            AlarmSound.stop()
            pickingTone = false
        })
        return
    }

    val spec = editorSpec(alarm.type, alarm)
    NavScreen(alarm.type.raw, onBack = null, leading = if (isNew) {
        {
            Text("Cancel", color = colors.blue, fontSize = 17.sp, modifier = Modifier.padding(4.dp).clickableNoRipple {
                remove()
                nav.pop()
            })
        }
    } else null, actions = {
        Text("Done", color = colors.blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp).clickableNoRipple {
            save()
            nav.pop()
        })
    }) {
        FormList {
            item { InfoBanner(spec.info, alarm.type.icon) }

            formSection(
                header = "General",
                footer = "Give each alarm a unique name—especially if you’ve added more than one of the same kind—so you can tell them apart at a glance.",
            ) {
                TextFieldRow(alarm.name, { alarm = alarm.copy(name = it) }, "Alarm Name", label = "Name")
                FormDivider()
                ToggleRow("Enabled", alarm.isEnabled) { alarm = alarm.copy(isEnabled = it) }
            }

            for (section in spec.sections) {
                formSection(header = section.header, footer = section.footer) {
                    SectionContent(section, alarm) { alarm = it }
                }
            }

            if (alarm.type == AlarmType.TEMPORARY && alarm.belowBG == null && alarm.aboveBG == null) {
                item { Text("⚠️ Please enable at least one limit.", color = colors.red, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) }
            }

            formSection(header = "Active During") {
                MenuPickerRow("Active", alarm.activeOption, listOf(DayNightOption.ALWAYS, DayNightOption.DAY, DayNightOption.NIGHT), { it.displayName }) { active ->
                    var updated = alarm.copy(activeOption = active)
                    val playAllowed = DayNightOption.allowed(active)
                    if (updated.playSoundOption !in playAllowed) updated = updated.copy(playSoundOption = playAllowed.last())
                    if (updated.repeatSoundOption !in playAllowed) updated = updated.copy(repeatSoundOption = playAllowed.last())
                    alarm = updated
                }
            }

            formSection(header = "Alert Sound") {
                FormRow(onClick = { pickingTone = true }) {
                    FormText("Tone")
                    Spacer(Modifier.weight(1f))
                    FormText(alarm.soundFile.displayName, color = colors.secondaryText)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.gray)
                }
                FormDivider()
                MenuPickerRow("Play", alarm.playSoundOption, DayNightOption.allowed(alarm.activeOption), { it.displayName }) { alarm = alarm.copy(playSoundOption = it) }
                if (!spec.hideRepeat) {
                    FormDivider()
                    MenuPickerRow("Repeat", alarm.repeatSoundOption, DayNightOption.allowed(alarm.activeOption), { it.displayName }) { alarm = alarm.copy(repeatSoundOption = it) }
                }
                FormDivider()
                StepperRow(
                    "Delay Between Sounds", alarm.soundDelay.toDouble(), 0.0..60.0, 5.0,
                    if (alarm.soundDelay == 0) "Off" else "${alarm.soundDelay} sec",
                ) { alarm = alarm.copy(soundDelay = it.toInt()) }
            }

            if (spec.showSnooze) {
                formSection(
                    header = "SNOOZE",
                    footer = "“Default Snooze” controls the default value for how long the alert stays quiet after you press Snooze. A snooze duration of 0 means the alarm is acknowledged (silenced), and will alert again next time the condition applies, without time limitation. Toggle “Snoozed” to mute this alarm right now.",
                ) {
                    val range = alarm.type.snoozeRange
                    StepperRow(
                        "Default Snooze:", alarm.snoozeDuration.toDouble(), range.first.toDouble()..range.last.toDouble(), alarm.type.snoozeStep.toDouble(),
                        "${alarm.snoozeDuration} ${alarm.type.snoozeTimeUnit.label}",
                    ) { alarm = alarm.copy(snoozeDuration = it.toInt()) }
                    FormDivider()
                    val snoozed = (alarm.snoozedUntil ?: 0.0) > nowSeconds()
                    ToggleRow("Snoozed", snoozed) { on ->
                        alarm = if (on) {
                            alarm.copy(snoozedUntil = nowSeconds() + alarm.snoozeDuration * alarm.type.snoozeTimeUnit.seconds)
                        } else {
                            alarm.copy(snoozedUntil = null)
                        }
                    }
                    if (snoozed) {
                        FormDivider()
                        FormRow(onClick = { pickDateTime(context, alarm.snoozedUntil ?: nowSeconds()) { t -> alarm = alarm.copy(snoozedUntil = t) } }) {
                            FormText("Until")
                            Spacer(Modifier.weight(1f))
                            Text(DateTimeUtils.formattedDate(alarm.snoozedUntil), fontSize = 17.sp, color = colors.blue)
                        }
                    }
                }
            }

            if (!isNew) {
                formSection {
                    ButtonRow("Delete Alarm", color = colors.red, centered = true) { askDelete = true }
                }
            }
        }
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Delete this alarm?") },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    remove()
                    nav.pop()
                }) { Text("Delete", color = colors.red) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionContent(section: EditorSection, alarm: Alarm, onChange: (Alarm) -> Unit) {
    when (section) {
        is EditorSection.Stepper -> {
            val value = section.get(alarm) ?: section.range.start
            val decimals = if (section.step % 1.0 == 0.0) 0 else section.step.toString().substringAfter('.').length
            StepperRow(section.title, value, section.range, section.step, cFormat(value, decimals) + (section.unit?.let { " $it" } ?: "")) {
                onChange(section.set(alarm, it))
            }
        }
        is EditorSection.BG -> {
            BGPickerRow(section.title, section.range, section.get(alarm) ?: section.range.start) { onChange(section.set(alarm, it)) }
        }
        is EditorSection.BGLimit -> {
            val value = section.get(alarm)
            val default = section.defaultOnValue?.takeIf { it in section.range } ?: section.range.start
            ToggleRow(section.toggleText, value != null) { on -> onChange(section.set(alarm, if (on) (value ?: default) else null)) }
            if (value != null) {
                FormDivider()
                BGPickerRow(section.pickerTitle, section.range, value) { onChange(section.set(alarm, it)) }
            }
        }
        is EditorSection.Toggle -> ToggleRow(section.title, section.get(alarm)) { onChange(section.set(alarm, it)) }
    }
}

@Composable
private fun TonePickerScreen(selected: SoundFile, onSelect: (SoundFile) -> Unit, onDone: () -> Unit) {
    val colors = LocalAppColors.current
    var current by remember { mutableStateOf(selected) }
    var customSounds by remember { mutableStateOf(CustomSoundStore.all()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        } ?: "Custom Sound"
        val id = CustomSoundStore.import(uri, name) ?: return@rememberLauncherForActivityResult
        customSounds = CustomSoundStore.all()
        val tone = SoundFile.Custom(id)
        current = tone
        onSelect(tone)
        AlarmSound.setSoundFile(tone)
        AlarmSound.stop()
        AlarmSound.playTest()
    }

    fun pick(tone: SoundFile) {
        current = tone
        onSelect(tone)
        AlarmSound.setSoundFile(tone)
        AlarmSound.stop()
        AlarmSound.playTest()
    }

    NavScreen("Choose Tone", actions = {
        Text("Done", color = colors.blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp).clickableNoRipple(onDone))
    }) {
        FormList {
            formSection(header = "Custom", footer = "Custom sounds stay on this device and aren't included in settings export.") {
                for ((id, name) in customSounds) {
                    ToneRow(name, current == SoundFile.Custom(id)) { pick(SoundFile.Custom(id)) }
                    FormDivider()
                }
                ButtonRow("Import Sound…") { importer.launch(arrayOf("audio/*")) }
            }
            formSection(header = "Built-in") {
                SoundCatalog.builtins.forEachIndexed { i, name ->
                    if (i > 0) FormDivider()
                    val tone = SoundFile.Builtin(name)
                    ToneRow(tone.displayName, current == tone) { pick(tone) }
                }
            }
        }
    }
}

@Composable
private fun ToneRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    FormRow(onClick = onClick) {
        FormText(label, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, null, tint = colors.blue)
    }
}
