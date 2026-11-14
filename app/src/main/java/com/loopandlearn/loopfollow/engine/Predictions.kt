package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.helpers.Globals
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.storage.PredictionDisplayType
import com.loopandlearn.loopfollow.storage.Storage
import kotlin.math.roundToInt

data class ConePoint(val date: Double, val yMin: Double, val yMax: Double)

/** OpenAPS/Trio forecast lines (ZT, IOB, COB, UAM) and the cone; set by [updateOpenAPSPredictionDisplay]. */
object PredictionStore {
    var zt: List<ShareGlucoseData> = emptyList()
    var iob: List<ShareGlucoseData> = emptyList()
    var cob: List<ShareGlucoseData> = emptyList()
    var uam: List<ShareGlucoseData> = emptyList()
    var cone: List<ConePoint> = emptyList()

    fun clearLines() {
        zt = emptyList()
        iob = emptyList()
        cob = emptyList()
        uam = emptyList()
    }
}

fun MainEngine.recomputeTopPredictionBG() {
    val maxBGOffset = 20.0
    topPredictionBG = Storage.minBGScale.value
    val all = predictionData + PredictionStore.zt + PredictionStore.iob + PredictionStore.cob + PredictionStore.uam
    for (entry in all) {
        if (entry.sgv > topPredictionBG - maxBGOffset) topPredictionBG = entry.sgv + maxBGOffset
    }
}

/**
 * Routes OpenAPS/Trio predictions into a cone band or individual lines.
 * Cone is only for OpenAPS-based backends; Loop always uses lines.
 */
fun MainEngine.updateOpenAPSPredictionDisplay() {
    val predBGs = openAPSPredBGs
    if (predBGs == null) {
        PredictionStore.cone = emptyList()
        chartChanged()
        return
    }

    val displayType = if (Storage.device.value == "Loop") PredictionDisplayType.LINES else Storage.predictionDisplayType.value
    val toLoad = (Storage.predictionToLoad.value * 12).toInt()
    val predictionStart = openAPSPredUpdatedTime ?: nowSeconds()
    val types = listOf("ZT", "IOB", "COB", "UAM")
    val minDisplay = Globals.MIN_DISPLAY_GLUCOSE
    val maxDisplay = Globals.MAX_DISPLAY_GLUCOSE

    topPredictionBG = Storage.minBGScale.value

    if (displayType == PredictionDisplayType.CONE) {
        PredictionStore.clearLines()
        val allArrays = types.mapNotNull { predBGs[it] }.filter { it.isNotEmpty() }
        val cone = mutableListOf<ConePoint>()
        if (allArrays.isNotEmpty()) {
            // Cap at the shortest array so every point uses the same set of arrays.
            val coneLength = minOf(allArrays.minOf { it.size }, toLoad + 1)
            var t = predictionStart
            for (i in 0 until coneLength) {
                val values = allArrays.mapNotNull { it.getOrNull(i) }
                if (values.isNotEmpty()) {
                    var yMin = maxOf(values.min(), minDisplay.toDouble())
                    var yMax = minOf(values.max(), maxDisplay.toDouble())
                    // Keep a minimum ±1 mg/dL band so the cone stays visible when predictions agree.
                    if (yMin == yMax) {
                        yMin -= 1
                        yMax += 1
                    }
                    cone.add(ConePoint(t, yMin, yMax))
                    if (yMax > topPredictionBG - 20) topPredictionBG = yMax + 20
                }
                t += 300
            }
        }
        PredictionStore.cone = cone
    } else {
        PredictionStore.cone = emptyList()
        val lines = types.map { type ->
            val graphData = predBGs[type] ?: return@map emptyList()
            val points = mutableListOf<ShareGlucoseData>()
            var t = predictionStart
            for (i in 0..toLoad) {
                if (i >= graphData.size) break
                val clamped = graphData[i].roundToInt().coerceIn(minDisplay, maxDisplay)
                points.add(ShareGlucoseData(clamped, t, "flat"))
                t += 300
            }
            points
        }
        PredictionStore.zt = lines[0]
        PredictionStore.iob = lines[1]
        PredictionStore.cob = lines[2]
        PredictionStore.uam = lines[3]
        recomputeTopPredictionBG()
    }
    chartChanged()
}
