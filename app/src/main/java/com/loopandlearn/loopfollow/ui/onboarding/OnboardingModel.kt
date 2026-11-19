package com.loopandlearn.loopfollow.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.loopandlearn.loopfollow.alarm.Alarm
import com.loopandlearn.loopfollow.alarm.AlarmType
import com.loopandlearn.loopfollow.alarm.DayNightOption
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.settings.DexcomSettingsModel
import com.loopandlearn.loopfollow.ui.settings.NightscoutSettingsModel
import java.util.UUID

/**
 * The phases of the first-run wizard. Some phases hold several pages and report
 * their position through [OnboardingModel.phaseProgress].
 */
enum class OnboardingStep(val phaseTitle: String) {
    WELCOME(""),
    OVERVIEW("Overview"),
    DATA_SOURCE("Data source"),
    CONNECT("Connect"),
    UNITS("Units & metrics"),
    GENERAL_ALARMS("Alarm basics"),
    ALARMS("Alarms"),
    TAB_ORDER("Tabs"),
    NOTIFICATIONS("Notifications"),
    BATTERY("Battery"),
    TELEMETRY("Privacy"),
    COMPLETION(""),
    ;

    val showsProgressHeader get() = this != WELCOME && this != COMPLETION
    val usesSharedFooter get() = this == DATA_SOURCE || this == GENERAL_ALARMS || this == TAB_ORDER
}

enum class OnboardingDataSource { NIGHTSCOUT, DEXCOM, COPY_FROM_PHONE }

/** A recommended alarm offered on the alarms phase. */
data class SeedAlarm(
    val id: String = UUID.randomUUID().toString(),
    val alarm: Alarm,
    val isEnabled: Boolean,
    val title: String,
    val detail: String,
    /** Needs Nightscout loop/uploader data, so it's hidden for Dexcom-only. */
    val requiresNightscout: Boolean,
) {
    val type: AlarmType get() = alarm.type
}

data class PhaseProgress(val page: Int, val count: Int)

class OnboardingModel(
    val nightscout: NightscoutSettingsModel,
    val dexcom: DexcomSettingsModel,
    /** Whether the notification permission is still undecided; the notifications phase only shows then. */
    private val notificationsUndecided: Boolean,
    /** Whether Android may still pause LoopFollow to save battery; the battery phase only shows then. */
    private val batteryRestricted: Boolean,
    private val onClose: () -> Unit,
) {
    var step by mutableStateOf(OnboardingStep.WELCOME)
        private set
    var dataSource by mutableStateOf<OnboardingDataSource?>(null)
    val seedAlarms = mutableStateListOf<SeedAlarm>().apply { addAll(defaultSeedAlarms()) }
    var phaseProgress by mutableStateOf<PhaseProgress?>(null)
    var didImportSettings by mutableStateOf(false)

    /** Captured once, so recording the decision doesn't remove the phase while it is shown. */
    private val includeTelemetryStep = !Storage.telemetryConsentDecisionMade.value

    /** Alarm types the user already has; only new types are offered. */
    private val existingAlarmTypes = Storage.alarms.value.map { it.type }.toSet()

    /** Captured once: the connect step saves as the user types, which must not drop phases mid-flow. */
    val isAlreadyConfigured: Boolean = Storage.url.value.isNotEmpty() ||
        (Storage.shareUserName.value.isNotEmpty() && Storage.sharePassword.value.isNotEmpty())

    val canProceed: Boolean
        get() = when (step) {
            OnboardingStep.DATA_SOURCE -> dataSource != null
            OnboardingStep.CONNECT -> when (dataSource) {
                OnboardingDataSource.NIGHTSCOUT -> nightscout.isConnected || nightscout.provisionedTokenPending
                OnboardingDataSource.DEXCOM -> dexcom.canVerifyProceed
                OnboardingDataSource.COPY_FROM_PHONE -> didImportSettings
                null -> false
            }
            else -> true
        }

    private val hasNightscoutData get() = dataSource == OnboardingDataSource.NIGHTSCOUT || Storage.url.value.isNotEmpty()

    fun isOffered(seed: SeedAlarm): Boolean {
        if (seed.requiresNightscout && !hasNightscoutData) return false
        return seed.type !in existingAlarmTypes
    }

    val offeredSeedAlarms get() = seedAlarms.filter { isOffered(it) }

    private val alarmsOffered get() = offeredSeedAlarms.isNotEmpty()

    /** A returning user with a data source and no new alarms to add. */
    val hasNothingToSetUp get() = isAlreadyConfigured && !alarmsOffered

    val activeSteps: List<OnboardingStep>
        get() {
            val steps = mutableListOf(OnboardingStep.WELCOME, OnboardingStep.OVERVIEW)
            if (hasNothingToSetUp) return steps
            if (!isAlreadyConfigured) steps += listOf(OnboardingStep.DATA_SOURCE, OnboardingStep.CONNECT)
            steps += OnboardingStep.UNITS
            if (alarmsOffered) steps += listOf(OnboardingStep.GENERAL_ALARMS, OnboardingStep.ALARMS)
            steps += OnboardingStep.TAB_ORDER
            if (notificationsUndecided) steps += OnboardingStep.NOTIFICATIONS
            if (batteryRestricted) steps += OnboardingStep.BATTERY
            if (includeTelemetryStep) steps += OnboardingStep.TELEMETRY
            steps += OnboardingStep.COMPLETION
            return steps
        }

    private val chromePhases get() = activeSteps.filter { it.showsProgressHeader }

    val progress: Double
        get() {
            val phases = chromePhases
            if (phases.isEmpty()) return 0.0
            val index = phases.indexOf(step)
            if (index < 0) return if (step == OnboardingStep.COMPLETION) 1.0 else 0.0
            val p = phaseProgress
            val within = if (p != null && p.count > 1) p.page.toDouble() / p.count else 0.0
            return (index + within) / phases.size
        }

    val progressLabel: String
        get() {
            val p = phaseProgress
            return if (p != null && p.count > 1) "${step.phaseTitle} · ${minOf(p.page + 1, p.count)} of ${p.count}" else step.phaseTitle
        }

    val canGoBack get() = activeSteps.indexOf(step) > 0

    fun advance() {
        phaseProgress = null
        val steps = activeSteps
        val index = steps.indexOf(step)
        if (index < 0) {
            val rank = step.ordinal
            val next = steps.firstOrNull { it.ordinal > rank }
            if (next != null) step = next else finish()
            return
        }
        if (index + 1 >= steps.size) {
            finish()
            return
        }
        step = steps[index + 1]
    }

    fun goBack() {
        phaseProgress = null
        val index = activeSteps.indexOf(step)
        if (index > 0) step = activeSteps[index - 1]
    }

    /** Leaves setup without seeding alarms or touching units. */
    fun skip() {
        Storage.hasCompletedOnboarding.value = true
        onClose()
    }

    fun finish() {
        persistSeededAlarms()
        Storage.hasConfiguredUnits.value = true
        Storage.hasCompletedOnboarding.value = true
        onClose()
        MainEngine.refresh()
    }

    fun updateSeed(id: String, transform: (SeedAlarm) -> SeedAlarm) {
        val i = seedAlarms.indexOfFirst { it.id == id }
        if (i >= 0) seedAlarms[i] = transform(seedAlarms[i])
    }

    private fun persistSeededAlarms() {
        val alarms = Storage.alarms.value.toMutableList()
        val existing = alarms.map { it.type }.toSet()
        for (seed in offeredSeedAlarms) {
            if (!seed.isEnabled || seed.type in existing) continue
            alarms += seed.alarm
        }
        Storage.alarms.value = alarms
    }

    companion object {
        private fun seed(type: AlarmType, title: String, detail: String, requiresNightscout: Boolean, configure: (Alarm) -> Alarm = { it }) =
            SeedAlarm(alarm = configure(Alarm.create(type)), isEnabled = true, title = title, detail = detail, requiresNightscout = requiresNightscout)

        fun defaultSeedAlarms(): List<SeedAlarm> = listOf(
            seed(AlarmType.LOW, "Low glucose", "Warns when glucose is low, now or soon.", false) {
                it.copy(name = "Low glucose", belowBG = 70.0, predictiveMinutes = 20)
            },
            seed(AlarmType.LOW, "Urgent low", "A separate warning for when glucose is very low.", false) {
                it.copy(name = "Urgent low", belowBG = 54.0, predictiveMinutes = 0, persistentMinutes = 0)
            },
            seed(AlarmType.HIGH, "High glucose", "Warns when glucose is high.", false) { it.copy(aboveBG = 180.0) },
            seed(AlarmType.FAST_DROP, "Fast drop", "Warns when glucose is falling quickly.", false),
            seed(AlarmType.MISSED_READING, "Missed readings", "Warns when glucose stops updating.", false),
            seed(AlarmType.NOT_LOOPING, "Not looping", "Warns when the loop stops running.", true),
            seed(AlarmType.BATTERY, "Looping phone battery", "Warns when the battery of the phone running Loop or Trio is low.", true),
            seed(AlarmType.IOB, "IOB", "Warns when insulin on board is high.", true),
            seed(AlarmType.COB, "COB", "Warns when carbs on board are high.", true),
            // ANDROID-DEVIATION: the change seeds keep the alarm's own threshold, hours of warning before the sensor
            // lifetime or the 3-day pump limit ends (12 h); iOS seeds 10 and 3 there and labels them as days.
            seed(AlarmType.SENSOR_CHANGE, "Sensor change", "Reminds you when the CGM sensor is due.", true) {
                it.copy(activeOption = DayNightOption.DAY)
            },
            seed(AlarmType.PUMP_CHANGE, "Pump change", "Reminds you when the pump site is due.", true) {
                it.copy(activeOption = DayNightOption.DAY)
            },
            seed(AlarmType.PUMP, "Pump insulin", "Warns when pump reservoir insulin is low.", true),
        )
    }
}
