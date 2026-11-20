package com.loopandlearn.loopfollow.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.components.formSection
import com.loopandlearn.loopfollow.ui.components.ScanQRCode
import com.loopandlearn.loopfollow.ui.settings.importexport.ImportConfirmationView
import com.loopandlearn.loopfollow.ui.settings.importexport.ImportExportSettingsViewModel
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors

/** Copies the connection from another phone by scanning its exported QR code. */
@Composable
fun ConnectImportStep(model: OnboardingModel) {
    val colors = LocalAppColors.current
    val importVM = remember { ImportExportSettingsViewModel() }
    val scanner = rememberLauncherForActivityResult(ScanQRCode()) { importVM.handleQRCodeScanResult(it) }
    LaunchedEffect(importVM.qrCodeErrorMessage) {
        if (importVM.qrCodeErrorMessage.contains("Successfully imported")) model.didImportSettings = true
    }

    if (importVM.showImportConfirmation) {
        BackHandler { importVM.cancelImport() }
        ImportConfirmationView(importVM)
        return
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            FormList {
                item {
                    StepHeader(
                        Icons.Outlined.QrCode2,
                        "Copy from another phone",
                        "On your other phone, open Settings → Import/Export and export to a QR code. Then scan it here.",
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
                formSection {
                    FormRow(onClick = {
                        scanner.launch("")
                    }) {
                        Icon(Icons.Outlined.QrCodeScanner, null, tint = colors.blue, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        FormText(if (model.didImportSettings) "Scan a different code" else "Scan QR Code")
                    }
                }
                if (model.didImportSettings) {
                    formSection {
                        FormRow {
                            Icon(Icons.Filled.CheckCircle, null, tint = colors.green, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            FormText("Settings imported — you're connected.", color = colors.green)
                        }
                    }
                } else if (importVM.qrCodeErrorMessage.isNotEmpty()) {
                    formSection { FormRow { Text(importVM.qrCodeErrorMessage, fontSize = 12.sp, color = colors.red) } }
                }
            }
        }
        NavFooter(continueEnabled = model.didImportSettings, showBack = model.canGoBack, onBack = model::goBack, onContinue = model::advance)
    }
}
