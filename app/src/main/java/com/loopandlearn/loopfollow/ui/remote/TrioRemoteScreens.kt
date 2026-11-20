package com.loopandlearn.loopfollow.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vaccines
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.engine.ProfileManager
import com.loopandlearn.loopfollow.engine.cFormat
import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.InsulinFormatter
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.remote.AuthResult
import com.loopandlearn.loopfollow.remote.AuthService
import com.loopandlearn.loopfollow.remote.BolusSteps
import com.loopandlearn.loopfollow.remote.QUICK_PICK_BOLUS_INFO
import com.loopandlearn.loopfollow.remote.QUICK_PICK_MEAL_INFO
import com.loopandlearn.loopfollow.remote.QuickPickBoluses
import com.loopandlearn.loopfollow.remote.QuickPickMeals
import com.loopandlearn.loopfollow.remote.RecommendedBolus
import com.loopandlearn.loopfollow.remote.RemoteCommandMessage
import com.loopandlearn.loopfollow.remote.TempTargetPresets
import com.loopandlearn.loopfollow.remote.TrioRemote
import com.loopandlearn.loopfollow.remote.profiles.ProfileRemote
import androidx.compose.material.icons.outlined.Sync
import com.loopandlearn.loopfollow.remote.remainingTimeText
import com.loopandlearn.loopfollow.remote.uninterruptedSend
import com.loopandlearn.loopfollow.storage.GlucoseDisplayUnit
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.NavStack
import com.loopandlearn.loopfollow.ui.Route
import com.loopandlearn.loopfollow.ui.components.ButtonRow
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.components.FormTextField
import com.loopandlearn.loopfollow.ui.components.LabeledValueRow
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.components.ToggleRow
import com.loopandlearn.loopfollow.ui.components.formSection
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/** Remote tab: the command set for the configured remote type. */
@Composable
fun RemoteScreen(nav: NavStack, onBack: (() -> Unit)?) {
    val type by Storage.remoteType.flow.collectAsStateWithLifecycle()
    val device by Storage.device.flow.collectAsStateWithLifecycle()
    when (type) {
        com.loopandlearn.loopfollow.storage.RemoteType.TRC -> {
            if (device == "Trio") {
                TrioRemoteControlScreen(nav, onBack)
            } else {
                NavScreen("Remote", onBack = onBack) { Message("Trio Remote Control is only supported for 'Trio'") }
            }
        }
        com.loopandlearn.loopfollow.storage.RemoteType.LOOP_APNS -> LoopRemoteScreen(nav, onBack)
        com.loopandlearn.loopfollow.storage.RemoteType.NONE -> NavScreen("Remote", onBack = onBack) {
            Message("Please select a Remote Type in Settings.")
        }
    }
}

@Composable
fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = LocalAppColors.current.secondaryText, fontSize = 17.sp)
    }
}

@Composable
private fun TrioRemoteControlScreen(nav: NavStack, onBack: (() -> Unit)?) {
    val override by Observable.override.flow.collectAsStateWithLifecycle()
    val tempTarget by Observable.tempTarget.flow.collectAsStateWithLifecycle()
    val remoteCommands by Storage.remoteCommands.flow.collectAsStateWithLifecycle()
    NavScreen("Trio Remote Control", onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CommandButton("Meal", Icons.Outlined.Restaurant, false, Modifier.weight(1f)) { nav.push(Route.TrioMeal) }
                CommandButton("Bolus", Icons.Outlined.Vaccines, false, Modifier.weight(1f)) { nav.push(Route.TrioBolus) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CommandButton("Temp Target", Icons.Outlined.TrackChanges, tempTarget != null, Modifier.weight(1f)) { nav.push(Route.TrioTempTarget) }
                CommandButton("Overrides", Icons.Outlined.Tune, override != null, Modifier.weight(1f)) { nav.push(Route.TrioOverride) }
            }
            if (ProfileRemote.TRIO.supportsAny(remoteCommands)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CommandButton("Profiles", Icons.Outlined.Sync, false, Modifier.weight(1f)) { nav.push(Route.TherapyProfiles(ProfileRemote.TRIO)) }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun CommandButton(title: String, icon: ImageVector, isActive: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier
            .then(if (isActive) Modifier.shadow(9.dp, RoundedCornerShape(8.dp), ambientColor = colors.green, spotColor = colors.green) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.blue)
            .then(if (isActive) Modifier.border(2.5.dp, colors.green, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .heightIn(min = 100.dp)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(50.dp))
        Text(title, color = Color.White, fontSize = 17.sp)
    }
}

/** Confirms with the device owner, then runs [onSuccess]; reports anything else as an alert. */
fun authenticateThen(activity: FragmentActivity?, reason: String, showAlert: (RemoteAlert) -> Unit, onSuccess: () -> Unit) {
    if (activity == null) {
        showAlert(RemoteAlert("Validation Error", "Authentication failed"))
        return
    }
    AuthService.authenticate(activity, reason) { result ->
        when (result) {
            AuthResult.Success -> onSuccess()
            is AuthResult.Unavailable -> showAlert(RemoteAlert("Validation Error", result.message))
            AuthResult.Failed -> showAlert(RemoteAlert("Validation Error", "Authentication failed"))
            AuthResult.Canceled -> {}
        }
    }
}

@Composable
fun TrioBolusScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val maxBolus by Storage.maxBolus.flow.collectAsStateWithLifecycle()
    var amount by remember { mutableStateOf(0.0) }
    var busy by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<RemoteAlert?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val quickPicks = remember { QuickPickBoluses.compute(Storage.maxBolus.value) }

    fun apply(units: Double) {
        amount = BolusSteps.roundedToStep(minOf(units, Storage.maxBolus.value))
    }

    fun send() {
        val units = amount
        // The guardrail holds at send time too.
        if (units <= 0 || units > Storage.maxBolus.value) return
        busy = true
        scope.launch {
            val error = uninterruptedSend {
                TrioRemote.bolus(units).also { if (it == null) QuickPickBoluses.record(units) }
            }
            busy = false
            if (error == null) {
                LogManager.log(LogManager.Category.APNS, "sendBolusPushNotification succeeded - Bolus: ${InsulinFormatter.string(units)} U")
                amount = 0.0
                alert = RemoteAlert("Command Sent", RemoteCommandMessage.SENT, onDismiss = onBack)
            } else {
                LogManager.log(LogManager.Category.APNS, "sendBolusPushNotification failed with error: $error")
                alert = RemoteAlert("Status", error)
            }
        }
    }

    NavScreen("Bolus", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                FormList {
                    val recommended = RecommendedBolus.current()
                    if (recommended != null) {
                        val (rec, at) = recommended
                        val ageSec = maxOf(0.0, now / 1000.0 - at)
                        if (ageSec < 12 * 60) {
                            val stepped = BolusSteps.roundedToStep(minOf(rec, maxBolus))
                            if (stepped > 0) {
                                val mins = (ageSec / 60).toInt()
                                formSection(header = "Recommended Bolus") {
                                    FormRow(onClick = {
                                        if (ageSec >= 5 * 60) {
                                            alert = RemoteAlert(
                                                "Old Calculation Warning",
                                                "This recommended bolus was calculated $mins minutes ago. New treatments may have occurred since then. Proceed with caution.",
                                                confirmTitle = "Use Anyway",
                                                onConfirm = { RecommendedBolus.current()?.let { apply(it.first) } },
                                            )
                                        } else {
                                            apply(stepped)
                                        }
                                    }) {
                                        Column(Modifier.weight(1f)) {
                                            FormText("${InsulinFormatter.string(stepped)}U")
                                            Text("Calculated $mins minute${if (mins == 1) "" else "s"} ago", fontSize = 12.sp, color = colors.secondaryText)
                                        }
                                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = colors.blue)
                                    }
                                }
                                formSection {
                                    Text(
                                        "WARNING: New treatments may have occurred since the last recommended bolus was calculated $mins minute${if (mins == 1) "" else "s"} ago.",
                                        fontSize = 16.sp,
                                        color = if (ageSec >= 5 * 60) colors.red else colors.yellow,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (quickPicks.isNotEmpty()) {
                        formSection(header = "Quick-Pick Boluses", footer = QUICK_PICK_BOLUS_INFO) {
                            QuickPickChips(quickPicks.map { "${InsulinFormatter.string(it)}U" to { apply(it) } })
                        }
                    }
                    formSection {
                        QuantityInputRow("Bolus Amount", amount, { amount = it }, "U", InsulinFormatter.fractionDigits, 5, 0.0, maxBolus) {
                            alert = RemoteAlert("Validation Error", it)
                        }
                    }
                }
            }
            BottomActionButton("Send Bolus", "Sending Bolus...", busy) {
                val stepped = BolusSteps.roundedToStep(amount)
                if (stepped > 0) {
                    amount = stepped
                    alert = RemoteAlert(
                        "Confirm Bolus",
                        "Are you sure you want to send ${InsulinFormatter.string(stepped)} U?",
                        confirmTitle = "Confirm",
                        onConfirm = { authenticateThen(activity, "Confirm your identity to send bolus.", { alert = it }) { send() } },
                    )
                }
            }
        }
    }
    RemoteAlertDialog(alert) { alert = null }
}

@Composable
fun TrioMealScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val withBolus by Storage.mealWithBolus.flow.collectAsStateWithLifecycle()
    val withFatProtein by Storage.mealWithFatProtein.flow.collectAsStateWithLifecycle()
    var carbs by remember { mutableStateOf(0.0) }
    var fat by remember { mutableStateOf(0.0) }
    var protein by remember { mutableStateOf(0.0) }
    var bolus by remember { mutableStateOf(0.0) }
    var scheduling by remember { mutableStateOf(false) }
    var selectedTime by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<RemoteAlert?>(null) }
    val quickPicks = remember { QuickPickMeals.compute(Storage.maxCarbs.value, Storage.mealWithFatProtein.value) }
    val validation: (String) -> Unit = { alert = RemoteAlert("Validation Error", it) }

    fun send() {
        busy = true
        val scheduled = if (scheduling) selectedTime?.let { (h, m) ->
            val now = Calendar.getInstance()
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
                set(Calendar.SECOND, now.get(Calendar.SECOND))
            }.timeInMillis / 1000.0
        } else {
            null
        }
        val b = if (withBolus) bolus else 0.0
        scope.launch {
            val error = uninterruptedSend {
                TrioRemote.meal(carbs, protein, fat, b, scheduled).also {
                    if (it == null && carbs > 0) QuickPickMeals.record(carbs, fat, protein, b)
                }
            }
            busy = false
            if (error == null) {
                LogManager.log(LogManager.Category.APNS, "sendMealPushNotification succeeded - Carbs: $carbs g, Protein: $protein g, Fat: $fat g, Bolus: $b U, Scheduled: ${scheduled?.let { DateTimeUtils.shortTime(it) } ?: "now"}")
                carbs = 0.0
                protein = 0.0
                fat = 0.0
                selectedTime = null
                scheduling = false
                alert = RemoteAlert("Command Sent", RemoteCommandMessage.SENT, onDismiss = onBack)
            } else {
                LogManager.log(LogManager.Category.APNS, "sendMealPushNotification failed with error: $error")
                alert = RemoteAlert("Status", error)
            }
        }
    }

    NavScreen("Meal", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                FormList {
                    if (quickPicks.isNotEmpty()) {
                        formSection(header = "Quick-Pick Meals", footer = QUICK_PICK_MEAL_INFO) {
                            QuickPickChips(quickPicks.map { meal ->
                                val lines = buildList {
                                    add("${meal.carbs.toInt()}g")
                                    if (withFatProtein && (meal.fat > 0 || meal.protein > 0)) add("F${meal.fat.toInt()} P${meal.protein.toInt()}")
                                    if (withBolus && meal.bolus > 0) add("${InsulinFormatter.string(meal.bolus)}U")
                                }
                                lines.joinToString("\n") to {
                                    carbs = minOf(meal.carbs, Storage.maxCarbs.value)
                                    if (withFatProtein) {
                                        fat = minOf(meal.fat, Storage.maxFat.value)
                                        protein = minOf(meal.protein, Storage.maxProtein.value)
                                    }
                                    if (withBolus && meal.bolus > 0) bolus = minOf(meal.bolus, Storage.maxBolus.value)
                                }
                            })
                        }
                    }
                    formSection(header = "Meal Data") {
                        QuantityInputRow("Carbs", carbs, { carbs = it }, "g", 0, 4, 0.0, Storage.maxCarbs.value, validation)
                        if (withFatProtein) {
                            FormDivider()
                            QuantityInputRow("Fat", fat, { fat = it }, "g", 0, 4, 0.0, Storage.maxFat.value, validation)
                            FormDivider()
                            QuantityInputRow("Protein", protein, { protein = it }, "g", 0, 4, 0.0, Storage.maxProtein.value, validation)
                        }
                        if (withBolus) {
                            FormDivider()
                            QuantityInputRow("Bolus Amount", bolus, { bolus = it }, "U", InsulinFormatter.fractionDigits, 4, 0.0, Storage.maxBolus.value, validation)
                        }
                    }
                    formSection(header = "Schedule") {
                        ToggleRow("Schedule for later", scheduling) {
                            scheduling = it
                            if (it && selectedTime == null) {
                                val c = Calendar.getInstance()
                                selectedTime = c.get(Calendar.HOUR_OF_DAY) to c.get(Calendar.MINUTE)
                            }
                        }
                        if (scheduling) {
                            FormDivider()
                            val (h, m) = selectedTime ?: (0 to 0)
                            FormRow(onClick = {
                                com.loopandlearn.loopfollow.ui.alarms.pickTime(context, com.loopandlearn.loopfollow.alarm.TimeOfDay(h, m)) { selectedTime = it.hour to it.minute }
                            }) {
                                FormText("Select Time")
                                Spacer(Modifier.weight(1f))
                                Text(com.loopandlearn.loopfollow.ui.alarms.formatTimeOfDay(context, com.loopandlearn.loopfollow.alarm.TimeOfDay(h, m)), fontSize = 17.sp, color = colors.blue)
                            }
                            if (withBolus && bolus > 0) {
                                FormDivider()
                                FormRow { Text("Note: The meal will be scheduled, but the bolus is enacted immediately.", fontSize = 15.sp, color = colors.secondaryText) }
                            }
                        }
                    }
                }
            }
            BottomActionButton("Send Meal", "Sending Meal Data...", busy) {
                if (carbs == 0.0 && protein == 0.0 && fat == 0.0) return@BottomActionButton
                val b = if (withBolus) bolus else 0.0
                if (b > Storage.maxBolus.value) return@BottomActionButton
                var message = "Are you sure you want to send the meal data"
                message += if (scheduling && selectedTime != null) {
                    " for ${com.loopandlearn.loopfollow.ui.alarms.formatTimeOfDay(context, com.loopandlearn.loopfollow.alarm.TimeOfDay(selectedTime!!.first, selectedTime!!.second))}?"
                } else {
                    " now?"
                }
                if (carbs > 0) message += "\nCarbs: ${cFormat(carbs, 0)} g"
                if (protein > 0) message += "\nProtein: ${cFormat(protein, 0)} g"
                if (fat > 0) message += "\nFat: ${cFormat(fat, 0)} g"
                if (b > 0) message += "\nBolus: ${cFormat(b, 2)} U"
                alert = RemoteAlert("Confirm Meal", message, confirmTitle = "Confirm", onConfirm = {
                    if (b > 0) authenticateThen(activity, "Confirm your identity to send bolus.", { alert = it }) { send() } else send()
                })
            }
        }
    }
    RemoteAlertDialog(alert) { alert = null }
}

@Composable
fun TrioOverrideScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val active by Observable.override.flow.collectAsStateWithLifecycle()
    val endAt by Observable.overrideEndAt.flow.collectAsStateWithLifecycle()
    val device by Storage.device.flow.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<RemoteAlert?>(null) }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    fun run(action: suspend () -> String?, name: String) {
        busy = true
        scope.launch {
            val error = action()
            busy = false
            if (error == null) {
                LogManager.log(LogManager.Category.APNS, "$name succeeded")
                alert = RemoteAlert("Command Sent", RemoteCommandMessage.SENT, onDismiss = onBack)
            } else {
                LogManager.log(LogManager.Category.APNS, "$name failed. Error: $error")
                alert = RemoteAlert("Error", error)
            }
        }
    }

    NavScreen("Overrides", onBack = onBack) {
        if (device != "Trio") {
            Message("Remote commands are currently only available for Trio.")
            return@NavScreen
        }
        FormList {
            active?.let { note ->
                formSection(header = "Active Override") {
                    LabeledValueRow("Override", note)
                    endAt?.let {
                        tick
                        FormDivider()
                        LabeledValueRow("Time Remaining", remainingTimeText(it))
                    }
                    FormDivider()
                    FormRow(onClick = {
                        alert = RemoteAlert("Cancel Override", "Are you sure you want to cancel the active override?", confirmTitle = "Confirm", onConfirm = {
                            run({ TrioRemote.cancelOverride() }, "sendCancelOverridePushNotification")
                        })
                    }) {
                        FormText("Cancel Override", color = colors.red, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.Cancel, null, tint = colors.red, modifier = Modifier.size(28.dp))
                    }
                }
            }
            formSection(header = "Available Overrides") {
                val overrides = ProfileManager.trioOverrides
                if (overrides.isEmpty()) {
                    FormRow { FormText("No overrides available.", color = colors.secondaryText) }
                } else {
                    overrides.forEachIndexed { i, o ->
                        if (i > 0) FormDivider()
                        FormRow(onClick = {
                            alert = RemoteAlert("Activate Override", "Do you want to activate the override '${o.name}'?", confirmTitle = "Confirm", onConfirm = {
                                run({ TrioRemote.startOverride(o.name) }, "sendOverridePushNotification for override: ${o.name}")
                            })
                        }) {
                            Column(Modifier.weight(1f)) {
                                Text(o.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
                                o.duration?.let { Text("Duration: ${it.toInt()} minutes", fontSize = 15.sp, color = colors.secondaryText) }
                                o.percentage?.let { Text("Percentage: ${it.toInt()}%", fontSize = 15.sp, color = colors.secondaryText) }
                                o.target?.let { Text("Target: ${Localizer.formatQuantity(it)} ${UnitSettingsStore.glucoseUnit.raw}", fontSize = 15.sp, color = colors.secondaryText) }
                            }
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = colors.blue)
                        }
                    }
                }
            }
            if (busy) item { Text("Please wait...", color = colors.secondaryText, modifier = Modifier.padding(16.dp)) }
        }
    }
    RemoteAlertDialog(alert) { alert = null }
}

@Composable
fun TrioTempTargetScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val active by Observable.tempTarget.flow.collectAsStateWithLifecycle()
    val endAt by Observable.tempTargetEndAt.flow.collectAsStateWithLifecycle()
    val device by Storage.device.flow.collectAsStateWithLifecycle()
    val presets by TempTargetPresets.presets.flow.collectAsStateWithLifecycle()
    val mmol = UnitSettingsStore.glucoseUnit == GlucoseDisplayUnit.MMOLL
    // The target field is in the display unit; commands and presets use mg/dL.
    var targetDisplay by remember { mutableStateOf(0.0) }
    var duration by remember { mutableStateOf(0.0) }
    var busy by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<RemoteAlert?>(null) }
    var askPresetName by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val targetMgdl = UnitSettingsStore.convertDisplayToMgdl(targetDisplay)
    val guarded = rememberGuardedAction()
    val disabled = targetDisplay == 0.0 || duration == 0.0 || busy
    val unit = UnitSettingsStore.glucoseUnit.raw

    fun run(action: suspend () -> String?, name: String) {
        busy = true
        scope.launch {
            val error = action()
            busy = false
            if (error == null) {
                LogManager.log(LogManager.Category.APNS, "$name succeeded")
                alert = RemoteAlert("Command Sent", RemoteCommandMessage.SENT, onDismiss = onBack)
            } else {
                LogManager.log(LogManager.Category.APNS, "$name failed. Error: $error")
                alert = RemoteAlert("Status", error)
            }
        }
    }

    fun confirmEnact(mgdl: Double, minutes: Double) {
        alert = RemoteAlert(
            "Confirm Command",
            "New Target: ${Localizer.formatQuantity(mgdl)} $unit\nDuration: ${minutes.toInt()} minutes",
            confirmTitle = "Confirm",
            onConfirm = { run({ TrioRemote.tempTarget(mgdl, minutes) }, "sendTempTargetPushNotification") },
        )
    }

    NavScreen("Remote", onBack = onBack) {
        if (device != "Trio") {
            Message("Remote commands are currently only available for Trio.")
            return@NavScreen
        }
        FormList {
            active?.let { target ->
                formSection(header = "Existing Temp Target") {
                    LabeledValueRow("Current Target", "${Localizer.formatQuantity(target)} $unit")
                    endAt?.let {
                        tick
                        FormDivider()
                        LabeledValueRow("Time Remaining", remainingTimeText(it))
                    }
                    FormDivider()
                    FormRow(onClick = {
                        alert = RemoteAlert("Cancel Temp Target", "Are you sure you want to cancel the existing temp target?", confirmTitle = "Confirm", onConfirm = {
                            run({ TrioRemote.cancelTempTarget() }, "sendCancelTempTargetPushNotification")
                        })
                    }) {
                        FormText("Cancel Temp Target", color = colors.red, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.Cancel, null, tint = colors.red, modifier = Modifier.size(28.dp))
                    }
                }
            }
            formSection(header = "Temporary Target") {
                val lo = UnitSettingsStore.convertMgdlToDisplay(80.0)
                val hi = UnitSettingsStore.convertMgdlToDisplay(200.0)
                QuantityInputRow("Target", targetDisplay, { targetDisplay = it }, unit, if (mmol) 1 else 0, 4, lo, hi) { alert = RemoteAlert("Validation Error", it) }
                FormDivider()
                QuantityInputRow("Duration", duration, { duration = it }, "minutes", 0, 4, 5.0, null) { alert = RemoteAlert("Validation Error", it) }
                FormDivider()
                FormRow {
                    Text("Enact", fontSize = 15.sp, color = if (disabled) colors.gray else colors.blue, modifier = Modifier.clickable(enabled = !disabled) {
                        guarded {
                            val mgdl = UnitSettingsStore.convertDisplayToMgdl(targetDisplay)
                            if (targetDisplay > 0 && duration > 0) confirmEnact(mgdl, duration)
                        }
                    })
                    Spacer(Modifier.weight(1f))
                    Text("Save as Preset", fontSize = 15.sp, color = if (disabled) colors.gray else colors.blue, modifier = Modifier.clickable(enabled = !disabled) { guarded { if (targetDisplay > 0 && duration > 0) askPresetName = true } })
                }
            }
            if (presets.isNotEmpty()) {
                formSection(header = "Presets") {
                    presets.forEachIndexed { i, p ->
                        if (i > 0) FormDivider()
                        FormRow(onClick = {
                            targetDisplay = UnitSettingsStore.convertMgdlToDisplay(p.targetValue)
                            duration = p.durationValue
                            confirmEnact(p.targetValue, p.durationValue)
                        }) {
                            FormText(p.name, modifier = Modifier.weight(1f))
                            Text("Delete", fontSize = 15.sp, color = colors.red, modifier = Modifier.clickable { TempTargetPresets.delete(p.id) })
                        }
                    }
                }
            }
            if (busy) item { Text("Please wait...", color = colors.secondaryText, modifier = Modifier.padding(16.dp)) }
        }
    }
    if (askPresetName) {
        AlertDialog(
            onDismissRequest = { askPresetName = false },
            title = { Text("Preset Name") },
            text = { FormTextField(presetName, { presetName = it }, "Name", Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    askPresetName = false
                    if (presetName.isNotBlank()) TempTargetPresets.add(presetName.trim(), targetMgdl, duration)
                    presetName = ""
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { askPresetName = false }) { Text("Cancel") } },
        )
    }
    RemoteAlertDialog(alert) { alert = null }
}
