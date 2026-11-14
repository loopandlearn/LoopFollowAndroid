package com.loopandlearn.loopfollow.ui.charts

import com.loopandlearn.loopfollow.engine.ConePoint
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.engine.PredictionStore
import com.loopandlearn.loopfollow.engine.Treatment
import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.Globals
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.storage.Prefs
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class BGColor { GREEN, YELLOW, RED, YESTERDAY }

data class BGPoint(val date: Double, val value: Double, val color: BGColor)

data class TreatmentPoint(
    val date: Double,
    val value: Double,
    val sgv: Double,
    val label: String,
    val pillText: String,
    val detail: Treatment? = null,
    /** Where the symbol is drawn; nudged left by [spread] in a crowded run. */
    val drawnDate: Double = date,
)

data class BasalStep(val start: Double, val end: Double, val rate: Double)

data class BandRect(
    val start: Double,
    val end: Double,
    val yBottom: Double,
    val yTop: Double,
    val label: String,
    val pillText: String,
    val detail: Treatment? = null,
)

/** A maximal stretch of consecutive readings sharing one range color. */
data class BGRun(val color: BGColor, val points: List<BGPoint>)

/** Everything the chart draws, built from the engine's data. */
class ChartData(
    val bg: List<BGPoint>,
    val bgRuns: List<BGRun>,
    val yesterday: List<BGPoint>,
    val prediction: List<BGPoint>,
    val zt: List<BGPoint>,
    val iob: List<BGPoint>,
    val cob: List<BGPoint>,
    val uam: List<BGPoint>,
    val cone: List<ConePoint>,
    val basal: List<BasalStep>,
    val basalScheduled: List<Pair<Double, Double>>,
    val boluses: List<TreatmentPoint>,
    val carbs: List<TreatmentPoint>,
    val smbs: List<TreatmentPoint>,
    val bgChecks: List<TreatmentPoint>,
    val suspends: List<TreatmentPoint>,
    val resumes: List<TreatmentPoint>,
    val sensorStarts: List<TreatmentPoint>,
    val notes: List<TreatmentPoint>,
    val sensorStates: List<TreatmentPoint>,
    val overrides: List<BandRect>,
    val tempTargets: List<BandRect>,
    val isLoop: Boolean,
    val maxBG: Double,
    val maxBasal: Double,
    val lowLine: Double,
    val highLine: Double,
    val domainStart: Double,
    val domainEnd: Double,
    val now: Double,
    val diaMarkers: List<Double>,
    val midnightMarkers: List<Double>,
    val priorDayTimeMarkers: List<Double>,
    val thirtyMinMark: Double,
    val ninetyMinMark: Double,
    val showLines: Boolean,
    val showDots: Boolean,
    val showValues: Boolean,
    val showDIA: Boolean,
    val show30Min: Boolean,
    val show90Min: Boolean,
    val showMidnight: Boolean,
    val smallGraphTreatments: Boolean,
    val showPriorDayTime: Boolean,
    val historyPanes: List<ChartHistory.Pane>,
    val historyOverlay: List<ChartHistory.OverlaySeries>,
    val smoothedBG: List<ChartHistory.Point>,
) {
    /** Scrub grid on the reading cadence. */
    val scrubSlots: ScrubSlots by lazy { ScrubSlots(bg.map { it.date }) }

    fun pillTime(date: Double): String = pillTimeString(date)

    companion object {
        private const val BOLUS_GAP = 240.0
        private const val BOLUS_SHIFT = 240.0
        private const val CARB_GAP = 250.0
        private const val CARB_SHIFT = 250.0

        fun pillTimeString(date: Double): String {
            val pattern = if (android.text.format.DateFormat.is24HourFormat(com.loopandlearn.loopfollow.LoopFollowApplication.instance)) "HH:mm" else "hh:mm"
            val df = SimpleDateFormat(pattern, Locale.getDefault())
            df.timeZone = DateTimeUtils.displayTimeZone()
            return df.format(Date((date * 1000).toLong()))
        }

        private fun formatDose(value: Double): String {
            val nf = NumberFormat.getNumberInstance(Locale.getDefault())
            nf.isGroupingUsed = false
            nf.minimumFractionDigits = 0
            nf.maximumFractionDigits = 2
            nf.minimumIntegerDigits = 0
            return nf.format(value)
        }

        /** Remote-command error notes embed a JSON payload; returns the message plus a summary of it. */
        private fun extractMessage(note: String): String? {
            val start = note.indexOf("{\"")
            if (start < 0) return null
            val errorMessage = note.substring(0, start).trim()
            var actionContext = ""
            val json = runCatching { Prefs.json.parseToJsonElement(note.substring(start)) as? JsonObject }.getOrNull()
            if (json != null) {
                val parts = mutableListOf<String>()
                fun d(key: String) = (json[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                fun s(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                d("bolus-entry")?.let { parts.add("Bolus: $it U") }
                d("carbs-entry")?.let { parts.add("Carbs: $it g") }
                d("absorption-time")?.let { parts.add("Absorption: $it hrs") }
                s("otp")?.let { parts.add("OTP: $it") }
                s("entered-by")?.let { parts.add("From: $it") }
                if (parts.isNotEmpty()) actionContext = " [" + parts.joinToString(", ") + "]"
            }
            val final = errorMessage + actionContext
            return final.ifEmpty { null }
        }

        private fun colorFor(sgv: Int, low: Double, high: Double): BGColor = when {
            sgv >= high -> BGColor.YELLOW
            sgv <= low -> BGColor.RED
            else -> BGColor.GREEN
        }

        private fun makeRuns(points: List<BGPoint>): List<BGRun> {
            val first = points.firstOrNull() ?: return emptyList()
            val runs = mutableListOf<BGRun>()
            var runColor = first.color
            var runPoints = mutableListOf(first)
            for (pt in points.drop(1)) {
                runPoints.add(pt)
                if (pt.color != runColor) {
                    runs.add(BGRun(runColor, runPoints))
                    runPoints = mutableListOf(pt)
                    runColor = pt.color
                }
            }
            if (runPoints.size > 1) runs.add(BGRun(runColor, runPoints))
            return runs
        }

        private fun spread(points: List<TreatmentPoint>, minGap: Double, maxShift: Double): List<TreatmentPoint> {
            val out = points.sortedBy { it.date }.toMutableList()
            spreadSorted(out, minGap, maxShift)
            return out
        }

        private fun spreadTogether(
            first: List<TreatmentPoint>,
            second: List<TreatmentPoint>,
            minGap: Double,
            maxShift: Double,
        ): Pair<List<TreatmentPoint>, List<TreatmentPoint>> {
            val tagged = (first.map { true to it } + second.map { false to it }).sortedBy { it.second.date }
            val points = tagged.map { it.second }.toMutableList()
            spreadSorted(points, minGap, maxShift)
            val a = mutableListOf<TreatmentPoint>()
            val b = mutableListOf<TreatmentPoint>()
            tagged.forEachIndexed { i, (isFirst, _) -> if (isFirst) a.add(points[i]) else b.add(points[i]) }
            return a to b
        }

        /** Nudges crowded treatments left so their symbols don't stack (newest keeps its time). */
        private fun spreadSorted(out: MutableList<TreatmentPoint>, minGap: Double, maxShift: Double) {
            if (out.size <= 1) return
            val clamped = BooleanArray(out.size)
            for (i in out.size - 2 downTo 0) {
                val wanted = out[i + 1].drawnDate - minGap
                if (out[i].drawnDate <= wanted) continue
                val leftBound = out[i].date - maxShift
                if (wanted <= leftBound) {
                    out[i] = out[i].copy(drawnDate = leftBound)
                    clamped[i] = true
                } else {
                    out[i] = out[i].copy(drawnDate = wanted)
                }
            }
            var i = 0
            while (i < out.size - 1) {
                if (!clamped[i]) {
                    i += 1
                    continue
                }
                var last = i
                while (clamped[last + 1]) last += 1
                val anchorIndex = last + 1
                val anchor = out[anchorIndex].drawnDate
                val leftBound = out[i].date - maxShift
                val spacing = (anchor - leftBound) / (anchorIndex - i)
                for (k in i..last) {
                    val ideal = anchor - spacing * (anchorIndex - k)
                    val boundK = out[k].date - maxShift
                    out[k] = out[k].copy(drawnDate = minOf(maxOf(ideal, boundK), out[k].date))
                }
                i = anchorIndex + 1
            }
        }

        fun build(): ChartData {
            val e = MainEngine
            val maxBG = maxOf(e.calculateMaxBgGraphValue().toDouble(), Storage.minBGScale.value)
            val (low, high) = UnitSettingsStore.effectiveThresholds()

            val showBasal = Storage.graphBasal.value
            val showBolus = Storage.graphBolus.value
            val showCarbs = Storage.graphCarbs.value
            val showOther = Storage.graphOtherTreatments.value
            val isLoop = Storage.device.value == "Loop"

            fun clamp(sgv: Int) = sgv.coerceIn(Globals.MIN_DISPLAY_GLUCOSE, Globals.MAX_DISPLAY_GLUCOSE).toDouble()

            val bg = e.bgData.map { BGPoint(it.date, clamp(it.sgv), colorFor(it.sgv, low, high)) }
            val yesterday = if (Storage.showYesterdayLine.value) {
                e.yesterdayBGData.map { BGPoint(it.date, clamp(it.sgv), BGColor.YESTERDAY) }
            } else {
                emptyList()
            }
            fun pred(list: List<com.loopandlearn.loopfollow.engine.ShareGlucoseData>) =
                list.map { BGPoint(it.date, it.sgv.toDouble(), BGColor.GREEN) }

            val bolusPoints = (if (showBolus) e.bolusData else emptyList()).map {
                val dose = formatDose(it.value)
                TreatmentPoint(it.date, it.value, it.sgv.toDouble(), dose, "Bolus\n${dose}U\n${pillTimeString(it.date)}", it.detail)
            }
            val carbs = spread(
                (if (showCarbs) e.carbData else emptyList()).map {
                    val grams = it.value.toInt()
                    var label = "$grams"
                    if (it.absorptionTime > 0 && Storage.showAbsorption.value) label += " ${it.absorptionTime / 60}h"
                    TreatmentPoint(it.date, it.value, it.sgv.toDouble(), label, "Carbs\n${grams}g\n${pillTimeString(it.date)}", it.detail)
                },
                CARB_GAP, CARB_SHIFT,
            )
            val automaticTitle = if (isLoop) "Auto Bolus" else "SMB"
            val smbPoints = (if (showBolus) e.smbData else emptyList()).map {
                val dose = formatDose(it.value)
                TreatmentPoint(it.date, it.value, it.sgv.toDouble(), dose, "$automaticTitle\n${dose}U\n${pillTimeString(it.date)}", it.detail)
            }
            val (boluses, smbs) = spreadTogether(bolusPoints, smbPoints, BOLUS_GAP, BOLUS_SHIFT)

            val bgChecks = (if (showOther) e.bgCheckData else emptyList()).map {
                TreatmentPoint(it.date, it.sgv.toDouble(), it.sgv.toDouble(), "", "BG Check\n${Localizer.toDisplayUnits(it.sgv)}\n${pillTimeString(it.date)}")
            }
            fun simple(list: List<com.loopandlearn.loopfollow.engine.TimestampPoint>, title: String) =
                (if (showOther) list else emptyList()).map {
                    TreatmentPoint(it.date, it.sgv.toDouble(), it.sgv.toDouble(), "", "$title\n${pillTimeString(it.date)}")
                }
            val notes = (if (showOther) e.noteGraphData else emptyList()).map {
                TreatmentPoint(it.date, it.sgv.toDouble(), it.sgv.toDouble(), it.note, "${extractMessage(it.note) ?: it.note}\n${pillTimeString(it.date)}")
            }
            val sensorStates = (if (showOther) e.cgmSensorStates else emptyList()).mapNotNull { state ->
                val sgv = e.bgData.lastOrNull { it.date <= state.date }?.sgv ?: e.bgData.firstOrNull()?.sgv ?: return@mapNotNull null
                TreatmentPoint(state.date, sgv.toDouble(), sgv.toDouble(), state.name, "CGM: ${state.displayName}\n${pillTimeString(state.date)}")
            }

            val basalScheduled = (if (showBasal) e.basalScheduleData else emptyList()).map { it.date to it.basalRate }
            val sortedBasal = (if (showBasal) e.basalData else emptyList()).sortedBy { it.date }
            val steps = mutableListOf<BasalStep>()
            for (i in sortedBasal.indices) {
                val start = sortedBasal[i].date
                val end = if (i + 1 < sortedBasal.size) sortedBasal[i + 1].date else nowSeconds()
                if (end <= start) continue
                steps.add(BasalStep(start, end, sortedBasal[i].basalRate))
            }
            val maxBasal = maxOf(steps.maxOfOrNull { it.rate } ?: 0.0, Storage.minBasalScale.value)

            val yTop = maxBG - 5
            val yBottom = maxBG - 25
            val overrides = (if (showOther) e.overrideGraphData else emptyList()).map {
                val name = it.reason.trim().ifEmpty { "Override" }
                BandRect(it.date, it.endDate, yBottom, yTop, name, "Override\n$name\n${pillTimeString(it.date)}", it.detail)
            }
            val tempTargets = (if (showOther) e.tempTargetGraphData else emptyList()).map {
                val target = it.correctionRange.firstOrNull()?.toString() ?: ""
                val yCenter = (it.correctionRange.firstOrNull() ?: 0).toDouble()
                BandRect(it.date, it.endDate, yCenter - 5, yCenter + 5, "Temp Target", "Temp Target\n${Localizer.toDisplayUnits(target)}\n${pillTimeString(it.date)}", it.detail)
            }

            val now = nowSeconds()
            val hoursBack = Storage.downloadDays.value * 24 * 3600.0
            val domainStart = now - hoursBack
            val domainEnd = now + maxOf(Storage.predictionToLoad.value, 0.25) * 3600

            val cal = Calendar.getInstance(DateTimeUtils.displayTimeZone())
            val prior = mutableListOf<Double>()
            cal.timeInMillis = (now * 1000).toLong()
            while (true) {
                cal.add(Calendar.DAY_OF_YEAR, -1)
                val t = cal.timeInMillis / 1000.0
                if (t <= domainStart) break
                prior.add(t)
            }
            val midnights = mutableListOf<Double>()
            cal.timeInMillis = (domainStart * 1000).toLong()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            while (cal.timeInMillis / 1000.0 <= domainEnd) {
                val t = cal.timeInMillis / 1000.0
                if (t >= domainStart) midnights.add(t)
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }

            // Device status history curves, over the window plus the join gap.
            val kinds = ChartHistory.PaneKind.entries.filter { it.isEnabled }
            val showSmoothed = Storage.showSmoothedBG.value && Storage.device.value != "Loop"
            val historyStart = domainStart - ChartHistory.MAX_JOIN_GAP
            val samples = if (kinds.isEmpty() && !showSmoothed) emptyList() else com.loopandlearn.loopfollow.engine.DeviceStatusHistory.state.samples.filter { it.date >= historyStart }
            val mainPlacement = Storage.historyCurvePlacement.value == com.loopandlearn.loopfollow.storage.HistoryCurvePlacement.MAIN_GRAPH
            val panes = if (mainPlacement) emptyList() else kinds.mapNotNull { ChartHistory.pane(it, samples) }
            val overlay = if (mainPlacement) ChartHistory.overlay(kinds, samples, minOf(maxBG * 0.22, low * 0.9)) else emptyList()
            val smoothed = if (showSmoothed) ChartHistory.points(samples) { it.smoothedBG } else emptyList()

            return ChartData(
                bg = bg,
                bgRuns = makeRuns(bg),
                yesterday = yesterday,
                prediction = pred(e.predictionData),
                zt = pred(PredictionStore.zt),
                iob = pred(PredictionStore.iob),
                cob = pred(PredictionStore.cob),
                uam = pred(PredictionStore.uam),
                cone = PredictionStore.cone,
                basal = steps,
                basalScheduled = basalScheduled,
                boluses = boluses,
                carbs = carbs,
                smbs = smbs,
                bgChecks = bgChecks,
                suspends = simple(e.suspendGraphData, "Suspend"),
                resumes = simple(e.resumeGraphData, "Resume"),
                sensorStarts = simple(e.sensorStartGraphData, "Sensor Start"),
                notes = notes,
                sensorStates = sensorStates,
                overrides = overrides,
                tempTargets = tempTargets,
                isLoop = isLoop,
                maxBG = maxBG,
                maxBasal = maxBasal,
                lowLine = low,
                highLine = high,
                domainStart = domainStart,
                domainEnd = domainEnd,
                now = now,
                diaMarkers = (1..6).map { now - it * 3600.0 },
                midnightMarkers = midnights,
                priorDayTimeMarkers = prior,
                thirtyMinMark = now - 1800,
                ninetyMinMark = now - 5400,
                showLines = Storage.showLines.value,
                showDots = Storage.showDots.value,
                showValues = Storage.showValues.value,
                showDIA = Storage.showDIALines.value,
                show30Min = Storage.show30MinLine.value,
                show90Min = Storage.show90MinLine.value,
                showMidnight = Storage.showMidnightLines.value,
                smallGraphTreatments = Storage.smallGraphTreatments.value,
                showPriorDayTime = Storage.showPriorDayTimeLines.value,
                historyPanes = panes,
                historyOverlay = overlay,
                smoothedBG = smoothed,
            )
        }
    }
}
