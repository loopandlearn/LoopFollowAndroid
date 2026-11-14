package com.loopandlearn.loopfollow.alarm

import android.content.Context
import android.media.AudioManager
import com.loopandlearn.loopfollow.LoopFollowApplication
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import java.util.Calendar

object AlarmManager {
    private val evaluators: Map<AlarmType, AlarmCondition> = listOf(
        BuildExpireCondition(), LowBGCondition(), HighBGCondition(), FastDropCondition(), NotLoopingCondition(),
        OverrideStartCondition(), OverrideEndCondition(), TempTargetStartCondition(), TempTargetEndCondition(),
        RecBolusCondition(), COBCondition(), MissedReadingCondition(), FastRiseCondition(), TemporaryCondition(),
        SensorAgeCondition(), PumpChangeCondition(), PumpVolumeCondition(), PumpBatteryCondition(), IOBCondition(),
        BatteryCondition(), BatteryDropCondition(), FutureCarbsCondition(), MissedBolusCondition(), DBSizeCondition(),
    ).associateBy { it.type }

    private var lastBGAlarmTime: Double? = null

    fun checkAlarms(data: AlarmData) {
        val now = nowSeconds()
        var alarmTriggered = false

        // No alarm activity while onboarding is on screen; existing or QR-imported alarms stay quiet during setup.
        if (!Storage.hasCompletedOnboarding.value) {
            if (Observable.currentAlarm.value != null) stopAlarm()
            return
        }

        val config = Storage.alarmConfiguration.value

        // Honor "Snooze All": stop any current alarm and exit.
        val snoozeUntil = config.snoozeUntil
        if (snoozeUntil != null && snoozeUntil > now) {
            if (Observable.currentAlarm.value != null) stopAlarm()
            return
        }

        val sorted = Storage.alarms.value.sortedWith(Alarm.byPriorityThenSpec)
        var skipType: AlarmType? = null

        val latest = data.bgReadings.lastOrNull()
        val isLatestReadingRecent = latest != null && now - latest.date <= 5 * 60

        for (alarm in sorted) {
            if (alarm.type == skipType) continue

            // BG-based alarms need recent data, except missed reading, which fires without it.
            if (alarm.type.isBGBased && alarm.type != AlarmType.MISSED_READING && !isLatestReadingRecent) continue

            // A BG-based alarm handles each reading once.
            val lastHandled = lastBGAlarmTime
            if (alarm.type.isBGBased && lastHandled != null && latest != null && latest.date <= lastHandled) continue

            // A snoozed alarm also skips lower-priority alarms of the same type.
            val until = alarm.snoozedUntil
            if (until != null && until > now) {
                skipType = alarm.type
                continue
            }

            val checker = evaluators[alarm.type]
            if (checker == null || !checker.shouldFire(alarm, data, now, Storage.alarmConfiguration.value)) {
                if (Observable.currentAlarm.value == alarm.id) {
                    LogManager.log(LogManager.Category.ALARM, "Stopping alarm $alarm because it no longer meets its requirements", isDebug = true)
                    stopAlarm()
                }
                continue
            }

            val titleOverride = checker.firedTitle

            // An active alarm that still fulfills the requirements stays active; a new
            // phase of it is announced with its own title.
            if (Observable.currentAlarm.value == alarm.id) {
                if (titleOverride != Observable.currentAlarmTitleOverride.value) {
                    Observable.currentAlarmTitleOverride.value = titleOverride
                    trigger(alarm, Storage.alarmConfiguration.value, now, titleOverride)
                }
                break
            }

            // One alarm per evaluation tick.
            Observable.currentAlarmTitleOverride.value = titleOverride
            Observable.currentAlarm.value = alarm.id
            trigger(alarm, Storage.alarmConfiguration.value, now, titleOverride)

            if (alarm.type.isBGBased && latest != null) lastBGAlarmTime = latest.date

            if (alarm.type == AlarmType.TEMPORARY) {
                Storage.alarms.value = Storage.alarms.value.map {
                    if (it.id == alarm.id) it.copy(isEnabled = false, snoozedUntil = null) else it
                }
            }
            alarmTriggered = true
            break
        }

        if (isLatestReadingRecent && Storage.persistentNotification.value && !alarmTriggered &&
            latest.date > Storage.persistentNotificationLastBGTime.value
        ) {
            AlarmNotifier.send("Latest BG")
            Storage.persistentNotificationLastBGTime.value = now
        }
    }

    /** Shows the notification and plays the sound as the alarm's day/night options allow. */
    private fun trigger(alarm: Alarm, config: AlarmConfiguration, now: Double, titleOverride: String?) {
        LogManager.log(LogManager.Category.ALARM, "Alarm triggered: ${titleOverride ?: alarm.type.raw}")

        var playSound = true
        val mute = config.muteUntil
        if (mute != null && mute > now) playSound = false
        if (!config.audioDuringCalls && isOnPhoneCall()) playSound = false

        val isNight = isNight(config, now)
        val isDay = !isNight
        when (alarm.playSoundOption) {
            DayNightOption.NEVER -> playSound = false
            DayNightOption.DAY -> if (!isDay) playSound = false
            DayNightOption.NIGHT -> if (!isNight) playSound = false
            DayNightOption.ALWAYS -> {}
        }
        val shouldRepeat = when (alarm.repeatSoundOption) {
            DayNightOption.ALWAYS -> true
            DayNightOption.NEVER -> false
            DayNightOption.DAY -> isDay
            DayNightOption.NIGHT -> isNight
        }

        AlarmNotifier.send(
            titleOverride ?: alarm.type.raw,
            body = alarm.notificationBody,
            actionTitle = if (alarm.snoozeDuration == 0) "Acknowledge" else "Snooze",
            urgent = true,
        )

        if (playSound) {
            AlarmSound.setSoundFile(alarm.soundFile)
            AlarmSound.play(shouldRepeat, if (shouldRepeat) alarm.soundDelay else 0)
        }
    }

    /** Night runs from nightStart to dayStart, across midnight when nightStart is later. */
    fun isNight(config: AlarmConfiguration, now: Double): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = (now * 1000).toLong() }
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val day = config.dayStart.minutesSinceMidnight
        val night = config.nightStart.minutesSinceMidnight
        return if (night >= day) nowMin >= night || nowMin < day else nowMin >= night && nowMin < day
    }

    private fun isOnPhoneCall(): Boolean {
        val am = LoopFollowApplication.instance.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    fun performSnooze(snoozeUnits: Int? = null) {
        val alarmID = Observable.currentAlarm.value ?: return
        val alarms = Storage.alarms.value
        val alarm = alarms.firstOrNull { it.id == alarmID } ?: return
        val units = snoozeUnits ?: alarm.snoozeDuration
        if (units > 0) {
            val until = nowSeconds() + units * alarm.type.snoozeTimeUnit.seconds
            Storage.alarms.value = alarms.map { if (it.id == alarmID) it.copy(snoozedUntil = until) else it }
        }
        Observable.alarmSoundPlaying.value = false
        stopAlarm()
    }

    fun stopAlarm() {
        AlarmSound.stop()
        Observable.currentAlarm.value = null
        Observable.currentAlarmTitleOverride.value = null
        AlarmNotifier.cancel()
    }
}
