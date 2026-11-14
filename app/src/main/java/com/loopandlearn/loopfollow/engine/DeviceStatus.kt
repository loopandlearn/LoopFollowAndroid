package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.service.OngoingNotification

import com.loopandlearn.loopfollow.helpers.Globals
import com.loopandlearn.loopfollow.helpers.InsulinFormatter
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.info.CarbMetric
import com.loopandlearn.loopfollow.info.InfoDataSeparator
import com.loopandlearn.loopfollow.info.InfoType
import com.loopandlearn.loopfollow.info.InsulinMetric
import com.loopandlearn.loopfollow.nightscout.EventType
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.nightscout.array
import com.loopandlearn.loopfollow.nightscout.bool
import com.loopandlearn.loopfollow.nightscout.double
import com.loopandlearn.loopfollow.nightscout.doubles
import com.loopandlearn.loopfollow.nightscout.hasNonNull
import com.loopandlearn.loopfollow.nightscout.obj
import com.loopandlearn.loopfollow.nightscout.objectList
import com.loopandlearn.loopfollow.nightscout.string
import com.loopandlearn.loopfollow.nightscout.strings
import com.loopandlearn.loopfollow.storage.GlucoseConversion
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.storage.UiColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

fun MainEngine.scheduleDeviceStatusTask(initialDelay: Double = 4.0) {
    TaskScheduler.scheduleTask(TaskID.DEVICE_STATUS, millisFromNow(initialDelay)) { deviceStatusAction() }
}

fun MainEngine.deviceStatusAction() {
    if (!isNightscoutEnabled()) {
        // No device-status source (e.g. Dexcom-only): drop any leftover forecast.
        clearLoopPredictionGraph()
        clearOpenAPSPredictionGraph()
        TaskScheduler.rescheduleTaskIn(TaskID.DEVICE_STATUS, 60.0)
        return
    }
    webLoadNSDeviceStatus()
}

fun MainEngine.clearLoopPredictionGraph() {
    loopPredBGs = null
    loopPredUpdatedTime = null
    predictionData = emptyList()
    updatePredictionGraph()
}

fun MainEngine.clearOpenAPSPredictionGraph() {
    openAPSPredBGs = null
    openAPSPredUpdatedTime = null
    PredictionStore.clearLines()
    PredictionStore.cone = emptyList()
    updatePredictionGraph()
}

fun MainEngine.webLoadNSDeviceStatus() {
    scope.launch {
        val records = try {
            NightscoutUtils.executeRequest(EventType.DEVICE_STATUS, mapOf("count" to "1"))
        } catch (_: Exception) {
            handleDeviceStatusError()
            return@launch
        }
        val list = records as? kotlinx.serialization.json.JsonArray
        if (list == null) {
            handleDeviceStatusError()
            return@launch
        }
        updateDeviceStatusDisplay(list.objectList())
        Storage.lastLoopingChecked.value = System.currentTimeMillis()
    }
}

private fun MainEngine.handleDeviceStatusError() {
    LogManager.log(LogManager.Category.DEVICE_STATUS, "Device status fetch failed!", limitIdentifier = "Device status fetch failed!")
    Storage.lastLoopingChecked.value = System.currentTimeMillis()
    TaskScheduler.rescheduleTaskIn(TaskID.DEVICE_STATUS, 10.0)
    evaluateNotLooping()
}

fun MainEngine.evaluateNotLooping() {
    val lastLoopTime = Observable.alertLastLoopTime.value ?: return
    if (lastLoopTime <= 0) return

    if (isNightscoutEnabled() && nowSeconds() - lastLoopTime >= 15 * 60) {
        Observable.isNotLooping.value = true
        Observable.loopStatusText.value = "⚠️ Not Looping!"
        Observable.loopStatusColor.value = UiColor.YELLOW
    } else {
        Observable.isNotLooping.value = false
        Observable.loopStatusColor.value = UiColor.PRIMARY
    }
    OngoingNotification.refresh()
}

/** NS device status response processor. */
fun MainEngine.updateDeviceStatusDisplay(jsonDeviceStatus: List<JsonObject>) {
    val previousIOBText = Observable.iobText.value
    val previousDeviceWasLoop = Storage.device.value == "Loop"
    infoManager.clear(
        listOf(
            InfoType.IOB, InfoType.COB, InfoType.BATTERY, InfoType.PUMP, InfoType.PUMP_BATTERY, InfoType.TARGET,
            InfoType.ISF, InfoType.CARB_RATIO, InfoType.UPDATED, InfoType.REC_BOLUS, InfoType.TDD, InfoType.SMOOTHED_BG,
        ),
    )

    // For Loop, clear the current override here; for Trio it is handled using treatments.
    if (Storage.device.value == "Loop") infoManager.clear(InfoType.OVERRIDE)

    if (jsonDeviceStatus.isEmpty()) {
        LogManager.log(LogManager.Category.DEVICE_STATUS, "Device status is empty")
        TaskScheduler.rescheduleTaskIn(TaskID.DEVICE_STATUS, 5 * 60.0)
        return
    }

    val lastDeviceStatus = jsonDeviceStatus[0]

    Observable.previousAlertLastLoopTime.value = Observable.alertLastLoopTime.value

    val lastPumpRecord = lastDeviceStatus.obj("pump")
    if (lastPumpRecord != null) {
        val bolusIncrement = lastPumpRecord.double("bolusIncrement")
        if (bolusIncrement != null && bolusIncrement > 0) {
            Storage.bolusIncrement.value = bolusIncrement
            Storage.bolusIncrementDetected.value = true
        } else if (lastPumpRecord.string("model") == "Dash") {
            Storage.bolusIncrement.value = 0.05
            Storage.bolusIncrementDetected.value = true
        } else {
            Storage.bolusIncrementDetected.value = false
        }

        val lastPumpTime = lastPumpRecord.string("clock")?.let { NightscoutUtils.parseISO(it) }
        if (lastPumpTime != null) {
            val storedTime = Observable.alertLastLoopTime.value ?: 0.0
            if (lastPumpTime > storedTime) {
                Observable.alertLastLoopTime.value = lastPumpTime
                Storage.lastLoopTime.value = lastPumpTime
            }

            val reservoir = PumpReservoirResolver.resolve(
                reservoir = lastPumpRecord.double("reservoir"),
                pumpID = lastPumpRecord.string("pumpID"),
                manufacturer = lastPumpRecord.string("manufacturer"),
                model = lastPumpRecord.string("model"),
                displayOverride = lastPumpRecord.string("reservoir_display_override"),
                cache = Storage.pumpReservoirCache.value,
                date = lastPumpTime,
            )
            Storage.pumpReservoirCache.value = reservoir.cache

            when (val state = reservoir.state) {
                is PumpReservoirState.Units -> {
                    latestPumpVolume = state.units
                    infoManager.update(InfoType.PUMP, String.format("%.0f", state.units) + "U", state.units)
                    Storage.lastPumpReservoirU.value = state.units
                }
                PumpReservoirState.AboveReportingLimit -> {
                    // Pumps that only report "50+" are treated as exactly 50.
                    latestPumpVolume = 50.0
                    infoManager.update(InfoType.PUMP, "50+U", 50.0)
                    Storage.lastPumpReservoirU.value = null
                }
                is PumpReservoirState.Status -> {
                    latestPumpVolume = null
                    infoManager.update(InfoType.PUMP, state.status)
                    Storage.lastPumpReservoirU.value = null
                }
                PumpReservoirState.Unknown -> {
                    latestPumpVolume = null
                    Storage.lastPumpReservoirU.value = null
                }
            }
        }

        // Pump battery percentage
        val pumpBatteryPercent = lastPumpRecord.obj("battery")?.double("percent")
        if (pumpBatteryPercent != null) {
            infoManager.update(InfoType.PUMP_BATTERY, String.format("%.0f", pumpBatteryPercent) + "%", pumpBatteryPercent)
            Observable.pumpBatteryLevel.value = pumpBatteryPercent
            latestPumpBatteryDate = nowSeconds()
        } else {
            val lastPercent = Observable.pumpBatteryLevel.value
            val lastDate = latestPumpBatteryDate
            // Some records leave out the pump battery; the last reading is shown for up to an hour.
            if (lastPercent != null && lastDate != null && nowSeconds() - lastDate <= 60 * 60) {
                infoManager.update(InfoType.PUMP_BATTERY, String.format("%.0f", lastPercent) + "%", lastPercent)
            }
        }

        val uploader = lastDeviceStatus.obj("uploader")
        val upbat = uploader?.double("battery")
        if (uploader != null && upbat != null) {
            val isCharging = uploader.bool("isCharging")
            val batteryText = if (isCharging == true) {
                "⚡️ " + String.format("%.0f", upbat) + "%"
            } else {
                String.format("%.0f", upbat) + "%"
            }
            infoManager.update(InfoType.BATTERY, batteryText, upbat)
            Observable.deviceBatteryLevel.value = upbat
            Observable.deviceBatteryIsCharging.value = isCharging

            deviceBatteryData.add(BatteryPoint(upbat, nowSeconds()))
            if (deviceBatteryData.size > 30) deviceBatteryData.removeAt(0)
        }
    }

    // Loop
    val lastLoopRecord = lastDeviceStatus.obj("loop")
    if (lastLoopRecord != null) {
        // Some pumps report no pump.clock; fall back to the loop cycle timestamp.
        if (lastPumpRecord?.string("clock") == null) {
            val loopTimestamp = lastLoopRecord.string("timestamp")?.let { NightscoutUtils.parseISO(it) }
            if (loopTimestamp != null && loopTimestamp > (Observable.alertLastLoopTime.value ?: 0.0)) {
                Observable.alertLastLoopTime.value = loopTimestamp
                Storage.lastLoopTime.value = loopTimestamp
            }
        }

        deviceStatusLoop(lastLoopRecord)

        var oText = ""
        val lastOverride = lastDeviceStatus.obj("override")
        if (lastOverride != null && lastOverride.bool("active") == true) {
            val lastCorrection = lastOverride.obj("currentCorrectionRange")
            val minValue = lastCorrection?.double("minValue")
            val maxValue = lastCorrection?.double("maxValue")
            if (minValue != null && maxValue != null) {
                val multiplier = lastOverride.double("multiplier")
                if (multiplier != null) {
                    oText += String.format("%.0f%%", multiplier * 100)
                } else {
                    oText += "100%"
                }
                oText += " (" + Localizer.toDisplayUnits(minValue) + "-" + Localizer.toDisplayUnits(maxValue) + ")"
            }
            infoManager.update(InfoType.OVERRIDE, oText)
        } else {
            infoManager.clear(InfoType.OVERRIDE)
        }
    }

    // OpenAPS / Trio
    val openaps = lastDeviceStatus.obj("openaps")
    if (openaps != null) deviceStatusOpenAPS(lastDeviceStatus, openaps)

    // If the active looping system flipped, drop the previous system's forecast.
    val currentDeviceIsLoop = Storage.device.value == "Loop"
    if (currentDeviceIsLoop != previousDeviceWasLoop) {
        if (currentDeviceIsLoop) clearOpenAPSPredictionGraph() else clearLoopPredictionGraph()
    }

    val secondsAgo = nowSeconds() - (Observable.alertLastLoopTime.value ?: 0.0)
    var interval = when {
        secondsAgo >= 20 * 60 -> 5 * 60.0
        secondsAgo >= 10 * 60 -> 60.0
        secondsAgo >= 7 * 60 -> 30.0
        secondsAgo >= 5 * 60 -> 10.0
        else -> {
            TaskScheduler.rescheduleTaskIn(TaskID.ALARM_CHECK, 3.0)
            310 - secondsAgo
        }
    }
    if (NightscoutSocketManager.isAuthenticated) interval = maxOf(interval * 3, 60.0)
    TaskScheduler.rescheduleTaskIn(TaskID.DEVICE_STATUS, interval)

    evaluateNotLooping()

    DeviceStatusHistory.ingestLatest(lastDeviceStatus)

    markDataLoaded("deviceStatus")

    if (Observable.iobText.value != previousIOBText) OngoingNotification.refresh()

    LogManager.log(LogManager.Category.DEVICE_STATUS, "Update Device Status done", isDebug = true)
}

private fun MainEngine.deviceStatusLoop(lastLoopRecord: JsonObject) {
    Storage.device.value = "Loop"
    Storage.loopRemoteCommands.value = lastLoopRecord.obj("testingDetails")?.array("remoteCommands")?.strings() ?: emptyList()

    if (Storage.remoteType.value == com.loopandlearn.loopfollow.storage.RemoteType.TRC) {
        Storage.remoteType.value = com.loopandlearn.loopfollow.storage.RemoteType.NONE
    }

    val previousLastLoopTime = Observable.previousAlertLastLoopTime.value ?: 0.0
    val lastLoopTime = Observable.alertLastLoopTime.value ?: 0.0

    // Loop reports IOB and COB even when the loop failed.
    InsulinMetric.from(lastLoopRecord["iob"], "iob")?.let {
        infoManager.update(InfoType.IOB, it)
        latestIOB = it
        Observable.iobText.value = it.formattedValue()
    }
    CarbMetric.from(lastLoopRecord["cob"], "cob")?.let {
        infoManager.update(InfoType.COB, it)
        latestCOB = it
    }
    Storage.lastIOB.value = latestIOB?.value
    Storage.lastCOB.value = latestCOB?.value

    if (lastLoopRecord.hasNonNull("failureReason")) {
        Observable.loopStatusText.value = "X"
        return
    }

    val wasEnacted = lastLoopRecord["enacted"] is JsonObject

    profileManager.currentISF()?.let {
        infoManager.updateGlucose(InfoType.ISF, it)
        Storage.lastIsfMgdlPerU.value = it
    }
    profileManager.currentCarbRatio()?.let {
        infoManager.update(InfoType.CARB_RATIO, it)
        Storage.lastCarbRatio.value = it
    }

    val targetLow = profileManager.currentTargetLow()
    val targetHigh = profileManager.currentTargetHigh()
    if (targetLow != null && targetHigh != null && targetLow != targetHigh) {
        infoManager.updateGlucose(InfoType.TARGET, targetLow, targetHigh, InfoDataSeparator.DASH)
    } else if (targetLow != null) {
        infoManager.updateGlucose(InfoType.TARGET, targetLow)
    }
    Storage.lastTargetLowMgdl.value = targetLow
    Storage.lastTargetHighMgdl.value = targetHigh

    val prediction = lastLoopRecord.obj("predicted")?.array("values")?.doubles()
    val lastPrediction = prediction?.lastOrNull()
    if (prediction != null && lastPrediction != null) {
        Observable.predictionText.value = Localizer.toDisplayUnits(lastPrediction.roundToInt())
        Observable.predictionColor.value = UiColor.PURPLE
        loopPredBGs = prediction
        loopPredUpdatedTime = lastLoopTime
        if (previousLastLoopTime < lastLoopTime || predictionData.isEmpty()) {
            val points = mutableListOf<ShareGlucoseData>()
            var predictionTime = lastLoopTime
            val toLoad = (Storage.predictionToLoad.value * 12).toInt()
            for (i in 0..toLoad) {
                if (i < prediction.size) {
                    val clamped = prediction[i].roundToInt().coerceIn(Globals.MIN_DISPLAY_GLUCOSE, Globals.MAX_DISPLAY_GLUCOSE)
                    points.add(ShareGlucoseData(clamped, predictionTime, "flat"))
                    predictionTime += 300
                }
            }
            predictionData = points

            val predMin = prediction.min()
            val predMax = prediction.max()
            infoManager.update(InfoType.MIN_MAX, "${Localizer.toDisplayUnits(predMin)}/${Localizer.toDisplayUnits(predMax)}")
            Storage.lastMinBgMgdl.value = predMin
            Storage.lastMaxBgMgdl.value = predMax

            updatePredictionGraph()
        }
    } else {
        loopPredBGs = null
        loopPredUpdatedTime = null
        predictionData = emptyList()
        infoManager.clear(InfoType.MIN_MAX)
        updatePredictionGraph()
    }

    val recBolus = lastLoopRecord.double("recommendedBolus")
    if (recBolus != null) {
        infoManager.update(InfoType.REC_BOLUS, InsulinFormatter.string(recBolus), recBolus)
        Observable.deviceRecBolus.value = recBolus
        val cycleTime = lastLoopRecord.string("timestamp")?.let { NightscoutUtils.parseISO(it) }
        Observable.deviceRecBolusAt.value = cycleTime ?: lastLoopTime
    } else {
        infoManager.clear(InfoType.REC_BOLUS)
        Observable.deviceRecBolus.value = null
        Observable.deviceRecBolusAt.value = null
    }

    // Loop uploads its Closed Loop toggle as loopSettings.dosingEnabled in the profile.
    val recommendedTempBasal = lastLoopRecord.obj("recommendedTempBasal")
    if (profileManager.loopDosingEnabled == false) {
        setLoopStatus("⏀")
    } else if (recommendedTempBasal != null) {
        val tempBasalTime = recommendedTempBasal.string("timestamp")?.let { NightscoutUtils.parseISO(it) }
        if (tempBasalTime != null) {
            val lastBGTime = bgData.lastOrNull()?.date ?: lastLoopTime
            setLoopStatus(if (tempBasalTime > lastBGTime && !wasEnacted) "⏀" else "↻")
        }
    } else {
        setLoopStatus("↻")
    }

    Storage.projectedBgMgdl.value = prediction?.lastOrNull()
}

private fun MainEngine.setLoopStatus(text: String) {
    Observable.loopStatusText.value = text
}

private fun MainEngine.deviceStatusOpenAPS(lastDeviceStatus: JsonObject, lastLoopRecord: JsonObject) {
    Storage.device.value = lastDeviceStatus.string("device") ?: ""
    if (lastLoopRecord.hasNonNull("failureReason")) {
        setLoopStatus("X")
        return
    }

    val enactedOrSuggested = lastLoopRecord.obj("suggested") ?: lastLoopRecord.obj("enacted")
    if (enactedOrSuggested == null) {
        setLoopStatus("↻")
        return
    }

    var updatedTime: Double? = null
    val timestamp = enactedOrSuggested.string("deliverAt") ?: enactedOrSuggested.string("timestamp")
    val parsedTime = timestamp?.let { NightscoutUtils.parseISO(it) }
    if (parsedTime != null) {
        updatedTime = parsedTime
        infoManager.update(InfoType.UPDATED, Localizer.formatTimestampToLocalString(parsedTime))
        Observable.enactedOrSuggested.value = updatedTime
    }

    // ISF. A placeholder 0 in the structured field is treated as missing.
    val profileISF = profileManager.currentISF()
    val enactedISF = enactedOrSuggested.double("ISF")?.takeIf { it != 0.0 }
    if (profileISF != null && enactedISF != null && profileISF != enactedISF) {
        infoManager.updateGlucose(InfoType.ISF, profileISF, enactedISF, InfoDataSeparator.ARROW)
        Storage.lastIsfMgdlPerU.value = enactedISF
    } else if (profileISF != null) {
        infoManager.updateGlucose(InfoType.ISF, profileISF)
        Storage.lastIsfMgdlPerU.value = profileISF
    }

    // Carb ratio: the structured field, else parsed from the reason string.
    val profileCR = profileManager.currentCarbRatio()
    var enactedCR: Double? = enactedOrSuggested.double("CR")?.takeIf { it != 0.0 }
    if (enactedCR == null) {
        val reason = enactedOrSuggested.string("reason")
        if (reason != null) {
            enactedCR = Regex("CR: (\\d+(?:\\.\\d+)?)").find(reason)?.groupValues?.get(1)?.toDoubleOrNull()
        }
    }
    if (profileCR != null && enactedCR != null && profileCR != enactedCR) {
        infoManager.update(InfoType.CARB_RATIO, profileCR, enactedCR, InfoDataSeparator.ARROW)
        Storage.lastCarbRatio.value = enactedCR
    } else if (profileCR != null) {
        infoManager.update(InfoType.CARB_RATIO, profileCR)
        Storage.lastCarbRatio.value = profileCR
    }

    // IOB
    val iobMetric = InsulinMetric.from(lastLoopRecord["iob"], "iob") ?: InsulinMetric.from(enactedOrSuggested, "IOB")
    if (iobMetric != null) {
        infoManager.update(InfoType.IOB, iobMetric)
        latestIOB = iobMetric
        Observable.iobText.value = iobMetric.formattedValue()
    }

    // COB, falling back to the reason string.
    val cobMetric = CarbMetric.from(enactedOrSuggested, "COB")
    if (cobMetric != null) {
        infoManager.update(InfoType.COB, cobMetric)
        latestCOB = cobMetric
    } else {
        val reason = enactedOrSuggested.string("reason")
        val cob = reason?.let { Regex("COB: (\\d+(?:\\.\\d+)?)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        if (cob != null) {
            val metric = CarbMetric(cob)
            infoManager.update(InfoType.COB, metric)
            latestCOB = metric
        }
    }

    // Smoothed BG: the glucose the algorithm ran on.
    enactedOrSuggested.double("bg")?.takeIf { it > 0 }?.let { infoManager.updateGlucose(InfoType.SMOOTHED_BG, it) }

    // Autosens
    enactedOrSuggested.double("sensitivityRatio")?.let { sens ->
        infoManager.update(InfoType.AUTOSENS, String.format("%.0f", sens * 100.0) + "%")
        Storage.lastAutosens.value = sens
    }

    // Recommended bolus
    val rec = lastLoopRecord.double("recommendedBolus")
    if (rec != null) {
        infoManager.update(InfoType.REC_BOLUS, InsulinFormatter.string(rec))
        Observable.deviceRecBolus.value = rec
        Observable.deviceRecBolusAt.value = updatedTime ?: Observable.enactedOrSuggested.value
    } else {
        infoManager.clear(InfoType.REC_BOLUS)
        Observable.deviceRecBolus.value = null
        Observable.deviceRecBolusAt.value = null
    }

    // Eventual BG
    val eventualBG = enactedOrSuggested.double("eventualBG")
    if (eventualBG != null) {
        Observable.predictionText.value = Localizer.formatQuantity(eventualBG)
        Storage.projectedBgMgdl.value = eventualBG
    } else {
        Storage.projectedBgMgdl.value = null
    }

    // Target. A value below 40 is in mmol/L.
    val profileTargetHigh = profileManager.currentTargetHigh()
    val enactedTarget = enactedOrSuggested.double("current_target")?.let {
        if (it < 40) it * GlucoseConversion.MMOL_TO_MGDL else it
    }
    if (profileTargetHigh != null && enactedTarget != null) {
        if (Localizer.formatQuantity(profileTargetHigh) != Localizer.formatQuantity(enactedTarget)) {
            infoManager.updateGlucose(InfoType.TARGET, profileTargetHigh, enactedTarget, InfoDataSeparator.ARROW)
        } else {
            infoManager.updateGlucose(InfoType.TARGET, profileTargetHigh)
        }
        Storage.lastTargetLowMgdl.value = enactedTarget
        Storage.lastTargetHighMgdl.value = enactedTarget
    } else if (profileTargetHigh != null) {
        Storage.lastTargetLowMgdl.value = profileTargetHigh
        Storage.lastTargetHighMgdl.value = profileTargetHigh
    }

    // TDD
    val tddMetric = InsulinMetric.from(enactedOrSuggested, "TDD") ?: InsulinMetric.from(lastLoopRecord["enacted"], "TDD")
    if (tddMetric != null) {
        infoManager.update(InfoType.TDD, tddMetric)
        Storage.lastTdd.value = tddMetric.value
    }

    val predBGsData = lastLoopRecord.obj("suggested")?.obj("predBGs") ?: lastLoopRecord.obj("enacted")?.obj("predBGs")

    Observable.predictionColor.value = UiColor.GRAY
    topPredictionBG = Storage.minBGScale.value

    if (predBGsData != null) {
        val toLoad = (Storage.predictionToLoad.value * 12).toInt()
        val raw = mutableMapOf<String, List<Double>>()
        var minPredBG = Double.POSITIVE_INFINITY
        var maxPredBG = Double.NEGATIVE_INFINITY
        for (type in listOf("ZT", "IOB", "COB", "UAM")) {
            val arr = predBGsData.array(type)?.doubles()
            if (arr != null && arr.isNotEmpty()) {
                raw[type] = arr
                for (i in 0..minOf(toLoad, arr.size - 1)) {
                    minPredBG = minOf(minPredBG, arr[i])
                    maxPredBG = maxOf(maxPredBG, arr[i])
                }
            }
        }
        openAPSPredBGs = raw.ifEmpty { null }
        openAPSPredUpdatedTime = updatedTime

        if (minPredBG != Double.POSITIVE_INFINITY && maxPredBG != Double.NEGATIVE_INFINITY) {
            infoManager.update(InfoType.MIN_MAX, "${Localizer.toDisplayUnits(minPredBG)}/${Localizer.toDisplayUnits(maxPredBG)}")
            Storage.lastMinBgMgdl.value = minPredBG
            Storage.lastMaxBgMgdl.value = maxPredBG
        } else {
            infoManager.clear(InfoType.MIN_MAX)
        }
        updateOpenAPSPredictionDisplay()
    } else {
        openAPSPredBGs = null
        openAPSPredUpdatedTime = null
    }

    // Trio uploads dosingMode "open" while Closed Loop is off.
    val recommendedTempBasal = lastLoopRecord.obj("recommendedTempBasal")
    if (lastLoopRecord.string("dosingMode") == "open") {
        setLoopStatus("⏀")
    } else if (recommendedTempBasal != null) {
        val tempBasalTime = recommendedTempBasal.string("timestamp")?.let { NightscoutUtils.parseISO(it) }
        if (tempBasalTime != null) {
            val lastBGTime = bgData.lastOrNull()?.date ?: (updatedTime ?: nowSeconds())
            setLoopStatus(if (tempBasalTime > lastBGTime) "⏀" else "↻")
        }
    } else {
        setLoopStatus("↻")
    }

    Storage.lastIOB.value = latestIOB?.value
    Storage.lastCOB.value = latestCOB?.value
}
