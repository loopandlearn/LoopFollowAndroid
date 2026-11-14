package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.alarm.AlarmData
import com.loopandlearn.loopfollow.alarm.AlarmManager
import com.loopandlearn.loopfollow.alarm.BatterySample
import com.loopandlearn.loopfollow.alarm.BolusEntry
import com.loopandlearn.loopfollow.alarm.CarbSample
import com.loopandlearn.loopfollow.alarm.GlucoseValue
import com.loopandlearn.loopfollow.alarm.LowBGCondition
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import kotlin.math.roundToInt

fun MainEngine.scheduleAlarmTask(initialDelay: Double = 30.0) {
    TaskScheduler.scheduleTask(TaskID.ALARM_CHECK, millisFromNow(initialDelay)) { alarmTaskAction() }
}

fun MainEngine.alarmTaskAction() {
    val now = nowSeconds()
    val data = AlarmData(
        // Oldest .. newest
        bgReadings = bgData.takeLast(24).map { GlucoseValue(it.sgv, it.date) },
        predictionData = alarmPredictionData(),
        expireDate = Storage.expirationDate.value?.toDouble(),
        lastLoopTime = Observable.alertLastLoopTime.value,
        latestOverrideStart = overrideGraphData.lastOrNull { it.date <= now }?.date,
        latestOverrideEnd = overrideGraphData.lastOrNull { it.endDate <= now }?.endDate,
        latestTempTargetStart = tempTargetGraphData.lastOrNull { it.date <= now }?.date,
        latestTempTargetEnd = tempTargetGraphData.lastOrNull { it.endDate <= now }?.endDate,
        activeOverrideEnd = overrideGraphData.lastOrNull { it.date <= now && (it.scheduledEndDate ?: 0.0) > now }?.scheduledEndDate,
        activeTempTargetEnd = tempTargetGraphData.lastOrNull { it.date <= now && it.endDate > now }?.scheduledEndDate,
        recBolus = Observable.deviceRecBolus.value,
        cob = latestCOB?.value,
        sageInsertTime = Storage.sageInsertTime.value,
        pumpInsertTime = Storage.cageInsertTime.value,
        latestPumpVolume = latestPumpVolume,
        iob = latestIOB?.value,
        recentBoluses = bolusData.map { BolusEntry(it.value, it.date) },
        latestBattery = Observable.deviceBatteryLevel.value,
        latestBatteryIsCharging = Observable.deviceBatteryIsCharging.value,
        latestPumpBattery = Observable.pumpBatteryLevel.value,
        batteryHistory = deviceBatteryData.map { BatterySample(it.batteryLevel, it.timestamp) },
        recentCarbs = carbData.map { CarbSample(it.value, it.date) },
        dbSizePercentage = Observable.dbSizePercentage.value,
    )
    AlarmManager.checkAlarms(data)
    TaskScheduler.rescheduleTaskIn(TaskID.ALARM_CHECK, 60.0)
}

/**
 * The forward glucose series the low alarm looks ahead in, oldest .. newest at
 * 5-minute spacing. Loop reports one forecast; Trio's four are collapsed into
 * the lowest value per point in time.
 */
fun MainEngine.alarmPredictionData(): List<GlucoseValue> {
    if (Storage.device.value == "Loop") {
        val pred = loopPredBGs ?: return emptyList()
        return lowestForecast(listOf(pred), loopPredUpdatedTime ?: nowSeconds())
    }
    val pred = openAPSPredBGs ?: return emptyList()
    val forecasts = listOf("ZT", "IOB", "COB", "UAM").mapNotNull { pred[it] }
    return lowestForecast(forecasts, openAPSPredUpdatedTime ?: nowSeconds())
}

/** Collapses several forecasts into the lowest value at each 5-minute point. */
fun lowestForecast(
    forecasts: List<List<Double>>,
    start: Double,
    cap: Int = LowBGCondition.forecastPoints(LowBGCondition.MAX_PREDICTIVE_MINUTES),
): List<GlucoseValue> {
    val nonEmpty = forecasts.filter { it.isNotEmpty() }
    if (nonEmpty.isEmpty()) return emptyList()
    val count = minOf(nonEmpty.maxOf { it.size }, cap)
    return (0 until count).mapNotNull { i ->
        val lowest = nonEmpty.mapNotNull { it.getOrNull(i) }.minOrNull() ?: return@mapNotNull null
        GlucoseValue(lowest.roundToInt(), start + i * 300.0)
    }
}
