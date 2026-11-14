package com.loopandlearn.loopfollow.ui.charts

import com.loopandlearn.loopfollow.engine.DeviceStatusHistory
import com.loopandlearn.loopfollow.engine.DeviceStatusHistorySample
import com.loopandlearn.loopfollow.helpers.Globals
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.storage.PredictionDisplayType
import com.loopandlearn.loopfollow.storage.Storage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Device status history curves: panes under the main chart, or an overlay in its bottom band. */
object ChartHistory {
    const val MAX_JOIN_GAP = 15 * 60.0

    data class Point(val date: Double, val value: Double, val segment: Int)

    enum class PaneKind(val title: String) {
        IOB("IOB"),
        COB("COB"),
        SENSITIVITY_RATIO("Sens. Ratio"),
        ;

        val fillsArea: Boolean get() = this == IOB || this == COB

        val isEnabled: Boolean
            get() = when (this) {
                IOB -> Storage.showIOBGraph.value
                COB -> Storage.showCOBGraph.value
                SENSITIVITY_RATIO -> Storage.showSensitivityRatioGraph.value && Storage.device.value != "Loop"
            }

        fun value(sample: DeviceStatusHistorySample): Double? = when (this) {
            IOB -> sample.iob
            COB -> sample.cob
            SENSITIVITY_RATIO -> sample.sensitivityRatio
        }

        fun format(value: Double): String = when (this) {
            IOB -> Localizer.formatToLocalizedString(value, 2) + "U"
            COB -> Localizer.formatToLocalizedString(value, 0) + "g"
            SENSITIVITY_RATIO -> Localizer.formatToLocalizedString(value * 100, 0) + "%"
        }

        fun axisLabel(value: Double): String = if (this == IOB) Localizer.formatToLocalizedString(value, 1) + "U" else format(value)
    }

    data class Pane(val kind: PaneKind, val points: List<Point>, val yMin: Double, val yMax: Double, val ticks: List<Double>, val baseline: Double)

    data class OverlaySeries(val kind: PaneKind, val isNegative: Boolean, val points: List<Point>)

    data class ForecastCurve(val name: String, val points: List<Pair<Double, Double>>)

    data class Forecast(val curves: List<ForecastCurve>, val cone: List<Triple<Double, Double, Double>>)

    fun points(samples: List<DeviceStatusHistorySample>, value: (DeviceStatusHistorySample) -> Double?): List<Point> {
        val points = mutableListOf<Point>()
        var segment = 0
        var previous: Double? = null
        for (sample in samples) {
            val v = value(sample) ?: continue
            if (previous != null && sample.date - previous > MAX_JOIN_GAP) segment++
            points.add(Point(sample.date, v, segment))
            previous = sample.date
        }
        return points
    }

    fun pane(kind: PaneKind, samples: List<DeviceStatusHistorySample>): Pane? {
        val points = points(samples, kind::value)
        val minValue = points.minOfOrNull { it.value } ?: return null
        val maxValue = points.maxOf { it.value }
        val lower: Double
        val upper: Double
        val ticks: MutableList<Double>
        when (kind) {
            PaneKind.IOB -> {
                lower = min(0.0, minValue)
                upper = max(1.0, ceil(maxValue))
                ticks = mutableListOf(0.0, upper)
            }
            PaneKind.COB -> {
                lower = 0.0
                upper = max(10.0, ceil(maxValue / 10) * 10)
                ticks = mutableListOf(0.0, upper)
            }
            PaneKind.SENSITIVITY_RATIO -> {
                lower = min(minValue, 0.9)
                upper = max(maxValue, 1.1)
                ticks = mutableListOf(1.0)
                if (minValue < 0.95) ticks.add(0, minValue)
                if (maxValue > 1.05) ticks.add(maxValue)
            }
        }
        val pad = (upper - lower) * 0.08
        return Pane(kind, points, lower - pad, upper + pad, ticks, if (kind.fillsArea) 0.0 else lower - pad)
    }

    /** Curves scaled into the main chart's bottom band, from 0 to [bandTop] mg/dL. */
    fun overlay(kinds: List<PaneKind>, samples: List<DeviceStatusHistorySample>, bandTop: Double): List<OverlaySeries> {
        val series = mutableListOf<OverlaySeries>()
        for (kind in kinds) {
            val points = points(samples, kind::value)
            val minValue = points.minOfOrNull { it.value } ?: continue
            val maxValue = points.maxOf { it.value }
            if (kind == PaneKind.IOB) {
                val magnitude = ceil(maxOf(abs(minValue), maxValue, 1.0))
                val (positive, negative) = splitAtZero(points)
                series.add(OverlaySeries(kind, false, positive.map { it.copy(value = abs(it.value) / magnitude * bandTop) }))
                if (negative.isNotEmpty()) series.add(OverlaySeries(kind, true, negative.map { it.copy(value = abs(it.value) / magnitude * bandTop) }))
                continue
            }
            val (lower, upper) = if (kind == PaneKind.COB) 0.0 to max(10.0, ceil(maxValue / 10) * 10) else min(minValue, 0.9) to max(maxValue, 1.1)
            val span = max(upper - lower, Math.ulp(1.0))
            series.add(OverlaySeries(kind, false, points.map { it.copy(value = (it.value - lower) / span * bandTop) }))
        }
        return series
    }

    /** Splits IOB at zero crossings so positive and negative runs draw in their own colors. */
    fun splitAtZero(points: List<Point>): Pair<List<Point>, List<Point>> {
        val positive = mutableListOf<Point>()
        val negative = mutableListOf<Point>()
        var segment = 0
        var previous: Point? = null
        for (point in points) {
            val prev = previous
            if (prev != null) {
                if (prev.segment != point.segment) {
                    segment++
                } else if (prev.value * point.value < 0) {
                    val fraction = abs(prev.value) / (abs(prev.value) + abs(point.value))
                    val crossing = prev.date + (point.date - prev.date) * fraction
                    val zeroEnd = Point(crossing, 0.0, segment)
                    if (prev.value < 0) negative.add(zeroEnd) else positive.add(zeroEnd)
                    segment++
                    val zeroStart = Point(crossing, 0.0, segment)
                    if (point.value < 0) negative.add(zeroStart) else positive.add(zeroStart)
                } else if (prev.value < 0 && point.value == 0.0) {
                    negative.add(Point(point.date, 0.0, segment))
                    segment++
                } else if (prev.value == 0.0 && point.value < 0) {
                    segment++
                    negative.add(Point(prev.date, 0.0, segment))
                }
            }
            val run = Point(point.date, point.value, segment)
            if (point.value < 0) negative.add(run) else positive.add(run)
            previous = point
        }
        return positive to negative
    }

    /** The forecast the loop made from the reading at [readingDate]. */
    fun forecast(readingDate: Double): Forecast? {
        val stored = DeviceStatusHistory.cycle(readingDate)?.forecast ?: return null
        val count = (Storage.predictionToLoad.value * 12).toInt() + 1
        if (count <= 1) return null
        val lo = Globals.MIN_DISPLAY_GLUCOSE.toDouble()
        val hi = Globals.MAX_DISPLAY_GLUCOSE.toDouble()
        val step = DeviceStatusHistorySample.Forecast.STEP
        fun curvePoints(values: List<Int>) = values.take(count).mapIndexed { i, v -> (stored.start + i * step) to v.toDouble().coerceIn(lo, hi) }

        stored.curves[DeviceStatusHistorySample.Forecast.LOOP_CURVE]?.let { main ->
            return Forecast(listOf(ForecastCurve("main", curvePoints(main))), emptyList())
        }
        val names = DeviceStatusHistorySample.Forecast.OPENAPS_CURVES.filter { stored.curves[it] != null }
        if (names.isEmpty()) return null
        if (Storage.predictionDisplayType.value == PredictionDisplayType.CONE) {
            val arrays = names.mapNotNull { stored.curves[it] }
            val length = min(arrays.minOf { it.size }, count)
            val cone = (0 until length).map { i ->
                val values = arrays.map { it[i].toDouble() }
                var yMin = max(values.min(), lo)
                var yMax = min(values.max(), hi)
                if (yMin == yMax) {
                    yMin -= 1
                    yMax += 1
                }
                Triple(stored.start + i * step, yMin, yMax)
            }
            return Forecast(emptyList(), cone)
        }
        return Forecast(names.mapNotNull { name -> stored.curves[name]?.let { ForecastCurve(name, curvePoints(it)) } }, emptyList())
    }
}

/** Reading-aligned 5-minute grid the scrub snaps to, with virtual slots across gaps. */
class ScrubSlots(readingDates: List<Double>) {
    data class Slot(val date: Double, val readingIndex: Int?, val blockStart: Double, val blockEnd: Double) {
        fun contains(d: Double) = d >= blockStart && d < blockEnd
    }

    private val sorted = readingDates.sorted()
    private val slotDates: List<Double>
    private val slotReadingIndices: List<Int?>

    init {
        val dates = mutableListOf<Double>()
        val indices = mutableListOf<Int?>()
        if (sorted.isNotEmpty()) {
            val first = sorted.first()
            var cursor = sorted.last()
            var cursorIndex = sorted.size - 1
            dates.add(cursor)
            indices.add(cursorIndex)
            while (first < cursor - CADENCE / 2) {
                val target = cursor - CADENCE
                val hit = nearestReading(cursorIndex, target, CADENCE / 2)
                if (hit != null) {
                    cursor = sorted[hit]
                    cursorIndex = hit
                    dates.add(cursor)
                    indices.add(hit)
                } else {
                    cursor = target
                    dates.add(cursor)
                    indices.add(null)
                }
            }
            dates.reverse()
            indices.reverse()
        }
        slotDates = dates
        slotReadingIndices = indices
    }

    private fun nearestReading(limit: Int, target: Double, tolerance: Double): Int? {
        if (limit <= 0) return null
        var low = 0
        var high = limit
        while (low < high) {
            val mid = (low + high) / 2
            if (sorted[mid] < target) low = mid + 1 else high = mid
        }
        var best: Int? = null
        for (candidate in listOf(low - 1, low)) {
            if (candidate < 0 || candidate >= limit) continue
            val distance = abs(sorted[candidate] - target)
            if (distance <= tolerance && (best == null || distance < abs(sorted[best] - target))) best = candidate
        }
        return best
    }

    fun slot(date: Double): Slot {
        val firstSlot = slotDates.firstOrNull() ?: return virtualSlot(0.0, date)
        val lastSlot = slotDates.last()
        if (date < firstSlot - CADENCE / 2) return virtualSlot(firstSlot, date)
        if (date >= lastSlot + CADENCE / 2) return virtualSlot(lastSlot, date)
        var low = 0
        var high = slotDates.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (slotDates[mid] <= date) low = mid else high = mid - 1
        }
        var i = low
        if (date < firstSlot) {
            i = 0
        } else if (i + 1 < slotDates.size && date >= (slotDates[i] + slotDates[i + 1]) / 2) {
            i++
        }
        val d = slotDates[i]
        val blockStart = if (i > 0) (slotDates[i - 1] + d) / 2 else d - CADENCE / 2
        val blockEnd = if (i + 1 < slotDates.size) (d + slotDates[i + 1]) / 2 else d + CADENCE / 2
        return Slot(d, slotReadingIndices[i], blockStart, blockEnd)
    }

    private fun virtualSlot(origin: Double, date: Double): Slot {
        val k = kotlin.math.floor((date - origin) / CADENCE + 0.5)
        return Slot(origin + k * CADENCE, null, origin + (k - 0.5) * CADENCE, origin + (k + 0.5) * CADENCE)
    }

    companion object {
        const val CADENCE = 5 * 60.0
    }
}
