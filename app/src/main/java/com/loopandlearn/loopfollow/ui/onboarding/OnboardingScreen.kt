package com.loopandlearn.loopfollow.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.NotificationAdd
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.R
import com.loopandlearn.loopfollow.alarm.AlarmPermissions
import com.loopandlearn.loopfollow.alarm.Alarm
import com.loopandlearn.loopfollow.alarm.AlarmType
import com.loopandlearn.loopfollow.alarm.DayNightOption
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.alarms.formatTimeOfDay
import com.loopandlearn.loopfollow.ui.alarms.icon
import com.loopandlearn.loopfollow.ui.alarms.pickTime
import com.loopandlearn.loopfollow.ui.components.BGPickerRow
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.components.FormTextField
import com.loopandlearn.loopfollow.ui.components.MenuPickerRow
import com.loopandlearn.loopfollow.ui.components.SegmentedPicker
import com.loopandlearn.loopfollow.ui.components.StepperRow
import com.loopandlearn.loopfollow.ui.components.TextFieldRow
import com.loopandlearn.loopfollow.ui.components.ToggleRow
import com.loopandlearn.loopfollow.ui.components.formSection
import com.loopandlearn.loopfollow.ui.settings.CreateTokenFromSecretRow
import com.loopandlearn.loopfollow.ui.settings.DexcomCredentialRows
import com.loopandlearn.loopfollow.ui.settings.DexcomSettingsModel
import com.loopandlearn.loopfollow.ui.settings.NightscoutSettingsModel
import com.loopandlearn.loopfollow.ui.settings.rangeModeSections
import com.loopandlearn.loopfollow.ui.settings.rememberDexcomSettingsModel
import com.loopandlearn.loopfollow.ui.settings.rememberNightscoutSettingsModel
import com.loopandlearn.loopfollow.ui.settings.rememberTabOrder
import com.loopandlearn.loopfollow.ui.settings.subscribeUnitsState
import com.loopandlearn.loopfollow.ui.settings.tabOrderSections
import com.loopandlearn.loopfollow.ui.settings.unitsGlucoseSection
import com.loopandlearn.loopfollow.ui.settings.unitsStatisticsSections
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors

/**
 * Notification permission asked before; Android only shows its prompt a limited number of times.
 *
 * ANDROID-DEVIATION: Android has no "not determined" permission state, so "undecided" means not granted and
 * not asked for before.
 */
private val notificationPermissionAsked = com.loopandlearn.loopfollow.storage.storageValue("notificationPermissionAsked", false)

fun notificationsUndecided(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
        !notificationPermissionAsked.value

/** Asks for the notification permission once, outside onboarding. */
@Composable
fun RequestNotificationsIfUndecided() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (notificationsUndecided(context)) {
            notificationPermissionAsked.value = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun OnboardingScreen(onClose: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val ns = rememberNightscoutSettingsModel()
    val dexcom = rememberDexcomSettingsModel()
    val model = remember { OnboardingModel(ns, dexcom, notificationsUndecided(context), !AlarmPermissions.batteryUnrestricted(context), onClose) }
    BackHandler(enabled = model.canGoBack) { model.goBack() }

    // The page covers the app; taps on its empty areas stay here.
    Column(
        Modifier.fillMaxSize().background(colors.groupedBackground).pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        if (model.step.showsProgressHeader) ProgressHeader(model)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = model.step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally { if (forward) it else -it } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it else it } + fadeOut())
                },
                label = "onboarding",
            ) { step ->
                Box(Modifier.fillMaxSize()) {
                    when (step) {
                        OnboardingStep.WELCOME -> WelcomeStep(model)
                        OnboardingStep.OVERVIEW -> OverviewStep(model)
                        OnboardingStep.DATA_SOURCE -> DataSourceStep(model)
                        OnboardingStep.CONNECT -> when (model.dataSource) {
                            OnboardingDataSource.DEXCOM -> DexcomConnectStep(model.dexcom, model)
                            OnboardingDataSource.COPY_FROM_PHONE -> ConnectImportStep(model)
                            else -> NightscoutConnectStep(model.nightscout, model)
                        }
                        OnboardingStep.UNITS -> UnitsStep(model)
                        OnboardingStep.GENERAL_ALARMS -> GeneralAlarmsStep()
                        OnboardingStep.ALARMS -> AlarmsStep(model)
                        OnboardingStep.TAB_ORDER -> TabOrderStep()
                        OnboardingStep.NOTIFICATIONS -> NotificationsStep(model)
                        OnboardingStep.BATTERY -> BatteryStep(model)
                        OnboardingStep.TELEMETRY -> TelemetryStep(model)
                        OnboardingStep.COMPLETION -> CompletionStep(model)
                    }
                }
            }
        }
        if (model.step.usesSharedFooter) {
            NavFooter(continueEnabled = model.canProceed, showBack = model.canGoBack, onBack = model::goBack, onContinue = model::advance)
        }
    }
}

// MARK: - Chrome

@Composable
private fun ProgressHeader(model: OnboardingModel) {
    val colors = LocalAppColors.current
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(
                progress = { model.progress.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                color = colors.blue,
                trackColor = colors.gray.copy(alpha = 0.25f),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Text("Skip", fontSize = 15.sp, color = colors.secondaryText, modifier = Modifier.clickable { model.skip() }.padding(4.dp))
        }
        if (model.progressLabel.isNotEmpty()) Text(model.progressLabel, fontSize = 12.sp, color = colors.secondaryText)
    }
}

@Composable
fun NavFooter(continueTitle: String = "Continue", continueEnabled: Boolean = true, showBack: Boolean = true, onBack: () -> Unit, onContinue: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().background(colors.cell).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            OutlinedButton(onClick = onBack, shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = colors.blue)
                Text("Back", color = colors.blue, fontWeight = FontWeight.Medium, fontSize = 17.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton(continueTitle, enabled = continueEnabled, modifier = Modifier.widthIn(min = 120.dp), onClick = onContinue)
    }
}

@Composable
private fun PrimaryButton(title: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.blue, contentColor = Color.White),
        modifier = modifier,
    ) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
}

@Composable
fun StepHeader(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = colors.blue, modifier = Modifier.size(48.dp).padding(bottom = 2.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.primaryText)
        Text(subtitle, fontSize = 15.sp, color = colors.secondaryText)
    }
}

private fun LazyListScope.headerItem(icon: ImageVector, title: String, subtitle: String) {
    item { StepHeader(icon, title, subtitle, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) }
}

private fun LazyListScope.titleItem(title: String, subtitle: String) {
    item {
        val colors = LocalAppColors.current
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.primaryText)
            Text(subtitle, fontSize = 15.sp, color = colors.secondaryText)
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    Box(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.cell)
            .border(2.dp, if (selected) colors.blue else Color.Transparent, RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun IconTextRow(icon: ImageVector, title: String, detail: String, tint: Color = LocalAppColors.current.blue, trailing: @Composable () -> Unit = {}) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.width(32.dp).size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
            Text(detail, fontSize = 15.sp, color = colors.secondaryText)
        }
        trailing()
    }
}

@Composable
private fun StatusPill(color: Color, message: String, isLoading: Boolean = false, icon: ImageVector? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
            if (isLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalAppColors.current.gray)
            else if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Text(message, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = color, modifier = Modifier.weight(1f))
    }
}

// MARK: - Steps

@Composable
private fun WelcomeStep(model: OnboardingModel) {
    val colors = LocalAppColors.current
    val angle = remember { Animatable(90f) }
    LaunchedEffect(Unit) { angle.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow)) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.height(160.dp), contentAlignment = Alignment.Center) {
                Image(
                    painterResource(R.drawable.loopfollow_logo),
                    null,
                    Modifier.size(140.dp)
                        .graphicsLayer { rotationY = angle.value; cameraDistance = 12f * density }
                        .shadow(12.dp, RoundedCornerShape(32.dp))
                        .clip(RoundedCornerShape(32.dp)),
                )
            }
            Text("Welcome to LoopFollow", fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = colors.primaryText, lineHeight = 40.sp)
            Text(
                if (model.isAlreadyConfigured) "You're already set up. You can skip this guide, or walk through it to review your settings."
                else "Let's get you connected to your data and set up a few recommended alarms. It only takes a few minutes.",
                fontSize = 17.sp,
                color = colors.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (model.isAlreadyConfigured) {
                PrimaryButton("Skip", modifier = Modifier.fillMaxWidth()) { model.skip() }
                TextButton(onClick = model::advance) { Text("Review setup anyway", color = colors.blue, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
            } else {
                PrimaryButton("Get Started", modifier = Modifier.fillMaxWidth()) { model.advance() }
                TextButton(onClick = model::skip) { Text("Skip", color = colors.blue, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun OverviewStep(model: OnboardingModel) {
    val steps = model.activeSteps
    data class Item(val icon: ImageVector, val title: String, val detail: String)
    val items = buildList {
        if (OnboardingStep.CONNECT in steps) add(Item(Icons.Outlined.SettingsInputAntenna, "Connect your data", "Link a Nightscout site or a Dexcom Share account."))
        if (OnboardingStep.UNITS in steps) add(Item(Icons.Outlined.Straighten, "Units & metrics", "Choose how glucose and statistics are shown."))
        if (OnboardingStep.ALARMS in steps) add(Item(Icons.Outlined.NotificationAdd, "Recommended alarms", "Turn on a few useful alarms with sensible defaults."))
        if (OnboardingStep.TAB_ORDER in steps) add(Item(Icons.Outlined.ViewModule, "Finish up", "Arrange your tabs and set notification preferences."))
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (model.hasNothingToSetUp) {
                StepHeader(Icons.Outlined.CheckCircle, "You're all set", "Your data source is connected and your alarms are in place — there's nothing to set up here. You can fine-tune anything later in Settings.", Modifier.padding(top = 16.dp))
            } else {
                StepHeader(Icons.AutoMirrored.Outlined.FormatListBulleted, "Here's what we'll do", "A quick, straightforward setup — about 5 minutes. You can change anything later in Settings.")
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items.forEach { Card { IconTextRow(it.icon, it.title, it.detail) } }
                }
            }
        }
        NavFooter(
            continueTitle = if (model.hasNothingToSetUp) "Done" else "Continue",
            showBack = model.canGoBack,
            onBack = model::goBack,
            onContinue = { if (model.hasNothingToSetUp) model.finish() else model.advance() },
        )
    }
}

@Composable
private fun DataSourceStep(model: OnboardingModel) {
    val colors = LocalAppColors.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        StepHeader(Icons.Outlined.SettingsInputAntenna, "Choose a data source", "LoopFollow needs a glucose data source. Pick one now — you can change or add more later in Settings.")
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            @Composable
            fun choice(source: OnboardingDataSource, icon: ImageVector, title: String, detail: String) {
                val selected = model.dataSource == source
                Card(selected = selected, onClick = { model.dataSource = source }) {
                    IconTextRow(icon, title, detail, tint = if (selected) colors.blue else colors.secondaryText) {
                        Icon(
                            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            null,
                            tint = if (selected) colors.blue else colors.gray.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            choice(OnboardingDataSource.NIGHTSCOUT, Icons.Outlined.Language, "Nightscout", "Follow a Nightscout site. Works with Loop, Trio, and most uploaders. Enables the full set of LoopFollow features.")
            choice(OnboardingDataSource.DEXCOM, Icons.Outlined.Sensors, "Dexcom Share", "Follow glucose directly from a Dexcom Share account, without a Nightscout site.")
            choice(OnboardingDataSource.COPY_FROM_PHONE, Icons.Outlined.QrCode2, "Copy from another phone", "Already using LoopFollow on another phone? Scan its QR code to copy the connection here.")
        }
    }
}

@Composable
private fun NightscoutConnectStep(ns: NightscoutSettingsModel, model: OnboardingModel) {
    val colors = LocalAppColors.current
    var tokenPage by remember { mutableStateOf(false) }
    var createFromSecret by remember { mutableStateOf(false) }
    var apiSecret by remember { mutableStateOf("") }
    LaunchedEffect(tokenPage) { model.phaseProgress = PhaseProgress(if (tokenPage) 1 else 0, if (ns.isConnected && !tokenPage) 1 else 2) }

    val (pillColor, pillIcon) = when (ns.statusKind) {
        NightscoutSettingsModel.StatusKind.IDLE -> colors.secondaryText to Icons.Outlined.Public
        NightscoutSettingsModel.StatusKind.CHECKING -> colors.orange to null
        NightscoutSettingsModel.StatusKind.PENDING -> colors.blue to Icons.Outlined.AccessTime
        NightscoutSettingsModel.StatusKind.NEEDS_TOKEN -> colors.green to Icons.Outlined.CheckCircle
        NightscoutSettingsModel.StatusKind.CONNECTED -> colors.green to Icons.Filled.CheckCircle
        NightscoutSettingsModel.StatusKind.ERROR -> colors.red to Icons.Filled.WarningAmber
    }
    Column(Modifier.fillMaxSize()) {
        StatusPill(pillColor, ns.friendlyStatus, ns.statusKind == NightscoutSettingsModel.StatusKind.CHECKING, pillIcon)
        Box(Modifier.weight(1f)) {
            FormList {
                if (!tokenPage) {
                    titleItem("Connect to Nightscout", "Enter your site address. We'll check it, and only ask for a token if your site needs one.")
                    formSection(header = "Site URL", footer = "Enter or paste your full Nightscout address. If the link already includes a token, we'll fill both in for you.") {
                        FormRow { FormTextField(ns.url, ns::onURLInput, "https://your-site.example.com", Modifier.weight(1f), keyboardType = KeyboardType.Uri) }
                    }
                } else if (ns.isConnected || ns.provisionedTokenPending) {
                    titleItem(
                        if (ns.provisionedTokenPending) "Token created" else "You're connected",
                        if (ns.provisionedTokenPending) "Your read-only token is ready. Tap Continue to keep going — your site may take a few minutes to start accepting it."
                        else "Your read-only token is set up. Tap Continue to keep going.",
                    )
                } else {
                    titleItem("Add a token", "This site needs a token. Paste one, or have LoopFollow create a read-only token from your API secret.")
                    formSection(
                        footer = if (createFromSecret) "Your API secret is used once to create a read-only access token and is never stored."
                        else "Type or paste a token, or a full Nightscout URL that includes a token.",
                    ) {
                        FormRow { SegmentedPicker(listOf(false, true), createFromSecret, { if (it) "Create one for me" else "I have a token" }, { createFromSecret = it }, Modifier.fillMaxWidth()) }
                    }
                    if (!createFromSecret) {
                        formSection(header = "Access Token") {
                            TextFieldRow(ns.token, ns::onTokenInput, "Enter Token", label = "Token", secure = true)
                            CreateTokenFromSecretRow(ns)
                        }
                    } else {
                        formSection(header = "API Secret") {
                            TextFieldRow(apiSecret, { apiSecret = it }, "Enter API Secret", label = "Secret", secure = true)
                            FormDivider()
                            val enabled = !ns.isProvisioningToken && apiSecret.isNotEmpty() && ns.url.isNotEmpty()
                            FormRow(onClick = if (enabled) ({ ns.createReadOnlyToken(apiSecret) }) else null) {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    if (ns.isProvisioningToken) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.gray)
                                    else Text("Create Read-Only Token", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) colors.blue else colors.gray)
                                }
                            }
                            ns.tokenProvisionError?.let {
                                FormDivider()
                                FormRow { Text(it, fontSize = 13.sp, color = colors.red) }
                            }
                        }
                    }
                }
            }
        }
        if (!tokenPage) {
            NavFooter(
                continueEnabled = ns.isConnected || ns.addressNeedsToken,
                showBack = model.canGoBack,
                onBack = model::goBack,
                onContinue = { if (ns.isConnected) model.advance() else if (ns.addressNeedsToken) tokenPage = true },
            )
        } else {
            BackHandler { tokenPage = false }
            NavFooter(
                continueEnabled = ns.isConnected || ns.provisionedTokenPending,
                onBack = { tokenPage = false },
                onContinue = model::advance,
            )
        }
    }
}

@Composable
private fun DexcomConnectStep(dexcom: DexcomSettingsModel, model: OnboardingModel) {
    val colors = LocalAppColors.current
    val (pillColor, pillIcon) = when (dexcom.statusKind) {
        DexcomSettingsModel.StatusKind.IDLE -> colors.secondaryText to Icons.Outlined.Circle
        DexcomSettingsModel.StatusKind.CHECKING -> colors.orange to null
        DexcomSettingsModel.StatusKind.CONNECTED -> colors.green to Icons.Filled.CheckCircle
        DexcomSettingsModel.StatusKind.ERROR -> colors.red to Icons.Filled.WarningAmber
    }
    Column(Modifier.fillMaxSize()) {
        StatusPill(pillColor, dexcom.statusMessage, dexcom.statusKind == DexcomSettingsModel.StatusKind.CHECKING, pillIcon)
        Box(Modifier.weight(1f)) {
            FormList {
                headerItem(Icons.Outlined.Sensors, "Connect Dexcom Share", "Sign in with the Dexcom Share account that shares glucose data.")
                formSection(header = "Dexcom Share") { DexcomCredentialRows(dexcom) }
            }
        }
        NavFooter(continueEnabled = dexcom.canVerifyProceed, showBack = model.canGoBack, onBack = model::goBack, onContinue = model::advance)
    }
}

@Composable
private fun UnitsStep(model: OnboardingModel) {
    subscribeUnitsState()
    var statistics by remember { mutableStateOf(false) }
    var showRange by remember { mutableStateOf(false) }
    LaunchedEffect(statistics) { model.phaseProgress = PhaseProgress(if (statistics) 1 else 0, 2) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            FormList {
                when {
                    !statistics -> {
                        headerItem(Icons.Outlined.Straighten, "Units", "Choose how glucose values are displayed. You can change this later in Settings.")
                        unitsGlucoseSection()
                    }
                    showRange -> rangeModeSections { showRange = false }
                    else -> {
                        headerItem(Icons.Outlined.BarChart, "Statistics", "Choose how LoopFollow's statistics are measured and displayed.")
                        unitsStatisticsSections(onRangeTap = { showRange = true })
                    }
                }
            }
        }
        when {
            !statistics -> NavFooter(showBack = model.canGoBack, onBack = model::goBack, onContinue = { statistics = true })
            showRange -> {
                BackHandler { showRange = false }
                NavFooter(continueTitle = "Done", onBack = { showRange = false }, onContinue = { showRange = false })
            }
            else -> {
                BackHandler { statistics = false }
                NavFooter(onBack = { statistics = false }, onContinue = model::advance)
            }
        }
    }
}

@Composable
private fun GeneralAlarmsStep() {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val cfg by Storage.alarmConfiguration.flow.collectAsStateWithLifecycle()
    FormList {
        headerItem(Icons.Outlined.Tune, "Alarm basics", "Set when your day and night begin, and how alarm sound behaves. You can fine-tune everything later in the Menu.")
        formSection(header = "Day / Night", footer = "Alarms can behave differently during the day and at night.") {
            FormRow(onClick = { pickTime(context, cfg.dayStart) { Storage.alarmConfiguration.value = Storage.alarmConfiguration.value.copy(dayStart = it) } }) {
                FormText("Day starts", modifier = Modifier.weight(1f))
                FormText(formatTimeOfDay(context, cfg.dayStart), color = colors.blue)
            }
            FormDivider()
            FormRow(onClick = { pickTime(context, cfg.nightStart) { Storage.alarmConfiguration.value = Storage.alarmConfiguration.value.copy(nightStart = it) } }) {
                FormText("Night starts", modifier = Modifier.weight(1f))
                FormText(formatTimeOfDay(context, cfg.nightStart), color = colors.blue)
            }
        }
        formSection(header = "Sound", footer = "Overriding the system volume lets alarms be heard even when your phone is silenced or in Do Not Disturb.") {
            ToggleRow("Override system volume", cfg.overrideSystemOutputVolume) {
                Storage.alarmConfiguration.value = Storage.alarmConfiguration.value.copy(overrideSystemOutputVolume = it)
            }
            if (cfg.overrideSystemOutputVolume) {
                FormRow {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = colors.secondaryText)
                    Slider(
                        value = cfg.forcedOutputVolume,
                        onValueChange = { Storage.alarmConfiguration.value = Storage.alarmConfiguration.value.copy(forcedOutputVolume = it) },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(activeTrackColor = colors.blue, thumbColor = Color.White),
                    )
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = colors.secondaryText)
                }
            }
            FormDivider()
            ToggleRow("Play sound during calls", cfg.audioDuringCalls) {
                Storage.alarmConfiguration.value = Storage.alarmConfiguration.value.copy(audioDuringCalls = it)
            }
        }
    }
}

@Composable
private fun AlarmsStep(model: OnboardingModel) {
    val colors = LocalAppColors.current
    var index by remember { mutableIntStateOf(0) }
    val offered = model.offeredSeedAlarms
    LaunchedEffect(index, offered.size) { model.phaseProgress = PhaseProgress(index, offered.size) }
    fun forward() = if (index < offered.size - 1) index++ else model.advance()
    fun back() = if (index > 0) index-- else model.goBack()
    BackHandler { back() }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            val seed = offered.getOrNull(index)
            if (seed != null) {
                FormList {
                    item {
                        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(seed.type.icon, null, tint = colors.blue, modifier = Modifier.size(44.dp))
                            Text(seed.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.primaryText)
                            Text(seed.detail, fontSize = 15.sp, color = colors.secondaryText, textAlign = TextAlign.Center)
                        }
                    }
                    formSection(footer = if (seed.isEnabled) explanation(seed) else null) {
                        ToggleRow("Enable this alarm", seed.isEnabled) { on -> model.updateSeed(seed.id) { it.copy(isEnabled = on) } }
                        if (seed.isEnabled) SeedControls(seed) { transform -> model.updateSeed(seed.id) { it.copy(alarm = transform(it.alarm)) } }
                    }
                }
            }
        }
        NavFooter(onBack = ::back, onContinue = ::forward)
    }
}

@Composable
private fun ColumnScope.SeedControls(seed: SeedAlarm, update: ((Alarm) -> Alarm) -> Unit) {
    val alarm = seed.alarm
    val defaults = Alarm.create(seed.type)
    fun fmt(v: Double) = if (v == Math.rint(v)) v.toInt().toString() else String.format("%.1f", v)

    @Composable
    fun bg(title: String, range: ClosedFloatingPointRange<Double>, value: Double?, set: (Alarm, Double) -> Alarm) {
        FormDivider()
        BGPickerRow(title, range, value ?: 0.0) { v -> update { set(it, v) } }
    }

    @Composable
    fun double(label: String, range: ClosedFloatingPointRange<Double>, step: Double, unit: String, value: Double?, set: (Alarm, Double) -> Alarm) {
        val v = value ?: 0.0
        FormDivider()
        StepperRow(label, v, range, step, "${fmt(v)} $unit") { n -> update { set(it, n) } }
    }

    @Composable
    fun int(label: String, range: IntRange, step: Int, unit: String, value: Int?, set: (Alarm, Int) -> Alarm) {
        val v = value ?: 0
        FormDivider()
        StepperRow(label, v.toDouble(), range.first.toDouble()..range.last.toDouble(), step.toDouble(), if (unit.isEmpty()) "$v" else "$v $unit") { n ->
            update { set(it, n.toInt()) }
        }
    }

    @Composable
    fun active() {
        FormDivider()
        MenuPickerRow("Active during", alarm.activeOption, listOf(DayNightOption.ALWAYS, DayNightOption.DAY, DayNightOption.NIGHT), { it.displayName }) { o ->
            update { it.copy(activeOption = o) }
        }
    }

    when (seed.type) {
        AlarmType.LOW -> {
            bg("Alert below", 40.0..150.0, alarm.belowBG ?: defaults.belowBG) { a, v -> a.copy(belowBG = v) }
            int("Warn early by", 0..30, 5, "min", alarm.predictiveMinutes ?: defaults.predictiveMinutes) { a, v -> a.copy(predictiveMinutes = v) }
        }
        AlarmType.HIGH -> {
            bg("Alert above", 120.0..350.0, alarm.aboveBG ?: defaults.aboveBG) { a, v -> a.copy(aboveBG = v) }
            int("Only after high for", 0..60, 5, "min", alarm.persistentMinutes ?: defaults.persistentMinutes) { a, v -> a.copy(persistentMinutes = v) }
        }
        AlarmType.FAST_DROP -> {
            bg("Drop per reading", 3.0..54.0, alarm.delta ?: defaults.delta) { a, v -> a.copy(delta = v) }
            int("Readings in a row", 1..3, 1, "", alarm.monitoringWindow ?: defaults.monitoringWindow) { a, v -> a.copy(monitoringWindow = v) }
        }
        AlarmType.MISSED_READING -> double("No reading for", 11.0..121.0, 5.0, "min", alarm.threshold ?: defaults.threshold) { a, v -> a.copy(threshold = v) }
        AlarmType.NOT_LOOPING -> double("No loop for", 16.0..61.0, 5.0, "min", alarm.threshold ?: defaults.threshold) { a, v -> a.copy(threshold = v) }
        AlarmType.BATTERY -> double("At or below", 0.0..100.0, 5.0, "%", alarm.threshold ?: defaults.threshold) { a, v -> a.copy(threshold = v) }
        AlarmType.IOB -> double("Alert above", 0.0..30.0, 1.0, "U", alarm.threshold ?: defaults.threshold) { a, v -> a.copy(threshold = v) }
        AlarmType.COB -> double("Alert above", 0.0..200.0, 5.0, "g", alarm.threshold ?: defaults.threshold) { a, v -> a.copy(threshold = v) }
        AlarmType.SENSOR_CHANGE -> {
            int("Sensor lifetime", 7..15, 1, "days", alarm.sensorLifetimeDays ?: defaults.sensorLifetimeDays) { a, v -> a.copy(sensorLifetimeDays = v) }
            double("Remind before", 1.0..24.0, 1.0, "hours", alarm.threshold ?: defaults.threshold) { a, v -> a.copy(threshold = v) }
            active()
        }
        AlarmType.PUMP_CHANGE -> {
            double("Remind before", 1.0..24.0, 1.0, "hours", alarm.threshold ?: defaults.threshold) { a, v -> a.copy(threshold = v) }
            active()
        }
        AlarmType.PUMP -> double("Alert below", 0.0..100.0, 5.0, "U", alarm.threshold ?: defaults.threshold) { a, v -> a.copy(threshold = v) }
        else -> {}
    }
}

/**
 * Plain-language summary of what a recommended alarm will do. Fast drop's per-reading × window behavior isn't
 * obvious from the two controls alone; the change reminders state what their hours count back from.
 */
private fun explanation(seed: SeedAlarm): String? {
    when (seed.type) {
        AlarmType.SENSOR_CHANGE -> {
            val fallback = Alarm.create(AlarmType.SENSOR_CHANGE)
            val days = seed.alarm.sensorLifetimeDays ?: fallback.sensorLifetimeDays ?: 10
            val hours = formatHours(seed.alarm.threshold ?: fallback.threshold ?: 12.0)
            return "Reminds $hours hours before the sensor’s $days-day change-over."
        }
        AlarmType.PUMP_CHANGE -> {
            val hours = formatHours(seed.alarm.threshold ?: Alarm.create(AlarmType.PUMP_CHANGE).threshold ?: 12.0)
            return "Reminds $hours hours before the 3-day pump change limit."
        }
        AlarmType.FAST_DROP -> {}
        else -> return null
    }
    val fallback = Alarm.create(AlarmType.FAST_DROP)
    val delta = seed.alarm.delta ?: fallback.delta ?: 0.0
    val window = seed.alarm.monitoringWindow ?: fallback.monitoringWindow ?: 0
    if (delta <= 0 || window <= 0) return null
    val unit = UnitSettingsStore.glucoseUnit.raw
    val perReading = Localizer.formatQuantity(delta)
    if (window == 1) return "Warns when glucose falls by at least $perReading $unit between two readings."
    val total = Localizer.formatQuantity(delta * window)
    return "Warns when glucose falls by at least $perReading $unit on each of $window readings in a row — about $total $unit over roughly ${window * 5} minutes."
}

private fun formatHours(value: Double): String =
    if (value == Math.rint(value)) value.toLong().toString() else String.format(java.util.Locale.US, "%.1f", value)

@Composable
private fun TabOrderStep() {
    val order = rememberTabOrder()
    FormList {
        headerItem(Icons.Outlined.Dashboard, "Arrange your tabs", "Pick which features sit in the tab bar. You can always reach everything through the Menu.")
        tabOrderSections(order)
    }
}

@Composable
private fun NotificationsStep(model: OnboardingModel) {
    val colors = LocalAppColors.current
    var requesting by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.advance() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Filled.NotificationsActive, null, tint = colors.blue, modifier = Modifier.size(48.dp))
            Text("Stay informed", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.primaryText)
            Text(
                "LoopFollow uses notifications to deliver your alarms — like low or high glucose, or a missed reading. Without them, alarms can't reach you when the app is in the background.",
                fontSize = 17.sp,
                color = colors.secondaryText,
            )
            Text("We'll ask Android for permission next. You can change this any time in the system settings.", fontSize = 15.sp, color = colors.secondaryText)
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Enable Notifications", enabled = !requesting, modifier = Modifier.fillMaxWidth()) {
                requesting = true
                notificationPermissionAsked.value = true
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            TextButton(onClick = model::advance, enabled = !requesting) {
                Text("Not now", color = colors.blue, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** ANDROID-DEVIATION: asks for unrestricted battery use, which Android needs to keep alarms on time; iOS has no such step. */
@Composable
private fun BatteryStep(model: OnboardingModel) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    var requesting by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.advance() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Filled.BatteryChargingFull, null, tint = colors.blue, modifier = Modifier.size(48.dp))
            Text("Keep LoopFollow running", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.primaryText)
            Text(
                "To save battery, Android can pause apps that run in the background. If it pauses LoopFollow, your data stops updating and alarms can be late or missed.",
                fontSize = 17.sp,
                color = colors.secondaryText,
            )
            Text("Android asks next whether LoopFollow may run without battery restrictions. Choose Allow. You can change this any time in Settings > Background Refresh.", fontSize = 15.sp, color = colors.secondaryText)
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Allow Unrestricted Battery Use", enabled = !requesting, modifier = Modifier.fillMaxWidth()) {
                requesting = true
                launcher.launch(AlarmPermissions.batteryRequestIntent(context))
            }
            TextButton(onClick = model::advance, enabled = !requesting) {
                Text("Not now", color = colors.blue, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun TelemetryStep(model: OnboardingModel) {
    val colors = LocalAppColors.current
    var showPreview by remember { mutableStateOf(false) }
    if (showPreview) {
        BackHandler { showPreview = false }
        Column(Modifier.fillMaxSize().background(colors.groupedBackground)) {
            com.loopandlearn.loopfollow.ui.components.NavBar("What's sent", onBack = { showPreview = false })
            com.loopandlearn.loopfollow.ui.settings.TelemetryPreviewContent()
        }
        return
    }
    fun decide(enabled: Boolean) {
        com.loopandlearn.loopfollow.helpers.TelemetryClient.recordDecision(enabled)
        model.advance()
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Analytics, null, tint = colors.blue, modifier = Modifier.size(48.dp))
            Text("Help us help you", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.primaryText)
            // ANDROID-DEVIATION: Says "Android version"; the app runs on Android.
            Text(
                "You can choose to share anonymous information with the developers to help improve LoopFollow — such as app and Android version, device type, which app you're following, and a few settings. Your health data, credentials, time zone, and logs remain on your device.",
                fontSize = 17.sp,
                color = colors.secondaryText,
            )
            Row(Modifier.clickable { showPreview = true }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FindInPage, null, tint = colors.blue, modifier = Modifier.size(18.dp))
                Text("See exactly what's sent", fontSize = 15.sp, color = colors.blue, modifier = Modifier.padding(start = 6.dp))
            }
            Text("You can change this any time in Settings → General → Diagnostics.", fontSize = 15.sp, color = colors.secondaryText)
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Yes, send anonymous stats", modifier = Modifier.fillMaxWidth()) { decide(true) }
            TextButton(onClick = { decide(false) }) {
                Text("No thanks", color = colors.blue, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun CompletionStep(model: OnboardingModel) {
    val colors = LocalAppColors.current
    val scale = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow)) }
    data class Gem(val icon: ImageVector, val title: String, val detail: String)
    // ANDROID-DEVIATION: No "Contact image" tip; contact images for the Apple Watch face are iOS-only.
    val gems = listOf(
        Gem(Icons.Outlined.NotificationAdd, "Custom alarms", "Build your own alarms beyond the recommended set."),
        Gem(Icons.Outlined.SettingsInputAntenna, "Remote commands", "Send carbs, boluses, and temp targets to Trio or Loop."),
    )
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(
                Icons.Filled.CheckCircle,
                null,
                tint = colors.green,
                modifier = Modifier.size(92.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = ((scale.value - 0.6f) / 0.4f).coerceIn(0f, 1f) },
            )
            Text("You're all set", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = colors.primaryText, textAlign = TextAlign.Center)
            Text(
                "You're ready to go. Adjust everything later from the Menu — and when you have a moment, these are worth a look:",
                fontSize = 17.sp,
                color = colors.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        Card(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                gems.forEach { gem ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(gem.icon, null, tint = colors.blue, modifier = Modifier.width(26.dp).size(22.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(gem.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
                            Text(gem.detail, fontSize = 12.sp, color = colors.secondaryText)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
        PrimaryButton("Finish", modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) { model.finish() }
    }
}

