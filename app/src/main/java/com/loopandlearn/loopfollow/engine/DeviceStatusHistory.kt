package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.LoopFollowApplication
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.nightscout.EventType
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.storage.GlucoseConversion
import com.loopandlearn.loopfollow.storage.Prefs
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** One loop cycle's values, kept for the history graphs and scrubbing. */
@Serializable
data class DeviceStatusHistorySample(
    val date: Double,
    val iob: Double? = null,
    val cob: Double? = null,
    val smoothedBG: Double? = null,
    val isf: Double? = null,
    val sensitivityRatio: Double? = null,
    val forecast: Forecast? = null,
    val eventualBG: Double? = null,
    val recommendedBolus: Double? = null,
    val target: Double? = null,
    val carbRatio: Double? = null,
    val tdd: Double? = null,
    val reservoir: Double? = null,
    val pumpBattery: Double? = null,
    val uploaderBattery: Double? = null,
    val loopOverride: LoopOverride? = null,
) {
    @Serializable
    data class LoopOverride(val multiplier: Double?, val minTarget: Double, val maxTarget: Double)

    @Serializable
    data class Forecast(val start: Double, val curves: Map<String, List<Int>>) {
        companion object {
            const val LOOP_CURVE = "main"
            val OPENAPS_CURVES = listOf("ZT", "IOB", "COB", "UAM")
            const val STEP = 300.0
            const val MAX_VALUES = 73
        }
    }
}

object DeviceStatusHistoryParser {
    private fun number(value: JsonElement?): Double? {
        val p = value as? JsonPrimitive ?: return null
        if (p.isString) return p.content.toDoubleOrNull()
        if (p.booleanOrNull != null) return null
        return p.doubleOrNull?.takeIf { it.isFinite() }
    }

    private fun obj(value: JsonElement?) = value as? JsonObject
    private fun str(value: JsonElement?) = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun parseDate(string: String): Double? = runCatching {
        OffsetDateTime.parse(string).toInstant().toEpochMilli() / 1000.0
    }.getOrNull()

    fun formatDate(seconds: Double): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(Instant.ofEpochMilli((seconds * 1000).toLong()).atOffset(ZoneOffset.UTC))

    fun parse(record: JsonObject): Pair<DeviceStatusHistorySample, Double>? {
        val createdAt = str(record["created_at"])?.let(::parseDate)
        val loop = obj(record["loop"])
        if (loop != null) {
            val date = str(loop["timestamp"])?.let(::parseDate) ?: createdAt ?: return null
            var sample = DeviceStatusHistorySample(
                date = date,
                iob = number(obj(loop["iob"])?.get("iob")),
                cob = number(obj(loop["cob"])?.get("cob")),
                recommendedBolus = number(loop["recommendedBolus"]),
            )
            val predicted = obj(loop["predicted"])
            val values = predicted?.get("values") as? JsonArray
            if (values != null) {
                val start = str(predicted["startDate"])?.let(::parseDate) ?: date
                sample = sample.copy(forecast = forecast(start, mapOf(DeviceStatusHistorySample.Forecast.LOOP_CURVE to values)), eventualBG = number(values.lastOrNull()))
            }
            val override = obj(record["override"])
            if (override != null && (override["active"] as? JsonPrimitive)?.booleanOrNull == true) {
                val range = obj(override["currentCorrectionRange"])
                val lo = number(range?.get("minValue"))
                val hi = number(range?.get("maxValue"))
                if (lo != null && hi != null) sample = sample.copy(loopOverride = DeviceStatusHistorySample.LoopOverride(number(override["multiplier"]), lo, hi))
            }
            return addDeviceFields(record, sample) to (createdAt ?: date)
        }
        val openAPS = obj(record["openaps"])
        if (openAPS != null) {
            val determination = obj(openAPS["suggested"]) ?: obj(openAPS["enacted"]) ?: return null
            val determinationDate = (str(determination["deliverAt"]) ?: str(determination["timestamp"]))?.let(::parseDate)
            val date = determinationDate ?: createdAt ?: return null
            var sample = DeviceStatusHistorySample(
                date = date,
                iob = openAPSIOB(openAPS["iob"]),
                cob = number(determination["COB"]) ?: reasonValue("COB", str(determination["reason"])),
                smoothedBG = number(determination["bg"])?.takeIf { it > 0 },
                isf = number(determination["ISF"])?.takeIf { it > 0 },
                sensitivityRatio = number(determination["sensitivityRatio"])?.takeIf { it > 0 },
                eventualBG = number(determination["eventualBG"]),
                recommendedBolus = number(openAPS["recommendedBolus"]),
                target = number(determination["current_target"])?.let { if (it < 40) it * GlucoseConversion.MMOL_TO_MGDL else it },
                carbRatio = number(determination["CR"])?.takeIf { it > 0 },
                tdd = number(determination["TDD"]) ?: number(obj(openAPS["enacted"])?.get("TDD")),
            )
            sample = addDeviceFields(record, sample)
            val predBGs = obj(determination["predBGs"])
            if (predBGs != null) {
                val curves = DeviceStatusHistorySample.Forecast.OPENAPS_CURVES.mapNotNull { name -> (predBGs[name] as? JsonArray)?.let { name to it } }.toMap()
                sample = sample.copy(forecast = forecast(date, curves))
            }
            return sample to (createdAt ?: date)
        }
        return null
    }

    private fun addDeviceFields(record: JsonObject, sample: DeviceStatusHistorySample): DeviceStatusHistorySample {
        var s = sample
        obj(record["pump"])?.let { pump ->
            s = s.copy(reservoir = number(pump["reservoir"]), pumpBattery = number(obj(pump["battery"])?.get("percent")))
        }
        return s.copy(uploaderBattery = number(obj(record["uploader"])?.get("battery")))
    }

    private fun openAPSIOB(value: JsonElement?): Double? = when (value) {
        is JsonObject -> number(value["iob"])
        is JsonArray -> obj(value.firstOrNull())?.let { number(it["iob"]) }
        else -> null
    }

    private fun forecast(start: Double, raw: Map<String, JsonArray>): DeviceStatusHistorySample.Forecast? {
        val curves = raw.mapNotNull { (name, values) ->
            val ints = values.take(DeviceStatusHistorySample.Forecast.MAX_VALUES).mapNotNull { v -> number(v)?.let { it.coerceIn(0.0, 10000.0).roundToInt() } }
            if (ints.isEmpty()) null else name to ints
        }.toMap()
        return if (curves.isEmpty()) null else DeviceStatusHistorySample.Forecast(start, curves)
    }

    private fun reasonValue(key: String, reason: String?): Double? {
        reason ?: return null
        return Regex("\\b$key: (-?\\d+(?:\\.\\d+)?)").find(reason)?.groupValues?.get(1)?.toDoubleOrNull()
    }
}

@Serializable
data class DeviceStatusHistoryState(
    val samples: List<DeviceStatusHistorySample> = emptyList(),
    val coverageStart: Double? = null,
    val coverageEnd: Double? = null,
) {
    data class FetchRange(val from: Double, val to: Double)

    fun merged(newSamples: List<DeviceStatusHistorySample>): DeviceStatusHistoryState {
        if (newSamples.isEmpty()) return this
        val byKey = LinkedHashMap<Long, DeviceStatusHistorySample>()
        for (s in samples) byKey[Math.round(s.date)] = s
        for (s in newSamples) byKey[Math.round(s.date)] = s
        return copy(samples = byKey.values.sortedBy { it.date })
    }

    fun covered(from: Double, to: Double): DeviceStatusHistoryState {
        if (to < from) return this
        val start = coverageStart
        val end = coverageEnd
        return if (start != null && end != null && from <= end + GAP_TOLERANCE && to >= start - GAP_TOLERANCE) {
            copy(coverageStart = minOf(start, from), coverageEnd = maxOf(end, to))
        } else {
            copy(coverageStart = from, coverageEnd = to)
        }
    }

    /** Appends the newest record when it continues the covered range; null otherwise. */
    fun withLatest(sample: DeviceStatusHistorySample, createdAt: Double): DeviceStatusHistoryState? {
        val start = coverageStart ?: return null
        val end = coverageEnd ?: return null
        if (createdAt < start || createdAt > end + GAP_TOLERANCE) return null
        return merged(listOf(sample)).copy(coverageEnd = maxOf(end, createdAt))
    }

    fun pruned(cutoff: Double): DeviceStatusHistoryState {
        val kept = samples.filter { it.date >= cutoff }
        val end = coverageEnd
        val start = coverageStart
        return when {
            end != null && end < cutoff -> copy(samples = kept, coverageStart = null, coverageEnd = null)
            start != null && start < cutoff -> copy(samples = kept, coverageStart = cutoff)
            else -> copy(samples = kept)
        }
    }

    fun missingRanges(windowStart: Double, latest: Double, now: Double): List<FetchRange> {
        val start = coverageStart
        val end = coverageEnd
        if (start == null || end == null || end < windowStart) return listOf(FetchRange(windowStart, now))
        val ranges = mutableListOf<FetchRange>()
        if (latest > end + GAP_TOLERANCE) ranges.add(FetchRange(end - OVERLAP, maxOf(now, latest)))
        if (windowStart < start - GAP_TOLERANCE) ranges.add(FetchRange(windowStart, start + OVERLAP))
        return ranges
    }

    fun sampleNear(date: Double, tolerance: Double): DeviceStatusHistorySample? {
        if (samples.isEmpty()) return null
        val nearest = samples.minBy { abs(it.date - date) }
        return if (abs(nearest.date - date) <= tolerance) nearest else null
    }

    /** The loop cycle that ran on a reading: the first sample from a minute before it to a gap after. */
    fun cycle(readingDate: Double): DeviceStatusHistorySample? =
        samples.firstOrNull { it.date >= readingDate - 60 }?.takeIf { it.date <= readingDate + GAP_TOLERANCE }

    companion object {
        const val GAP_TOLERANCE = 6.5 * 60
        const val OVERLAP = 60.0
    }
}

/** Device status history over the graph window, fetched in pages and cached on disk. */
object DeviceStatusHistory {
    private const val PAGE_SIZE = 100
    private const val CACHE_VERSION = 2

    @Serializable
    private data class CacheFile(val version: Int, val url: String, val state: DeviceStatusHistoryState)

    var state = DeviceStatusHistoryState()
        private set

    private var loaded = false
    private var started = false
    private var generation = 0
    private var isFetching = false
    private var consecutiveFailures = 0
    private var retryAfter = 0.0

    /** Where a range that failed part-way resumes, so pages already merged are not downloaded again. */
    private var resumePoint: ResumePoint? = null

    private data class ResumePoint(val range: DeviceStatusHistoryState.FetchRange, val upperBound: String, val pagesFetched: Int)

    private var saveScheduled = false

    private val cacheFile: File get() = File(LoopFollowApplication.instance.filesDir, "DeviceStatusHistory.json")

    fun start() {
        if (started) return
        started = true
        Storage.url.flow.drop(1).onEach { reset() }.launchIn(MainEngine.scope)
        merge(
            Storage.showIOBGraph.flow, Storage.showCOBGraph.flow, Storage.showSensitivityRatioGraph.flow,
            Storage.showSmoothedBG.flow, Storage.historyCurvePlacement.flow, Storage.downloadDays.flow,
        ).drop(6).onEach { settingsChanged() }.launchIn(MainEngine.scope)
        load()
    }

    fun ingestLatest(record: JsonObject) {
        if (!loaded) return
        val parsed = DeviceStatusHistoryParser.parse(record)
        if (parsed == null) {
            val createdAt = (record["created_at"] as? JsonPrimitive)?.content?.let(DeviceStatusHistoryParser::parseDate)
            refreshIfNeeded(createdAt)
            return
        }
        val (sample, createdAt) = parsed
        val next = state.withLatest(sample, createdAt)
        if (next != null && next != state) {
            state = next.pruned(windowStart())
            scheduleSave()
            MainEngine.chartChanged()
        }
        refreshIfNeeded(createdAt)
    }

    fun sampleNear(date: Double, tolerance: Double = 5 * 60.0) = state.sampleNear(date, tolerance)

    fun cycle(readingDate: Double) = state.cycle(readingDate)

    private fun settingsChanged() {
        if (loaded) {
            state = state.pruned(windowStart())
            refreshIfNeeded(null)
        }
        MainEngine.chartChanged()
    }

    private fun reset() {
        generation++
        state = DeviceStatusHistoryState()
        isFetching = false
        consecutiveFailures = 0
        retryAfter = 0.0
        resumePoint = null
        loaded = true
        cacheFile.delete()
        MainEngine.chartChanged()
    }

    private fun windowStart() = nowSeconds() - Storage.downloadDays.value * 24 * 3600.0

    private fun refreshIfNeeded(latest: Double?) {
        if (!loaded || isFetching || !MainEngine.isNightscoutEnabled() || nowSeconds() < retryAfter) return
        val now = nowSeconds()
        val resume = resumePoint
        resumePoint = null
        // A resumed range runs on its own; the next poll plans what is still missing.
        val ranges = resume?.let { listOf(it.range) } ?: state.missingRanges(windowStart(), latest ?: now, now)
        if (ranges.isEmpty()) return
        isFetching = true
        val gen = generation
        MainEngine.scope.launch { fetch(ranges, resume, gen) }
    }

    /** Fetches [ranges] in order; the first continues from [resume] when set. */
    private suspend fun fetch(ranges: List<DeviceStatusHistoryState.FetchRange>, resume: ResumePoint?, gen: Int) {
        for ((i, range) in ranges.withIndex()) {
            LogManager.log(LogManager.Category.DEVICE_STATUS, "Device status history fetch ${DeviceStatusHistoryParser.formatDate(range.from)} – ${DeviceStatusHistoryParser.formatDate(range.to)}", isDebug = true)
            val resumeHere = resume?.takeIf { i == 0 }
            var upperBound: String? = resumeHere?.upperBound
            var pages = resumeHere?.pagesFetched ?: 0
            val maxPages = ceil((range.to - range.from) / 60 / PAGE_SIZE).toInt() + 1
            while (true) {
                val params = buildMap {
                    put("count", PAGE_SIZE.toString())
                    put("find[created_at][\$gte]", DeviceStatusHistoryParser.formatDate(range.from))
                    if (upperBound != null) put("find[created_at][\$lt]", upperBound!!) else put("find[created_at][\$lte]", DeviceStatusHistoryParser.formatDate(range.to))
                }
                val records = try {
                    NightscoutUtils.executeRequest(EventType.DEVICE_STATUS, params) as? JsonArray ?: throw IllegalStateException("unexpected response")
                } catch (e: Exception) {
                    if (gen != generation) return
                    upperBound?.let { resumePoint = ResumePoint(range, it, pages) }
                    consecutiveFailures++
                    val backoff = min(30 * 2.0.pow(consecutiveFailures - 1), 600.0)
                    retryAfter = nowSeconds() + backoff
                    isFetching = false
                    LogManager.log(LogManager.Category.DEVICE_STATUS, "Device status history fetch failed (${e.message}), retrying in ${backoff.toInt()} s", limitIdentifier = "Device status history fetch failed")
                    return
                }
                if (gen != generation) return
                var oldestRaw: String? = null
                var oldest = Double.POSITIVE_INFINITY
                val samples = withContext(Dispatchers.Default) {
                    records.mapNotNull { r ->
                        val record = r as? JsonObject ?: return@mapNotNull null
                        val raw = (record["created_at"] as? JsonPrimitive)?.content
                        val d = raw?.let(DeviceStatusHistoryParser::parseDate)
                        if (d != null && d < oldest) {
                            oldest = d
                            oldestRaw = raw
                        }
                        DeviceStatusHistoryParser.parse(record)?.first
                    }
                }
                if (gen != generation) return
                state = state.merged(samples)
                MainEngine.chartChanged()
                pages++
                val isFull = records.size >= PAGE_SIZE && oldestRaw != null
                if (isFull && pages < maxPages) {
                    upperBound = oldestRaw
                } else {
                    if (isFull) LogManager.log(LogManager.Category.DEVICE_STATUS, "Device status history page limit reached; older records in the range are skipped")
                    break
                }
            }
            consecutiveFailures = 0
            state = state.covered(range.from, range.to).pruned(windowStart())
            scheduleSave()
        }
        isFetching = false
    }

    private fun load() {
        val expectedURL = Storage.url.value
        MainEngine.scope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching { Prefs.json.decodeFromString(CacheFile.serializer(), cacheFile.readText()) }.getOrNull()
            }
            if (cached != null && cached.version == CACHE_VERSION && cached.url == expectedURL) {
                state = cached.state.pruned(windowStart())
            }
            loaded = true
            MainEngine.chartChanged()
            refreshIfNeeded(null)
        }
    }

    private fun scheduleSave() {
        if (saveScheduled) return
        saveScheduled = true
        MainEngine.scope.launch {
            delay(5000)
            saveScheduled = false
            val file = CacheFile(CACHE_VERSION, Storage.url.value, state)
            withContext(Dispatchers.IO) {
                runCatching { cacheFile.writeText(Prefs.json.encodeToString(CacheFile.serializer(), file)) }
                    .onFailure { LogManager.log(LogManager.Category.DEVICE_STATUS, "Device status history save failed: ${it.message}") }
            }
        }
    }
}
