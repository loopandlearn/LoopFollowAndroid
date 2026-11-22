package com.loopandlearn.loopfollow.alarm

import com.loopandlearn.loopfollow.storage.Observable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Calendar
import java.util.UUID

/** Categorizes alarms, prioritized in the order they appear here. */
@Serializable
enum class AlarmType(val raw: String) {
    @SerialName("Temporary Alert") TEMPORARY("Temporary Alert"),
    @SerialName("IOB Alert") IOB("IOB Alert"),
    @SerialName("COB Alert") COB("COB Alert"),
    @SerialName("Low BG Alert") LOW("Low BG Alert"),
    @SerialName("High BG Alert") HIGH("High BG Alert"),
    @SerialName("Fast Drop Alert") FAST_DROP("Fast Drop Alert"),
    @SerialName("Fast Rise Alert") FAST_RISE("Fast Rise Alert"),
    @SerialName("Missed Reading Alert") MISSED_READING("Missed Reading Alert"),
    @SerialName("Not Looping Alert") NOT_LOOPING("Not Looping Alert"),
    @SerialName("Missed Bolus Alert") MISSED_BOLUS("Missed Bolus Alert"),
    @SerialName("Future Carbs Alert") FUTURE_CARBS("Future Carbs Alert"),
    @SerialName("Sensor Change Alert") SENSOR_CHANGE("Sensor Change Alert"),
    @SerialName("Pump Change Alert") PUMP_CHANGE("Pump Change Alert"),
    @SerialName("Pump Insulin Alert") PUMP("Pump Insulin Alert"),
    @SerialName("Pump Battery Alert") PUMP_BATTERY("Pump Battery Alert"),
    @SerialName("Low Battery") BATTERY("Low Battery"),
    @SerialName("Battery Drop") BATTERY_DROP("Battery Drop"),
    @SerialName("Rec. Bolus") REC_BOLUS("Rec. Bolus"),
    @SerialName("Override Started") OVERRIDE_START("Override Started"),
    @SerialName("Override Ended") OVERRIDE_END("Override Ended"),
    @SerialName("Temp Target Started") TEMP_TARGET_START("Temp Target Started"),
    @SerialName("Temp Target Ended") TEMP_TARGET_END("Temp Target Ended"),
    @SerialName("Looping app expiration") BUILD_EXPIRE("Looping app expiration"),
    @SerialName("Nightscout Database Size") DB_SIZE("Nightscout Database Size"),
    ;

    val priority: Int get() = ordinal

    /** True for alarms whose primary trigger is a glucose value or its rate of change. */
    val isBGBased: Boolean
        get() = this in setOf(LOW, HIGH, FAST_DROP, FAST_RISE, MISSED_READING, TEMPORARY)

    /** True if this alarm may be silenced with "Acknowledge" (snooze time 0). */
    val canAcknowledge: Boolean
        get() = this in setOf(LOW, HIGH, FAST_DROP, FAST_RISE, TEMPORARY, COB, MISSED_BOLUS, FUTURE_CARBS, REC_BOLUS, OVERRIDE_START, OVERRIDE_END, TEMP_TARGET_START, TEMP_TARGET_END)

    val snoozeTimeUnit: TimeUnit
        get() = when (this) {
            BUILD_EXPIRE, DB_SIZE -> TimeUnit.DAY
            LOW, HIGH, FAST_DROP, FAST_RISE, MISSED_READING, NOT_LOOPING, MISSED_BOLUS, FUTURE_CARBS, REC_BOLUS,
            OVERRIDE_START, OVERRIDE_END, TEMP_TARGET_START, TEMP_TARGET_END -> TimeUnit.MINUTE
            BATTERY, BATTERY_DROP, SENSOR_CHANGE, PUMP_CHANGE, COB, IOB, PUMP, PUMP_BATTERY -> TimeUnit.HOUR
            TEMPORARY -> TimeUnit.NONE
        }

    val snoozeRange: IntRange
        get() = when (snoozeTimeUnit) {
            TimeUnit.MINUTE -> (if (canAcknowledge) 0 else 5)..120
            TimeUnit.HOUR -> (if (canAcknowledge) 0 else 1)..24
            TimeUnit.DAY -> (if (canAcknowledge) 0 else 1)..10
            TimeUnit.NONE -> 0..0
        }

    val snoozeStep: Int get() = if (snoozeTimeUnit == TimeUnit.MINUTE) 5 else 1

    enum class Group(val title: String) {
        GLUCOSE("Glucose"),
        INSULIN("Insulin / Food"),
        DEVICE("Device / System"),
        OTHER("Override / Target"),
    }

    val group: Group
        get() = when (this) {
            LOW, HIGH, FAST_DROP, FAST_RISE, MISSED_READING, TEMPORARY -> Group.GLUCOSE
            IOB, COB, MISSED_BOLUS, FUTURE_CARBS, REC_BOLUS -> Group.INSULIN
            BATTERY, BATTERY_DROP, PUMP, PUMP_BATTERY, PUMP_CHANGE, SENSOR_CHANGE, NOT_LOOPING, BUILD_EXPIRE, DB_SIZE -> Group.DEVICE
            OVERRIDE_START, OVERRIDE_END, TEMP_TARGET_START, TEMP_TARGET_END -> Group.OTHER
        }

    val blurb: String
        get() = when (this) {
            LOW -> "Alerts when BG goes below a limit."
            HIGH -> "Alerts when BG rises above a limit."
            FAST_DROP -> "Rapid downward BG trend."
            FAST_RISE -> "Rapid upward BG trend."
            MISSED_READING -> "No CGM data for X minutes."
            IOB -> "High insulin-on-board."
            COB -> "High carbs-on-board."
            MISSED_BOLUS -> "Carbs without bolus."
            FUTURE_CARBS -> "Reminder when future carbs are due."
            REC_BOLUS -> "Recommended bolus issued."
            BATTERY -> "Phone battery low."
            BATTERY_DROP -> "Battery drops quickly."
            PUMP -> "Reservoir level low."
            PUMP_BATTERY -> "Pump battery low."
            PUMP_CHANGE -> "Pump change due."
            SENSOR_CHANGE -> "Sensor change due."
            NOT_LOOPING -> "Loop hasn’t completed."
            BUILD_EXPIRE -> "Looping-app build expiring."
            DB_SIZE -> "Nightscout database filling up."
            OVERRIDE_START -> "Override just started."
            OVERRIDE_END -> "Override ended."
            TEMP_TARGET_START -> "Temp target started."
            TEMP_TARGET_END -> "Temp target ended."
            TEMPORARY -> "One-time BG limit alert."
        }

    enum class SortDirection { ASCENDING, DESCENDING }

    /** Which field orders alarms of this type, and in which direction. */
    val sortSpec: Pair<SortDirection, (Alarm) -> Double?>?
        get() = when (this) {
            LOW -> SortDirection.ASCENDING to { a -> a.belowBG ?: Double.NaN }
            HIGH -> SortDirection.DESCENDING to { a -> a.aboveBG ?: Double.NEGATIVE_INFINITY }
            FAST_DROP, FAST_RISE -> SortDirection.DESCENDING to { a -> a.delta?.let { kotlin.math.abs(it) } }
            MISSED_READING, NOT_LOOPING, MISSED_BOLUS, BUILD_EXPIRE -> SortDirection.ASCENDING to { a -> a.threshold }
            IOB, COB -> SortDirection.DESCENDING to { a -> a.threshold }
            SENSOR_CHANGE, DB_SIZE -> SortDirection.ASCENDING to { a -> a.threshold }
            else -> null
        }
}

enum class TimeUnit(val seconds: Double, val label: String) {
    MINUTE(60.0, "min"),
    HOUR(3600.0, "hours"),
    DAY(86400.0, "days"),
    NONE(0.0, "none"),
}

@Serializable
enum class DayNightOption(val raw: String) {
    @SerialName("always") ALWAYS("always"),
    @SerialName("day") DAY("day"),
    @SerialName("night") NIGHT("night"),
    @SerialName("never") NEVER("never"),
    ;

    val displayName: String get() = if (this == ALWAYS) "Day & Night" else raw.replaceFirstChar { it.uppercase() }

    companion object {
        /** Sound/repeat options that make sense for an alarm's active period. */
        fun allowed(active: DayNightOption): List<DayNightOption> = when (active) {
            ALWAYS -> entries
            DAY -> listOf(DAY, NEVER)
            NIGHT -> listOf(NIGHT, NEVER)
            NEVER -> listOf(NEVER)
        }
    }
}

/** A sound chosen for an alarm: a bundled sound by name, or an imported one by id. */
@Serializable(with = SoundFileSerializer::class)
sealed class SoundFile {
    data class Builtin(val name: String) : SoundFile()
    data class Custom(val id: String) : SoundFile()

    val key: String get() = when (this) {
        is Builtin -> "builtin:$name"
        is Custom -> "custom:$id"
    }

    val displayName: String
        get() = when (this) {
            is Builtin -> name.replace("_", " ").replace("  ", " ")
            is Custom -> CustomSoundStore.displayName(id) ?: "Custom Sound"
        }

    companion object {
        val INDEED = Builtin("Indeed")
        val FALLBACK = INDEED
    }
}

/** Built-in sounds encode as a bare string, custom sounds as {kind, id}. */
object SoundFileSerializer : KSerializer<SoundFile> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SoundFile")

    override fun serialize(encoder: Encoder, value: SoundFile) {
        val json = encoder as JsonEncoder
        when (value) {
            is SoundFile.Builtin -> json.encodeJsonElement(JsonPrimitive(value.name))
            is SoundFile.Custom -> json.encodeJsonElement(buildJsonObject {
                put("kind", "custom")
                put("id", value.id)
            })
        }
    }

    override fun deserialize(decoder: Decoder): SoundFile {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        if (element is JsonPrimitive && element.isString) return SoundFile.Builtin(element.content)
        val obj = element as? JsonObject ?: return SoundFile.FALLBACK
        return when ((obj["kind"] as? JsonPrimitive)?.content) {
            "builtin" -> SoundFile.Builtin((obj["name"] as? JsonPrimitive)?.content ?: "Indeed")
            "custom" -> SoundFile.Custom((obj["id"] as? JsonPrimitive)?.content ?: "")
            else -> SoundFile.FALLBACK
        }
    }
}

@Serializable
data class Alarm(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val type: AlarmType,
    /** Name of the alarm, defaults to the alarm type. */
    val name: String = type.raw,
    val isEnabled: Boolean = true,
    /** End of a manual snooze, epoch seconds. */
    val snoozedUntil: Double? = null,
    val aboveBG: Double? = null,
    val belowBG: Double? = null,
    val threshold: Double? = null,
    val predictiveMinutes: Int? = null,
    val delta: Double? = null,
    val persistentMinutes: Int? = null,
    /** The low alarm stays silent while BG is rising. */
    val suppressIfRising: Boolean = false,
    /** The high alarm stays silent while BG is falling. */
    val suppressIfFalling: Boolean = false,
    /** The phone-battery alarm stays silent while the phone is charging. */
    val suppressIfCharging: Boolean = false,
    val monitoringWindow: Int? = null,
    val soundFile: SoundFile = SoundFile.INDEED,
    val snoozeDuration: Int = 5,
    val playSoundOption: DayNightOption = DayNightOption.ALWAYS,
    val repeatSoundOption: DayNightOption = DayNightOption.ALWAYS,
    /** Seconds between repeated sounds (0 = none). */
    val soundDelay: Int = 0,
    val activeOption: DayNightOption = DayNightOption.ALWAYS,
    val missedBolusPrebolusWindow: Int? = null,
    val missedBolusIgnoreSmallBolusUnits: Double? = null,
    val missedBolusIgnoreUnderGrams: Double? = null,
    val missedBolusIgnoreUnderBG: Double? = null,
    val bolusCountThreshold: Int? = null,
    val bolusWindowMinutes: Int? = null,
    /** CGM sensor lifetime in days. */
    val sensorLifetimeDays: Int? = null,
) {
    /** Extra notification text: the CGM state behind a gap in readings. */
    val notificationBody: String?
        get() {
            if (type != AlarmType.MISSED_READING && type != AlarmType.NOT_LOOPING) return null
            val state = Observable.cgmSensorState.value ?: return null
            return "CGM reported: ${state.summary}"
        }

    companion object {
        /** A new alarm of [type] with that type's defaults. */
        fun create(type: AlarmType): Alarm {
            val base = Alarm(type = type)
            fun s(name: String) = SoundFile.Builtin(name)
            return when (type) {
                AlarmType.BUILD_EXPIRE -> base.copy(threshold = 7.0, soundFile = s("Wrong_Answer"), snoozeDuration = 1)
                AlarmType.LOW -> base.copy(soundFile = s("Indeed"), belowBG = 80.0, persistentMinutes = 0, predictiveMinutes = 0)
                AlarmType.IOB -> base.copy(soundFile = s("Alert_Tone_Ringtone_1"), delta = 1.0, monitoringWindow = 2, predictiveMinutes = 30, threshold = 6.0)
                AlarmType.COB -> base.copy(soundFile = s("Alert_Tone_Ringtone_2"), threshold = 20.0)
                AlarmType.HIGH -> base.copy(soundFile = s("Time_Has_Come"), aboveBG = 180.0, persistentMinutes = 0)
                AlarmType.FAST_DROP -> base.copy(soundFile = s("Big_Clock_Ticking"), delta = 15.0, monitoringWindow = 3)
                AlarmType.FAST_RISE -> base.copy(soundFile = s("Cartoon_Fail_Strings_Trumpet"), delta = 10.0, monitoringWindow = 3)
                AlarmType.MISSED_READING -> base.copy(soundFile = s("Cartoon_Tip_Toe_Sneaky_Walk"), threshold = 16.0)
                AlarmType.NOT_LOOPING -> base.copy(soundFile = s("Sci-Fi_Engine_Shut_Down"), threshold = 16.0)
                AlarmType.MISSED_BOLUS -> base.copy(soundFile = s("Dhol_Shuffleloop"), monitoringWindow = 15, predictiveMinutes = 15, delta = 0.1, threshold = 4.0)
                AlarmType.FUTURE_CARBS -> base.copy(soundFile = s("Alert_Tone_Ringtone_1"), threshold = 45.0, delta = 5.0, snoozeDuration = 0, repeatSoundOption = DayNightOption.NEVER)
                AlarmType.SENSOR_CHANGE -> base.copy(soundFile = s("Wake_Up_Will_You"), threshold = 12.0, sensorLifetimeDays = 10)
                AlarmType.PUMP_CHANGE -> base.copy(soundFile = s("Wake_Up_Will_You"), threshold = 12.0)
                AlarmType.PUMP -> base.copy(soundFile = s("Marimba_Descend"), threshold = 20.0)
                AlarmType.PUMP_BATTERY -> base.copy(soundFile = s("Machine_Charge"), threshold = 20.0)
                AlarmType.BATTERY -> base.copy(soundFile = s("Machine_Charge"), threshold = 20.0)
                AlarmType.BATTERY_DROP -> base.copy(soundFile = s("Machine_Charge"), delta = 10.0, monitoringWindow = 15)
                AlarmType.REC_BOLUS -> base.copy(soundFile = s("Dhol_Shuffleloop"), threshold = 1.0)
                AlarmType.OVERRIDE_START -> base.copy(soundFile = s("Ending_Reached"), repeatSoundOption = DayNightOption.NEVER)
                AlarmType.OVERRIDE_END -> base.copy(soundFile = s("Alert_Tone_Busy"), repeatSoundOption = DayNightOption.NEVER)
                AlarmType.TEMP_TARGET_START -> base.copy(soundFile = s("Ending_Reached"), repeatSoundOption = DayNightOption.NEVER)
                AlarmType.TEMP_TARGET_END -> base.copy(soundFile = s("Alert_Tone_Busy"), repeatSoundOption = DayNightOption.NEVER)
                AlarmType.TEMPORARY -> base.copy(soundFile = s("Indeed"), snoozeDuration = 0, aboveBG = 180.0, belowBG = 70.0)
                // Nightscout's own dbsize plugin calls 75% urgent; the database fills over weeks.
                AlarmType.DB_SIZE -> base.copy(
                    soundFile = s("Wrong_Answer"), threshold = 75.0, snoozeDuration = 1,
                    activeOption = DayNightOption.DAY, playSoundOption = DayNightOption.DAY, repeatSoundOption = DayNightOption.DAY,
                )
            }
        }

        /** Sorts by type priority, then the per-type sort spec. */
        val byPriorityThenSpec = Comparator<Alarm> { l, r ->
            if (l.type.priority != r.type.priority) return@Comparator l.type.priority.compareTo(r.type.priority)
            val spec = l.type.sortSpec
            if (l.type == r.type && spec != null) {
                val lv = spec.second(l)
                val rv = spec.second(r)
                return@Comparator when (spec.first) {
                    AlarmType.SortDirection.ASCENDING -> (lv ?: Double.POSITIVE_INFINITY).compareTo(rv ?: Double.POSITIVE_INFINITY)
                    AlarmType.SortDirection.DESCENDING -> (rv ?: Double.NEGATIVE_INFINITY).compareTo(lv ?: Double.NEGATIVE_INFINITY)
                }
            }
            0
        }
    }
}

@Serializable
data class TimeOfDay(val hour: Int, val minute: Int) {
    val minutesSinceMidnight: Int get() = hour * 60 + minute
}

@Serializable
data class AlarmConfiguration(
    val snoozeUntil: Double? = null,
    val muteUntil: Double? = null,
    val dayStart: TimeOfDay = TimeOfDay(6, 0),
    val nightStart: TimeOfDay = TimeOfDay(22, 0),
    val overrideSystemOutputVolume: Boolean = true,
    /** 0 … 1 */
    val forcedOutputVolume: Float = 0.5f,
    val audioDuringCalls: Boolean = true,
    val ignoreZeroBG: Boolean = true,
    val autoSnoozeCGMStart: Boolean = false,
    val enableVolumeButtonSnooze: Boolean = false,
) {
    /** Night runs from [nightStart] up to the next [dayStart], wrapping past midnight when [nightStart] is later in the day. */
    fun isNight(now: Double): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = (now * 1000).toLong() }
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val day = dayStart.minutesSinceMidnight
        val night = nightStart.minutesSinceMidnight
        return if (night >= day) nowMin >= night || nowMin < day else nowMin >= night && nowMin < day
    }
}

/** A future-dated carb entry whose scheduled time has not yet arrived. */
@Serializable
data class PendingFutureCarb(val carbDate: Double, val grams: Double, val observedAt: Double)

data class GlucoseValue(val sgv: Int, val date: Double)

data class BolusEntry(val units: Double, val date: Double)

data class CarbSample(val grams: Double, val date: Double)

data class BatterySample(val batteryLevel: Double, val timestamp: Double)

data class AlarmData(
    val bgReadings: List<GlucoseValue>,
    val predictionData: List<GlucoseValue>,
    val expireDate: Double?,
    val lastLoopTime: Double?,
    val latestOverrideStart: Double?,
    val latestOverrideEnd: Double?,
    val latestTempTargetStart: Double?,
    val latestTempTargetEnd: Double?,
    /** Scheduled end of the active override/temp target, for the end alarms' early warning. */
    val activeOverrideEnd: Double?,
    val activeTempTargetEnd: Double?,
    val recBolus: Double?,
    val cob: Double?,
    val sageInsertTime: Double?,
    val pumpInsertTime: Double?,
    val latestPumpVolume: Double?,
    val iob: Double?,
    val recentBoluses: List<BolusEntry>,
    val latestBattery: Double?,
    val latestBatteryIsCharging: Boolean?,
    val latestPumpBattery: Double?,
    val batteryHistory: List<BatterySample>,
    val recentCarbs: List<CarbSample>,
    val dbSizePercentage: Double?,
)
