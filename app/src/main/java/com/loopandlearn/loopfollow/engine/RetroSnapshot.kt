package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.helpers.Globals
import com.loopandlearn.loopfollow.helpers.InsulinFormatter
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.helpers.positionalDaysHours
import com.loopandlearn.loopfollow.info.CarbMetric
import com.loopandlearn.loopfollow.info.InfoDataSeparator
import com.loopandlearn.loopfollow.info.InfoType
import com.loopandlearn.loopfollow.info.InsulinMetric
import com.loopandlearn.loopfollow.storage.ObservableValue
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.storage.UiColor
import com.loopandlearn.loopfollow.ui.charts.ChartData
import java.util.Calendar
import kotlin.math.abs

/** What the home screen shows for a past time while the chart is scrubbed. */
data class RetroSnapshot(
    val date: Double,
    val timeText: String,
    val bgText: String,
    val bgColor: UiColor,
    val directionText: String,
    val deltaText: String,
    val predictionText: String,
    val predictionColor: UiColor,
    val rows: Map<InfoType, Row>,
) {
    data class Row(val value: String, val numericValue: Double?)
}

/** The scrubbed time's snapshot; null while not scrubbing. */
object RetroSelection {
    val snapshot = ObservableValue<RetroSnapshot?>(null)

    fun select(date: Double?, refresh: Boolean = false) {
        if (date == null) {
            if (snapshot.value != null) snapshot.value = null
            return
        }
        if (!refresh && snapshot.value?.date == date) return
        snapshot.value = MainEngine.retroSnapshot(date)
    }
}

fun MainEngine.retroSnapshot(date: Double): RetroSnapshot {
    val sample = DeviceStatusHistory.cycle(date) ?: DeviceStatusHistory.sampleNear(date)
    var bgText = "—"
    var bgColor = UiColor.PRIMARY
    var directionText = ""
    var deltaText = ""
    val index = bgData.indexOfLast { abs(it.date - date) <= 30 }
    if (index >= 0) {
        val reading = bgData[index]
        bgText = when {
            reading.sgv <= Globals.MIN_DISPLAY_GLUCOSE -> "LOW"
            reading.sgv >= Globals.MAX_DISPLAY_GLUCOSE -> "HIGH"
            else -> Localizer.toDisplayUnits(reading.sgv)
        }
        if (Storage.colorBGText.value) {
            val (low, high) = UnitSettingsStore.effectiveThresholds()
            bgColor = when {
                reading.sgv >= high -> UiColor.YELLOW
                reading.sgv <= low -> UiColor.RED
                else -> UiColor.GREEN
            }
        }
        reading.direction?.let { directionText = bgDirectionGraphic(it) }
        if (index > 0) {
            val delta = reading.sgv - bgData[index - 1].sgv
            deltaText = (if (delta < 0) "" else "+") + Localizer.toDisplayUnits(delta)
        }
    }
    val isLoopSample = sample?.forecast?.curves?.get(DeviceStatusHistorySample.Forecast.LOOP_CURVE) != null
    return RetroSnapshot(
        date = date,
        timeText = ChartData.pillTimeString(date),
        bgText = bgText,
        bgColor = bgColor,
        directionText = directionText,
        deltaText = deltaText,
        predictionText = sample?.eventualBG?.let { Localizer.formatQuantity(it) } ?: "",
        predictionColor = if (isLoopSample) UiColor.PURPLE else UiColor.GRAY,
        rows = retroRows(date, sample),
    )
}

private fun MainEngine.retroRows(date: Double, sample: DeviceStatusHistorySample?): Map<InfoType, RetroSnapshot.Row> {
    val rows = mutableMapOf<InfoType, RetroSnapshot.Row>()
    fun set(type: InfoType, value: String?, numeric: Double? = null) {
        if (!value.isNullOrEmpty()) rows[type] = RetroSnapshot.Row(value, numeric)
    }
    val millis = (date * 1000).toLong()

    if (sample != null) {
        sample.iob?.let { set(InfoType.IOB, InsulinMetric(it).formattedValue(), it) }
        sample.cob?.let { set(InfoType.COB, CarbMetric(it).formattedValue(), it) }
        sample.tdd?.let { set(InfoType.TDD, InsulinMetric(it).formattedValue(), it) }
        set(InfoType.REC_BOLUS, sample.recommendedBolus?.let { InsulinFormatter.string(it) }, sample.recommendedBolus)
        set(InfoType.BATTERY, sample.uploaderBattery?.let { cFormat(it, 0) + "%" }, sample.uploaderBattery)
        set(InfoType.PUMP_BATTERY, sample.pumpBattery?.let { cFormat(it, 0) + "%" }, sample.pumpBattery)
        set(InfoType.PUMP, sample.reservoir?.let { cFormat(it, 0) + "U" }, sample.reservoir)
        set(InfoType.AUTOSENS, sample.sensitivityRatio?.let { cFormat(it * 100, 0) + "%" })
        set(InfoType.SMOOTHED_BG, sample.smoothedBG?.let { Localizer.formatQuantity(it) })
        set(InfoType.UPDATED, Localizer.formatTimestampToLocalString(sample.date))
        set(InfoType.MIN_MAX, retroMinMax(sample.forecast))
        sample.loopOverride?.let { o ->
            val percent = o.multiplier?.let { cFormat(it * 100, 0) + "%" } ?: "100%"
            set(InfoType.OVERRIDE, "$percent (${Localizer.toDisplayUnits(o.minTarget)}-${Localizer.toDisplayUnits(o.maxTarget)})")
        }
    }

    val arrow = InfoDataSeparator.ARROW.raw
    val profileISF = profileManager.isf(millis)
    if (profileISF != null) {
        val isf = sample?.isf
        if (isf != null && Localizer.formatQuantity(profileISF) != Localizer.formatQuantity(isf)) {
            set(InfoType.ISF, "${Localizer.formatQuantity(profileISF)} $arrow ${Localizer.formatQuantity(isf)}")
        } else {
            set(InfoType.ISF, Localizer.formatQuantity(profileISF))
        }
    } else {
        sample?.isf?.let { set(InfoType.ISF, Localizer.formatQuantity(it)) }
    }

    val profileCR = profileManager.carbRatio(millis)
    val loopCR = sample?.carbRatio
    if (profileCR != null && loopCR != null && profileCR != loopCR) {
        set(InfoType.CARB_RATIO, "${Localizer.formatToLocalizedString(profileCR)} $arrow ${Localizer.formatToLocalizedString(loopCR)}")
    } else {
        (profileCR ?: loopCR)?.let { set(InfoType.CARB_RATIO, Localizer.formatToLocalizedString(it)) }
    }

    val target = sample?.target
    if (target != null) {
        set(InfoType.TARGET, Localizer.formatQuantity(target))
    } else {
        profileManager.targetLow(millis)?.let { low ->
            val lowText = Localizer.formatQuantity(low)
            val high = profileManager.targetHigh(millis)
            if (high != null && Localizer.formatQuantity(high) != lowText) {
                set(InfoType.TARGET, "$lowText ${InfoDataSeparator.DASH.raw} ${Localizer.formatQuantity(high)}")
            } else {
                set(InfoType.TARGET, lowText)
            }
        }
    }

    for (type in listOf(InfoType.PROFILE, InfoType.DB_SIZE)) {
        val live = infoManager.value(type)
        set(type, live.value, live.numericValue)
    }

    basalData.filter { it.date <= date }.maxByOrNull { it.date }?.basalRate?.let { rate ->
        val actual = Localizer.formatToLocalizedString(rate, 2, 0)
        val scheduled = profileManager.basal(millis)
        set(InfoType.BASAL, if (scheduled != null && scheduled != actual) "$scheduled $arrow $actual" else actual)
    }

    if (Storage.device.value != "Loop") {
        overrideGraphData.lastOrNull { it.date <= date && date < it.endDate }?.let { set(InfoType.OVERRIDE, it.reason.ifEmpty { "Override" }) }
    }

    val day = Calendar.getInstance().apply { timeInMillis = millis }
    fun sameDay(t: Double) = Calendar.getInstance().apply { timeInMillis = (t * 1000).toLong() }.let {
        it.get(Calendar.YEAR) == day.get(Calendar.YEAR) && it.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
    }
    val carbs = carbData.filter { it.date <= date && sameDay(it.date) }.sumOf { it.value }
    set(InfoType.CARBS_TODAY, cFormat(carbs, 0), carbs)

    for ((type, insert) in listOf(
        InfoType.SAGE to Storage.sageInsertTime.value,
        InfoType.CAGE to Storage.cageInsertTime.value,
        InfoType.IAGE to Storage.iageInsertTime.value,
    )) {
        if (insert > 0 && insert <= date) {
            val age = date - insert
            set(type, positionalDaysHours(age), age / 86400)
        }
    }
    return rows
}

private fun retroMinMax(forecast: DeviceStatusHistorySample.Forecast?): String? {
    forecast ?: return null
    val values = forecast.curves[DeviceStatusHistorySample.Forecast.LOOP_CURVE] ?: run {
        val count = (Storage.predictionToLoad.value * 12).toInt() + 1
        forecast.curves.values.flatMap { it.take(count) }
    }
    val low = values.minOrNull() ?: return null
    val high = values.max()
    return "${Localizer.toDisplayUnits(low)}/${Localizer.toDisplayUnits(high)}"
}
