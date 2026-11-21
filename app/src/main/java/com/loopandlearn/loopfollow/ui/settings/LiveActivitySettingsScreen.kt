package com.loopandlearn.loopfollow.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.liveactivity.LASettings
import com.loopandlearn.loopfollow.liveactivity.LiveActivitySlotOption
import com.loopandlearn.loopfollow.liveactivity.LiveUpdate
import com.loopandlearn.loopfollow.service.OngoingNotification
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.components.ButtonRow
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.MenuPickerRow
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.components.ToggleRow
import com.loopandlearn.loopfollow.ui.components.formSection
import kotlinx.coroutines.delay

private val slotLabels = listOf("Top left", "Top right", "Bottom left", "Bottom right")

@Composable
fun LiveActivitySettingsScreen(onBack: () -> Unit) {
    val laEnabled by Storage.laEnabled.flow.collectAsStateWithLifecycle()
    val storedSlots by Storage.laSlots.flow.collectAsStateWithLifecycle()
    val slots = remember(storedSlots) { LASettings.slots() }
    var restartConfirmed by remember { mutableStateOf(false) }
    val statusChip by Storage.laStatusChip.flow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var promotedAllowed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        promotedAllowed = NotificationManagerCompat.from(context).canPostPromotedNotifications()
        onPauseOrDispose {}
    }

    LaunchedEffect(restartConfirmed) {
        if (restartConfirmed) {
            delay(2000)
            restartConfirmed = false
        }
    }

    NavScreen("Live Activity", onBack = onBack) {
        FormList {
            // ANDROID-DEVIATION: the footer describes the ongoing notification, and there is no APNs credentials
            // warning or CarPlay / Watch slot section: the card is updated locally and has no CarPlay or Watch form.
            formSection(
                header = "Live Activity",
                footer = "The Live Activity is shown as LoopFollow's ongoing notification while Background Refresh is on. When it is off, the notification stays with minimal content, since the background service that keeps alarms running needs it.",
            ) {
                ToggleRow("Enable Live Activity", laEnabled) { Storage.laEnabled.value = it }
            }
            // ANDROID-DEVIATION: the status bar chip is Android's Live Update; iOS shows the Dynamic Island.
            if (laEnabled && LiveUpdate.supported) {
                formSection(
                    header = "Status Bar",
                    footer = "Shows glucose and trend in a chip in the status bar and keeps the card at the top of the notifications and the lock screen. The card then uses Android's standard layout.",
                ) {
                    ToggleRow("Show in Status Bar", statusChip) { Storage.laStatusChip.value = it }
                    if (statusChip && !promotedAllowed) {
                        FormDivider()
                        ButtonRow("Allow Live Updates in Settings") {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    }
                }
            }
            if (laEnabled) {
                formSection {
                    ButtonRow(if (restartConfirmed) "Live Activity Restarted" else "Restart Live Activity", enabled = !restartConfirmed) {
                        OngoingNotification.refresh()
                        restartConfirmed = true
                    }
                }
            }
            formSection(header = "Grid Slots - Live Activity") {
                slotLabels.forEachIndexed { index, label ->
                    if (index > 0) FormDivider()
                    MenuPickerRow(label, slots[index], LiveActivitySlotOption.gridCases, { it.displayName }) { selectSlot(slots, it, index) }
                }
            }
        }
    }
}

/** Selects [option] for slot [index]; another slot holding the same option is cleared to Empty. */
private fun selectSlot(current: List<LiveActivitySlotOption>, option: LiveActivitySlotOption, index: Int) {
    val slots = current.toMutableList()
    if (option != LiveActivitySlotOption.NONE) {
        for (i in slots.indices) if (i != index && slots[i] == option) slots[i] = LiveActivitySlotOption.NONE
    }
    slots[index] = option
    LASettings.setSlots(slots)
}
