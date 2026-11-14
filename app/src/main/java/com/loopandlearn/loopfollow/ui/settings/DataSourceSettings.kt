package com.loopandlearn.loopfollow.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopandlearn.loopfollow.engine.KnownShareServers
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.engine.NightscoutSocketManager
import com.loopandlearn.loopfollow.engine.ShareClient
import com.loopandlearn.loopfollow.engine.ShareError
import com.loopandlearn.loopfollow.nightscout.NightscoutError
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.components.SegmentedPicker
import com.loopandlearn.loopfollow.ui.components.TextFieldRow
import com.loopandlearn.loopfollow.ui.components.ToggleRow
import com.loopandlearn.loopfollow.ui.components.clickableNoRipple
import com.loopandlearn.loopfollow.ui.components.formSection
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlinx.coroutines.delay

@Composable
fun NightscoutSettingsScreen(onBack: () -> Unit, onDone: () -> Unit, onImport: () -> Unit, closeLabel: String? = null) {
    val colors = LocalAppColors.current
    val model = rememberNightscoutSettingsModel()
    var webSocket by remember { mutableStateOf(Storage.webSocketEnabled.value) }

    NavScreen("Nightscout Settings", onBack = if (closeLabel == null) onBack else null, actions = {
        if (closeLabel != null) {
            Text(closeLabel, color = colors.blue, fontSize = 17.sp, modifier = Modifier.padding(4.dp).clickableNoRipple(onBack))
        }
    }) {
        FormList {
            formSection(header = "URL") {
                TextFieldRow(model.url, model::onURLInput, "Enter URL", keyboardType = KeyboardType.Uri)
            }
            formSection(
                header = "Token",
                footer = "Paste a Nightscout token. If your site needs one and you only have the API secret, paste that instead — LoopFollow can create a read-only token for you.",
            ) {
                TextFieldRow(model.token, model::onTokenInput, "Token or API secret", label = "Access Token", secure = true)
                CreateTokenFromSecretRow(model)
            }
            formSection(header = "Status") {
                FormRow {
                    FormText(model.status, modifier = Modifier.weight(1f))
                    if (model.isConnected) Icon(Icons.Filled.CheckCircle, null, tint = colors.green)
                }
            }
            formSection(header = "Real-time Updates") {
                ToggleRow("Enable WebSocket", webSocket) {
                    webSocket = it
                    Storage.webSocketEnabled.value = it
                    if (it) NightscoutSocketManager.connectIfNeeded() else {
                        NightscoutSocketManager.disconnect()
                        MainEngine.refresh()
                    }
                }
                if (webSocket) {
                    FormDivider()
                    FormRow {
                        FormText("Status", modifier = Modifier.weight(1f))
                        FormText(if (NightscoutSocketManager.isAuthenticated) "Connected" else "Disconnected", color = colors.secondaryText)
                    }
                }
            }
            if (model.isFreshSetup) {
                item {
                    ContinueButton(enabled = model.isConnected, onClick = onDone)
                }
            }
            importSettingsSection(onImport)
        }
    }
}

/** Offers to turn a pasted API secret into a read-only token, and shows provisioning errors. */
@Composable
fun CreateTokenFromSecretRow(model: NightscoutSettingsModel) {
    val colors = LocalAppColors.current
    if (model.tokenIsVerifiedSecret || model.isProvisioningToken) {
        FormDivider()
        FormRow(onClick = if (model.isProvisioningToken) null else ({ model.createReadOnlyToken(model.token) })) {
            if (model.isProvisioningToken) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.gray)
                Spacer(Modifier.width(10.dp))
                FormText("Creating read-only token…", color = colors.secondaryText)
            } else {
                Icon(Icons.Filled.AutoFixHigh, null, tint = colors.blue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                FormText("That's your API secret — create a read-only token", color = colors.blue)
            }
        }
    }
    model.tokenProvisionError?.let {
        FormDivider()
        FormRow { Text(it, fontSize = 13.sp, color = colors.red) }
    }
}

@Composable
fun DexcomSettingsScreen(onBack: () -> Unit, onDone: () -> Unit, onImport: () -> Unit, closeLabel: String? = null) {
    val colors = LocalAppColors.current
    val model = rememberDexcomSettingsModel()

    NavScreen("Dexcom Settings", onBack = if (closeLabel == null) onBack else null, actions = {
        if (closeLabel != null) {
            Text(closeLabel, color = colors.blue, fontSize = 17.sp, modifier = Modifier.padding(4.dp).clickableNoRipple(onBack))
        }
    }) {
        FormList {
            formSection(header = "Dexcom Settings") {
                DexcomCredentialRows(model)
            }
            formSection(header = "Status") {
                FormRow { FormText(model.statusMessage) }
            }
            if (model.isFreshSetup) {
                item { ContinueButton(enabled = model.hasCredentials, onClick = onDone) }
            }
            importSettingsSection(onImport)
        }
    }
}

@Composable
fun DexcomCredentialRows(model: DexcomSettingsModel) {
    TextFieldRow(model.userName, model::onUserName, "Enter Username", label = "Username")
    FormDivider()
    TextFieldRow(model.password, model::onPassword, "Enter Password", label = "Password", secure = true)
    FormDivider()
    FormRow {
        SegmentedPicker(
            listOf("US", "NON-US"),
            model.server,
            { if (it == "US") "US" else "Outside US" },
            model::onServer,
            Modifier.fillMaxWidth(),
        )
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.importSettingsSection(onImport: () -> Unit) {
    formSection(header = "Import Settings") {
        com.loopandlearn.loopfollow.ui.components.NavigationRow("Import Settings from QR Code", Icons.Outlined.Download, iconTint = LocalAppColors.current.blue, onClick = onImport)
    }
}

@Composable
fun ContinueButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.blue, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Text("Continue", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}
