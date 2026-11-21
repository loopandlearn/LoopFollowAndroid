package com.loopandlearn.loopfollow.storage

import com.loopandlearn.loopfollow.info.InfoColoring
import com.loopandlearn.loopfollow.info.InfoDisplayItem
import com.loopandlearn.loopfollow.info.InfoType
import java.util.TimeZone

/** Observable persistent storage. */
object Storage {
    // Remote
    val remoteType = storageValue("remoteType", RemoteType.NONE)
    val deviceToken = storageValue("deviceToken", "")
    val expirationDate = storageValue<Long?>("expirationDate", null)
    val sharedSecret = storageValue("sharedSecret", "", secret = true)
    val productionEnvironment = storageValue("productionEnvironment", false)
    val remoteApnsKey = storageValue("remoteApnsKey", "", secret = true)
    val teamId = storageValue<String?>("teamId", null)
    val remoteKeyId = storageValue("remoteKeyId", "")
    val lfApnsKey = storageValue("lfApnsKey", "", secret = true)
    val lfKeyId = storageValue("lfKeyId", "")
    /** The Apple team of [lfApnsKey], when the import carried it. */
    val lfTeamId = storageValue<String?>("lfTeamId", null)
    val bundleId = storageValue("bundleId", "")
    val user = storageValue("user", "")
    /** Remote command types the AID app advertises in its Nightscout profile. */
    val remoteCommands = storageValue("remoteCommands", emptyList<String>())
    /** Remote command types the Loop build advertises in its devicestatus. */
    val loopRemoteCommands = storageValue("loopRemoteCommands", emptyList<String>())

    val maxBolus = storageValue("maxBolus", 1.0)
    val maxCarbs = storageValue("maxCarbs", 30.0)
    val maxProtein = storageValue("maxProtein", 30.0)
    val maxFat = storageValue("maxFat", 30.0)
    val mealWithBolus = storageValue("mealWithBolus", false)
    val mealWithFatProtein = storageValue("mealWithFatProtein", false)

    val backgroundRefreshType = storageValue("backgroundRefreshType", BackgroundRefreshType.FOREGROUND_SERVICE)

    val debugLogLevel = storageValue("debugLogLevel", true)

    val sensorScheduleOffset = storageValue<Double?>("sensorScheduleOffset", null)

    val lastOverrideStartNotified = storageValue<Double?>("lastOverrideStartNotified", null)
    val lastOverrideEndNotified = storageValue<Double?>("lastOverrideEndNotified", null)
    val lastOverridePreEndNotified = storageValue<Double?>("lastOverridePreEndNotified", null)
    val lastTempTargetStartNotified = storageValue<Double?>("lastTempTargetStartNotified", null)
    val lastTempTargetEndNotified = storageValue<Double?>("lastTempTargetEndNotified", null)
    val lastTempTargetPreEndNotified = storageValue<Double?>("lastTempTargetPreEndNotified", null)
    val lastRecBolusNotified = storageValue<Double?>("lastRecBolusNotified", null)
    val lastCOBNotified = storageValue<Double?>("lastCOBNotified", null)
    val lastMissedBolusNotified = storageValue<Double?>("lastMissedBolusNotified", null)
    val pendingFutureCarbs = storageValue("pendingFutureCarbs", emptyList<com.loopandlearn.loopfollow.alarm.PendingFutureCarb>())

    val alarms = storageValue("alarms", emptyList<com.loopandlearn.loopfollow.alarm.Alarm>())
    val alarmConfiguration = storageValue("alarmConfiguration", com.loopandlearn.loopfollow.alarm.AlarmConfiguration())

    // General settings
    val appBadge = storageValue("appBadge", true)
    val colorBGText = storageValue("colorBGText", true)
    val appearanceMode = storageValue("appearanceMode", AppearanceMode.DARK)
    val showStats = storageValue("showStats", true)
    /** Show the home screen stats as a Time in Range band; off shows the statistics box. */
    val showTIRBand = storageValue("showTIRBand", false)
    /** Period both home screen stats cover: the last 24 hours, or today since midnight. */
    val statsSinceMidnight = storageValue("statsSinceMidnight", false)
    val useIFCC = storageValue("useIFCC", false)
    val showSmallGraph = storageValue("showSmallGraph", true)
    val screenlockSwitchState = storageValue("screenlockSwitchState", true)
    val showDisplayName = storageValue("showDisplayName", false)
    val snoozerEmoji = storageValue("snoozerEmoji", false)
    val forcePortraitMode = storageValue("forcePortraitMode", false)

    val speakBG = storageValue("speakBG", false)
    val speakBGAlways = storageValue("speakBGAlways", true)
    val speakLowBG = storageValue("speakLowBG", false)
    val speakProactiveLowBG = storageValue("speakProactiveLowBG", false)
    val speakFastDropDelta = storageValue("speakFastDropDelta", 10.0)
    val speakLowBGLimit = storageValue("speakLowBGLimit", 72.0)
    val speakHighBGLimit = storageValue("speakHighBGLimit", 180.0)
    val speakHighBG = storageValue("speakHighBG", false)
    val speakLanguage = storageValue("speakLanguage", "en")

    // Latest glucose state (ongoing notification / widget)
    val lastBgReadingTimeSeconds = storageValue<Double?>("lastBgReadingTimeSeconds", null)
    val lastDeltaMgdl = storageValue<Double?>("lastDeltaMgdl", null)
    val lastTrendCode = storageValue<String?>("lastTrendCode", null)
    val lastIOB = storageValue<Double?>("lastIOB", null)
    val lastCOB = storageValue<Double?>("lastCOB", null)
    val projectedBgMgdl = storageValue<Double?>("projectedBgMgdl", null)

    val lastBasal = storageValue("lastBasal", "")
    val lastPumpReservoirU = storageValue<Double?>("lastPumpReservoirU", null)
    val pumpReservoirCache = storageValue<com.loopandlearn.loopfollow.engine.PumpReservoirCache?>("pumpReservoirCache", null)
    val lastAutosens = storageValue<Double?>("lastAutosens", null)
    val lastTdd = storageValue<Double?>("lastTdd", null)
    val lastTargetLowMgdl = storageValue<Double?>("lastTargetLowMgdl", null)
    val lastTargetHighMgdl = storageValue<Double?>("lastTargetHighMgdl", null)
    val lastIsfMgdlPerU = storageValue<Double?>("lastIsfMgdlPerU", null)
    val lastCarbRatio = storageValue<Double?>("lastCarbRatio", null)
    val lastCarbsToday = storageValue<Double?>("lastCarbsToday", null)
    val lastProfileName = storageValue("lastProfileName", "")
    val iageInsertTime = storageValue("iageInsertTime", 0.0)
    val lastMinBgMgdl = storageValue<Double?>("lastMinBgMgdl", null)
    val lastMaxBgMgdl = storageValue<Double?>("lastMaxBgMgdl", null)

    // Live Activity (shown as the ongoing notification)
    // ANDROID-DEVIATION: on by default; the ongoing notification is posted whenever the background service runs, so it
    // shows glucose unless the user turns it off.
    val laEnabled = storageValue("laEnabled", true)
    /** Raw values of the four grid slots, in the order top left, top right, bottom left, bottom right. */
    val laSlots = storageValue("la.slots", com.loopandlearn.loopfollow.liveactivity.LiveActivitySlotDefaults.all.map { it.raw })
    /** On Android 16 and later, the notification is a Live Update with glucose in a status bar chip. */
    val laStatusChip = storageValue("la.statusChip", true)

    // Graph settings
    val showDots = storageValue("showDots", true)
    val showLines = storageValue("showLines", true)
    val showValues = storageValue("showValues", true)
    val showAbsorption = storageValue("showAbsorption", true)
    val showDIALines = storageValue("showDIAMarkers", true)
    val show30MinLine = storageValue("show30MinLine", false)
    val show90MinLine = storageValue("show90MinLine", false)
    val showMidnightLines = storageValue("showMidnightMarkers", false)
    val showPriorDayTimeLines = storageValue("showPriorDayTimeMarkers", false)
    val showYesterdayLine = storageValue("showYesterdayLine", false)
    val smallGraphTreatments = storageValue("smallGraphTreatments", true)
    val showIOBGraph = storageValue("showIOBGraph", false)
    val showCOBGraph = storageValue("showCOBGraph", false)
    val showSensitivityRatioGraph = storageValue("showSensitivityRatioGraph", false)
    val showSmoothedBG = storageValue("showSmoothedBG", false)
    val historyCurvePlacement = storageValue("historyCurvePlacement", HistoryCurvePlacement.SEPARATE)

    val smallGraphHeight = storageValue("smallGraphHeight", 40)
    val predictionToLoad = storageValue("predictionToLoad", 1.0)
    val predictionDisplayType = storageValue("predictionDisplayType", PredictionDisplayType.CONE)
    val minBasalScale = storageValue("minBasalScale", 5.0)
    val minBGScale = storageValue("minBGScale", 250.0)
    val lowLine = storageValue("lowLine", 70.0)
    val highLine = storageValue("highLine", 180.0)
    val downloadDays = storageValue("downloadDays", 1)
    val graphTimeZoneEnabled = storageValue("graphTimeZoneEnabled", false)
    val graphTimeZoneIdentifier = storageValue("graphTimeZoneIdentifier", TimeZone.getDefault().id)

    val watchLine1 = storageValue("watchLine1", "%BG% %DIRECTION% %DELTA% %MINAGO%")
    val watchLine2 = storageValue("watchLine2", "C:%COB% I:%IOB% B:%BASAL%")

    // Dexcom Share
    val shareUserName = storageValue("shareUserName", "")
    val sharePassword = storageValue("sharePassword", "", secret = true)
    val shareServer = storageValue("shareServer", "US")

    val chartScaleX = storageValue("chartScaleX", 18.0)

    // Advanced settings
    val downloadTreatments = storageValue("downloadTreatments", true)
    val graphOtherTreatments = storageValue("graphOtherTreatments", true)
    val graphBasal = storageValue("graphBasal", true)
    val graphBolus = storageValue("graphBolus", true)
    val graphCarbs = storageValue("graphCarbs", true)
    val bgUpdateDelay = storageValue("bgUpdateDelay", 10)

    // Insert times (sensor / pump)
    val cageInsertTime = storageValue("cageInsertTime", 0.0)
    val sageInsertTime = storageValue("sageInsertTime", 0.0)

    val hideInfoTable = storageValue("hideInfoTable", false)
    val token = storageValue("token", "", secret = true)
    val units = storageValue("units", "mg/dL")
    val hasConfiguredUnits = storageValue("hasConfiguredUnits", false)
    val hasCompletedOnboarding = storageValue("hasCompletedOnboarding", false)

    val infoDisplayItems = storageValue(
        "infoDisplayItems",
        InfoType.entries.map { InfoDisplayItem(it, it.defaultVisible, InfoColoring()) },
    )

    val url = storageValue("url", "")
    val device = storageValue("device", "")
    val nsWriteAuth = storageValue("nsWriteAuth", false)
    val nsAdminAuth = storageValue("nsAdminAuth", false)
    val webSocketEnabled = storageValue("webSocketEnabled", true)

    val persistentNotification = storageValue("persistentNotification", false)
    val persistentNotificationLastBGTime = storageValue("persistentNotificationLastBGTime", 0.0)

    val lastLoopingChecked = storageValue<Long?>("lastLoopingChecked", null)
    val lastBGChecked = storageValue<Long?>("lastBGChecked", null)
    val lastLoopTime = storageValue("lastLoopTime", 0.0)

    // Tab positions (positions 1-4 are customizable, 5 is always Menu)
    val homePosition = storageValue("homePosition", TabPosition.POSITION1)
    val alarmsPosition = storageValue("alarmsPosition", TabPosition.POSITION2)
    val snoozerPosition = storageValue("snoozerPosition", TabPosition.POSITION3)
    val nightscoutPosition = storageValue("nightscoutPosition", TabPosition.POSITION4)
    val remotePosition = storageValue("remotePosition", TabPosition.MENU)
    val statisticsPosition = storageValue("statisticsPosition", TabPosition.MENU)
    val treatmentsPosition = storageValue("treatmentsPosition", TabPosition.MENU)

    val loopAPNSQrCodeURL = storageValue("loopAPNSQrCodeURL", "", secret = true)
    val loopMaxFutureCarbHours = storageValue("loopMaxFutureCarbHours", 1)

    val bolusIncrement = storageValue("bolusIncrement", 0.05)
    val bolusIncrementDetected = storageValue("bolusIncrementDetected", false)

    val remoteBolusHistory = storageValue("remoteBolusHistory", emptyList<com.loopandlearn.loopfollow.remote.RemoteBolusHistoryEntry>())
    val remoteMealHistory = storageValue("remoteMealHistory", emptyList<com.loopandlearn.loopfollow.remote.RemoteMealHistoryEntry>())

    // Telemetry: see helpers/Telemetry.kt.
    val telemetryEnabled = storageValue("telemetryEnabled", true)
    val telemetryConsentDecisionMade = storageValue("telemetryConsentDecisionMade", false)
    val telemetryLastSentAt = storageValue<Long?>("telemetryLastSentAt", null)
    /** [com.loopandlearn.loopfollow.helpers.TelemetryClient.buildIdentifier] at the last successful send. */
    val telemetryLastSentBuild = storageValue("telemetryLastSentBuild", "")
    /** Process start times (epoch millis) within the last 7 days. */
    val telemetryColdLaunchTimes = storageValue("telemetryColdLaunchTimes", emptyList<Long>())
    /** Random UUID sent as `idfv`, created on first use. */
    val telemetryInstallId = storageValue("telemetryInstallId", "")

    // Statistics display preferences
    val showGMI = storageValue("showGMI", true)
    val showStdDev = storageValue("showStdDev", true)
    val showTITR = storageValue("showTITR", false)
    val timeInRangeModeRaw = storageValue("timeInRangeMode", "TIR")

    // Tab position helpers

    fun positionValue(item: TabItem): StorageValue<TabPosition> = when (item) {
        TabItem.HOME -> homePosition
        TabItem.ALARMS -> alarmsPosition
        TabItem.REMOTE -> remotePosition
        TabItem.NIGHTSCOUT -> nightscoutPosition
        TabItem.SNOOZER -> snoozerPosition
        TabItem.STATS -> statisticsPosition
        TabItem.TREATMENTS -> treatmentsPosition
    }

    fun position(item: TabItem): TabPosition = positionValue(item).value

    fun setPosition(position: TabPosition, item: TabItem) {
        positionValue(item).value = position
    }

    fun tabItem(position: TabPosition): TabItem? = TabItem.entries.firstOrNull { position(it) == position }

    fun itemsInMenu(): List<TabItem> = TabItem.featureOrder.filter { position(it) == TabPosition.MENU }

    fun orderedTabBarItems(): List<TabItem> = TabPosition.customizablePositions.mapNotNull { tabItem(it) }
}
