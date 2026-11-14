package com.loopandlearn.loopfollow.ui.settings.importexport

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.loopandlearn.loopfollow.settings.importexport.QRCodeGenerator
import com.loopandlearn.loopfollow.ui.NavStack
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.components.clickableNoRipple
import com.loopandlearn.loopfollow.ui.components.formSection
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ImportExportSettingsViewModel.ExportType.icon: ImageVector
    get() = when (this) {
        ImportExportSettingsViewModel.ExportType.NIGHTSCOUT -> Icons.Outlined.Language
        ImportExportSettingsViewModel.ExportType.DEXCOM -> Icons.Outlined.Language
        ImportExportSettingsViewModel.ExportType.REMOTE -> Icons.Outlined.SettingsInputAntenna
        ImportExportSettingsViewModel.ExportType.ALARMS -> Icons.Outlined.Notifications
        ImportExportSettingsViewModel.ExportType.APNS -> Icons.Outlined.Key
    }

@Suppress("UNUSED_PARAMETER")
@Composable
fun ImportExportSettingsScreen(nav: NavStack, onBack: () -> Unit) {
    val viewModel = remember { ImportExportSettingsViewModel() }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        viewModel.handleQRCodeScanResult(result.contents)
    }

    when {
        viewModel.showImportConfirmation -> {
            BackHandler { viewModel.cancelImport() }
            ImportConfirmationView(viewModel)
        }
        viewModel.isShowingQRCodeDisplay -> {
            BackHandler { viewModel.isShowingQRCodeDisplay = false }
            QRCodeExportView(viewModel)
        }
        viewModel.isShowingAlarmSelection -> {
            BackHandler { viewModel.cancelAlarmSelection() }
            AlarmSelectionScreen(
                exportedAlarmIds = viewModel.exportedAlarmIds,
                onConfirm = { viewModel.exportSelectedAlarms(it) },
                onCancel = { viewModel.cancelAlarmSelection() },
            )
        }
        else -> ImportExportList(viewModel, onBack) {
            scanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setBeepEnabled(false)
                    .setOrientationLocked(true)
                    .setPrompt("")
                    // ANDROID-DEVIATION: Reads UTF-8 so non-ASCII alarm names in iPhone-made codes come through.
                    .addExtra(Intents.Scan.CHARACTER_SET, "UTF-8"),
            )
        }
    }
}

@Composable
private fun ImportExportList(viewModel: ImportExportSettingsViewModel, onBack: () -> Unit, onScan: () -> Unit) {
    val colors = LocalAppColors.current
    NavScreen("Import/Export Settings", onBack = onBack) {
        FormList {
            formSection(header = "Import Settings") {
                FormRow(onClick = onScan) {
                    Icon(Icons.Outlined.QrCodeScanner, null, tint = colors.blue, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    FormText("Scan QR Code to Import Settings")
                }
            }

            formSection(header = "Export Settings To QR Code") {
                ImportExportSettingsViewModel.ExportType.entries.forEachIndexed { index, type ->
                    if (index > 0) FormDivider()
                    FormRow(onClick = { viewModel.export(type) }) {
                        Icon(type.icon, null, tint = colors.blue, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        FormText("Export ${type.rawValue}", modifier = Modifier.weight(1f))
                        Icon(
                            if (type == ImportExportSettingsViewModel.ExportType.ALARMS) Icons.AutoMirrored.Outlined.List else Icons.Outlined.QrCode,
                            null,
                            tint = colors.secondaryText,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            val message = viewModel.qrCodeErrorMessage
            if (message.isNotEmpty()) {
                formSection {
                    val isSuccess = message.contains("successfully") || message.contains("Successfully imported")
                    FormRow {
                        Text(
                            if (isSuccess) "✅ $message" else message,
                            fontSize = 12.sp,
                            color = if (isSuccess) colors.green else colors.red,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QRCodeExportView(viewModel: ImportExportSettingsViewModel) {
    val colors = LocalAppColors.current
    NavScreen("Export ${viewModel.exportType.rawValue}", actions = {
        Text(
            "Close",
            color = colors.blue,
            fontSize = 17.sp,
            modifier = Modifier.padding(4.dp).clickableNoRipple { viewModel.isShowingQRCodeDisplay = false },
        )
    }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (viewModel.qrCodeString.isNotEmpty()) {
                QRCodeDisplayView(viewModel.qrCodeString, 300.dp, Modifier.padding(16.dp))
                Text(
                    "Scan this QR code with another LoopFollow app to import ${viewModel.exportType.importDescription}",
                    fontSize = 12.sp,
                    color = colors.secondaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                Text("Failed to generate QR code", color = colors.red, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/** Renders [qrCodeString] off the main thread and shows a placeholder until it is ready. */
@Composable
fun QRCodeDisplayView(qrCodeString: String, size: Dp = 250.dp, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(null, qrCodeString) {
        value = withContext(Dispatchers.Default) {
            QRCodeGenerator.generateQRCode(qrCodeString)?.asImageBitmap()
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val current = image
    if (current != null) {
        Image(
            current,
            contentDescription = null,
            modifier = modifier.size(size).shadow(4.dp, shape).clip(shape).background(Color.White),
        )
    } else {
        Column(
            modifier.size(size).clip(shape).background(Color.Gray.copy(alpha = 0.3f)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun ImportConfirmationView(viewModel: ImportExportSettingsViewModel) {
    val colors = LocalAppColors.current
    val preview = viewModel.importPreview
    Column(Modifier.fillMaxSize().background(colors.groupedBackground)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            Icon(Icons.Outlined.Download, null, tint = colors.blue, modifier = Modifier.size(50.dp))
            Spacer(Modifier.height(8.dp))
            Text("Import Settings", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
            Spacer(Modifier.height(8.dp))
            Text(
                "Review the settings that will be imported",
                fontSize = 15.sp,
                color = colors.secondaryText,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))

            if (preview != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Settings to Import", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
                    preview.nightscoutURL?.takeIf { it.isNotEmpty() }?.let {
                        SettingRowView(Icons.Outlined.Language, "Nightscout URL", it, colors.blue)
                    }
                    preview.dexcomUsername?.takeIf { it.isNotEmpty() }?.let {
                        SettingRowView(Icons.Outlined.AccountCircle, "Dexcom Username", it, colors.green)
                    }
                    preview.remoteType?.takeIf { it.isNotEmpty() && it != "None" }?.let {
                        SettingRowView(Icons.Outlined.SettingsInputAntenna, "Remote Type", it, colors.orange)
                    }
                    if (preview.alarmCount > 0) {
                        SettingRowView(
                            Icons.Outlined.Notifications,
                            "Alarms",
                            "${preview.alarmCount} alarm(s): ${preview.alarmNames.joinToString(", ")}",
                            colors.red,
                        )
                    }
                    preview.apnsKeyId?.takeIf { it.isNotEmpty() }?.let {
                        SettingRowView(Icons.Outlined.Key, "APNS Key ID", it, colors.purple)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = colors.orange, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Warning", fontWeight = FontWeight.SemiBold, color = colors.orange, fontSize = 17.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "This will overwrite your current settings",
                    fontSize = 15.sp,
                    color = colors.secondaryText,
                    textAlign = TextAlign.Center,
                )
                if (!preview?.apnsKeyId.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "APNS warning: importing APNS settings will overwrite your existing APNS Key ID and APNS Key.",
                        fontSize = 12.sp,
                        color = colors.orange,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConfirmationButton("Import Settings", Icons.Outlined.Check, colors.blue, Color.White) { viewModel.confirmImport() }
            ConfirmationButton(
                "Cancel",
                Icons.Outlined.Close,
                if (colors.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
                colors.primaryText,
            ) { viewModel.cancelImport() }
        }
    }
}

@Composable
private fun ConfirmationButton(title: String, icon: ImageVector, background: Color, foreground: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = foreground, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingRowView(icon: ImageVector, title: String, value: String, color: Color) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.cell)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.primaryText)
            Spacer(Modifier.height(2.dp))
            Text(value, fontSize = 12.sp, color = colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
