package com.loopandlearn.loopfollow.ui.settings.importexport

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.alarm.Alarm
import com.loopandlearn.loopfollow.settings.importexport.AlarmSettingsExport
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.components.clickableNoRipple
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors

/** QR code character budget calibrated for alarm exports. */
private const val MAX_QR_CHARACTERS = 2000
private const val MAX_RECOMMENDED_ALARMS = 5

@Composable
fun AlarmSelectionScreen(
    exportedAlarmIds: Set<String>,
    onConfirm: (List<Alarm>) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalAppColors.current
    val allAlarms by Storage.alarms.flow.collectAsStateWithLifecycle()
    var selectedAlarmIds by remember { mutableStateOf(emptySet<String>()) }
    var showingCharacterLimitAlert by remember { mutableStateOf(false) }

    fun characterCount(ids: Set<String>): Int =
        AlarmSettingsExport.fromSelectedAlarms(allAlarms.filter { it.id in ids }).encodeToJSON().length

    fun canSelectAlarm(alarm: Alarm): Boolean {
        val testSelection = selectedAlarmIds + alarm.id
        if (allAlarms.count { it.id in testSelection } > MAX_RECOMMENDED_ALARMS) return false
        return characterCount(testSelection) <= MAX_QR_CHARACTERS
    }

    fun toggleAlarm(alarm: Alarm) {
        if (alarm.id in selectedAlarmIds) {
            selectedAlarmIds = selectedAlarmIds - alarm.id
        } else if (canSelectAlarm(alarm)) {
            selectedAlarmIds = selectedAlarmIds + alarm.id
        } else {
            showingCharacterLimitAlert = true
        }
    }

    val canExport = selectedAlarmIds.isNotEmpty()
    NavScreen(
        "Select Alarms to Export",
        leading = {
            Text("Cancel", color = colors.blue, fontSize = 17.sp, modifier = Modifier.padding(4.dp).clickableNoRipple(onCancel))
        },
        actions = {
            Text(
                "Export",
                color = if (canExport) colors.blue else colors.gray,
                fontSize = 17.sp,
                modifier = Modifier.padding(4.dp).clickableNoRipple {
                    if (canExport) onConfirm(allAlarms.filter { it.id in selectedAlarmIds })
                },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(colors.cell).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Selected Alarms: ${selectedAlarmIds.size}",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primaryText,
                        modifier = Modifier.weight(1f),
                    )
                    Text("Max $MAX_RECOMMENDED_ALARMS alarms at a time", fontSize = 12.sp, color = colors.secondaryText)
                }
                if (selectedAlarmIds.isNotEmpty()) {
                    val progressColor = when {
                        characterCount(selectedAlarmIds) > MAX_QR_CHARACTERS -> colors.red
                        selectedAlarmIds.size >= MAX_RECOMMENDED_ALARMS -> colors.blue
                        else -> colors.green
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (selectedAlarmIds.size.toFloat() / MAX_RECOMMENDED_ALARMS).coerceAtMost(1f) },
                        color = progressColor,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            LazyColumn(Modifier.fillMaxSize().background(colors.cell)) {
                itemsIndexed(allAlarms, key = { _, alarm -> alarm.id }) { index, alarm ->
                    if (index > 0) FormDivider()
                    val isSelected = alarm.id in selectedAlarmIds
                    AlarmSelectionRow(
                        alarm = alarm,
                        isSelected = isSelected,
                        isExported = alarm.id in exportedAlarmIds,
                        isDisabled = !isSelected && !canSelectAlarm(alarm),
                        onToggle = { toggleAlarm(alarm) },
                    )
                }
            }
        }
    }

    if (showingCharacterLimitAlert) {
        AlertDialog(
            onDismissRequest = { showingCharacterLimitAlert = false },
            title = { Text("Character Limit Reached") },
            text = { Text("Adding this alarm would exceed the QR code character limit. Please remove some alarms first.") },
            confirmButton = { TextButton(onClick = { showingCharacterLimitAlert = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun AlarmSelectionRow(
    alarm: Alarm,
    isSelected: Boolean,
    isExported: Boolean,
    isDisabled: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDisabled || isSelected, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    alarm.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDisabled) colors.secondaryText else colors.primaryText,
                )
                if (isExported) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.CheckCircle, null, tint = colors.green, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(alarm.type.raw, fontSize = 12.sp, color = colors.secondaryText)
            alarm.aboveBG?.let { Text("Above: ${"%.0f".format(it)}", fontSize = 11.sp, color = colors.secondaryText) }
            alarm.belowBG?.let { Text("Below: ${"%.0f".format(it)}", fontSize = 11.sp, color = colors.secondaryText) }
        }
        Icon(
            if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            null,
            tint = if (isSelected) colors.blue else if (isDisabled) colors.secondaryText else colors.primaryText,
            modifier = Modifier.size(26.dp),
        )
    }
}
