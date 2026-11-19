package com.loopandlearn.loopfollow.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.theme.AppColors
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class ChartConfig { MAIN, SMALL }

private object ChartTuning {
    const val MIN_VISIBLE = 15 * 60.0
    const val MAX_VISIBLE = 24 * 3600.0
    val ZOOM_PRESETS = listOf(3600.0, 3 * 3600.0, 6 * 3600.0, 12 * 3600.0, 24 * 3600.0)
    const val FOLLOW_EDGE_TOLERANCE = 90.0
    const val FOLLOW_NOW_FRACTION = 0.7
    const val AUTO_FOLLOW_PAUSE_MS = 5 * 60 * 1000L
    const val INSPECT_HOLD_MS = 200L
    const val TAP_HIT_RADIUS_DP = 30f
    const val DOUBLE_TAP_MS = 300L
}

/** Pan/zoom/follow state shared by the main chart and the small overview chart. */
object ChartInteraction {
    var scrollStart by mutableDoubleStateOf(0.0)
    var visibleSeconds by mutableDoubleStateOf(visibleSecondsFor(Storage.chartScaleX.value))
    var followLatest by mutableStateOf(true)
    var initialized = false
    var autoFollowPausedUntil: Long? = null

    fun visibleSecondsFor(scale: Double): Double =
        if (scale <= 0) 6 * 3600.0 else (24 * 3600 / scale).coerceIn(ChartTuning.MIN_VISIBLE, ChartTuning.MAX_VISIBLE)

    fun persistZoom() {
        Storage.chartScaleX.value = 24 * 3600 / visibleSeconds
    }

    fun clampedLeadingEdge(proposed: Double, data: ChartData): Double {
        if (data.domainEnd <= data.domainStart + 1) return proposed
        val earliest = data.domainStart
        val latest = data.domainEnd - visibleSeconds
        return min(max(proposed, earliest), max(earliest, latest))
    }

    fun updateFollowState(data: ChartData) {
        val trailing = scrollStart + visibleSeconds
        val follow = trailing >= data.now - ChartTuning.FOLLOW_EDGE_TOLERANCE
        autoFollowPausedUntil = if (follow) null else System.currentTimeMillis() + ChartTuning.AUTO_FOLLOW_PAUSE_MS
        if (followLatest != follow) followLatest = follow
    }

    fun scrollToNow(data: ChartData) {
        followLatest = true
        scrollStart = clampedLeadingEdge(data.now - visibleSeconds * ChartTuning.FOLLOW_NOW_FRACTION, data)
    }
}

/** A selection drawn as a vertical indicator, with a pill when it has texts. */
private data class SelectionAnchor(val date: Double, val value: Double, val texts: List<String>, val readingDate: Double? = null)

private fun yDomainUpper(maxBG: Double): Double {
    val clamped = max(maxBG, 1.0)
    return clamped + max(clamped * 0.02, 1.0)
}

private val ZT_COLOR = Color(0.443f, 0.380f, 0.937f)
private val INSULIN_COLOR = Color(0.118f, 0.588f, 0.988f)
private val UAM_COLOR = Color(0.820f, 0.169f, 0.969f)

private fun AppColors.bg(c: BGColor): Color = when (c) {
    BGColor.GREEN -> green
    BGColor.YELLOW -> yellow
    BGColor.RED -> red
    BGColor.YESTERDAY -> gray.copy(alpha = 0.4f)
}

@Composable
fun BGChart(config: ChartConfig, modifier: Modifier = Modifier) {
    val revision by MainEngine.chartRevision.collectAsStateWithLifecycle()
    var minuteTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60_000)
            minuteTick += 1
        }
    }
    val data = remember(revision, minuteTick) { ChartData.build() }

    if (config == ChartConfig.SMALL) {
        SmallChart(data, modifier)
    } else {
        MainChart(data, modifier)
    }
}

@Composable
private fun MainChart(data: ChartData, modifier: Modifier) {
    val colors = LocalAppColors.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val inter = ChartInteraction

    var selection by remember { mutableStateOf<Double?>(null) }
    var tapped by remember { mutableStateOf<SelectionAnchor?>(null) }
    var momentum by remember { mutableStateOf<Job?>(null) }
    var interacting by remember { mutableStateOf(false) }

    LaunchedEffect(data.now) {
        if (!inter.initialized && data.domainEnd > data.domainStart + 1) {
            inter.initialized = true
            inter.scrollToNow(data)
        } else if (!interacting) {
            val paused = inter.autoFollowPausedUntil
            if (inter.followLatest || (paused != null && System.currentTimeMillis() >= paused)) {
                inter.scrollToNow(data)
            }
        }
    }

    BoxWithConstraints(modifier.background(colors.background)) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat()
        val xLabelHeight = with(density) { 20.dp.toPx() }
        // History panes stack under the main chart, which keeps most of the space.
        val paneCount = data.historyPanes.size
        val paneHeight = if (paneCount == 0) 0f else min(with(density) { (heightPx * 0.15f).coerceIn(44.dp.toPx(), 72.dp.toPx()) }, heightPx * 0.45f / paneCount)
        val mainHeight = heightPx - paneHeight * paneCount
        val plot = Rect(0f, with(density) { 4.dp.toPx() }, widthPx, mainHeight - xLabelHeight)
        val yMax = yDomainUpper(data.maxBG)

        fun xFor(date: Double) = ((date - inter.scrollStart) / inter.visibleSeconds * widthPx).toFloat()
        fun yFor(value: Double) = plot.top + (1 - (value.coerceIn(0.0, yMax) / yMax)).toFloat() * plot.height
        fun dateAt(x: Float) = inter.scrollStart + inter.visibleSeconds * (x / widthPx)
        fun valueAt(y: Float) = ((1 - (y - plot.top) / plot.height).coerceIn(0f, 1f) * yMax)

        /**
         * Scrub lookup: the grid mark whose block holds the time. The indicator stands on the
         * reading nearest the mark, else the nearest treatment, else a band; scrubbing shows no pill.
         */
        fun scrubAnchor(date: Double): SelectionAnchor? {
            val slot = data.scrubSlots.slot(date)
            val mark = slot.date
            val reading = data.bg.filter { slot.contains(it.date) }.minByOrNull { abs(it.date - mark) }
            if (reading != null) return SelectionAnchor(mark, reading.value, emptyList(), reading.date)
            val treatments = listOf(data.boluses, data.carbs, data.smbs, data.bgChecks, data.notes, data.sensorStates, data.suspends, data.resumes, data.sensorStarts)
                .flatten().filter { slot.contains(it.date) }
            treatments.minByOrNull { abs(it.date - mark) }?.let { return SelectionAnchor(mark, it.sgv, emptyList()) }
            val band = data.overrides.firstOrNull { mark >= it.start && mark <= it.end } ?: data.tempTargets.firstOrNull { mark >= it.start && mark <= it.end }
            return band?.let { SelectionAnchor(mark, (it.yTop + it.yBottom) / 2, emptyList()) }
        }

        fun bandTexts(date: Double): List<String> {
            val texts = mutableListOf<String>()
            data.overrides.filter { date >= it.start && date <= it.end }.maxByOrNull { it.start }?.let { texts.add(it.pillText) }
            data.tempTargets.filter { date >= it.start && date <= it.end }.maxByOrNull { it.start }?.let { texts.add(it.pillText) }
            return texts
        }

        fun tapAnchor(location: Offset): SelectionAnchor? {
            val radius = with(density) { ChartTuning.TAP_HIT_RADIUS_DP.dp.toPx() }
            var bestD2 = radius * radius
            var best: SelectionAnchor? = null
            fun consider(date: Double, value: Double, text: String) {
                val dx = xFor(date) - location.x
                val dy = yFor(value) - location.y
                val d2 = dx * dx + dy * dy
                if (d2 <= bestD2) {
                    bestD2 = d2
                    best = SelectionAnchor(date, value, listOf(text))
                }
            }
            for (group in listOf(data.boluses, data.carbs, data.smbs, data.bgChecks, data.notes, data.sensorStates, data.suspends, data.resumes, data.sensorStarts)) {
                for (t in group) consider(t.drawnDate, t.sgv, t.pillText)
            }
            if (best == null) {
                for (p in data.bg) {
                    consider(p.date, p.value, "BG\n${Localizer.toDisplayUnits(p.value.toInt())}\n${data.pillTime(p.date)}")
                }
            }
            val found = best
            if (found == null) {
                val date = dateAt(location.x)
                val value = valueAt(location.y)
                for (band in data.overrides + data.tempTargets) {
                    if (date >= band.start && date <= band.end && value >= band.yBottom && value <= band.yTop) {
                        return SelectionAnchor(date, (band.yTop + band.yBottom) / 2, listOf(band.pillText))
                    }
                }
                return null
            }
            return found.copy(texts = found.texts + bandTexts(found.date))
        }

        fun cycleZoomPreset() {
            val current = inter.visibleSeconds
            val next = ChartTuning.ZOOM_PRESETS.firstOrNull { it > current + 1 } ?: ChartTuning.ZOOM_PRESETS[0]
            tapped = null
            val trailing = inter.scrollStart + current
            inter.visibleSeconds = next
            inter.scrollStart = if (inter.followLatest) {
                inter.clampedLeadingEdge(data.now - next * ChartTuning.FOLLOW_NOW_FRACTION, data)
            } else {
                inter.clampedLeadingEdge(trailing - next, data)
            }
            inter.persistZoom()
        }

        // The displays show the scrubbed time only while the finger is down.
        LaunchedEffect(selection, data) {
            val anchor = selection?.let { scrubAnchor(it) }
            com.loopandlearn.loopfollow.engine.RetroSelection.select(anchor?.let { it.readingDate ?: it.date }, refresh = true)
        }

        // The gesture handling below runs for the chart's lifetime and reads the latest data and size, so a gesture
        // in progress lasts until the finger lifts, through new data and the layout change the scrubbed displays cause.
        val currentData by rememberUpdatedState(data)
        val currentWidthPx by rememberUpdatedState(widthPx)
        val currentDateAt by rememberUpdatedState<(Float) -> Double> { dateAt(it) }
        val currentTapAnchor by rememberUpdatedState<(Offset) -> SelectionAnchor?> { tapAnchor(it) }
        val currentCycleZoomPreset by rememberUpdatedState<() -> Unit> { cycleZoomPreset() }
        DisposableEffect(Unit) {
            onDispose {
                interacting = false
                selection = null
            }
        }

        var lastTapTime = 0L
        var pendingTap: Job? = null

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        momentum?.cancel()
                        momentum = null
                        interacting = true
                        val downTime = System.currentTimeMillis()
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var mode = 0 // 0 undecided, 1 pan, 2 inspect, 3 pinch
                        var panBaseline = inter.scrollStart
                        var panOrigin = down.position
                        var pinchStartDistance = 0f
                        var pinchStartVisible = inter.visibleSeconds
                        var pinchAnchorDate = 0.0
                        var pinchAnchorFraction = 0f
                        val slop = viewConfiguration.touchSlop

                        var lastPos = down.position
                        while (true) {
                            val event = if (mode == 0) {
                                val remaining = ChartTuning.INSPECT_HOLD_MS - (System.currentTimeMillis() - downTime)
                                withTimeoutOrNull(max(1L, remaining)) { awaitPointerEvent(PointerEventPass.Main) }
                            } else {
                                awaitPointerEvent(PointerEventPass.Main)
                            }
                            if (event == null) {
                                // Held in place: latch inspect.
                                mode = 2
                                tapped = null
                                selection = currentDateAt(lastPos.x)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                continue
                            }
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                val up = event.changes.first()
                                tracker.addPosition(up.uptimeMillis, up.position)
                                break
                            }
                            if (pressed.size >= 2) {
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val dist = hypot(a.x - b.x, a.y - b.y)
                                val centroid = (a.x + b.x) / 2
                                if (mode != 3) {
                                    mode = 3
                                    tapped = null
                                    selection = null
                                    pinchStartDistance = dist
                                    pinchStartVisible = inter.visibleSeconds
                                    pinchAnchorFraction = centroid / currentWidthPx
                                    pinchAnchorDate = currentDateAt(centroid)
                                } else if (pinchStartDistance > 0) {
                                    val proposed = (pinchStartVisible * pinchStartDistance / dist)
                                        .coerceIn(ChartTuning.MIN_VISIBLE, ChartTuning.MAX_VISIBLE)
                                    inter.visibleSeconds = proposed
                                    inter.scrollStart = inter.clampedLeadingEdge(pinchAnchorDate - proposed * pinchAnchorFraction, currentData)
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val change = pressed[0]
                            lastPos = change.position
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val moved = change.position - panOrigin
                            when (mode) {
                                0 -> {
                                    if (hypot(moved.x, moved.y) > slop) {
                                        mode = 1
                                        tapped = null
                                        panBaseline = inter.scrollStart
                                        panOrigin = change.position
                                    } else if (System.currentTimeMillis() - downTime >= ChartTuning.INSPECT_HOLD_MS) {
                                        mode = 2
                                        tapped = null
                                        selection = currentDateAt(change.position.x)
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                                1 -> {
                                    val dx = change.position.x - panOrigin.x
                                    inter.scrollStart = inter.clampedLeadingEdge(panBaseline - dx / currentWidthPx * inter.visibleSeconds, currentData)
                                    inter.updateFollowState(currentData)
                                    change.consume()
                                }
                                2 -> {
                                    selection = currentDateAt(change.position.x)
                                    change.consume()
                                }
                            }
                        }

                        interacting = false
                        when (mode) {
                            0 -> {
                                val now = System.currentTimeMillis()
                                val pos = down.position
                                if (now - lastTapTime < ChartTuning.DOUBLE_TAP_MS) {
                                    pendingTap?.cancel()
                                    lastTapTime = 0
                                    currentCycleZoomPreset()
                                } else {
                                    lastTapTime = now
                                    pendingTap = scope.launch {
                                        delay(ChartTuning.DOUBLE_TAP_MS)
                                        tapped = currentTapAnchor(pos)
                                    }
                                }
                            }
                            1 -> {
                                val velocity = tracker.calculateVelocity().x
                                if (abs(velocity) > 300) {
                                    momentum = scope.launch {
                                        val anim = Animatable(0f)
                                        val start = inter.scrollStart
                                        val target = -velocity * 0.35f
                                        anim.animateTo(target, tween(600)) {
                                            inter.scrollStart = inter.clampedLeadingEdge(start + value / currentWidthPx * inter.visibleSeconds, currentData)
                                            inter.updateFollowState(currentData)
                                        }
                                        momentum = null
                                    }
                                }
                            }
                            2 -> selection = null
                            3 -> inter.persistZoom()
                        }
                    }
                },
        ) {
            clipRect(0f, 0f, size.width, mainHeight) {
                drawChartContent(data, colors, measurer, false, plot, ::xFor, ::yFor, inter.visibleSeconds, inter.scrollStart, widthPx)
            }
            drawYAxes(data, colors, measurer, plot, ::yFor)
            data.historyPanes.forEachIndexed { i, pane ->
                drawHistoryPane(pane, Rect(0f, mainHeight + i * paneHeight, widthPx, mainHeight + (i + 1) * paneHeight), data.now, ::xFor, colors, measurer)
            }

            // The forecast the loop made from the scrubbed reading.
            val scrubbed = selection?.let { scrubAnchor(it) }?.readingDate
            if (scrubbed != null) {
                ChartHistory.forecast(scrubbed)?.let { forecast ->
                    if (forecast.cone.size > 1) {
                        val path = Path()
                        forecast.cone.forEachIndexed { i, (d, _, yMax) -> if (i == 0) path.moveTo(xFor(d), yFor(yMax)) else path.lineTo(xFor(d), yFor(yMax)) }
                        for ((d, yMin, _) in forecast.cone.asReversed()) path.lineTo(xFor(d), yFor(yMin))
                        path.close()
                        drawPath(path, colors.blue.copy(alpha = 0.3f))
                    }
                    for (curve in forecast.curves) {
                        if (curve.points.size < 2) continue
                        val path = Path()
                        curve.points.forEachIndexed { i, (d, v) -> if (i == 0) path.moveTo(xFor(d), yFor(v)) else path.lineTo(xFor(d), yFor(v)) }
                        val color = when (curve.name) {
                            "ZT" -> ZT_COLOR
                            "IOB" -> INSULIN_COLOR
                            "COB" -> colors.orange
                            "UAM" -> UAM_COLOR
                            else -> colors.purple
                        }
                        drawPath(path, color, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))))
                    }
                }
            }

            // Override labels inside their bands.
            for (band in data.overrides) {
                val xs = max(xFor(band.start), 0f)
                val xe = min(xFor(band.end), widthPx)
                val w = xe - xs
                if (w > 24.dp.toPx()) {
                    val layout = measurer.measure(
                        band.label,
                        TextStyle(fontSize = 11.sp, color = Color.White),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = max(1, (w - 16.dp.toPx()).toInt())),
                    )
                    val y = yFor((band.yBottom + band.yTop) / 2) - layout.size.height / 2
                    drawText(layout, topLeft = Offset(xe - 10.dp.toPx() - layout.size.width, y))
                }
            }

            val anchor = selection?.let { scrubAnchor(it) } ?: tapped
            if (anchor != null) {
                val x = xFor(anchor.date)
                if (x in 0f..widthPx) {
                    val lineBottom = if (data.historyPanes.isEmpty()) plot.bottom else heightPx
                    drawLine(colors.primaryText.copy(alpha = 0.5f), Offset(x, plot.top), Offset(x, lineBottom), 1.dp.toPx())
                    if (anchor.texts.isNotEmpty()) drawPill(anchor.texts, x, yFor(anchor.value), plot, widthPx, colors, measurer)
                }
            }
        }

        if (!inter.followLatest) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 44.dp, bottom = 28.dp)
                    .size(34.dp)
                    .background(if (colors.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7), CircleShape)
                    .border(0.5.dp, colors.primaryText.copy(alpha = 0.25f), CircleShape)
                    .clickable { inter.scrollToNow(data) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.LastPage, contentDescription = "Jump to now", tint = colors.primaryText, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SmallChart(data: ChartData, modifier: Modifier) {
    val colors = LocalAppColors.current
    val measurer = rememberTextMeasurer()
    val inter = ChartInteraction
    BoxWithConstraints(modifier.background(colors.background)) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat()
        val domainSeconds = max(data.domainEnd - data.domainStart, 1.0)
        val plot = Rect(0f, 0f, widthPx, heightPx)
        val yMax = yDomainUpper(data.maxBG)
        fun xFor(date: Double) = ((date - data.domainStart) / domainSeconds * widthPx).toFloat()
        fun yFor(value: Double) = plot.top + (1 - (value.coerceIn(0.0, yMax) / yMax)).toFloat() * plot.height

        fun navigate(x: Float) {
            val fraction = (x / widthPx).coerceIn(0f, 1f)
            val date = data.domainStart + domainSeconds * fraction
            val length = inter.visibleSeconds
            var target = date - length / 2
            val minStart = data.domainStart
            val maxStart = data.domainEnd - length
            if (maxStart > minStart) target = target.coerceIn(minStart, maxStart)
            inter.scrollStart = target
            inter.updateFollowState(data)
        }

        // The gesture handling runs for the chart's lifetime and reads the latest data and size.
        val currentNavigate by rememberUpdatedState<(Float) -> Unit> { navigate(it) }
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var scrubbing = false
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.first()
                        if (!change.pressed) break
                        last = change.position
                        if (scrubbing || hypot((last - down.position).x, (last - down.position).y) >= 10.dp.toPx()) {
                            scrubbing = true
                            currentNavigate(last.x)
                            change.consume()
                        }
                    }
                    currentNavigate(last.x)
                }
            },
        ) {
            drawChartContent(data, colors, measurer, true, plot, ::xFor, ::yFor, domainSeconds, data.domainStart, widthPx)
            val start = (inter.scrollStart - data.domainStart) / domainSeconds
            val boxWidth = (inter.visibleSeconds / domainSeconds * widthPx).toFloat().coerceIn(2f, widthPx)
            val boxLeft = (start * widthPx).toFloat().coerceIn(0f, widthPx - boxWidth)
            drawRect(colors.primaryText.copy(alpha = 0.08f), Offset(boxLeft, 0f), Size(boxWidth, heightPx))
            drawRect(colors.primaryText.copy(alpha = 0.35f), Offset(boxLeft, 0f), Size(boxWidth, heightPx), style = Stroke(1.dp.toPx()))
        }
    }
}

// MARK: - Drawing

/** Diameter of a Swift Charts symbol of the given area. */
private fun DrawScope.symbolDiameter(area: Float): Float = sqrt(area * 4 / Math.PI.toFloat()).dp.toPx()

private fun DrawScope.drawChartContent(
    data: ChartData,
    colors: AppColors,
    measurer: TextMeasurer,
    isSmall: Boolean,
    plot: Rect,
    xFor: (Double) -> Float,
    yFor: (Double) -> Float,
    visibleSeconds: Double,
    windowStart: Double,
    widthPx: Float,
) {
    val windowEnd = windowStart + visibleSeconds
    val pad = visibleSeconds * 0.1
    fun visible(d: Double) = d >= windowStart - pad && d <= windowEnd + pad
    val basalScale = if (data.maxBasal > 0) data.maxBG / data.maxBasal else 0.0
    val showTreatments = !isSmall || data.smallGraphTreatments

    clipRect(0f, 0f, size.width, plot.bottom) {
        if (showTreatments) {
            // Override and temp target bands
            val overrideColor = if (data.isLoop) colors.green else colors.purple
            val tempTargetColor = if (data.isLoop) colors.purple else colors.green
            for (b in data.overrides) {
                if (b.end < windowStart || b.start > windowEnd) continue
                drawRect(overrideColor.copy(alpha = 0.6f), Offset(xFor(b.start), yFor(b.yTop)), Size(xFor(b.end) - xFor(b.start), yFor(b.yBottom) - yFor(b.yTop)))
            }
            for (b in data.tempTargets) {
                if (b.end < windowStart || b.start > windowEnd) continue
                drawRect(tempTargetColor.copy(alpha = 0.5f), Offset(xFor(b.start), yFor(b.yTop)), Size(xFor(b.end) - xFor(b.start), yFor(b.yBottom) - yFor(b.yTop)))
            }
            // Basal (drawn from the top of the plot down: the rate maps onto the BG scale from 0)
            for (step in data.basal) {
                if (step.end < windowStart || step.start > windowEnd) continue
                val top = yFor(step.rate * basalScale)
                drawRect(colors.blue.copy(alpha = 0.35f), Offset(xFor(step.start), top), Size(xFor(step.end) - xFor(step.start), yFor(0.0) - top))
            }
            if (data.basalScheduled.size > 1) {
                val path = Path()
                data.basalScheduled.forEachIndexed { i, (d, r) ->
                    val p = Offset(xFor(d), yFor(r * basalScale))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, colors.blue.copy(alpha = 0.8f), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 5.dp.toPx()))))
            }
        }

        // Cone
        if (data.cone.size > 1) {
            val path = Path()
            data.cone.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(xFor(p.date), yFor(p.yMax)) else path.lineTo(xFor(p.date), yFor(p.yMax))
            }
            for (p in data.cone.asReversed()) path.lineTo(xFor(p.date), yFor(p.yMin))
            path.close()
            drawPath(path, colors.blue.copy(alpha = 0.4f))
        }

        if (!isSmall && data.yesterday.size > 1) {
            drawSeries(data.yesterday, colors.gray.copy(alpha = 0.4f), 1.5f, xFor, yFor)
        }
        if (!isSmall) {
            // Under the readings, so low BG stays visible above the band.
            for (series in data.historyOverlay) {
                val color = if (series.isNegative) PINK else historyColor(series.kind, colors)
                drawSegments(series.points, color, 1.5f, xFor, yFor)
            }
        }

        if (data.showLines) {
            for (run in data.bgRuns) {
                if (run.points.last().date < windowStart || run.points.first().date > windowEnd) continue
                drawSeries(run.points, colors.bg(run.color), 2.5f, xFor, yFor)
            }
        }
        if (data.showDots) {
            val d = symbolDiameter(if (isSmall) 14f else 30f)
            for (p in data.bg) {
                if (!visible(p.date)) continue
                drawCircle(colors.bg(p.color), d / 2, Offset(xFor(p.date), yFor(p.value)))
            }
        }

        if (!isSmall) drawSegments(data.smoothedBG, CYAN, 2f, xFor, yFor)
        drawSeries(data.prediction, colors.purple, 2f, xFor, yFor)
        drawSeries(data.zt, ZT_COLOR, 2f, xFor, yFor)
        drawSeries(data.iob, INSULIN_COLOR, 2f, xFor, yFor)
        drawSeries(data.cob, colors.orange, 2f, xFor, yFor)
        drawSeries(data.uam, UAM_COLOR, 2f, xFor, yFor)

        if (showTreatments) {
            val big = symbolDiameter(if (isSmall) 24f else 64f)
            val small = symbolDiameter(if (isSmall) 22f else 54f)
            val labelStyle = TextStyle(fontSize = 11.sp, color = colors.primaryText)
            fun label(t: TreatmentPoint, d: Float) {
                if (isSmall || !data.showValues || t.label.isEmpty()) return
                val layout = measurer.measure(t.label, labelStyle)
                drawText(layout, topLeft = Offset(xFor(t.drawnDate) - layout.size.width / 2, yFor(t.sgv) - d / 2 - layout.size.height - 1.dp.toPx()))
            }
            for (t in data.boluses) {
                if (!visible(t.drawnDate)) continue
                drawCircle(colors.blue.copy(alpha = 0.75f), big / 2, Offset(xFor(t.drawnDate), yFor(t.sgv)))
                label(t, big)
            }
            for (t in data.carbs) {
                if (!visible(t.drawnDate)) continue
                drawCircle(colors.orange.copy(alpha = 0.75f), big / 2, Offset(xFor(t.drawnDate), yFor(t.sgv)))
                label(t, big)
            }
            val tri = symbolDiameter(if (isSmall) 28f else 64f)
            for (t in data.smbs) {
                if (!visible(t.drawnDate)) continue
                val cx = xFor(t.drawnDate)
                val cy = yFor(t.sgv)
                val h = tri * 0.9f
                val path = Path().apply {
                    moveTo(cx - tri / 2, cy - h / 2)
                    lineTo(cx + tri / 2, cy - h / 2)
                    lineTo(cx, cy + h / 2)
                    close()
                }
                drawPath(path, colors.blue.copy(alpha = 0.75f))
                label(t, tri)
            }
            for (t in data.bgChecks) if (visible(t.drawnDate)) drawCircle(colors.red.copy(alpha = 0.75f), small / 2, Offset(xFor(t.drawnDate), yFor(t.sgv)))
            val teal = Color(0xFF40C8E0)
            for (t in data.suspends) if (visible(t.drawnDate)) drawSquare(teal.copy(alpha = 0.75f), small * 0.85f, Offset(xFor(t.drawnDate), yFor(t.sgv)))
            for (t in data.resumes) if (visible(t.drawnDate)) drawSquare(teal.copy(alpha = 0.5f), small * 0.85f, Offset(xFor(t.drawnDate), yFor(t.sgv)))
            val indigo = Color(0xFF5E5CE6)
            for (t in data.sensorStarts) {
                if (!visible(t.drawnDate)) continue
                val c = Offset(xFor(t.drawnDate), yFor(t.sgv))
                val r = small / 2
                drawLine(indigo.copy(alpha = 0.75f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), r * 0.6f)
                drawLine(indigo.copy(alpha = 0.75f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), r * 0.6f)
            }
            for (t in data.notes) if (visible(t.drawnDate)) drawCircle(colors.gray.copy(alpha = 0.75f), small / 2, Offset(xFor(t.drawnDate), yFor(t.sgv)))
            for (t in data.sensorStates) {
                if (!visible(t.drawnDate)) continue
                val cx = xFor(t.drawnDate)
                val cy = yFor(t.sgv)
                val path = Path().apply {
                    moveTo(cx, cy - small / 2)
                    lineTo(cx + small / 2, cy + small / 2)
                    lineTo(cx - small / 2, cy + small / 2)
                    close()
                }
                drawPath(path, colors.orange.copy(alpha = 0.85f))
            }
        }

        if (data.showPriorDayTime) {
            for (d in data.priorDayTimeMarkers) {
                val dash = if (isSmall) floatArrayOf(2.dp.toPx(), 2.dp.toPx()) else floatArrayOf(2.dp.toPx(), 5.dp.toPx())
                vRule(xFor(d), plot, colors.orange.copy(alpha = if (isSmall) 1f else 0.5f), 1f, dash)
            }
        }

        if (!isSmall) {
            drawLine(colors.red.copy(alpha = 0.5f), Offset(0f, yFor(data.lowLine)), Offset(size.width, yFor(data.lowLine)), 2.dp.toPx())
            drawLine(colors.yellow.copy(alpha = 0.5f), Offset(0f, yFor(data.highLine)), Offset(size.width, yFor(data.highLine)), 2.dp.toPx())
            vRule(xFor(data.now), plot, colors.gray.copy(alpha = 0.6f), 1f, null)
            if (data.show30Min) vRule(xFor(data.thirtyMinMark), plot, colors.blue.copy(alpha = 0.7f), 0.5f, null)
            if (data.show90Min) vRule(xFor(data.ninetyMinMark), plot, colors.orange.copy(alpha = 0.7f), 0.5f, null)
            if (data.showDIA) {
                for (d in data.diaMarkers) vRule(xFor(d), plot, colors.gray.copy(alpha = 0.5f), 0.5f, floatArrayOf(2.dp.toPx(), 2.dp.toPx()))
            }
        }
        if (data.showMidnight) {
            val teal = Color(0xFF40C8E0)
            for (d in data.midnightMarkers) vRule(xFor(d), plot, teal.copy(alpha = 0.6f), 0.5f, floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
        }
    }

    if (!isSmall) drawXAxis(colors, measurer, plot, xFor, windowStart, windowEnd, visibleSeconds)
}

private val PINK = Color(0xFFFF375F)
private val CYAN = Color(0xFF64D2FF)
private val INDIGO = Color(0xFF5E5CE6)

private fun historyColor(kind: ChartHistory.PaneKind, colors: AppColors): Color = when (kind) {
    ChartHistory.PaneKind.IOB -> INSULIN_COLOR
    ChartHistory.PaneKind.COB -> colors.orange
    ChartHistory.PaneKind.SENSITIVITY_RATIO -> INDIGO
}

/** A line per joined segment of history points. */
private fun DrawScope.drawSegments(points: List<ChartHistory.Point>, color: Color, widthDp: Float, xFor: (Double) -> Float, yFor: (Double) -> Float) {
    for ((_, segment) in points.groupBy { it.segment }) {
        if (segment.size < 2) continue
        val path = Path()
        segment.forEachIndexed { i, p -> if (i == 0) path.moveTo(xFor(p.date), yFor(p.value)) else path.lineTo(xFor(p.date), yFor(p.value)) }
        drawPath(path, color, style = Stroke(widthDp.dp.toPx()))
    }
}

/** One history pane: dashed ticks, area and line, its own axis labels and title. */
private fun DrawScope.drawHistoryPane(pane: ChartHistory.Pane, rect: Rect, now: Double, xFor: (Double) -> Float, colors: AppColors, measurer: TextMeasurer) {
    val span = (pane.yMax - pane.yMin).takeIf { it > 0 } ?: 1.0
    fun y(v: Double) = rect.top + ((pane.yMax - v) / span).toFloat() * rect.height
    val color = historyColor(pane.kind, colors)
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        for (tick in pane.ticks) {
            drawLine(colors.gray.copy(alpha = 0.4f), Offset(0f, y(tick)), Offset(rect.right, y(tick)), 0.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
        }
        for ((_, segment) in pane.points.groupBy { it.segment }) {
            if (pane.kind.fillsArea && segment.size > 1) {
                val area = Path()
                area.moveTo(xFor(segment.first().date), y(pane.baseline))
                for (p in segment) area.lineTo(xFor(p.date), y(p.value))
                area.lineTo(xFor(segment.last().date), y(pane.baseline))
                area.close()
                drawPath(area, color.copy(alpha = 0.25f))
            }
        }
        val nowX = xFor(now)
        if (nowX in 0f..rect.right) drawLine(colors.gray.copy(alpha = 0.6f), Offset(nowX, rect.top), Offset(nowX, rect.bottom), 1.dp.toPx())
        drawSegments(pane.points, color, 1.5f, xFor, ::y)
    }
    val labelStyle = TextStyle(fontSize = 11.sp, color = colors.secondaryText)
    for (tick in pane.ticks) {
        val layout = measurer.measure(pane.kind.axisLabel(tick), labelStyle)
        val ty = y(tick).coerceIn(rect.top + 7.dp.toPx(), rect.bottom - 7.dp.toPx())
        drawText(layout, topLeft = Offset(rect.right - 4.dp.toPx() - layout.size.width, ty - layout.size.height / 2))
    }
    drawText(measurer.measure(pane.kind.title, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)), topLeft = Offset(4.dp.toPx(), rect.top + 2.dp.toPx()))
    drawLine(colors.primaryText.copy(alpha = 0.15f), Offset(0f, rect.top), Offset(rect.right, rect.top), 0.5.dp.toPx())
}

private fun DrawScope.drawSquare(color: Color, side: Float, center: Offset) {
    drawRect(color, Offset(center.x - side / 2, center.y - side / 2), Size(side, side))
}

private fun DrawScope.vRule(x: Float, plot: Rect, color: Color, widthDp: Float, dash: FloatArray?) {
    if (x < 0 || x > size.width) return
    drawLine(color, Offset(x, plot.top), Offset(x, plot.bottom), widthDp.dp.toPx(), pathEffect = dash?.let { PathEffect.dashPathEffect(it) })
}

private fun DrawScope.drawSeries(points: List<BGPoint>, color: Color, widthDp: Float, xFor: (Double) -> Float, yFor: (Double) -> Float) {
    if (points.size < 2) return
    val path = Path()
    points.forEachIndexed { i, p ->
        val x = xFor(p.date)
        val y = yFor(p.value)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(widthDp.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.drawXAxis(
    colors: AppColors,
    measurer: TextMeasurer,
    plot: Rect,
    xFor: (Double) -> Float,
    windowStart: Double,
    windowEnd: Double,
    visibleSeconds: Double,
) {
    val hours = visibleSeconds / 3600
    val stride = when {
        hours <= 2 -> 1800.0
        hours <= 6 -> 3600.0
        hours <= 12 -> 2 * 3600.0
        else -> 4 * 3600.0
    }
    val tz = DateTimeUtils.displayTimeZone()
    val cal = Calendar.getInstance(tz)
    cal.timeInMillis = ((windowStart - stride) * 1000).toLong()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    var mark = cal.timeInMillis / 1000.0
    val is24 = android.text.format.DateFormat.is24HourFormat(com.loopandlearn.loopfollow.LoopFollowApplication.instance)
    val hourFmt = SimpleDateFormat(if (is24) "HH" else "h a", Locale.getDefault()).apply { timeZone = tz }
    val hmFmt = SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", Locale.getDefault()).apply { timeZone = tz }
    val dayFmt = SimpleDateFormat("EEE d", Locale.getDefault()).apply { timeZone = tz }
    val style = TextStyle(fontSize = 13.sp, color = colors.secondaryText)
    while (mark <= windowEnd) {
        if (mark >= windowStart) {
            val c = Calendar.getInstance(tz).apply { timeInMillis = (mark * 1000).toLong() }
            val date = Date((mark * 1000).toLong())
            val text = when {
                c.get(Calendar.HOUR_OF_DAY) == 0 && c.get(Calendar.MINUTE) == 0 -> dayFmt.format(date)
                stride < 3600 -> hmFmt.format(date)
                else -> hourFmt.format(date)
            }
            val layout = measurer.measure(text, style)
            val x = xFor(mark)
            drawText(layout, topLeft = Offset(x - layout.size.width / 2, plot.bottom + 2.dp.toPx()))
        }
        mark += stride
    }
}

/** A "nice" tick step for about [count] ticks over [range]. */
private fun niceStep(range: Double, count: Double = 5.0): Double {
    val raw = range / count
    val mag = 10.0.pow(floor(log10(raw)))
    val norm = raw / mag
    val step = when {
        norm <= 1 -> 1.0
        norm <= 2 -> 2.0
        norm <= 2.5 -> 2.5
        norm <= 5 -> 5.0
        else -> 10.0
    }
    return step * mag
}

private fun DrawScope.drawYAxes(data: ChartData, colors: AppColors, measurer: TextMeasurer, plot: Rect, yFor: (Double) -> Float) {
    val style = TextStyle(fontSize = 11.sp, color = colors.secondaryText, textAlign = TextAlign.End)
    val tick = 4.dp.toPx()
    val yMax = yDomainUpper(data.maxBG)
    // About one label per 110 dp of plot height, like the automatic axis on iOS.
    val step = niceStep(yMax, max(2.0, (plot.height / 110.dp.toPx()).toDouble()))
    var v = 0.0
    while (v <= yMax) {
        val y = yFor(v)
        drawLine(colors.secondaryText, Offset(size.width - tick, y), Offset(size.width, y), 0.5.dp.toPx())
        val layout = measurer.measure(Localizer.toDisplayUnits(v), style)
        drawText(layout, topLeft = Offset(size.width - tick - 2.dp.toPx() - layout.size.width, y - layout.size.height / 2))
        v += step
    }
    // Basal rates on the leading edge.
    val basalScale = if (data.maxBasal > 0) data.maxBG / data.maxBasal else 0.0
    if (basalScale > 0) {
        var r = 0.0
        val rStep = max(0.5, data.maxBasal / 4)
        while (r <= data.maxBasal + 1e-9) {
            val y = yFor(r * basalScale)
            drawLine(colors.secondaryText, Offset(0f, y), Offset(tick, y), 0.5.dp.toPx())
            val rate = java.math.BigDecimal(r).setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString()
            val layout = measurer.measure(rate + "U", style.copy(textAlign = TextAlign.Start))
            drawText(layout, topLeft = Offset(tick + 2.dp.toPx(), y - layout.size.height / 2))
            r += rStep
        }
    }
}

private fun DrawScope.drawPill(texts: List<String>, x: Float, y: Float, plot: Rect, widthPx: Float, colors: AppColors, measurer: TextMeasurer) {
    val text = if (texts.size == 1) texts[0] else texts.joinToString("\n") { it.replace("\n", " ") }
    val layout = measurer.measure(
        text,
        TextStyle(fontSize = 11.sp, color = colors.primaryText, textAlign = TextAlign.Center, fontWeight = FontWeight.Normal),
        constraints = Constraints(maxWidth = min(300.dp.toPx(), widthPx - 16.dp.toPx()).toInt()),
        maxLines = 10,
    )
    val pillW = layout.size.width + 12.dp.toPx()
    val pillH = layout.size.height + 6.dp.toPx()
    val labelX = min(max(x, pillW / 2 + 4.dp.toPx()), widthPx - pillW / 2 - 4.dp.toPx())
    val below = y + 14.dp.toPx() + pillH / 2
    val above = y - 14.dp.toPx() - pillH / 2
    val fitsBelow = below + pillH / 2 <= plot.bottom - 4.dp.toPx()
    val cy = if (fitsBelow) below else max(above, plot.top + pillH / 2 + 4.dp.toPx())
    val topLeft = Offset(labelX - pillW / 2, cy - pillH / 2)
    val bgColor = if (colors.isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    drawRoundRect(bgColor, topLeft, Size(pillW, pillH), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
    drawRoundRect(colors.primaryText, topLeft, Size(pillW, pillH), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()), style = Stroke(0.5.dp.toPx()))
    drawText(layout, topLeft = Offset(topLeft.x + 6.dp.toPx(), topLeft.y + 3.dp.toPx()))
}
