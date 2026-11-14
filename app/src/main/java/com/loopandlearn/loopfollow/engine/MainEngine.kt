package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.helpers.CGMSensorState
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.helpers.positionalMinutes
import com.loopandlearn.loopfollow.info.InfoManager
import com.loopandlearn.loopfollow.info.InfoType
import com.loopandlearn.loopfollow.info.InfoDisplayItem
import com.loopandlearn.loopfollow.info.InfoColoring
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.storage.TabItem
import com.loopandlearn.loopfollow.storage.UiColor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class ShareGlucoseData(val sgv: Int, val date: Double, val direction: String?)

data class BasalGraphPoint(val basalRate: Double, val date: Double)

data class BolusGraphPoint(val value: Double, val date: Double, val sgv: Int, val detail: Treatment? = null)

data class CarbGraphPoint(val value: Double, val date: Double, val sgv: Int, val absorptionTime: Int, val detail: Treatment? = null)

data class TimestampPoint(val date: Double, val sgv: Int)

data class NotePoint(val date: Double, val sgv: Int, val note: String)

data class BatteryPoint(val batteryLevel: Double, val timestamp: Double)

data class OverrideSpan(
    val insulNeedsScaleFactor: Double,
    val date: Double,
    val endDate: Double,
    val duration: Double,
    val correctionRange: List<Int>,
    val enteredBy: String,
    val reason: String,
    val sgv: Float,
    /** Scheduled end (start + duration), never clamped to the graph horizon; null while indefinite. */
    val scheduledEndDate: Double?,
    val detail: Treatment? = null,
)

data class TempTargetSpan(
    val date: Double,
    var endDate: Double,
    val duration: Double,
    val correctionRange: List<Int>,
    val enteredBy: String,
    val reason: String,
    val detail: Treatment? = null,
) {
    /** Scheduled end; null for an indefinite target, which Trio stores with a duration of about 30 days. */
    val scheduledEndDate: Double? get() = if (duration >= 7 * 24 * 3600) null else endDate
}

data class BasalProfileEntry(val value: Double, val time: String, val timeAsSeconds: Double)

/**
 * The long-lived data pipeline: fetches from Nightscout / Dexcom Share,
 * derives the displayed values and holds the graph data.
 */
object MainEngine {
    /** A failure while processing downloaded data is logged and the downloads start over, so one bad record cannot stop the pipeline or the app. */
    private val failureHandler = CoroutineExceptionHandler { _, e ->
        LogManager.log(LogManager.Category.GENERAL, "Data pipeline failure: $e", limitIdentifier = "Data pipeline failure")
        recoverAfterFailure()
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + failureHandler)

    val infoManager = InfoManager()
    val profileManager = ProfileManager

    private var started = false
    private var recovery: Job? = null

    var currentSage: String? = null
    var currentCage: String? = null
    var currentIage: String? = null

    var bgData: List<ShareGlucoseData> = emptyList()
    /** Readings already shifted +24h for the comparison overlay. */
    var yesterdayBGData: List<ShareGlucoseData> = emptyList()
    var basalProfile: List<BasalProfileEntry> = emptyList()
    var basalData: List<BasalGraphPoint> = emptyList()
    var basalScheduleData: List<BasalGraphPoint> = emptyList()
    var bolusData: List<BolusGraphPoint> = emptyList()
    var smbData: List<BolusGraphPoint> = emptyList()
    var carbData: List<CarbGraphPoint> = emptyList()
    var overrideGraphData: List<OverrideSpan> = emptyList()
    var tempTargetGraphData: List<TempTargetSpan> = emptyList()
    var predictionData: List<ShareGlucoseData> = emptyList()
    var loopPredBGs: List<Double>? = null
    var loopPredUpdatedTime: Double? = null
    var openAPSPredBGs: Map<String, List<Double>>? = null
    var openAPSPredUpdatedTime: Double? = null
    var bgCheckData: List<ShareGlucoseData> = emptyList()
    var suspendGraphData: List<TimestampPoint> = emptyList()
    var resumeGraphData: List<TimestampPoint> = emptyList()
    var sensorStartGraphData: List<TimestampPoint> = emptyList()
    var noteGraphData: List<NotePoint> = emptyList()
    var cgmSensorStates: List<CGMSensorState> = emptyList()
    var deviceBatteryData: MutableList<BatteryPoint> = mutableListOf()
    var latestCOB: com.loopandlearn.loopfollow.info.CarbMetric? = null
    var latestBasal = ""
    var latestPumpVolume: Double? = null
    var latestPumpBatteryDate: Double? = null
    var latestIOB: com.loopandlearn.loopfollow.info.InsulinMetric? = null

    var topBG: Double = Storage.minBGScale.value
    var topPredictionBG: Double = Storage.minBGScale.value

    /** Data the chart draws; bumped whenever any of it changes. */
    private val chartRevisionState = MutableStateFlow(0L)
    val chartRevision: StateFlow<Long> = chartRevisionState.asStateFlow()

    // Initial loading state
    private var isInitialLoad = true
    private val loadingStates = mutableMapOf("bg" to false, "profile" to false, "deviceStatus" to false)

    var firstGraphLoad = true

    fun isNightscoutEnabled(): Boolean = Storage.url.value.isNotEmpty()

    fun isDataSourceConfigured(): Boolean =
        Storage.url.value.isNotEmpty() ||
            (Storage.shareUserName.value.isNotEmpty() && Storage.sharePassword.value.isNotEmpty())

    /** Starts the pipeline once; safe to call repeatedly. */
    fun bootstrap() {
        if (started) return
        started = true

        synchronizeInfoTypes()
        DexcomShare.configure()

        TaskScheduler.onTick = { com.loopandlearn.loopfollow.service.Watchdog.reschedule(com.loopandlearn.loopfollow.LoopFollowApplication.instance) }

        DeviceStatusHistory.start()
        scheduleAllTasks()
        scheduleLogCleanupTask()
        NightscoutSocketManager.connectIfNeeded()

        // When an alarm is triggered, go to the snoozer tab.
        Observable.currentAlarm.flow.filterNotNull().onEach {
            val index = Storage.orderedTabBarItems().indexOf(TabItem.SNOOZER)
            if (index >= 0) Observable.selectedTabIndex.value = index
        }.launchIn(scope)

        Storage.colorBGText.flow.onEach { updateBGTextAppearance() }.launchIn(scope)

        merge(
            Storage.units.flow, Storage.useIFCC.flow, Storage.showGMI.flow, Storage.showStdDev.flow,
            Storage.statsSinceMidnight.flow, Storage.lowLine.flow, Storage.highLine.flow,
            Storage.graphTimeZoneEnabled.flow, Storage.graphTimeZoneIdentifier.flow,
        ).drop(1).onEach {
            updateStats()
            chartChanged()
        }.launchIn(scope)

        Storage.timeInRangeModeRaw.flow.drop(1).onEach {
            updateBGTextAppearance()
            chartChanged()
            updateStats()
        }.launchIn(scope)

        Storage.showDisplayName.flow.onEach { updateServerText() }.launchIn(scope)

        merge(Storage.shareUserName.flow, Storage.sharePassword.flow, Storage.shareServer.flow)
            .drop(1)
            .onEach { DexcomShare.configure() }
            .launchIn(scope)

        Storage.device.flow.onEach {
            val isTrio = Storage.device.value == "Trio"
            val isLoop = Storage.device.value == "Loop"
            val current = Storage.remoteType.value
            val shouldReset = (current == com.loopandlearn.loopfollow.storage.RemoteType.LOOP_APNS && !isLoop) ||
                (current == com.loopandlearn.loopfollow.storage.RemoteType.TRC && !isTrio)
            if (shouldReset) Storage.remoteType.value = com.loopandlearn.loopfollow.storage.RemoteType.NONE
        }.launchIn(scope)

        Storage.device.flow
            .map { if (it.isEmpty()) null else it == "Loop" }
            .distinctUntilChanged()
            .drop(1)
            .onEach { isLoop ->
                isLoop ?: return@onEach
                Storage.predictionDisplayType.value = if (isLoop) {
                    com.loopandlearn.loopfollow.storage.PredictionDisplayType.LINES
                } else {
                    com.loopandlearn.loopfollow.storage.PredictionDisplayType.CONE
                }
                updateOpenAPSPredictionDisplay()
            }.launchIn(scope)

        if (isDataSourceConfigured()) {
            setupLoadingState()
        } else {
            isInitialLoad = false
        }
    }

    private fun recoverAfterFailure() {
        if (recovery?.isActive == true) return
        recovery = scope.launchDelayed(FAILURE_RETRY_MILLIS) { scheduleAllTasks() }
    }

    fun scheduleAllTasks() {
        scheduleBGTask()
        scheduleProfileTask()
        scheduleDeviceStatusTask()
        scheduleTreatmentsTask()
        scheduleMinAgoTask()
        scheduleAlarmTask()
        scheduleDBSizeTask()
    }

    // ANDROID-DEVIATION: old log files are removed at process start and again shortly after each midnight, since
    // the background service can keep one process alive for days.
    private fun scheduleLogCleanupTask() {
        fun nextRun() = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 1)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        TaskScheduler.scheduleTask(TaskID.LOG_CLEANUP, nextRun()) {
            LogManager.cleanupOldLogs()
            TaskScheduler.rescheduleTask(TaskID.LOG_CLEANUP, nextRun())
        }
    }

    /** Clean all timers and start new ones. */
    fun refresh() {
        LogManager.log(LogManager.Category.GENERAL, "Refreshing")
        Observable.minAgoText.value = "Refreshing"
        scheduleAllTasks()
        NightscoutSocketManager.connectIfNeeded()
        currentCage = null
        currentSage = null
        currentIage = null
    }

    /** Called after the data source was configured from first-time setup. */
    fun startInitialLoad() {
        loadingStates.keys.forEach { loadingStates[it] = false }
        isInitialLoad = true
        setupLoadingState()
        refresh()
    }

    private fun setupLoadingState() {
        // Without Nightscout only BG data is needed (from Dexcom Share).
        if (!isNightscoutEnabled()) {
            loadingStates["profile"] = true
            loadingStates["deviceStatus"] = true
        }
        Observable.isInitialLoading.value = true
        scope.launchDelayed(15_000) {
            if (isInitialLoad) {
                LogManager.log(LogManager.Category.GENERAL, "Loading timeout reached, hiding overlay")
                isInitialLoad = false
                Observable.isInitialLoading.value = false
            }
        }
    }

    fun markDataLoaded(key: String) {
        if (!isInitialLoad) return
        loadingStates[key] = true
        if (loadingStates.values.all { it }) {
            isInitialLoad = false
            Observable.isInitialLoading.value = false
        }
    }

    fun chartChanged() {
        chartRevisionState.value = chartRevisionState.value + 1
    }

    // Graph update hooks. The chart reads the engine's data when the revision changes.
    fun updateBGGraph() {
        val maxBGOffset = 50.0
        topBG = Storage.minBGScale.value
        for (entry in bgData) {
            if (entry.sgv > topBG - maxBGOffset) topBG = entry.sgv + maxBGOffset
        }
        // Profile processing defers the scheduled-basal segments until the first BG render.
        if (bgData.isNotEmpty()) firstGraphLoad = false
        chartChanged()
    }
    fun updatePredictionGraph() {
        recomputeTopPredictionBG()
        chartChanged()
    }
    fun updateBasalGraph() = chartChanged()
    fun updateBasalScheduledGraph() = chartChanged()
    fun updateBolusGraph() = chartChanged()
    fun updateSmbGraph() = chartChanged()
    fun updateCarbGraph() = chartChanged()
    fun updateBGCheckGraph() = chartChanged()
    fun updateOverrideGraph() = chartChanged()
    fun updateTempTargetGraph() = chartChanged()
    fun updateSuspendGraph() = chartChanged()
    fun updateResumeGraph() = chartChanged()
    fun updateSensorStart() = chartChanged()
    fun updateNotes() = chartChanged()

    fun calculateMaxBgGraphValue(): Float = maxOf(topBG.toFloat(), topPredictionBG.toFloat())

    fun updateBGTextAppearance() {
        val latest = bgData.lastOrNull() ?: return
        if (Storage.colorBGText.value) {
            val (low, high) = UnitSettingsStore.effectiveThresholds()
            Observable.bgTextColor.value = when {
                latest.sgv.toDouble() >= high -> UiColor.YELLOW
                latest.sgv.toDouble() <= low -> UiColor.RED
                else -> UiColor.GREEN
            }
        } else {
            Observable.bgTextColor.value = UiColor.PRIMARY
        }
    }

    fun bgDirectionGraphic(value: String): String = when (value) {
        "Flat" -> "→"
        "DoubleUp" -> "↑↑"
        "SingleUp" -> "↑"
        "FortyFiveUp" -> "↗"
        "FortyFiveDown" -> "↘︎"
        "SingleDown" -> "↓"
        "DoubleDown" -> "↓↓"
        else -> "-"
    }

    private fun synchronizeInfoTypes() {
        val items = Storage.infoDisplayItems.value.filter { it.type in InfoType.entries }.toMutableList()
        val present = items.map { it.type }.toSet()
        for (type in InfoType.entries) {
            if (type !in present) items.add(InfoDisplayItem(type, type.defaultVisible, InfoColoring()))
        }
        Storage.infoDisplayItems.value = items
    }

    // MARK: - Min ago

    private fun scheduleMinAgoTask(initialDelay: Double = 1.0) {
        TaskScheduler.scheduleTask(TaskID.MIN_AGO_UPDATE, millisFromNow(initialDelay)) { minAgoTaskAction() }
    }

    private fun minAgoTaskAction() {
        val lastBG = bgData.lastOrNull()
        if (lastBG == null) {
            Observable.minAgoText.value = ""
            Observable.bgText.value = ""
            TaskScheduler.rescheduleTaskIn(TaskID.MIN_AGO_UPDATE, 1.0)
            return
        }

        val secondsAgo = nowSeconds() - lastBG.date
        val shouldDisplaySeconds = secondsAgo >= 270 && secondsAgo < 720

        val text = positionalMinutes(secondsAgo, shouldDisplaySeconds) + " min ago"
        if (text != Observable.minAgoText.value) Observable.minAgoText.value = text

        val deltaTime = secondsAgo / 60
        Observable.bgStale.value = deltaTime >= 12

        val nextUpdateInterval = when {
            shouldDisplaySeconds -> 1.0
            secondsAgo >= 240 && secondsAgo < 720 -> 270.0 - secondsAgo
            else -> 60.0 - (secondsAgo % 60.0)
        }
        TaskScheduler.rescheduleTaskIn(TaskID.MIN_AGO_UPDATE, maxOf(nextUpdateInterval, 1.0))
    }

    // MARK: - Stats

    fun updateStats() {
        StatsDisplayModel.update(bgData)
    }
}

private const val FAILURE_RETRY_MILLIS = 60_000L

fun millisFromNow(seconds: Double): Long = System.currentTimeMillis() + (seconds * 1000).toLong()

fun CoroutineScope.launchDelayed(millis: Long, block: () -> Unit) =
    launch {
        delay(millis)
        block()
    }
