package com.loopandlearn.loopfollow.ui.remote

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.remote.AuthResult
import com.loopandlearn.loopfollow.remote.AuthService
import com.loopandlearn.loopfollow.remote.RemoteCommandMessage
import com.loopandlearn.loopfollow.remote.APNSDelivery
import com.loopandlearn.loopfollow.remote.profiles.EditableSegment
import com.loopandlearn.loopfollow.remote.profiles.ProfileCommand
import com.loopandlearn.loopfollow.remote.profiles.ProfileCommandKind
import com.loopandlearn.loopfollow.remote.profiles.ProfileCommands
import com.loopandlearn.loopfollow.remote.profiles.ProfileDiff
import com.loopandlearn.loopfollow.remote.profiles.ProfileDraft
import com.loopandlearn.loopfollow.remote.profiles.ProfileEditLimits
import com.loopandlearn.loopfollow.remote.profiles.ProfileInsulinComparison
import com.loopandlearn.loopfollow.remote.profiles.ProfileInsulinIncrease
import com.loopandlearn.loopfollow.remote.profiles.ProfilePushSize
import com.loopandlearn.loopfollow.remote.profiles.ProfileRemote
import com.loopandlearn.loopfollow.remote.profiles.ProfileScheduleKind
import com.loopandlearn.loopfollow.remote.profiles.ProfileSegmentGrid
import com.loopandlearn.loopfollow.remote.profiles.ProfileValidation
import com.loopandlearn.loopfollow.remote.profiles.ProfileValueFormat
import com.loopandlearn.loopfollow.remote.profiles.ProfileValueStatus
import com.loopandlearn.loopfollow.remote.profiles.ScheduledProfileSwitch
import com.loopandlearn.loopfollow.remote.profiles.ScheduledSwitchChange
import com.loopandlearn.loopfollow.remote.profiles.ScheduledSwitchDiff
import com.loopandlearn.loopfollow.remote.profiles.ScheduledSwitchList
import com.loopandlearn.loopfollow.remote.profiles.ScheduledSwitches
import com.loopandlearn.loopfollow.remote.profiles.TherapyProfile
import com.loopandlearn.loopfollow.remote.profiles.TherapyProfileSet
import com.loopandlearn.loopfollow.remote.profiles.TherapyProfileValues
import com.loopandlearn.loopfollow.remote.profiles.TherapyProfiles
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.NavStack
import com.loopandlearn.loopfollow.ui.Route
import com.loopandlearn.loopfollow.ui.components.ButtonRow
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.components.LabeledValueRow
import com.loopandlearn.loopfollow.ui.components.MenuPickerRow
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.components.StepperButtons
import com.loopandlearn.loopfollow.ui.components.TextFieldRow
import com.loopandlearn.loopfollow.ui.components.ToggleRow
import com.loopandlearn.loopfollow.ui.components.formSection
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val DOSING_NOTE = "Profiles hold the settings used to dose insulin."
private const val SCHEDULED_SWITCH_DOSING_NOTE =
    "A scheduled switch changes the profile that doses insulin at the set time, also when nobody is looking at the phone."

/** The commands the app accepts, as its profile document and (for Loop) device status list them. */
@Composable
private fun acceptedCommands(remote: ProfileRemote): List<String> {
    val remoteCommands by Storage.remoteCommands.flow.collectAsStateWithLifecycle()
    val loopRemoteCommands by Storage.loopRemoteCommands.flow.collectAsStateWithLifecycle()
    return remote.acceptedCommands(remoteCommands, loopRemoteCommands)
}

/** Device owner authentication; an unavailable or failed check goes to [onError], a cancel is silent. */
private fun authenticate(activity: FragmentActivity?, reason: String, onError: (String) -> Unit, onSuccess: () -> Unit) {
    if (activity == null) {
        onError("Authentication failed")
        return
    }
    AuthService.authenticate(activity, reason) { result ->
        when (result) {
            AuthResult.Success -> onSuccess()
            is AuthResult.Unavailable -> onError(result.message)
            AuthResult.Failed -> onError("Authentication failed")
            AuthResult.Canceled -> {}
        }
    }
}

object ProfileEditing {
    /** Why profiles of this set can not be edited remotely, or null when they can. */
    fun blocker(limits: ProfileEditLimits?, remote: ProfileRemote): String? {
        if (limits == null) {
            val missing = "${remote.appName} has not shared the limits LoopFollow needs to change profiles safely, so profiles can only be viewed here."
            return when (remote) {
                ProfileRemote.LOOP -> "$missing Loop shares them once a pump and a maximum basal rate are set up in it; an older version of Loop may need an update first."
                ProfileRemote.TRIO -> "$missing An older version of Trio may need an update first."
            }
        }
        if (!limits.isUsable) return "The limits ${remote.appName} shared are incomplete, so profiles can only be viewed here."
        return null
    }

    /** Why this profile can not be edited remotely, or null when it can. */
    fun blocker(profile: TherapyProfile, limits: ProfileEditLimits?, remote: ProfileRemote): String? {
        blocker(limits, remote)?.let { return it }
        if (limits != null && profile.values.units != limits.normalizedUnits) {
            return "This profile is stored in ${profile.values.units}, but ${remote.appName} shared its limits in ${limits.normalizedUnits}. Change it in ${remote.appName} instead."
        }
        if (!profile.isWellFormed) return "The correction range of this profile could not be read completely. Change it in ${remote.appName} instead."
        return null
    }

    /** Why the scheduled switches can not be changed from LoopFollow, or null when they can. */
    fun scheduledSwitchesBlocker(list: ScheduledSwitchList, supported: Boolean, remote: ProfileRemote): String? {
        if (!supported) {
            return "${remote.appName} has not said it accepts changes to its scheduled switches from LoopFollow, so they can only be viewed here. An older version of ${remote.appName} may need an update first."
        }
        if (!list.isComplete) {
            return "The scheduled switches ${remote.appName} uploaded could not be read completely, so they can only be viewed here. Change them in ${remote.appName} instead."
        }
        return null
    }
}

/** One sentence for a confirmation, empty when nothing gives more insulin. */
private fun moreInsulinSentence(increases: List<ProfileInsulinIncrease>, comparedWith: String): String {
    if (increases.isEmpty()) return ""
    return "\n\nThis gives more insulin than “$comparedWith”: ${increases.joinToString("; ") { it.text }}."
}

private fun unitsText(value: Double) = Localizer.formatToLocalizedString(value, 2, 2)

/** Lists where a profile gives more insulin than the one it is compared with. */
@Composable
private fun MoreInsulinWarning(increases: List<ProfileInsulinIncrease>, basalTotal: Pair<Double, Double>?, comparedWith: String, appliesNow: Boolean) {
    val colors = LocalAppColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ArrowCircleUp, null, tint = colors.red, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("More insulin than “$comparedWith”", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.red)
        }
        for (increase in increases) Text("• ${increase.text}", fontSize = 15.sp, color = colors.red)
        if (basalTotal != null) Text("• Basal per day ${unitsText(basalTotal.first)} → ${unitsText(basalTotal.second)} U", fontSize = 15.sp, color = colors.red)
        Text(
            if (appliesNow) {
                "More insulin raises the risk of lows. Check that these values are what was agreed, and watch glucose closely after sending."
            } else {
                "More insulin raises the risk of lows once this profile is active. Check that these values are what was agreed."
            },
            fontSize = 13.sp,
            color = colors.secondaryText,
        )
    }
}

/** The warning shown when a change applies to the profile that is in use. */
@Composable
private fun ActiveProfileWarning(remote: ProfileRemote) {
    IconMessage(
        Icons.Filled.Warning,
        "This is the active profile. Saving changes insulin dosing right away: ${remote.appName} sends the new basal rates to the pump and starts using all new values as soon as it gets the command.",
        LocalAppColors.current.red,
        bold = true,
    )
}

@Composable
private fun IconMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 16.sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else null)
    }
}

/** Footer lines under a section, in [color]. */
private fun LazyListScope.footerLines(lines: List<Pair<String, Color?>>) {
    if (lines.isEmpty()) return
    item {
        val colors = LocalAppColors.current
        Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((text, color) in lines) Text(text, fontSize = 13.sp, color = color ?: colors.secondaryText)
        }
    }
}

private fun LazyListScope.noteSection(text: String) {
    formSection { FormRow { Text(text, fontSize = 13.sp, color = LocalAppColors.current.secondaryText) } }
}

private fun LazyListScope.problemsSection(header: String, problems: List<String>) {
    if (problems.isEmpty()) return
    formSection(header = header) {
        problems.forEachIndexed { i, problem ->
            if (i > 0) FormDivider()
            FormRow { FormText(problem, color = LocalAppColors.current.red) }
        }
    }
}

/** A row that shows a long text when expanded, selectable. */
@Composable
private fun DisclosureRow(title: String, text: String) {
    val colors = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }
    FormRow(onClick = { expanded = !expanded }) {
        FormText(title, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.gray.copy(alpha = 0.6f), modifier = Modifier.padding(start = 4.dp))
    }
    if (expanded) {
        SelectionContainer {
            Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.primaryText, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

/** The JSON with sorted keys, as the review shows what is sent. */
private fun sortedJson(element: kotlinx.serialization.json.JsonElement): String = when (element) {
    is kotlinx.serialization.json.JsonObject -> element.keys.sorted().joinToString(",", "{", "}") { "\"$it\":" + sortedJson(element.getValue(it)) }
    is kotlinx.serialization.json.JsonArray -> element.joinToString(",", "[", "]") { sortedJson(it) }
    else -> element.toString()
}

/** A value picked from [options], with −/+ to step through them and a menu that opens at the current value. */
// ANDROID-DEVIATION: A menu with −/+ steps takes the place of the iOS wheel picker, with the same allowed values.
@Composable
private fun OptionStepperRow(title: String, selected: Double, options: List<Double>, label: (Double) -> String, color: (Double) -> Color, onSelect: (Double) -> Unit) {
    val colors = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }
    val index = options.indexOfFirst { abs(it - selected) < ProfileEditLimits.TOLERANCE }
    val scroll = rememberScrollState()
    val itemPx = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(expanded) {
        if (expanded && index > 3) scroll.scrollTo(((index - 3) * itemPx).toInt())
    }
    FormRow(onClick = { expanded = true }) {
        FormText(title)
        Spacer(Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label(selected), fontSize = 17.sp, color = color(selected))
                Icon(Icons.Filled.UnfoldMore, null, tint = colors.secondaryText, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, scrollState = scroll, modifier = Modifier.heightIn(max = 420.dp)) {
                for (option in options) {
                    DropdownMenuItem(
                        text = { Text(label(option) + if (abs(option - selected) < ProfileEditLimits.TOLERANCE) "  ✓" else "", color = color(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        StepperButtons(
            canDecrement = index > 0,
            canIncrement = index in 0 until options.size - 1,
            onDecrement = { onSelect(options[index - 1]) },
            onIncrement = { onSelect(options[index + 1]) },
        )
    }
}

/**
 * One schedule of a profile: a list of start times with values, on the time grid and inside the bounds of the
 * published limits. Without [onChange] or [limits] the schedule is shown read-only.
 */
private fun LazyListScope.profileValuesSection(
    kind: ProfileScheduleKind,
    segments: List<EditableSegment>,
    units: String,
    limits: ProfileEditLimits?,
    singleTarget: Boolean,
    expandedID: String? = null,
    onExpand: (String?) -> Unit = {},
    onChange: ((List<EditableSegment>) -> Unit)? = null,
) {
    val editable = limits != null && onChange != null
    val isRange = kind.isRange && !singleTarget
    val unitText = kind.unitText(units)

    fun valueStatus(segment: EditableSegment): ProfileValueStatus {
        limits ?: return ProfileValueStatus.OK
        val statuses = if (kind.isRange) listOf(limits.status(segment.value, kind), limits.status(segment.high, kind)) else listOf(limits.status(segment.value, kind))
        if (ProfileValueStatus.NOT_ALLOWED in statuses || (kind.isRange && segment.value > segment.high + ProfileEditLimits.TOLERANCE)) return ProfileValueStatus.NOT_ALLOWED
        return if (ProfileValueStatus.OUTSIDE_RECOMMENDED in statuses) ProfileValueStatus.OUTSIDE_RECOMMENDED else ProfileValueStatus.OK
    }

    /** The allowed values, with the current value kept as it is when it is not on the grid. */
    fun options(current: Double, lower: Double? = null, upper: Double? = null): List<Double> {
        limits ?: return listOf(current)
        val values = kind.options(limits).filter { v ->
            (lower == null || v >= lower - ProfileEditLimits.TOLERANCE) && (upper == null || v <= upper + ProfileEditLimits.TOLERANCE)
        }.toMutableList()
        val i = values.indexOfFirst { abs(it - current) < ProfileEditLimits.TOLERANCE }
        if (i >= 0) values[i] = current else {
            values.add(current)
            values.sort()
        }
        return values
    }

    formSection(header = if (singleTarget && kind.isRange) "Glucose Target" else kind.title) {
        val colors = LocalAppColors.current
        fun statusColor(status: ProfileValueStatus) = when (status) {
            ProfileValueStatus.OK -> if (limits == null) colors.secondaryText else colors.primaryText
            ProfileValueStatus.OUTSIDE_RECOMMENDED -> colors.orange
            ProfileValueStatus.NOT_ALLOWED -> colors.red
        }
        segments.forEachIndexed { index, segment ->
            if (index > 0) FormDivider()
            val status = valueStatus(segment)
            FormRow(onClick = if (editable) ({ onExpand(if (expandedID == segment.id) null else segment.id) }) else null) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FormText(ProfileValueFormat.timeText(segment.seconds))
                        Spacer(Modifier.weight(1f))
                        FormText("${ProfileValueFormat.segmentText(segment, kind, limits, units, singleTarget)} $unitText", color = statusColor(status))
                    }
                    when (status) {
                        ProfileValueStatus.NOT_ALLOWED -> Text("Outside what is allowed. Change this value before sending.", fontSize = 12.sp, color = colors.red)
                        ProfileValueStatus.OUTSIDE_RECOMMENDED -> Text("Unusual value. Double-check it.", fontSize = 12.sp, color = colors.orange)
                        ProfileValueStatus.OK -> {}
                    }
                }
            }
            if (editable && expandedID == segment.id) {
                limits!!
                onChange!!
                fun update(transform: (EditableSegment) -> EditableSegment) {
                    onChange(segments.mapIndexed { i, s -> if (i == index) transform(s) else s })
                }
                fun valueText(v: Double) = ProfileValueFormat.text(v, kind, limits, units)
                fun valueColor(v: Double) = statusColor(limits.status(v, kind))
                if (index > 0) {
                    MenuPickerRow("Start Time", segment.seconds, ProfileSegmentGrid.allowedStarts(index, segments, limits.segmentSeconds), { ProfileValueFormat.timeText(it) }) { seconds ->
                        update { it.copy(seconds = seconds) }
                    }
                }
                if (isRange) {
                    OptionStepperRow("Low", segment.value, options(segment.value, upper = segment.high), ::valueText, ::valueColor) { v -> update { it.copy(value = v) } }
                    OptionStepperRow("High", segment.high, options(segment.high, lower = segment.value), ::valueText, ::valueColor) { v -> update { it.copy(high = v) } }
                } else {
                    OptionStepperRow(if (singleTarget && kind.isRange) "Target" else "Value", segment.value, options(segment.value), ::valueText, ::valueColor) { v ->
                        update { it.copy(value = v, high = if (singleTarget && kind.isRange) v else it.high) }
                    }
                }
                // ANDROID-DEVIATION: A Remove Time button in the expanded row takes the place of the iOS swipe to delete.
                if (index > 0) {
                    ButtonRow("Remove Time", color = colors.red) {
                        onExpand(null)
                        onChange(segments.filterIndexed { i, _ -> i != index })
                    }
                }
            }
        }
        if (editable) {
            limits!!
            val maxSegments = limits.segmentLimit(kind)
            FormDivider()
            ButtonRow("Add Time", enabled = ProfileSegmentGrid.newSegmentStart(segments, limits.segmentSeconds, maxSegments) != null) {
                val added = ProfileSegmentGrid.addSegment(segments, limits.segmentSeconds, maxSegments)
                onExpand(added.firstOrNull { s -> segments.none { it.id == s.id } }?.id)
                onChange!!(added)
            }
        }
    }
    val footer = buildList<Pair<String, Color?>> {
        if (kind == ProfileScheduleKind.BASAL) {
            add("Total: ${unitsText(TherapyProfile.basalTotal(segments.map { com.loopandlearn.loopfollow.remote.profiles.ProfileScheduleItem(it.seconds, it.value) }))} U/day" to null)
        }
        if (limits != null) {
            add(limits.boundsText(kind) to null)
            if (editable) add("${segments.size} of ${limits.segmentLimit(kind)} times. Tap a row to change or remove it." to null)
        }
    }
    footerLines(footer)
}

// MARK: Profile list

@Composable
fun TherapyProfilesScreen(remote: ProfileRemote, nav: NavStack, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val profileSet by TherapyProfiles.current.flow.collectAsStateWithLifecycle()
    val commands = acceptedCommands(remote)
    val canSave = remote.supports(ProfileCommandKind.SAVE, commands)
    LaunchedEffect(Unit) { TherapyProfiles.refresh() }

    /** Default is a real profile once the app can save profiles. Older Loop builds upload a Default entry that only mirrors the active settings. */
    fun visibleProfiles(set: TherapyProfileSet) = if (canSave) set.profiles else set.profiles.filter { !it.isDefault }

    NavScreen("Remote Profiles", onBack = onBack) {
        FormList {
            noteSection(DOSING_NOTE)
            val set = profileSet
            if (set != null && visibleProfiles(set).isNotEmpty()) {
                formSection(header = "Profiles", footer = set.uploadedAt?.let { "Uploaded by ${remote.appName} ${DateTimeUtils.formattedDate(it)}." }) {
                    visibleProfiles(set).forEachIndexed { i, profile ->
                        if (i > 0) FormDivider()
                        FormRow(onClick = { nav.push(Route.TherapyProfilePreview(remote, profile.name)) }) {
                            FormText(profile.name, modifier = Modifier.weight(1f))
                            if (set.isActive(profile)) {
                                Text("Active", fontSize = 12.sp, color = colors.green)
                                Spacer(Modifier.width(4.dp))
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.gray.copy(alpha = 0.6f))
                        }
                    }
                }

                scheduledSwitchesSection(remote, set, commands, nav)

                if (canSave) {
                    val createBlocker = ProfileEditing.blocker(set.limits, remote) ?: ProfileValidation.countProblem(set.profiles.size)
                    formSection(footer = createBlocker ?: "A new profile starts as a copy of the active profile. Dosing does not change until it is activated.") {
                        ButtonRow("New Profile", enabled = createBlocker == null && set.profiles.isNotEmpty()) {
                            val source = set.activeProfile ?: set.profiles.first()
                            nav.push(Route.ProfileEditor(ProfileEditSession(remote, null, source, set, nav.stack.size)))
                        }
                    }
                }
            } else {
                formSection {
                    FormRow {
                        Text(
                            if (remote == ProfileRemote.LOOP) {
                                "No saved profiles found. Profiles are saved in Loop under Settings → Profiles."
                            } else {
                                "No profiles found. Profiles are saved in Trio under Settings → Therapy Settings → Therapy Profiles."
                            },
                            fontSize = 17.sp,
                            fontStyle = FontStyle.Italic,
                            color = colors.secondaryText,
                        )
                    }
                }
            }
        }
    }
}

/** One start in the week, with how long its profile stays active. */
@Composable
private fun ScheduledSwitchRowContent(entry: ScheduledProfileSwitch, entries: List<ScheduledProfileSwitch>, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(modifier) {
        FormText("${ScheduledSwitches.slotText(entry, short = true)} · ${entry.profile} starts", color = if (entry.paused) colors.secondaryText else colors.primaryText)
        Text(ScheduledSwitches.spanText(entry, entries), fontSize = 12.sp, color = colors.secondaryText)
    }
}

/** The scheduled switches section of the profile list: the week with pause and resume per start, and the editor. */
private fun LazyListScope.scheduledSwitchesSection(remote: ProfileRemote, set: TherapyProfileSet, commands: List<String>, nav: NavStack) {
    val list = set.scheduledSwitches
    val supported = remote.supports(ProfileCommandKind.SAVE_SCHEDULED_SWITCHES, commands)
    if (!supported && list.entries.isEmpty()) return
    val blocker = ProfileEditing.scheduledSwitchesBlocker(list, supported, remote)
    formSection(header = "Scheduled Switches") {
        val colors = LocalAppColors.current
        ScheduledSwitches.sortedForWeek(list.entries).forEachIndexed { i, entry ->
            if (i > 0) FormDivider()
            FormRow {
                ScheduledSwitchRowContent(entry, list.entries, Modifier.weight(1f))
                // ANDROID-DEVIATION: A Pause or Resume button on each start takes the place of the iOS swipe action.
                if (blocker == null) {
                    TextButton(onClick = {
                        nav.push(Route.ScheduledSwitchesReview(remote, set, ScheduledSwitches.togglingPause(entry.id, list.entries), nav.stack.size))
                    }) { Text(if (entry.paused) "Resume" else "Pause", color = if (entry.paused) colors.green else colors.orange) }
                }
            }
        }
        if (supported) {
            if (list.entries.isNotEmpty()) FormDivider()
            ButtonRow("Edit Scheduled Switches", enabled = blocker == null) {
                nav.push(Route.ScheduledSwitchesEditor(ScheduledSwitchesSession(remote, set, nav.stack.size)))
            }
        }
    }
    footerLines(
        buildList {
            add(ScheduledSwitches.footerText(list, remote.appName) to null)
            ScheduledSwitches.timeZoneNote(list.timeZone)?.let { add(it to null) }
            if (blocker != null) add(blocker to null) else if (list.entries.isNotEmpty()) add("Tap Pause or Resume to pause or resume a start." to null)
        },
    )
}

// MARK: Profile preview

@Composable
fun TherapyProfilePreviewScreen(remote: ProfileRemote, name: String, nav: NavStack, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val profileSet by TherapyProfiles.current.flow.collectAsStateWithLifecycle()
    val commands = acceptedCommands(remote)
    var alert by remember { mutableStateOf<RemoteAlert?>(null) }
    var isSending by remember { mutableStateOf(false) }
    val set = profileSet
    val profile = set?.profile(name)
    val isActive = profile != null && set.isActive(profile)

    fun presentFailure(kind: ProfileCommandKind, message: String) {
        scope.launch {
            // A result that arrives at once comes while the confirmation dialog is still closing.
            delay(500)
            alert = RemoteAlert(ProfileCommands.failureTitle(kind), message)
        }
    }

    // ANDROID-DEVIATION: A sent command shows "Command Sent" and returns to the list, where the change shows once the
    // app uploads its profiles; Android cannot receive the app's reply push, so there is no waiting state or reply alert.
    fun send(kind: ProfileCommandKind, fingerprint: String) {
        val current = TherapyProfiles.current.value?.profile(name) ?: return
        if (current.fingerprint != fingerprint) {
            presentFailure(kind, "$name changed while the confirmation was open, so nothing was sent. Review it again.")
            return
        }
        val command = if (kind == ProfileCommandKind.DELETE) ProfileCommand.Delete(current) else ProfileCommand.Activate(current)
        val verb = if (kind == ProfileCommandKind.DELETE) "delete" else "activate"
        authenticate(activity, "Confirm your identity to $verb ${current.name} in ${remote.appName}.", { presentFailure(kind, it) }) {
            isSending = true
            scope.launch {
                val error = ProfileCommands.send(command, remote)
                isSending = false
                if (error == null) {
                    alert = RemoteAlert("Command Sent", RemoteCommandMessage.SENT, onDismiss = onBack)
                } else {
                    LogManager.log(LogManager.Category.APNS, "Profile command not sent: $error")
                    presentFailure(kind, error)
                }
            }
        }
    }

    NavScreen(name, onBack = onBack, actions = {
        if (profile != null && remote.supports(ProfileCommandKind.SAVE, commands)) {
            val blocker = ProfileEditing.blocker(profile, set.limits, remote)
            TextButton(onClick = { nav.push(Route.ProfileEditor(ProfileEditSession(remote, profile, profile, set, nav.stack.size))) }, enabled = blocker == null && !isSending) {
                Text("Edit", fontSize = 17.sp)
            }
        }
    }) {
        if (profile == null) {
            Message("This profile is no longer in the list uploaded by ${remote.appName}.")
            return@NavScreen
        }
        val activeProfile = set.activeProfile
        val activationIncreases = if (!isActive && activeProfile != null) ProfileInsulinComparison.increases(activeProfile.values, profile.values) else emptyList()
        val editBlocker = ProfileEditing.blocker(profile, set.limits, remote)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                FormList {
                    formSection(footer = "${remote.appName} writes the basal rates to the pump when the profile is activated.") {
                        FormRow {
                            FormText(if (isActive) "This is the active profile. ${remote.appName} doses insulin with these values." else "Review the profile before activating it in ${remote.appName}.")
                        }
                        if (activationIncreases.isNotEmpty() && activeProfile != null) {
                            FormDivider()
                            MoreInsulinWarning(activationIncreases, ProfileInsulinComparison.basalTotalIncrease(activeProfile.values, profile.values), activeProfile.name, appliesNow = true)
                        }
                    }
                    val draft = ProfileDraft.of(profile.values, remote.hasSingleTarget)
                    for (kind in ProfileScheduleKind.entries) {
                        profileValuesSection(kind, draft[kind], profile.values.units, null, remote.hasSingleTarget)
                    }
                    val canSave = remote.supports(ProfileCommandKind.SAVE, commands)
                    val canDeleteCommand = remote.supports(ProfileCommandKind.DELETE, commands)
                    if (canSave || canDeleteCommand) {
                        val footer = when {
                            editBlocker != null && canSave -> editBlocker
                            profile.isDefault -> "The Default profile always exists. It can be edited, but not renamed or deleted."
                            isActive -> "The active profile can't be deleted. Activate another profile first."
                            else -> null
                        }
                        val showRename = canSave && !profile.isDefault
                        val showDelete = canDeleteCommand && !profile.isDefault && !isActive
                        if (showRename || showDelete) {
                            formSection(footer = footer) {
                                if (showRename) {
                                    ButtonRow("Rename Profile", enabled = editBlocker == null && !isSending) {
                                        nav.push(Route.ProfileRename(ProfileRenameSession(remote, profile, set, nav.stack.size - 1)))
                                    }
                                }
                                if (showDelete) {
                                    if (showRename) FormDivider()
                                    ButtonRow("Delete Profile", color = colors.red, enabled = !isSending) {
                                        val fingerprint = profile.fingerprint
                                        alert = RemoteAlert(
                                            "Delete $name?",
                                            "${remote.appName} removes this profile. This can't be undone from LoopFollow." +
                                                (ScheduledSwitches.deletionText(set.scheduledSwitches, name)?.let { " $it" } ?: ""),
                                            confirmTitle = "Delete",
                                            onConfirm = { send(ProfileCommandKind.DELETE, fingerprint) },
                                            destructive = true,
                                        )
                                    }
                                }
                            }
                        } else if (footer != null) {
                            footerLines(listOf(footer to null))
                        }
                    }
                }
            }
            if (remote.supports(ProfileCommandKind.ACTIVATE, commands)) {
                BottomActionButton(if (isActive) "Active Profile" else "Activate Profile", "Sending...", isSending, enabled = !isActive) {
                    val fingerprint = profile.fingerprint
                    alert = RemoteAlert(
                        "Activate $name in ${remote.appName}?",
                        "${remote.appName} replaces its correction range, carb ratios, basal rates and insulin sensitivities with these values and writes the basal rates to the pump. Dosing changes right away." +
                            (ScheduledSwitches.holdText(set.scheduledSwitches)?.let { " $it" } ?: "") +
                            moreInsulinSentence(activationIncreases, activeProfile?.name ?: ""),
                        confirmTitle = "Activate",
                        onConfirm = { send(ProfileCommandKind.ACTIVATE, fingerprint) },
                    )
                }
            }
        }
    }
    RemoteAlertDialog(alert) { alert = null }
}

// MARK: Rename

/** A rename in progress; it keeps the profile and profiles it was opened with. [returnTo] is the stack size to go back to. */
class ProfileRenameSession(val remote: ProfileRemote, val profile: TherapyProfile, val profileSet: TherapyProfileSet, val returnTo: Int) {
    var name by mutableStateOf("")
    val isActive: Boolean get() = profileSet.isActive(profile)

    val problem: String?
        get() {
            if (ProfileValidation.normalizedName(name) == profile.name) return "Enter a new name."
            return ProfileValidation.nameProblem(name, profileSet.profiles.map { it.name }, profile.name)
        }

    val command: ProfileCommand.Save get() = ProfileCommand.Save(ProfileValidation.normalizedName(name), profile, profile.values)
}

@Composable
fun ProfileRenameScreen(session: ProfileRenameSession, nav: NavStack) {
    val colors = LocalAppColors.current
    NavScreen("Rename ${session.profile.name}", onBack = { nav.pop() }, actions = {
        TextButton(onClick = {
            nav.push(Route.ProfileReview(session.remote, session.command, session.profile, session.isActive, session.profileSet.limits, session.returnTo))
        }, enabled = session.problem == null) { Text("Review", fontSize = 17.sp) }
    }) {
        FormList {
            formSection(header = "New Name") {
                TextFieldRow(session.name, { session.name = it }, session.profile.name)
            }
            val problem = session.problem
            footerLines(
                listOf(
                    if (problem != null && session.name.isNotEmpty()) {
                        problem to colors.red
                    } else {
                        "The values stay the same${if (session.isActive) " and the profile stays active" else ""}. Up to ${ProfileValidation.MAX_NAME_LENGTH} characters, not “Default”, no period (.), and not starting with $." to null
                    },
                ),
            )
        }
    }
}

// MARK: Editor

/**
 * A new profile ([original] null, copied from [initialSource]) or an edit of [original], in progress. It keeps the
 * profiles it was opened with. [returnTo] is the stack size to go back to.
 */
class ProfileEditSession(
    val remote: ProfileRemote,
    val original: TherapyProfile?,
    initialSource: TherapyProfile,
    val profileSet: TherapyProfileSet,
    val returnTo: Int,
) {
    val isNew: Boolean get() = original == null
    var name by mutableStateOf("")
    var source by mutableStateOf(initialSource)
    var draft by mutableStateOf(ProfileDraft.of(initialSource.values, remote.hasSingleTarget))
    var expandedID by mutableStateOf<String?>(null)

    val limits: ProfileEditLimits? get() = profileSet.limits
    val blocker: String? get() = ProfileEditing.blocker(source, limits, remote)
    val editableLimits: ProfileEditLimits? get() = if (blocker == null) limits else null

    val targetName: String get() = if (isNew) ProfileValidation.normalizedName(name) else source.name

    val nameProblem: String?
        get() = if (!isNew) null else ProfileValidation.countProblem(profileSet.profiles.size) ?: ProfileValidation.nameProblem(name, profileSet.profiles.map { it.name })

    val problems: List<String> get() = editableLimits?.let { ProfileValidation.problems(draft.values, it) } ?: emptyList()

    val hasChanges: Boolean get() = draft.values != ProfileDraft.of(source.values, remote.hasSingleTarget).values

    val isEditingActive: Boolean get() = !isNew && profileSet.isActive(source)

    val canReview: Boolean get() = editableLimits != null && nameProblem == null && problems.isEmpty() && (isNew || hasChanges)

    val command: ProfileCommand.Save get() = ProfileCommand.Save(targetName, original, draft.values)

    fun selectSource(profile: TherapyProfile) {
        source = profile
        draft = ProfileDraft.of(profile.values, remote.hasSingleTarget)
        expandedID = null
    }
}

@Composable
fun ProfileEditorScreen(session: ProfileEditSession, nav: NavStack) {
    val colors = LocalAppColors.current
    NavScreen(if (session.isNew) "New Profile" else "Edit ${session.source.name}", onBack = { nav.pop() }, actions = {
        TextButton(onClick = {
            nav.push(Route.ProfileReview(session.remote, session.command, session.source, session.isEditingActive, session.editableLimits, session.returnTo))
        }, enabled = session.canReview) { Text("Review", fontSize = 17.sp) }
    }) {
        FormList {
            session.blocker?.let { blocker ->
                formSection { IconMessage(Icons.Filled.Lock, blocker, colors.secondaryText) }
            }
            if (session.isEditingActive) {
                formSection { ActiveProfileWarning(session.remote) }
            }
            if (session.isNew) {
                formSection(header = "Name") {
                    TextFieldRow(session.name, { session.name = it }, "Profile name")
                }
                val nameProblem = session.nameProblem
                footerLines(
                    listOf(
                        if (nameProblem != null && session.name.isNotEmpty()) {
                            nameProblem to colors.red
                        } else {
                            "Up to ${ProfileValidation.MAX_NAME_LENGTH} characters. Not “Default”, no period (.), and not starting with $." to null
                        },
                    ),
                )
                formSection(header = "Start From", footer = "The new profile starts with a copy of these values. Choosing another profile discards changes made below.") {
                    MenuPickerRow("Profile", session.source.name, session.profileSet.profiles.map { it.name }, { it }) { name ->
                        session.profileSet.profile(name)?.let { session.selectSource(it) }
                    }
                }
            }
            for (kind in ProfileScheduleKind.entries) {
                profileValuesSection(
                    kind,
                    session.draft[kind],
                    session.draft.units,
                    session.editableLimits,
                    session.remote.hasSingleTarget,
                    session.expandedID,
                    onExpand = { session.expandedID = it },
                    onChange = { session.draft = session.draft.with(kind, it) },
                )
            }
            problemsSection("Fix Before Sending", session.problems)
        }
    }
}

// MARK: Review

/**
 * Shows exactly what a profile save sends, compared with [baseline] (the profile being changed, or the one a new
 * profile is copied from), and sends it after confirmation and device authentication.
 */
@Composable
fun ProfileReviewScreen(
    remote: ProfileRemote,
    command: ProfileCommand.Save,
    baseline: TherapyProfile,
    isActive: Boolean,
    limits: ProfileEditLimits?,
    nav: NavStack,
    onFinished: () -> Unit,
) {
    val colors = LocalAppColors.current
    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    var alert by remember { mutableStateOf<RemoteAlert?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val values = command.values
    val original = command.original
    val increases = remember(command) { ProfileInsulinComparison.increases(baseline.values, values) }
    val pushSize = remember(command) { ProfileCommands.pushSize(command, remote) }
    val isTooLarge = (pushSize ?: 0) > ProfilePushSize.reviewLimit(remote)
    val problems = limits?.let { ProfileValidation.problems(command, it) } ?: listOf("Profiles can not be changed without the limits from ${remote.appName}.")
    val isRename = command.isRename
    // True when the command changes the values the phone doses with as soon as it arrives. A rename keeps the values.
    val appliesNow = isActive && !isRename
    val canSend = problems.isEmpty() && !isTooLarge && !isSending && !checking
    val comparedWith = original?.name ?: baseline.name

    val summary = when {
        original != null && original.name != command.targetName -> "Rename “${original.name}” to “${command.targetName}” in ${remote.appName}."
        original != null -> "Save these changes to “${original.name}” in ${remote.appName}. Changed values are marked."
        else -> "Create “${command.targetName}” in ${remote.appName}, starting from “${baseline.name}”. Values that differ from “${baseline.name}” are marked."
    }
    val confirmTitle = when {
        isRename -> "Rename ${original?.name ?: ""}?"
        isActive -> "Change the Active Profile?"
        original == null -> "Create ${command.targetName}?"
        else -> "Save ${command.targetName}?"
    }
    val confirmMessage = when {
        isRename -> "Only the name changes. The values stay the same, so dosing does not change."
        isActive -> "${remote.appName} starts dosing with these values as soon as it gets the command, and sends the new basal rates to the pump." + moreInsulinSentence(increases, comparedWith)
        else -> "${remote.appName} saves the profile. Dosing does not change unless this profile is the active one in ${remote.appName} when the command arrives." + moreInsulinSentence(increases, comparedWith)
    }

    fun showFailure(message: String) {
        scope.launch {
            delay(500)
            alert = RemoteAlert(ProfileCommands.failureTitle(ProfileCommandKind.SAVE), message)
        }
    }

    fun authenticateAndSend() {
        authenticate(activity, "Confirm your identity to change ${command.targetName} in ${remote.appName}.", ::showFailure) {
            isSending = true
            scope.launch {
                val error = ProfileCommands.send(command, remote)
                isSending = false
                if (error == null) {
                    alert = RemoteAlert("Command Sent", RemoteCommandMessage.SENT, onDismiss = onFinished)
                } else {
                    LogManager.log(LogManager.Category.APNS, "Profile command not sent: $error")
                    showFailure(error)
                }
            }
        }
    }

    /** Checks against the newest profile document that the profile is unchanged and has the active state the review showed. */
    fun send() {
        if (original == null) {
            authenticateAndSend()
            return
        }
        checking = true
        scope.launch {
            val current = TherapyProfiles.fetch()
            checking = false
            val reason = ProfileCommands.saveStaleReason(original, isActive, current, remote)
            if (reason == null) {
                authenticateAndSend()
                return@launch
            }
            if (current != null) {
                TherapyProfiles.current.value = current
                alert = RemoteAlert(ProfileCommands.failureTitle(ProfileCommandKind.SAVE), ProfileCommands.outdatedMessage(reason, remote), onDismiss = onFinished)
            } else {
                alert = RemoteAlert(ProfileCommands.failureTitle(ProfileCommandKind.SAVE), reason)
            }
        }
    }

    NavScreen("Review", onBack = { nav.pop() }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                FormList {
                    formSection {
                        FormRow { FormText(summary) }
                        if (appliesNow) {
                            FormDivider()
                            ActiveProfileWarning(remote)
                        }
                        if (increases.isNotEmpty()) {
                            FormDivider()
                            MoreInsulinWarning(increases, ProfileInsulinComparison.basalTotalIncrease(baseline.values, values), comparedWith, appliesNow)
                        }
                    }
                    for (kind in ProfileScheduleKind.entries) diffSection(kind, remote, baseline.values, values, limits, increases)
                    formSection(header = "Sent to ${remote.appName}") {
                        LabeledValueRow("Name", command.targetName)
                        if (original != null) {
                            if (original.name != command.targetName) {
                                FormDivider()
                                LabeledValueRow("Renames", original.name)
                            }
                            FormDivider()
                            LabeledValueRow("Based on version", original.fingerprint)
                        }
                        FormDivider()
                        LabeledValueRow("Units", values.units)
                        FormDivider()
                        DisclosureRow("Exact values", sortedJson(values.json))
                    }
                    footerLines(listOf(sizeFooter(pushSize, isTooLarge, remote, ProfilePushSize::tooLargeMessage, colors.red)))
                    problemsSection("Can't Send", problems)
                    noteSection(DOSING_NOTE)
                }
            }
            val title = when {
                checking -> "Checking the profile..."
                appliesNow -> "Send and Apply Now"
                else -> "Send to ${remote.appName}"
            }
            BottomActionButton(title, if (checking) "Checking the profile..." else "Sending...", isSending || checking, enabled = canSend, color = if (appliesNow) colors.red else null) {
                alert = RemoteAlert(confirmTitle, confirmMessage, confirmTitle = "Send", onConfirm = ::send, destructive = appliesNow)
            }
        }
    }
    RemoteAlertDialog(alert) { alert = null }
}

private fun sizeFooter(size: Int?, tooLarge: Boolean, remote: ProfileRemote, tooLargeMessage: (Int) -> String, red: Color): Pair<String, Color?> {
    // ANDROID-DEVIATION: The Trio command size needs the shared secret, which encrypts the command; Android commands
    // carry no reply block and so need no LoopFollow APNs credentials.
    size ?: return "The size of the command can not be computed until the ${remote.appName} remote control settings are complete in Remote Settings." to null
    if (tooLarge) return tooLargeMessage(size) to red
    return "Command size: about $size of ${APNSDelivery.MAX_PAYLOAD_BYTES} bytes." to null
}

private fun LazyListScope.diffSection(
    kind: ProfileScheduleKind,
    remote: ProfileRemote,
    oldValues: TherapyProfileValues,
    newValues: TherapyProfileValues,
    limits: ProfileEditLimits?,
    increases: List<ProfileInsulinIncrease>,
) {
    val unitText = kind.unitText(newValues.units)
    val singleTarget = remote.hasSingleTarget
    val old = ProfileDraft.of(oldValues, singleTarget)[kind]
    val new = ProfileDraft.of(newValues, singleTarget)[kind]
    val rows = ProfileDiff.rows(old, new) { ProfileValueFormat.segmentText(it, kind, limits, newValues.units, singleTarget) }
    val changed = rows.any { it.changed } || old.map { it.seconds } != new.map { it.seconds }
    val moreInsulin = increases.firstOrNull { it.kind == kind }
    val footer = if (changed) {
        "Before → after, in $unitText." + if (moreInsulin == null) "" else " Red values give more insulin."
    } else {
        "Unchanged, in $unitText."
    }
    formSection(header = if (singleTarget && kind.isRange) "Glucose Target" else kind.title, footer = footer) {
        val colors = LocalAppColors.current
        rows.forEachIndexed { i, row ->
            if (i > 0) FormDivider()
            FormRow {
                FormText(ProfileValueFormat.timeText(row.seconds))
                Spacer(Modifier.weight(1f))
                if (row.changed) {
                    Text(row.old, fontSize = 17.sp, color = colors.secondaryText, textDecoration = TextDecoration.LineThrough)
                    Text("  →  ", fontSize = 13.sp, color = colors.secondaryText)
                    Text(
                        row.new,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (moreInsulin?.covers(row.seconds) == true) colors.red else colors.orange,
                    )
                } else {
                    FormText(row.new, color = colors.secondaryText)
                }
            }
        }
    }
}

// MARK: Scheduled switches

/** An edit of the scheduled switches in progress; it keeps the profiles it was opened with. */
class ScheduledSwitchesSession(val remote: ProfileRemote, val profileSet: TherapyProfileSet, val returnTo: Int) {
    var draft by mutableStateOf(profileSet.scheduledSwitches.entries)
    val list: ScheduledSwitchList get() = profileSet.scheduledSwitches
    val profileNames: List<String> get() = profileSet.profiles.map { it.name }
    val problems: List<String> get() = ScheduledSwitches.problems(draft, profileNames, remote.appName)
    val hasChanges: Boolean get() = draft != list.entries
    val canAdd: Boolean get() = draft.size < ScheduledProfileSwitch.MAX_COUNT && profileNames.isNotEmpty()
}

@Composable
fun ScheduledSwitchesEditorScreen(session: ScheduledSwitchesSession, nav: NavStack) {
    val colors = LocalAppColors.current
    val remote = session.remote
    NavScreen("Scheduled Switches", onBack = { nav.pop() }, actions = {
        TextButton(onClick = {
            nav.push(Route.ScheduledSwitchesReview(remote, session.profileSet, session.draft, session.returnTo))
        }, enabled = session.hasChanges && session.problems.isEmpty()) { Text("Review", fontSize = 17.sp) }
    }) {
        FormList {
            formSection(
                header = "Starts",
                footer = if (session.draft.isEmpty()) {
                    "No starts. ${remote.appName} keeps the active profile until someone switches."
                } else {
                    "Each profile stays active until the next start. Tap a start to change, pause or delete it."
                },
            ) {
                ScheduledSwitches.sortedForWeek(session.draft).forEachIndexed { i, entry ->
                    if (i > 0) FormDivider()
                    FormRow(onClick = { nav.push(Route.ScheduledSwitchEntry(session, entry.id)) }) {
                        ScheduledSwitchRowContent(entry, session.draft, Modifier.weight(1f))
                        if (ScheduledSwitches.entryProblem(entry, session.draft, session.profileNames, remote.appName) != null) {
                            Icon(Icons.Filled.ErrorOutline, null, tint = colors.red, modifier = Modifier.size(20.dp))
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.gray.copy(alpha = 0.6f))
                    }
                }
                if (session.draft.isNotEmpty()) FormDivider()
                ButtonRow("Add Start", enabled = session.canAdd) {
                    val entry = ScheduledProfileSwitch(profile = session.profileSet.profiles.firstOrNull()?.name ?: TherapyProfile.DEFAULT_NAME, weekday = 2, time = 7 * 3600)
                    session.draft = session.draft + entry
                    nav.push(Route.ScheduledSwitchEntry(session, entry.id))
                }
            }
            ScheduledSwitches.timeZoneNote(session.list.timeZone)?.let { noteSection(it) }
            problemsSection("Fix Before Sending", session.problems)
            noteSection(SCHEDULED_SWITCH_DOSING_NOTE)
        }
    }
}

/** Edits one start: profile, weekday, time and pause. */
@Composable
fun ScheduledSwitchEntryScreen(session: ScheduledSwitchesSession, id: String, nav: NavStack) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val entry = session.draft.firstOrNull { it.id == id }
    fun update(transform: (ScheduledProfileSwitch) -> ScheduledProfileSwitch) {
        session.draft = session.draft.map { if (it.id == id) transform(it) else it }
    }
    NavScreen("Start", onBack = { nav.pop() }) {
        if (entry == null) return@NavScreen
        val names = session.profileNames.let { if (entry.profile in it) it else it + entry.profile }
        FormList {
            formSection(
                footer = "${if (entry.paused) "Paused. When resumed, " else ""}${entry.profile} starts every ${ScheduledSwitches.weekdayName(entry.weekday)} at ${ScheduledSwitches.timeText(entry.time)} and stays active until the next scheduled start.",
            ) {
                MenuPickerRow("Profile", entry.profile, names, { it }) { name -> update { it.copy(profile = name) } }
                FormDivider()
                MenuPickerRow("Day", entry.weekday, ScheduledSwitches.weekdaysFromMonday, { ScheduledSwitches.weekdayName(it) }) { day -> update { it.copy(weekday = day) } }
                FormDivider()
                // ANDROID-DEVIATION: An Android time dialog replaces the iOS time wheel, with the same whole-minute values.
                FormRow(onClick = {
                    TimePickerDialog(context, { _, h, m -> update { it.copy(time = h * 3600 + m * 60) } }, entry.time / 3600, entry.time % 3600 / 60, DateFormat.is24HourFormat(context)).show()
                }) {
                    FormText("Time")
                    Spacer(Modifier.weight(1f))
                    FormText(ScheduledSwitches.timeText(entry.time), color = colors.secondaryText)
                }
            }
            ScheduledSwitches.entryProblem(entry, session.draft, session.profileNames, session.remote.appName)?.let { problem ->
                formSection { FormRow { FormText(problem, color = colors.red) } }
            }
            formSection(footer = "A paused start does not run until it is resumed. Nothing switches when it is resumed; the start runs again at its next time.") {
                ToggleRow("Paused", entry.paused) { paused -> update { it.copy(paused = paused) } }
            }
            formSection {
                ButtonRow("Delete Start", color = colors.red) {
                    session.draft = session.draft.filter { it.id != id }
                    nav.pop()
                }
            }
        }
    }
}

/** Shows what a scheduled switches save changes, and sends it after confirmation and device authentication. */
@Composable
fun ScheduledSwitchesReviewScreen(
    remote: ProfileRemote,
    profileSet: TherapyProfileSet,
    entries: List<ScheduledProfileSwitch>,
    nav: NavStack,
    onFinished: () -> Unit,
) {
    val colors = LocalAppColors.current
    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    var alert by remember { mutableStateOf<RemoteAlert?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val list = profileSet.scheduledSwitches
    val command = ProfileCommand.SaveScheduledSwitches(entries, list.fingerprint)
    val changes = remember(entries) { ScheduledSwitchDiff.changes(list.entries, entries) }
    val problems = ScheduledSwitches.problems(entries, profileSet.profiles.map { it.name }, remote.appName) + if (changes.isEmpty()) listOf("Nothing changed.") else emptyList()
    val increases = remember(entries) { ScheduledSwitchDiff.moreInsulinProfiles(list.entries, entries, profileSet.profiles) }
    val pushSize = remember(entries) { ProfileCommands.pushSize(command, remote) }
    val isTooLarge = (pushSize ?: 0) > ProfilePushSize.reviewLimit(remote)
    val canSend = problems.isEmpty() && !isTooLarge && !isSending && !checking
    val defaultValues = profileSet.profiles.firstOrNull { it.isDefault }?.values ?: TherapyProfileValues(TherapyProfileValues.MGDL, emptyList(), emptyList(), emptyList(), emptyList())

    fun showFailure(message: String) {
        scope.launch {
            delay(500)
            alert = RemoteAlert(ProfileCommands.failureTitle(ProfileCommandKind.SAVE_SCHEDULED_SWITCHES), message)
        }
    }

    fun send() {
        checking = true
        scope.launch {
            val current = TherapyProfiles.fetch()
            checking = false
            val reason = ProfileCommands.scheduledSwitchesStaleReason(list.fingerprint, entries, current, remote)
            if (reason != null) {
                if (current != null) {
                    TherapyProfiles.current.value = current
                    alert = RemoteAlert(
                        ProfileCommands.failureTitle(ProfileCommandKind.SAVE_SCHEDULED_SWITCHES),
                        ProfileCommands.outdatedMessage(reason, remote, scheduledSwitches = true),
                        onDismiss = onFinished,
                    )
                } else {
                    alert = RemoteAlert(ProfileCommands.failureTitle(ProfileCommandKind.SAVE_SCHEDULED_SWITCHES), reason)
                }
                return@launch
            }
            authenticate(activity, "Confirm your identity to change the scheduled switches in ${remote.appName}.", ::showFailure) {
                isSending = true
                scope.launch {
                    val error = ProfileCommands.send(command, remote)
                    isSending = false
                    if (error == null) {
                        alert = RemoteAlert("Command Sent", RemoteCommandMessage.SENT, onDismiss = onFinished)
                    } else {
                        LogManager.log(LogManager.Category.APNS, "Profile command not sent: $error")
                        showFailure(error)
                    }
                }
            }
        }
    }

    NavScreen("Review", onBack = { nav.pop() }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                FormList {
                    formSection {
                        FormRow {
                            FormText("Replace the scheduled switches in ${remote.appName} with the list below. ${remote.appName} then switches profiles at these times by itself, without asking anyone, and each switch changes insulin dosing. Nothing switches when the list is saved.")
                        }
                    }
                    formSection(header = "Changes") {
                        changes.forEachIndexed { i, change ->
                            if (i > 0) FormDivider()
                            FormRow { FormText(change.text, color = if (change.kind == ScheduledSwitchChange.Kind.REMOVED) colors.secondaryText else colors.primaryText) }
                        }
                    }
                    for ((profile, profileIncreases) in increases) {
                        formSection(header = "When ${profile.name} starts") {
                            MoreInsulinWarning(profileIncreases, ProfileInsulinComparison.basalTotalIncrease(defaultValues, profile.values), TherapyProfile.DEFAULT_NAME, appliesNow = false)
                        }
                    }
                    formSection(
                        header = "The Week After Saving",
                        footer = ScheduledSwitches.footerText(ScheduledSwitchList(entries, "", true, list.timeZone), remote.appName),
                    ) {
                        if (entries.isEmpty()) FormRow { FormText("No starts", color = colors.secondaryText) }
                        ScheduledSwitches.sortedForWeek(entries).forEachIndexed { i, entry ->
                            if (i > 0) FormDivider()
                            FormRow { ScheduledSwitchRowContent(entry, entries, Modifier.weight(1f)) }
                        }
                    }
                    formSection(header = "Sent to ${remote.appName}") {
                        LabeledValueRow("Starts", "${entries.size} of ${ScheduledProfileSwitch.MAX_COUNT}")
                        FormDivider()
                        LabeledValueRow("Based on version", list.fingerprint)
                        FormDivider()
                        DisclosureRow("Exact list", sortedJson(kotlinx.serialization.json.JsonArray(entries.map { it.json })))
                    }
                    footerLines(listOf(sizeFooter(pushSize, isTooLarge, remote, ProfilePushSize::scheduledSwitchesTooLargeMessage, colors.red)))
                    problemsSection("Can't Send", problems)
                    noteSection(SCHEDULED_SWITCH_DOSING_NOTE)
                }
            }
            BottomActionButton(
                if (checking) "Checking the scheduled switches..." else "Send to ${remote.appName}",
                if (checking) "Checking the scheduled switches..." else "Sending...",
                isSending || checking,
                enabled = canSend,
            ) {
                alert = RemoteAlert(
                    "Save Scheduled Switches?",
                    "${remote.appName} replaces its scheduled switches with this list and switches profiles at these times without asking. Nothing switches right now." +
                        if (increases.isEmpty()) "" else "\n\n" + increases.joinToString(" ") { "${it.first.name} gives more insulin than Default." },
                    confirmTitle = "Send",
                    onConfirm = ::send,
                )
            }
        }
    }
    RemoteAlertDialog(alert) { alert = null }
}
