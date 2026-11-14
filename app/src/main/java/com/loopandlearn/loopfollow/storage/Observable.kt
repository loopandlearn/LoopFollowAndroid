package com.loopandlearn.loopfollow.storage

import com.loopandlearn.loopfollow.helpers.CGMSensorState

/** Semantic colors the engine publishes; the UI maps them to theme colors. */
enum class UiColor { PRIMARY, RED, YELLOW, GREEN, PURPLE, GRAY, ORANGE }

/** Observable in-memory storage. */
object Observable {
    /** Active temp target in mg/dL. */
    val tempTarget = ObservableValue<Double?>(null)
    val override = ObservableValue<String?>(null)

    /** End of the active override as Unix epoch seconds; null = no override or indefinite. */
    val overrideEndAt = ObservableValue<Double?>(null)
    /** End of the active temp target as Unix epoch seconds; null = no active temp target. */
    val tempTargetEndAt = ObservableValue<Double?>(null)

    val minAgoText = ObservableValue("?? min ago")
    val bgText = ObservableValue("BG")
    val bg = ObservableValue<Int?>(null)
    val bgStale = ObservableValue(true)
    val bgTextColor = ObservableValue(UiColor.PRIMARY)
    val directionText = ObservableValue("-")
    val deltaText = ObservableValue("+0")
    val iobText = ObservableValue("--")

    val serverText = ObservableValue("Server")
    val loopStatusText = ObservableValue("")
    val loopStatusColor = ObservableValue(UiColor.PRIMARY)
    val predictionText = ObservableValue("")
    val predictionColor = ObservableValue(UiColor.PURPLE)

    val currentAlarm = ObservableValue<String?>(null)
    /** Replaces the current alarm's name on screen when the firing needs its own wording. */
    val currentAlarmTitleOverride = ObservableValue<String?>(null)
    val alarmSoundPlaying = ObservableValue(false)

    val debug = ObservableValue(false)

    val chartSettingsChanged = ObservableValue(false)

    val alertLastLoopTime = ObservableValue<Double?>(null)
    val previousAlertLastLoopTime = ObservableValue<Double?>(null)
    val deviceRecBolus = ObservableValue<Double?>(null)
    /** When the loop cycle behind [deviceRecBolus] ran. */
    val deviceRecBolusAt = ObservableValue<Double?>(null)
    val deviceBatteryLevel = ObservableValue<Double?>(null)
    val deviceBatteryIsCharging = ObservableValue<Boolean?>(null)
    val pumpBatteryLevel = ObservableValue<Double?>(null)
    val dbSizePercentage = ObservableValue<Double?>(null)
    val enactedOrSuggested = ObservableValue<Double?>(null)

    val isNotLooping = ObservableValue(false)

    /** CGM state reported after the newest reading; null while readings flow. */
    val cgmSensorState = ObservableValue<CGMSensorState?>(null)

    /** Selected tab index; set by the engine to switch tabs. */
    val selectedTabIndex = ObservableValue(0)

    /** True while the data source is configured but the first load has not finished. */
    val isInitialLoading = ObservableValue(false)
}
