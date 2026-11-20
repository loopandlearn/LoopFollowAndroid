package com.loopandlearn.loopfollow.ui.remote

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.remote.RemoteDiagnostics
import com.loopandlearn.loopfollow.remote.loop.LoopAPNSService
import com.loopandlearn.loopfollow.remote.loop.TOTPGenerator
import com.loopandlearn.loopfollow.storage.RemoteType
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.components.FormTextField
import com.loopandlearn.loopfollow.ui.components.MenuPickerRow
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.components.ToggleRow
import com.loopandlearn.loopfollow.ui.components.formSection
import com.loopandlearn.loopfollow.ui.components.ScanQRCode
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val OTP_PERIOD = 30L

@Composable
fun RemoteSettingsScreen(onBack: () -> Unit, onImportExport: () -> Unit) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val remoteType by Storage.remoteType.flow.collectAsStateWithLifecycle()
    val device by Storage.device.flow.collectAsStateWithLifecycle()
    val mealWithBolus by Storage.mealWithBolus.flow.collectAsStateWithLifecycle()
    val mealWithFatProtein by Storage.mealWithFatProtein.flow.collectAsStateWithLifecycle()
    val maxFutureCarbHours by Storage.loopMaxFutureCarbHours.flow.collectAsStateWithLifecycle()
    val incrementDetected by Storage.bolusIncrementDetected.flow.collectAsStateWithLifecycle()
    val bolusIncrement by Storage.bolusIncrement.flow.collectAsStateWithLifecycle()
    val maxBolus by Storage.maxBolus.flow.collectAsStateWithLifecycle()
    val maxCarbs by Storage.maxCarbs.flow.collectAsStateWithLifecycle()
    val maxFat by Storage.maxFat.flow.collectAsStateWithLifecycle()
    val maxProtein by Storage.maxProtein.flow.collectAsStateWithLifecycle()
    val user by Storage.user.flow.collectAsStateWithLifecycle()
    val sharedSecret by Storage.sharedSecret.flow.collectAsStateWithLifecycle()
    val keyId by Storage.remoteKeyId.flow.collectAsStateWithLifecycle()
    val teamId by Storage.teamId.flow.collectAsStateWithLifecycle()
    val qrUrl by Storage.loopAPNSQrCodeURL.flow.collectAsStateWithLifecycle()
    val deviceToken by Storage.deviceToken.flow.collectAsStateWithLifecycle()
    val production by Storage.productionEnvironment.flow.collectAsStateWithLifecycle()
    val bundleId by Storage.bundleId.flow.collectAsStateWithLifecycle()
    // The key field keeps what was typed; storage gets the normalized PEM.
    var apnsKeyText by remember { mutableStateOf(Storage.remoteApnsKey.value) }
    var diagnostics by remember { mutableStateOf(RemoteDiagnostics()) }
    var alert by remember { mutableStateOf<RemoteAlert?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var otpTicked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
            otpTicked = true
        }
    }
    val validation: (String) -> Unit = { alert = RemoteAlert("Validation Error", it) }

    val scanner = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        val code = result
        if (code != null) {
            Storage.loopAPNSQrCodeURL.value = code
            Storage.device.value = "Loop"
            Storage.remoteType.value = RemoteType.LOOP_APNS
            scanError = null
            LogManager.log(LogManager.Category.APNS, "Loop APNS QR code scanned")
        }
    }

    fun runDiagnostics() {
        diagnostics = RemoteDiagnostics(RemoteDiagnostics.Status.Running)
        scope.launch { diagnostics = RemoteDiagnostics.run() }
    }

    NavScreen("Remote Settings", onBack = onBack) {
        FormList {
            formSection {
                RemoteTypeRow("None", remoteType == RemoteType.NONE, true) { Storage.remoteType.value = RemoteType.NONE }
                FormDivider()
                RemoteTypeRow("Loop Remote Control", remoteType == RemoteType.LOOP_APNS, device == "Loop") { Storage.remoteType.value = RemoteType.LOOP_APNS }
                FormDivider()
                RemoteTypeRow("Trio Remote Control", remoteType == RemoteType.TRC, device == "Trio") { Storage.remoteType.value = RemoteType.TRC }
            }

            formSection {
                com.loopandlearn.loopfollow.ui.components.NavigationRow("Import/Export Settings", Icons.Outlined.Download, iconTint = colors.blue, onClick = onImportExport)
            }

            if (remoteType == RemoteType.TRC) {
                formSection(header = "Meal Settings") {
                    ToggleRow("Meal with Bolus", mealWithBolus) { Storage.mealWithBolus.value = it }
                    FormDivider()
                    ToggleRow("Meal with Fat/Protein", mealWithFatProtein) { Storage.mealWithFatProtein.value = it }
                }
            }
            if (remoteType == RemoteType.LOOP_APNS) {
                formSection(
                    header = "Meal Settings",
                    footer = "How far ahead carbs can be entered. Loop accepts up to 1 hr; use 4 hr if Loop is built with the future_carbs_4h customization.",
                ) {
                    MenuPickerRow("Max Future Carbs", maxFutureCarbHours, (1..4).toList(), { "$it hr" }) { Storage.loopMaxFutureCarbHours.value = it }
                }
            }

            if (remoteType != RemoteType.NONE) {
                formSection(header = "Guardrails") {
                    QuantityInputRow("Max Bolus", maxBolus, { Storage.maxBolus.value = it }, "U", 2, 5, 0.0, 10.0, validation)
                    FormDivider()
                    QuantityInputRow("Max Carbs", maxCarbs, { Storage.maxCarbs.value = it }, "g", 1, 4, 0.0, 100.0, validation)
                    if (device == "Trio") {
                        FormDivider()
                        QuantityInputRow("Max Fat", maxFat, { Storage.maxFat.value = it }, "g", 1, 4, 0.0, 100.0, validation)
                        FormDivider()
                        QuantityInputRow("Max Protein", maxProtein, { Storage.maxProtein.value = it }, "g", 1, 4, 0.0, 100.0, validation)
                    }
                }
            }

            if (!incrementDetected) {
                formSection(header = "Bolus Increment") {
                    QuantityInputRow("Increment", bolusIncrement, { Storage.bolusIncrement.value = it }, "U", 3, 5, 0.001, 1.0, validation)
                }
            }

            if (remoteType == RemoteType.TRC) {
                formSection(header = "User Information") {
                    FormRow {
                        FormText("User")
                        Spacer(Modifier.width(12.dp))
                        FormTextField(user, { Storage.user.value = it }, "Enter User", Modifier.weight(1f), textAlign = TextAlign.End)
                    }
                }
                // ANDROID-DEVIATION: The APNs key fields are always shown. Android has no LoopFollow team of its own,
                // so commands always sign with the key entered here (the iOS "team IDs differ" case).
                formSection(header = "Trio Remote Control Settings") {
                    SecureRow("Shared Secret", sharedSecret, "Enter Shared Secret") { Storage.sharedSecret.value = it }
                    FormDivider()
                    SecureRow("APNS Key ID", keyId, "Enter APNS Key ID") { Storage.remoteKeyId.value = it }
                    FormDivider()
                    ApnsKeyField(apnsKeyText) {
                        apnsKeyText = it
                        Storage.remoteApnsKey.value = LoopAPNSService.validateAndFixAPNSKey(it)
                    }
                }
                formSection(header = "Debug / Info") {
                    InfoLine("Device Token: $deviceToken")
                    FormDivider()
                    InfoLine("APNS Environment: ${if (production) "Production" else "Development"}")
                    FormDivider()
                    InfoLine("Team ID: ${teamId ?: ""}")
                    FormDivider()
                    InfoLine("Bundle ID: $bundleId")
                    if (incrementDetected) {
                        FormDivider()
                        InfoLine("Bolus Increment: ${String.format(Locale.US, "%.3f", bolusIncrement)} U")
                    }
                    FormDivider()
                    DiagnosticsRows(diagnostics, ::runDiagnostics)
                }
            }

            if (remoteType == RemoteType.LOOP_APNS) {
                formSection(header = "Loop APNS Configuration") {
                    SecureRow("Developer Team ID", teamId ?: "", "Enter Team ID") { Storage.teamId.value = it }
                    FormDivider()
                    SecureRow("APNS Key ID", keyId, "Enter APNS Key ID") { Storage.remoteKeyId.value = it }
                    FormDivider()
                    ApnsKeyField(apnsKeyText) {
                        apnsKeyText = it
                        Storage.remoteApnsKey.value = LoopAPNSService.validateAndFixAPNSKey(it)
                    }
                    FormDivider()
                    FormRow {
                        FormText("QR Code URL")
                        Spacer(Modifier.width(12.dp))
                        Box(
                            Modifier.weight(1f).background(colors.background, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            FormTextField(qrUrl, { Storage.loopAPNSQrCodeURL.value = it }, "Enter QR code URL or scan from Loop app", Modifier.fillMaxWidth())
                        }
                    }
                    FormRow {
                        Button(
                            onClick = {
                                scanner.launch("Scan QR Code from Loop App")
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.blue, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Scan QR Code from Loop App", fontSize = 17.sp)
                        }
                    }
                }
                scanError?.takeIf { it.isNotEmpty() }?.let { message ->
                    formSection { FormRow { Text(message, fontSize = 12.sp, color = colors.red) } }
                }
                formSection(header = "Debug / Info") {
                    InfoLine("Device Token: $deviceToken")
                    FormDivider()
                    InfoLine("APNS Environment: ${if (production) "Production" else "Development"}")
                    FormDivider()
                    InfoLine("Bundle ID: $bundleId")
                    FormDivider()
                    now
                    val otp = TOTPGenerator.extractOTPFromURL(qrUrl)
                    if (otp != null) {
                        FormRow {
                            FormText("Current TOTP Code:")
                            Spacer(Modifier.width(8.dp))
                            Text(
                                otp,
                                fontSize = 17.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.green,
                                modifier = Modifier.background(colors.green.copy(alpha = 0.1f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            val left = OTP_PERIOD - (now / 1000) % OTP_PERIOD
                            Text("(" + (if (otpTicked) "${left}s left" else "-") + ")", fontSize = 12.sp, color = colors.secondaryText)
                        }
                    } else {
                        InfoLine("TOTP Code: Invalid QR code URL", colors.red)
                    }
                    if (incrementDetected) {
                        FormDivider()
                        InfoLine("Bolus Increment: ${String.format(Locale.US, "%.3f", bolusIncrement)} U")
                    }
                    FormDivider()
                    DiagnosticsRows(diagnostics, ::runDiagnostics)
                }
            }
        }
    }
    RemoteAlertDialog(alert) { alert = null }
}

@Composable
private fun RemoteTypeRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    FormRow(onClick = if (enabled) onClick else null) {
        FormText(label, color = if (enabled) colors.primaryText else colors.gray, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Outlined.Check, null, tint = colors.blue)
    }
}

@Composable
private fun SecureRow(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    FormRow {
        FormText(label)
        Spacer(Modifier.width(12.dp))
        FormTextField(value, onChange, placeholder, Modifier.weight(1f), secure = true, textAlign = TextAlign.End)
    }
}

@Composable
private fun ApnsKeyField(value: String, onChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    var revealed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FormText("APNS Key", modifier = Modifier.weight(1f))
            Icon(
                if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (revealed) "Hide" else "Show",
                tint = colors.gray,
                modifier = Modifier.size(22.dp).clickable { revealed = !revealed },
            )
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 110.dp).padding(top = 6.dp)) {
            if (value.isEmpty()) Text("Paste APNS Key", fontSize = 15.sp, color = colors.gray)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(fontSize = 13.sp, color = colors.primaryText, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(colors.blue),
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            )
        }
    }
}

@Composable
private fun InfoLine(text: String, color: Color = LocalAppColors.current.primaryText) {
    FormRow { Text(text, fontSize = 17.sp, color = color) }
}

private val shiftFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun abbreviateToken(token: String) = if (token.length > 16) "${token.take(7)}…${token.takeLast(6)}" else token

@Composable
private fun DiagnosticsRows(diagnostics: RemoteDiagnostics, run: () -> Unit) {
    val colors = LocalAppColors.current
    @Composable
    fun runButton(title: String) {
        FormRow(onClick = run) {
            Icon(Icons.Outlined.MedicalServices, null, tint = colors.blue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            FormText(title, color = colors.blue)
        }
    }

    @Composable
    fun warning(title: String, detail: String, extra: @Composable () -> Unit = {}) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, null, tint = colors.orange, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.orange)
            }
            Text(detail, fontSize = 13.sp, color = colors.secondaryText)
            extra()
        }
    }

    when (val status = diagnostics.status) {
        RemoteDiagnostics.Status.Running -> FormRow {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.gray)
            Spacer(Modifier.width(10.dp))
            FormText("Checking Nightscout profile history…", color = colors.secondaryText)
        }
        RemoteDiagnostics.Status.Unknown -> runButton("Run diagnostics")
        is RemoteDiagnostics.Status.Failed -> {
            runButton("Run diagnostics again")
            FormRow { Text("Diagnostics unavailable: ${status.message}", fontSize = 13.sp, color = colors.secondaryText) }
        }
        RemoteDiagnostics.Status.Ok -> {
            runButton("Run diagnostics again")
            diagnostics.bundleMismatch?.let {
                warning(
                    "Profile uploaded by a different app",
                    "The current Nightscout profile was uploaded by ${it.observedBundleId}, but you're configured for ${it.expectedDevice}. When Loop and Trio share a Nightscout, they overwrite each other's profile.",
                )
            }
            diagnostics.bouncingTokens?.let { b ->
                warning(
                    "Multiple devices uploading profiles",
                    "Device tokens are alternating in recent profile uploads (${b.distinctCount} tokens involved across ${b.recordsScanned} records). This usually means more than one app installation is uploading to the same Nightscout. Remove the app from spare or unused phones.",
                ) {
                    Column(Modifier.padding(top = 2.dp)) {
                        b.shifts.forEach { s ->
                            Text(
                                "${shiftFormatter.format(Date((s.time * 1000).toLong()))}  ${abbreviateToken(s.fromToken)} → ${abbreviateToken(s.toToken)}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.secondaryText,
                            )
                        }
                    }
                }
            }
            diagnostics.futureStartDate?.let {
                warning(
                    "Future-dated profile record found",
                    "A profile record has startDate ${DateTimeUtils.formattedDate(it)}. LoopFollow ignores future-dated records, but it will still appear as the current profile in your Nightscout dashboard. Consider deleting it — it usually means a phone with the wrong system clock is uploading.",
                )
            }
            if (!diagnostics.hasAnyWarning) {
                FormRow {
                    Icon(Icons.Outlined.Verified, null, tint = colors.green, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    FormText("No issues detected", color = colors.secondaryText)
                }
            }
        }
    }
}
