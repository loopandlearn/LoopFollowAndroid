package com.loopandlearn.loopfollow.ui.remote

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vaccines
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.engine.ProfileManager
import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.InsulinFormatter
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.remote.AuthResult
import com.loopandlearn.loopfollow.remote.AuthService
import com.loopandlearn.loopfollow.remote.QUICK_PICK_BOLUS_INFO
import com.loopandlearn.loopfollow.remote.QUICK_PICK_MEAL_INFO
import com.loopandlearn.loopfollow.remote.QuickPickBoluses
import com.loopandlearn.loopfollow.remote.QuickPickMeals
import com.loopandlearn.loopfollow.remote.RecommendedBolus
import com.loopandlearn.loopfollow.remote.RemoteCommandMessage
import com.loopandlearn.loopfollow.remote.loop.LoopAPNSPayload
import com.loopandlearn.loopfollow.remote.loop.LoopAPNSService
import com.loopandlearn.loopfollow.remote.loop.LoopCommands
import com.loopandlearn.loopfollow.remote.profiles.ProfileRemote
import com.loopandlearn.loopfollow.remote.loop.TOTPGenerator
import com.loopandlearn.loopfollow.remote.loop.TOTPService
import com.loopandlearn.loopfollow.remote.loop.dateUsingCurrentSeconds
import com.loopandlearn.loopfollow.remote.remainingTimeText
import com.loopandlearn.loopfollow.remote.uninterruptedSend
import com.loopandlearn.loopfollow.storage.GlucoseConversion
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.NavStack
import com.loopandlearn.loopfollow.ui.Route
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.components.LabeledValueRow
import com.loopandlearn.loopfollow.ui.components.MenuPickerRow
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.components.ToggleRow
import com.loopandlearn.loopfollow.ui.components.formSection
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val OTP_PERIOD = 30L

private fun f2(value: Double) = String.format(Locale.US, "%.2f", value)
private fun f1(value: Double) = String.format(Locale.US, "%.1f", value)

private fun logAPNS(message: String) = LogManager.log(LogManager.Category.APNS, message)

private fun errorAlert(message: String) = RemoteAlert("Error", message)

/** Device owner authentication; an unavailable or failed check goes to [onError], a cancel is silent. */
private fun authenticateLoop(activity: FragmentActivity?, reason: String, onError: (String) -> Unit, onSuccess: () -> Unit) {
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

@Composable
fun LoopRemoteScreen(nav: NavStack, onBack: (() -> Unit)?) {
    val colors = LocalAppColors.current
    val override by Observable.override.flow.collectAsStateWithLifecycle()
    val loopRemoteCommands by Storage.loopRemoteCommands.flow.collectAsStateWithLifecycle()
    val remoteCommands by Storage.remoteCommands.flow.collectAsStateWithLifecycle()
    val keyId by Storage.remoteKeyId.flow.collectAsStateWithLifecycle()
    val apnsKey by Storage.remoteApnsKey.flow.collectAsStateWithLifecycle()
    val teamId by Storage.teamId.flow.collectAsStateWithLifecycle()
    val qrCode by Storage.loopAPNSQrCodeURL.flow.collectAsStateWithLifecycle()
    val deviceToken by Storage.deviceToken.flow.collectAsStateWithLifecycle()
    val bundleId by Storage.bundleId.flow.collectAsStateWithLifecycle()
    var info by remember { mutableStateOf<RemoteAlert?>(null) }
    val configured = keyId.isNotEmpty() && apnsKey.isNotEmpty() && !teamId.isNullOrEmpty() && qrCode.isNotEmpty() &&
        deviceToken.isNotEmpty() && bundleId.isNotEmpty()

    NavScreen("Loop Remote Control", onBack = onBack) {
        if (configured) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CommandButton("Meal", Icons.Outlined.Restaurant, false, Modifier.weight(1f)) { nav.push(Route.LoopCarbs) }
                    CommandButton("Bolus", Icons.Outlined.Vaccines, false, Modifier.weight(1f)) { nav.push(Route.LoopBolus) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CommandButton("Overrides", Icons.Outlined.Tune, override != null, Modifier.weight(1f)) { nav.push(Route.LoopOverrides) }
                    if (ProfileRemote.LOOP.supportsAny(ProfileRemote.LOOP.acceptedCommands(remoteCommands, loopRemoteCommands))) {
                        CommandButton("Profiles", Icons.Outlined.Sync, false, Modifier.weight(1f)) { nav.push(Route.TherapyProfiles(ProfileRemote.LOOP)) }
                    } else {
                        UnavailableCommandButton("Profiles", Icons.Outlined.Sync, Modifier.weight(1f)) {
                            info = RemoteAlert(
                                "Profiles Not Available",
                                "Loop has not said it accepts profile commands. It does once it has a pump and a maximum basal rate set up and can receive remote commands. An older Loop also needs the Profile Save & Load customization, added with the Loop and Learn Customization Select script.",
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
            ) {
                Icon(Icons.Filled.Warning, null, tint = colors.orange, modifier = Modifier.size(80.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Loop APNS Not Configured", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
                    Text(
                        "Please configure Loop APNS settings in Remote Settings to use APNS commands.",
                        fontSize = 17.sp,
                        color = colors.secondaryText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(colors.blue).clickable { nav.push(Route.RemoteSettings) }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.Settings, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("Configure Loop APNS", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
    RemoteAlertDialog(info) { info = null }
}

/** A remote command button for a command the connected Loop build does not offer. */
@Composable
private fun UnavailableCommandButton(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Gray.copy(alpha = 0.35f))
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

/** A 1 s clock for the one-time code; it clears the used-code flag at the start of each period. */
private class OtpClock {
    var remaining by mutableStateOf<Int?>(null)
    var now by mutableLongStateOf(System.currentTimeMillis())
}

@Composable
private fun rememberOtpClock(onTick: (OtpClock) -> Unit = {}): OtpClock {
    val clock = remember { OtpClock() }
    LaunchedEffect(Unit) {
        clock.remaining = null
        while (true) {
            delay(1000)
            val nowMillis = System.currentTimeMillis()
            val newRemaining = (OTP_PERIOD - (nowMillis / 1000.0) % OTP_PERIOD).toInt()
            val current = clock.remaining
            if (current != null && newRemaining > current) TOTPService.resetTOTPUsage()
            if (newRemaining >= 29) TOTPService.resetTOTPUsage()
            clock.remaining = newRemaining
            clock.now = nowMillis
            onTick(clock)
        }
    }
    return clock
}

/** Whether the current code was already used, and whether to warn about it; the warning waits half a second so it does not flash after a send. */
@Composable
private fun totpBlockedState(): Pair<Boolean, Boolean> {
    val lastSent by TOTPService.lastSentTOTP.flow.collectAsStateWithLifecycle()
    val qrCode by Storage.loopAPNSQrCodeURL.flow.collectAsStateWithLifecycle()
    var showWarning by remember { mutableStateOf(false) }
    val blocked = lastSent != null && TOTPGenerator.extractOTPFromURL(qrCode) == lastSent
    LaunchedEffect(Unit) {
        if (TOTPService.isTOTPBlocked(Storage.loopAPNSQrCodeURL.value)) {
            delay(500)
            showWarning = true
        } else {
            showWarning = false
        }
    }
    return blocked to showWarning
}

private fun LazyListScope.totpWarningSection() {
    formSection {
        val colors = LocalAppColors.current
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Warning, null, tint = colors.orange)
                Text("TOTP Code Already Used", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.orange)
            }
            Text(
                "This TOTP code has already been used for a command. Please wait for the next code to be generated before sending another command.",
                fontSize = 12.sp,
                color = colors.secondaryText,
            )
        }
    }
}

private fun LazyListScope.securitySection(clock: OtpClock) {
    formSection(header = "Security") {
        val colors = LocalAppColors.current
        clock.now
        Column(Modifier.padding(16.dp)) {
            Text("Current OTP Code", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
            val otpCode = TOTPGenerator.extractOTPFromURL(Storage.loopAPNSQrCodeURL.value)
            if (otpCode != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        otpCode,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 17.sp,
                        color = colors.green,
                        modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(4.dp))
                            .background(colors.green.copy(alpha = 0.1f)).padding(vertical = 4.dp, horizontal = 8.dp),
                    )
                    Text("(" + (clock.remaining?.let { "${it}s left" } ?: "-") + ")", fontSize = 12.sp, color = colors.secondaryText)
                }
            } else {
                Text("Invalid QR code URL", fontSize = 17.sp, color = colors.red)
            }
        }
    }
}

private fun presentableMinutesFormat(seconds: Double): String {
    val minutes = (seconds / 60).toInt()
    return if (minutes == 0 || minutes > 1) "$minutes minutes" else "$minutes minute"
}

@Composable
fun LoopBolusScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val maxBolus by Storage.maxBolus.flow.collectAsStateWithLifecycle()
    var amount by remember { mutableStateOf(0.0) }
    var busy by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<RemoteAlert?>(null) }
    val recommended = remember { RecommendedBolus.current() }
    val recommendedBolus = recommended?.first
    val lastLoopTime = recommended?.second
    var showOldCalculationWarning by remember { mutableStateOf(false) }
    val quickPicks = remember { QuickPickBoluses.compute(Storage.maxBolus.value) }
    val (isTOTPBlocked, showTOTPWarning) = totpBlockedState()

    fun applyRecommendedBolus() {
        val rec = recommendedBolus ?: return
        if (rec > 0) amount = rec
    }

    val clock = rememberOtpClock { c ->
        val loopTime = lastLoopTime ?: return@rememberOtpClock
        val minutes = ((c.now / 1000.0 - loopTime) / 60).toInt()
        if (minutes > 5 && minutes < 12 && !showOldCalculationWarning) {
            showOldCalculationWarning = true
            alert = RemoteAlert(
                "Old Calculation Warning",
                "This recommended bolus was calculated $minutes minutes ago. New treatments may have occurred since then. Proceed with caution.",
                confirmTitle = "Use Anyway",
                onConfirm = { applyRecommendedBolus() },
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!LoopAPNSService.validateSetup()) {
            alert = errorAlert("Loop APNS setup is incomplete. Please configure all required fields in settings.")
        }
    }

    fun sendInsulinConfirmed() {
        val units = amount
        if (units <= 0 || units > Storage.maxBolus.value) return
        busy = true
        val otpCode = TOTPGenerator.extractOTPFromURL(Storage.loopAPNSQrCodeURL.value)
        if (otpCode == null) {
            busy = false
            alert = errorAlert("Invalid QR code URL. Please re-scan the QR code in settings.")
            return
        }
        scope.launch {
            val error = uninterruptedSend {
                LoopAPNSService.sendBolusViaAPNS(LoopAPNSPayload(LoopAPNSPayload.Type.BOLUS, bolusAmount = units, otp = otpCode)).also {
                    if (it == null) {
                        QuickPickBoluses.record(units)
                        TOTPService.markTOTPAsUsed(Storage.loopAPNSQrCodeURL.value)
                    }
                }
            }
            busy = false
            if (error == null) {
                alert = RemoteAlert("Command Sent", RemoteCommandMessage.SENT, onDismiss = onBack)
                logAPNS("Insulin sent - Amount: ${units}U")
            } else {
                alert = errorAlert(error)
                logAPNS("Failed to send insulin: $error")
            }
        }
    }

    fun sendInsulin() {
        if (amount <= 0) {
            alert = errorAlert("Please enter a valid insulin amount")
            return
        }
        val max = Storage.maxBolus.value
        if (amount > max) {
            alert = errorAlert("Insulin amount (${f2(amount)}U) exceeds the maximum allowed (${f2(max)}U). Please reduce the amount.")
            return
        }
        alert = RemoteAlert(
            "Confirm Insulin",
            "Send ${f2(amount)} units of insulin?",
            confirmTitle = "Send",
            onConfirm = {
                authenticateLoop(activity, "Confirm your identity to send insulin.", { alert = errorAlert(it) }) { sendInsulinConfirmed() }
            },
        )
    }

    NavScreen("Insulin", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                FormList {
                    val ageSeconds = lastLoopTime?.let { clock.now / 1000.0 - it }
                    val minutes = ageSeconds?.let { (it / 60).toInt() }
                    if (recommendedBolus != null && recommendedBolus > 0 && minutes != null && minutes < 12) {
                        formSection(header = "Recommended Bolus") {
                            FormRow(onClick = { applyRecommendedBolus() }) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${f2(recommendedBolus)}U", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
                                    Text("Calculated $minutes minute${if (minutes == 1) "" else "s"} ago", fontSize = 12.sp, color = colors.secondaryText)
                                }
                                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = colors.blue)
                            }
                        }
                    }
                    if (quickPicks.isNotEmpty()) {
                        formSection(header = "Quick-Pick Boluses", info = QUICK_PICK_BOLUS_INFO) {
                            QuickPickChips(quickPicks.map { units -> "${InsulinFormatter.string(units)}U" to { amount = units } })
                        }
                    }
                    formSection {
                        QuantityInputRow("Insulin Amount", amount, { amount = it }, "U", InsulinFormatter.fractionDigits, 5, 0.025, maxBolus) {
                            alert = errorAlert(it)
                        }
                    }
                    if (recommendedBolus != null && ageSeconds != null && minutes != null && minutes < 12) {
                        formSection {
                            Text(
                                "WARNING: New treatments may have occurred since the last recommended bolus was calculated ${presentableMinutesFormat(ageSeconds)} ago.",
                                fontSize = 16.sp,
                                color = if (minutes >= 5) colors.red else colors.yellow,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    if (isTOTPBlocked && showTOTPWarning) totpWarningSection()
                    securitySection(clock)
                }
            }
            BottomActionButton("Send Insulin", "Sending...", busy, enabled = amount > 0 && !isTOTPBlocked) { sendInsulin() }
        }
    }
    RemoteAlertDialog(alert) { alert = null }
}

private const val MAX_PAST_HOURS = 12
private const val TIME_ADJUSTMENT_STEP_MINUTES = 5
private const val MIN_ALLOWED_ABSORPTION_TIME = 0.5
private const val MAX_ALLOWED_ABSORPTION_TIME = 8.0
private const val MIN_ABSORPTION_HOURS = 0
private const val MIN_ABSORPTION_MINUTES = 30
private const val MAX_ABSORPTION_HOURS = 8

private fun absorptionMinuteOptions(hours: Int): List<Int> = when (hours) {
    MIN_ABSORPTION_HOURS -> listOf(MIN_ABSORPTION_MINUTES)
    MAX_ABSORPTION_HOURS -> listOf(0)
    else -> listOf(0, 30)
}

/** Clamps an absorption time to 0.5…8 h in half-hour steps. */
private fun normalizedAbsorption(hours: Int, minutes: Int): Pair<Int, Int> {
    val value = hours + minutes / 60.0
    if (value < MIN_ALLOWED_ABSORPTION_TIME) return MIN_ABSORPTION_HOURS to MIN_ABSORPTION_MINUTES
    if (value >= MAX_ALLOWED_ABSORPTION_TIME) return MAX_ABSORPTION_HOURS to 0
    val options = absorptionMinuteOptions(hours)
    return hours to if (minutes in options) minutes else options.first()
}

@Composable
fun LoopCarbsScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val maxCarbs by Storage.maxCarbs.flow.collectAsStateWithLifecycle()
    val maxFutureHours by Storage.loopMaxFutureCarbHours.flow.collectAsStateWithLifecycle()
    var carbs by remember { mutableStateOf(0.0) }
    var absorptionHours by remember { mutableIntStateOf(3) }
    var absorptionMinutes by remember { mutableIntStateOf(0) }
    var foodType by remember { mutableStateOf("") }
    var consumedDate by remember { mutableStateOf(nowSeconds()) }
    var showAbsorptionPicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<RemoteAlert?>(null) }
    val quickPicks = remember { QuickPickMeals.compute(Storage.maxCarbs.value, false) }
    val (isTOTPBlocked, showTOTPWarning) = totpBlockedState()
    val clock = rememberOtpClock()

    val absorptionTimeValue = absorptionHours + absorptionMinutes / 60.0
    val absorptionTimeText = if (absorptionMinutes == 0) "$absorptionHours hr" else "$absorptionHours hr $absorptionMinutes min"

    fun oldestAcceptedDate() = nowSeconds() - MAX_PAST_HOURS * 3600.0
    fun latestAcceptedDate() = nowSeconds() + Storage.loopMaxFutureCarbHours.value * 3600.0
    fun clamped(date: Double) = minOf(maxOf(date, oldestAcceptedDate()), latestAcceptedDate())

    fun setAbsorptionTime(hours: Int, minutes: Int) {
        val (h, m) = normalizedAbsorption(hours.coerceIn(0, MAX_ABSORPTION_HOURS), minutes)
        absorptionHours = h
        absorptionMinutes = m
    }

    fun pickConsumedDate() {
        val start = Calendar.getInstance().apply { timeInMillis = (consumedDate * 1000).toLong() }
        val dialog = DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                val picked = Calendar.getInstance().apply {
                    timeInMillis = (consumedDate * 1000).toLong()
                    set(year, month, day, hour, minute)
                }
                consumedDate = clamped(picked.timeInMillis / 1000.0)
            }, start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), DateFormat.is24HourFormat(context)).show()
        }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH))
        dialog.setTitle("Consumption Time")
        dialog.datePicker.minDate = (oldestAcceptedDate() * 1000).toLong()
        dialog.datePicker.maxDate = (latestAcceptedDate() * 1000).toLong()
        dialog.show()
    }

    LaunchedEffect(Unit) {
        if (!LoopAPNSService.validateSetup()) {
            alert = errorAlert("Loop APNS setup is incomplete. Please configure all required fields in settings.")
        }
    }
    LaunchedEffect(maxFutureHours) { consumedDate = clamped(consumedDate) }

    fun sendCarbsConfirmed() {
        val amount = carbs
        if (amount <= 0 || amount > Storage.maxCarbs.value) return
        busy = true
        val otpCode = TOTPGenerator.extractOTPFromURL(Storage.loopAPNSQrCodeURL.value)
        if (otpCode == null) {
            busy = false
            alert = errorAlert("Invalid QR code URL. Please re-scan the QR code in settings.")
            return
        }
        val selectedAbsorptionTime = absorptionTimeValue
        if (selectedAbsorptionTime < MIN_ALLOWED_ABSORPTION_TIME || selectedAbsorptionTime > MAX_ALLOWED_ABSORPTION_TIME) {
            busy = false
            alert = errorAlert("Please enter a valid absorption time between ${f1(MIN_ALLOWED_ABSORPTION_TIME)} and ${f1(MAX_ALLOWED_ABSORPTION_TIME)} hours")
            return
        }
        val absorptionText = f1(selectedAbsorptionTime)
        // Current seconds and milliseconds keep Nightscout from seeing two entries at the same instant.
        val adjustedConsumedDate = dateUsingCurrentSeconds(consumedDate)
        val payload = LoopAPNSPayload(
            LoopAPNSPayload.Type.CARBS,
            carbsAmount = amount,
            absorptionTime = selectedAbsorptionTime,
            foodType = foodType.ifEmpty { null },
            consumedDate = adjustedConsumedDate,
            otp = otpCode,
        )
        scope.launch {
            val error = uninterruptedSend {
                LoopAPNSService.sendCarbsViaAPNS(payload).also {
                    if (it == null) {
                        QuickPickMeals.record(amount, 0.0, 0.0, 0.0)
                        TOTPService.markTOTPAsUsed(Storage.loopAPNSQrCodeURL.value)
                    }
                }
            }
            busy = false
            if (error == null) {
                alert = RemoteAlert("Command Sent", RemoteCommandMessage.SENT, onDismiss = onBack)
                logAPNS("Carbs sent - Amount: ${amount}g, Absorption: ${absorptionText}h, Time: ${Date((adjustedConsumedDate * 1000).toLong())}")
            } else {
                alert = errorAlert(error)
                logAPNS("Failed to send carbs: $error")
            }
        }
    }

    fun sendCarbs() {
        if (carbs <= 0) {
            alert = errorAlert("Please enter a valid carb amount")
            return
        }
        val max = Storage.maxCarbs.value
        if (carbs > max) {
            alert = errorAlert("Carbs amount (${carbs.toInt()}g) exceeds the maximum allowed (${max.toInt()}g). Please reduce the amount.")
            return
        }
        val future = Storage.loopMaxFutureCarbHours.value
        val now = nowSeconds()
        if (consumedDate < now - MAX_PAST_HOURS * 3600.0) {
            alert = errorAlert("Time must be within the prior $MAX_PAST_HOURS hours")
            return
        }
        if (consumedDate > now + future * 3600.0) {
            alert = errorAlert("Time must be within the next $future ${if (future == 1) "hour" else "hours"}")
            return
        }
        alert = RemoteAlert(
            "Confirm Carbs",
            "Send ${carbs.toInt()}g of carbs with ${f1(absorptionTimeValue)}h absorption time at ${DateTimeUtils.formattedDate(consumedDate)}?",
            confirmTitle = "Send",
            onConfirm = { sendCarbsConfirmed() },
        )
    }

    NavScreen("Carbs", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                FormList {
                    if (quickPicks.isNotEmpty()) {
                        formSection(header = "Quick-Pick Meals", info = QUICK_PICK_MEAL_INFO) {
                            QuickPickChips(quickPicks.map { meal -> "${meal.carbs.toInt()}g" to { carbs = meal.carbs } })
                        }
                    }
                    formSection {
                        QuantityInputRow("Carbs Amount", carbs, { carbs = it }, "g", 0, 4, 1.0, maxCarbs) { alert = errorAlert(it) }
                        FormDivider()
                        FormRow {
                            FormText("Time")
                            Spacer(Modifier.weight(1f))
                            CircleIconButton(Icons.Outlined.Remove) { consumedDate = clamped(consumedDate - TIME_ADJUSTMENT_STEP_MINUTES * 60) }
                            Text(
                                DateTimeUtils.shortTime(consumedDate),
                                fontSize = 17.sp,
                                color = colors.primaryText,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(min = 58.dp).clickable { pickConsumedDate() }.padding(horizontal = 8.dp),
                            )
                            CircleIconButton(Icons.Outlined.Add) { consumedDate = clamped(consumedDate + TIME_ADJUSTMENT_STEP_MINUTES * 60) }
                        }
                        FormDivider()
                        FormRow {
                            FormText("Food Type")
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FoodTypeButton("🍭", foodType) {
                                    foodType = "🍭"
                                    setAbsorptionTime(0, 30)
                                }
                                FoodTypeButton("🌮", foodType) {
                                    foodType = "🌮"
                                    setAbsorptionTime(3, 0)
                                }
                                FoodTypeButton("🍕", foodType) {
                                    foodType = "🍕"
                                    setAbsorptionTime(5, 0)
                                }
                                FoodTypeButton("🍽️", foodType) {
                                    foodType = "🍽️"
                                    showAbsorptionPicker = true
                                }
                            }
                        }
                        FormDivider()
                        FormRow(onClick = { showAbsorptionPicker = true }) {
                            FormText("Absorption Time")
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Outlined.Info, null, tint = colors.blue, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.weight(1f))
                            FormText(absorptionTimeText, color = colors.secondaryText)
                        }
                    }
                    if (isTOTPBlocked && showTOTPWarning) totpWarningSection()
                    securitySection(clock)
                }
            }
            BottomActionButton("Send Carbs", "Sending...", busy, enabled = carbs > 0 && !isTOTPBlocked) { sendCarbs() }
        }
    }
    if (showAbsorptionPicker) {
        LaunchedEffect(Unit) { setAbsorptionTime(absorptionHours, absorptionMinutes) }
        AlertDialog(
            onDismissRequest = {
                setAbsorptionTime(absorptionHours, absorptionMinutes)
                showAbsorptionPicker = false
            },
            title = { Text("Absorption Time") },
            text = {
                Column {
                    MenuPickerRow("Hours", absorptionHours, (0..MAX_ABSORPTION_HOURS).toList(), { "$it hr" }) {
                        setAbsorptionTime(it, absorptionMinutes)
                    }
                    MenuPickerRow("Minutes", absorptionMinutes, absorptionMinuteOptions(absorptionHours), { "$it min" }) {
                        setAbsorptionTime(absorptionHours, it)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    setAbsorptionTime(absorptionHours, absorptionMinutes)
                    showAbsorptionPicker = false
                }) { Text("Done") }
            },
        )
    }
    RemoteAlertDialog(alert) { alert = null }
}

@Composable
private fun CircleIconButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(LocalAppColors.current.blue).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun FoodTypeButton(emoji: String, selected: String, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected == emoji) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 20.sp)
    }
}

/** A Loop override preset as offered for activation; [duration] in seconds, 0 is indefinite. */
private class OverridePreset(
    val name: String,
    val symbol: String?,
    val targetRange: Pair<Double, Double>?,
    val insulinNeedsScaleFactor: Double?,
    val duration: Double,
) {
    val durationDescription: String
        get() {
            if (duration == 0.0) return "Indefinite"
            val hours = duration.toInt() / 3600
            val minutes = duration.toInt() % 3600 / 60
            return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }
}

/** Presets with their target range in the profile's own unit. */
private fun overridePresets(): List<OverridePreset> = ProfileManager.loopOverrides.map { o ->
    fun profileUnit(mgdl: Double) = if (ProfileManager.unitsAreMgdl) mgdl else mgdl * GlucoseConversion.MGDL_TO_MMOL
    OverridePreset(
        name = o.name,
        symbol = o.symbol.ifEmpty { null },
        targetRange = if (o.targetRange.size >= 2) profileUnit(o.targetRange[0]) to profileUnit(o.targetRange[1]) else null,
        insulinNeedsScaleFactor = o.insulinNeedsScaleFactor,
        duration = (o.duration ?: 0).toDouble(),
    )
}

@Composable
fun LoopOverridePresetsScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val activeNote by Observable.override.flow.collectAsStateWithLifecycle()
    val endAt by Observable.overrideEndAt.flow.collectAsStateWithLifecycle()
    val presets = remember { overridePresets() }
    var isActivating by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf<OverridePreset?>(null) }
    var showModal by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<RemoteAlert?>(null) }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    fun activate(preset: OverridePreset, duration: Double?) {
        isActivating = true
        scope.launch {
            val error = LoopAPNSService.sendOverrideNotification(preset.name, duration)
            isActivating = false
            alert = if (error == null) {
                RemoteAlert("Success", "${preset.name} override activated successfully.", onDismiss = onBack)
            } else {
                RemoteAlert("Error", "Failed to activate override: $error")
            }
        }
    }

    fun cancelOverride() {
        isActivating = true
        scope.launch {
            val error = LoopAPNSService.sendCancelOverrideNotification()
            isActivating = false
            alert = if (error == null) {
                RemoteAlert("Success", "Active override cancelled successfully.", onDismiss = onBack)
            } else {
                RemoteAlert("Error", "Failed to cancel override: $error")
            }
        }
    }

    NavScreen("Remote Overrides", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                FormList {
                    activeNote?.let { note ->
                        formSection(header = "Active Override") {
                            FormRow {
                                Icon(Icons.Filled.CheckCircle, null, tint = colors.green, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Active Override", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
                                    Text(note, fontSize = 15.sp, color = colors.secondaryText)
                                    endAt?.let {
                                        tick
                                        Text(remainingTimeText(it), fontSize = 15.sp, color = colors.secondaryText)
                                    }
                                }
                            }
                            FormDivider()
                            FormRow(onClick = {
                                alert = RemoteAlert(
                                    "Cancel Override",
                                    "Are you sure you want to cancel the active override?",
                                    confirmTitle = "Confirm",
                                    onConfirm = { cancelOverride() },
                                )
                            }) {
                                Icon(Icons.Outlined.Cancel, null, tint = colors.red)
                                Spacer(Modifier.width(8.dp))
                                FormText("Cancel Active Override", color = colors.red)
                            }
                        }
                    }
                    formSection(header = "Available Overrides") {
                        if (presets.isEmpty()) {
                            FormRow {
                                Text("No override presets found. Configure presets in your Loop app.", fontSize = 17.sp, fontStyle = FontStyle.Italic, color = colors.secondaryText)
                            }
                        } else {
                            presets.forEachIndexed { i, preset ->
                                if (i > 0) FormDivider()
                                val activatingThis = isActivating && selectedPreset?.name == preset.name
                                FormRow(onClick = if (activatingThis) null else ({
                                    selectedPreset = preset
                                    showModal = true
                                })) {
                                    preset.symbol?.let {
                                        Text(it, fontSize = 22.sp)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(preset.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            preset.targetRange?.let { (lo, hi) ->
                                                Text("Target: ${Localizer.formatLocalDouble(lo)}-${Localizer.formatLocalDouble(hi)}", fontSize = 12.sp, color = colors.secondaryText)
                                            }
                                            preset.insulinNeedsScaleFactor?.let {
                                                Text("Insulin: ${(it * 100).toInt()}%", fontSize = 12.sp, color = colors.secondaryText)
                                            }
                                            Text("Duration: ${preset.durationDescription}", fontSize = 12.sp, color = colors.secondaryText)
                                        }
                                    }
                                    if (activatingThis) {
                                        CircularProgressIndicator(Modifier.size(18.dp), color = colors.blue, strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.secondaryText)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (isActivating) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = colors.secondaryText, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Please wait...", color = colors.secondaryText)
                }
            }
        }
    }
    val preset = selectedPreset
    if (showModal && preset != null) {
        OverrideActivationDialog(
            preset,
            onActivate = { duration ->
                showModal = false
                activate(preset, duration)
            },
            onCancel = { showModal = false },
        )
    }
    RemoteAlertDialog(alert) { alert = null }
}

private fun formatDuration(hours: Double): String {
    val totalMinutes = (hours * 60).toInt()
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

@Composable
private fun OverrideActivationDialog(preset: OverridePreset, onActivate: (Double?) -> Unit, onCancel: () -> Unit) {
    val colors = LocalAppColors.current
    var enableIndefinitely by remember { mutableStateOf(preset.duration == 0.0) }
    var durationHours by remember { mutableStateOf(if (preset.duration == 0.0) 1.0 else preset.duration / 3600) }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavScreen("Activate Override", actions = {
            Text("Cancel", fontSize = 17.sp, color = colors.blue, modifier = Modifier.clickable(onClick = onCancel).padding(8.dp))
        }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    preset.symbol?.let { Text(it, fontSize = 34.sp) }
                    Text(preset.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText, textAlign = TextAlign.Center)
                    preset.targetRange?.let { (lo, hi) ->
                        Text("Target: ${Localizer.formatLocalDouble(lo)}-${Localizer.formatLocalDouble(hi)}", fontSize = 15.sp, color = colors.secondaryText)
                    }
                    preset.insulinNeedsScaleFactor?.let { Text("Insulin: ${(it * 100).toInt()}%", fontSize = 15.sp, color = colors.secondaryText) }
                    Text("Preset: ${preset.durationDescription}", fontSize = 15.sp, color = colors.secondaryText)
                }
                Spacer(Modifier.height(40.dp))
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!enableIndefinitely) {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Row {
                                Text("Duration", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
                                Spacer(Modifier.weight(1f))
                                Text(formatDuration(durationHours), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.blue)
                            }
                            Slider(
                                value = durationHours.toFloat().coerceIn(0.25f, 24f),
                                onValueChange = { durationHours = Math.round(it * 4) / 4.0 },
                                valueRange = 0.25f..24f,
                                steps = 94,
                                colors = SliderDefaults.colors(thumbColor = colors.blue, activeTrackColor = colors.blue),
                            )
                            Row {
                                Text("15m", fontSize = 12.sp, color = colors.secondaryText)
                                Spacer(Modifier.weight(1f))
                                Text("24h", fontSize = 12.sp, color = colors.secondaryText)
                            }
                        }
                    }
                    ToggleRow("Enable indefinitely", enableIndefinitely) { enableIndefinitely = it }
                }
                Spacer(Modifier.height(24.dp))
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { onActivate(if (enableIndefinitely) 0.0 else durationHours * 3600) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.blue, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    ) { Text("Activate Override", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                    Button(
                        onClick = onCancel,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(alpha = 0.2f), contentColor = colors.secondaryText),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    ) { Text("Cancel", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}
