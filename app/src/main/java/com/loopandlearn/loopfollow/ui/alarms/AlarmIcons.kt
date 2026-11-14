package com.loopandlearn.loopfollow.ui.alarms

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.outlined.Battery2Bar
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.OutlinedFlag
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Vaccines
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.ui.graphics.vector.ImageVector
import com.loopandlearn.loopfollow.alarm.AlarmType

val AlarmType.icon: ImageVector
    get() = when (this) {
        AlarmType.LOW -> Icons.Outlined.VerticalAlignBottom
        AlarmType.HIGH -> Icons.Outlined.VerticalAlignTop
        AlarmType.FAST_DROP -> Icons.Filled.KeyboardDoubleArrowDown
        AlarmType.FAST_RISE -> Icons.Filled.KeyboardDoubleArrowUp
        AlarmType.MISSED_READING -> Icons.Outlined.WifiOff
        AlarmType.IOB -> Icons.Outlined.Vaccines
        AlarmType.COB -> Icons.Outlined.Restaurant
        AlarmType.MISSED_BOLUS -> Icons.Outlined.ReportProblem
        AlarmType.FUTURE_CARBS -> Icons.Outlined.History
        AlarmType.REC_BOLUS -> Icons.Outlined.Bolt
        AlarmType.BATTERY -> Icons.Outlined.Battery2Bar
        AlarmType.BATTERY_DROP -> Icons.Outlined.BatteryChargingFull
        AlarmType.PUMP -> Icons.Outlined.WaterDrop
        AlarmType.PUMP_BATTERY -> Icons.Outlined.Speed
        AlarmType.PUMP_CHANGE -> Icons.Outlined.Sync
        AlarmType.SENSOR_CHANGE -> Icons.Outlined.Sensors
        AlarmType.NOT_LOOPING -> Icons.Outlined.Block
        AlarmType.BUILD_EXPIRE -> Icons.Outlined.EventBusy
        AlarmType.DB_SIZE -> Icons.Outlined.Storage
        AlarmType.OVERRIDE_START -> Icons.Outlined.PlayCircle
        AlarmType.OVERRIDE_END -> Icons.Outlined.StopCircle
        AlarmType.TEMP_TARGET_START -> Icons.Outlined.Flag
        AlarmType.TEMP_TARGET_END -> Icons.Outlined.OutlinedFlag
        AlarmType.TEMPORARY -> Icons.Outlined.NotificationsNone
    }

