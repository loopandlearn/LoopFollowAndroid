package com.loopandlearn.loopfollow.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopandlearn.loopfollow.helpers.TelemetryClient
import com.loopandlearn.loopfollow.ui.components.NavBar
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors

/** The exact payload that would be sent right now, with a copy button. */
@Composable
fun TelemetryPreviewScreen(onBack: () -> Unit) {
    NavScreen("What's sent", onBack = onBack) { TelemetryPreviewContent() }
}

/** The payload preview without a navigation bar, for screens that supply their own. */
@Composable
fun TelemetryPreviewContent() {
    val colors = LocalAppColors.current
    val clipboard = LocalClipboardManager.current
    val jsonText = remember { TelemetryClient.renderPayload() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Below is the exact JSON object that LoopFollow would send to lf.bjorkert.se right now.",
            fontSize = 15.sp,
            color = colors.secondaryText,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        SelectionContainer {
            Text(
                jsonText,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.primaryText,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(colors.cell).padding(8.dp),
            )
        }
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(jsonText)) }, shape = RoundedCornerShape(8.dp)) {
            Icon(Icons.Outlined.ContentCopy, null, tint = colors.blue, modifier = Modifier.size(18.dp))
            Text("Copy JSON", color = colors.blue, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** In-app summary of what is collected. */
@Composable
fun TelemetryPrivacyScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val sections = listOf(
        "Endpoint" to "Once a day (or after a new build), the app sends a small JSON object to https://lf.bjorkert.se. The endpoint is self-hosted by the maintainer; no third-party analytics service is involved.",
        // ANDROID-DEVIATION: Lists the Android payload: a random install identifier, manufacturer and model, and Android version; no TestFlight flag or calendar/contact integration flags.
        "What is sent" to "App version, build date, the install instance number, a random install identifier created by LoopFollow that resets when the app is uninstalled or its data is cleared, the device manufacturer and model (e.g. Google Pixel 8), and Android version. Whether Nightscout and Dexcom are configured (yes/no — no URLs or usernames). Which app you're following (Loop, Trio, etc), if known. A small set of preference flags (units, appearance mode, remote-command type, background refresh method). The full JSON is visible under Diagnostics → What's sent.",
        "What stays on your device" to "All glucose, insulin, and carb data. Your Nightscout URL and API token. Your Dexcom credentials. Remote-command secrets and APNS keys. Time zone. Location data. Logs — these are never sent automatically; the Settings → Logs sharing flow is unchanged and only triggered by you.",
        "Frequency" to "Once every 24 hours, plus once after installing a new build. The check runs in the background while the app is active or refreshing in the background.",
        "Opt out" to "Use the Send anonymous usage stats toggle above. Turning it off is immediate and persistent.",
        // ANDROID-DEVIATION: Names the Android source file.
        "Source" to "helpers/Telemetry.kt in the LoopFollow Android source.",
    )
    NavScreen("Privacy", onBack = onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            sections.forEach { (title, body) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
                    Text(body, fontSize = 17.sp, color = colors.primaryText)
                }
            }
        }
    }
}

/**
 * One-time consent prompt shown when the decision hasn't been made, for example
 * after an update from a version without telemetry or when onboarding is skipped.
 *
 * ANDROID-DEVIATION: A full-screen page that is left only through its two
 * buttons (Back does nothing); it covers the same ground as the iOS sheet with
 * swipe-to-dismiss disabled.
 */
@Composable
fun TelemetryConsentScreen() {
    val colors = LocalAppColors.current
    var showPreview by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (showPreview) showPreview = false }

    // The page covers the app; taps on its empty areas stay here.
    Column(
        Modifier.fillMaxSize().background(colors.groupedBackground).pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        if (showPreview) {
            NavBar("What's sent", onBack = { showPreview = false })
            TelemetryPreviewContent()
            return@Column
        }
        NavBar("Help us help you!")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ANDROID-DEVIATION: Says "Android version"; the app runs on Android.
            Text(
                "You can choose to share anonymous information with the developers to help improve LoopFollow—such as app and Android version, device type, which app you're following, and a few settings. Your health data, credentials, time zone, and logs remain on your device.",
                fontSize = 17.sp,
                color = colors.primaryText,
            )
            Text("You can change this any time in Settings → General → Diagnostics.", fontSize = 15.sp, color = colors.secondaryText)
            Row(
                Modifier.padding(top = 4.dp).clickable { showPreview = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.FindInPage, null, tint = colors.blue, modifier = Modifier.size(20.dp))
                Text("See exactly what's sent", fontSize = 17.sp, color = colors.blue, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Column(
            Modifier.fillMaxWidth().background(colors.cell).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { TelemetryClient.recordDecision(true) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.blue, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Yes, send anonymous stats", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }
            OutlinedButton(
                onClick = { TelemetryClient.recordDecision(false) },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("No thanks", color = colors.blue, fontSize = 17.sp) }
        }
    }
}
