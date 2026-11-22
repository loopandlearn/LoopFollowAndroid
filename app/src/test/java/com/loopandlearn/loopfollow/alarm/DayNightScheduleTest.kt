package com.loopandlearn.loopfollow.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DayNightScheduleTest {
    private fun config(day: Pair<Int, Int>, night: Pair<Int, Int>) =
        AlarmConfiguration(dayStart = TimeOfDay(day.first, day.second), nightStart = TimeOfDay(night.first, night.second))

    private fun at(hour: Int, minute: Int): Double {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis / 1000.0
    }

    private fun lowAlarm(active: DayNightOption) =
        Alarm(type = AlarmType.LOW, belowBG = 80.0, persistentMinutes = 0, activeOption = active)

    private fun lowData(now: Double) = AlarmData(
        bgReadings = listOf(60, 60, 60).mapIndexed { i, v -> GlucoseValue(v, now - (2 - i) * 300.0) },
        predictionData = emptyList(),
        expireDate = null,
        lastLoopTime = null,
        latestOverrideStart = null,
        latestOverrideEnd = null,
        latestTempTargetStart = null,
        latestTempTargetEnd = null,
        activeOverrideEnd = null,
        activeTempTargetEnd = null,
        recBolus = null,
        cob = null,
        sageInsertTime = null,
        pumpInsertTime = null,
        latestPumpVolume = null,
        iob = null,
        recentBoluses = emptyList(),
        latestBattery = null,
        latestBatteryIsCharging = null,
        latestPumpBattery = null,
        batteryHistory = emptyList(),
        recentCarbs = emptyList(),
        dbSizePercentage = null,
    )

    @Test fun eveningNightStartWrapsPastMidnight() {
        val c = config(7 to 0, 22 to 0)
        assertFalse(c.isNight(at(7, 0)))
        assertFalse(c.isNight(at(12, 0)))
        assertFalse(c.isNight(at(21, 59)))
        assertTrue(c.isNight(at(22, 0)))
        assertTrue(c.isNight(at(0, 0)))
        assertTrue(c.isNight(at(6, 59)))
    }

    @Test fun midnightNightStartIsNightOnlyUntilDayStart() {
        val c = config(7 to 0, 0 to 0)
        assertTrue(c.isNight(at(0, 0)))
        assertTrue(c.isNight(at(3, 30)))
        assertTrue(c.isNight(at(6, 59)))
        assertFalse(c.isNight(at(7, 0)))
        assertFalse(c.isNight(at(12, 0)))
        assertFalse(c.isNight(at(23, 59)))
    }

    @Test fun nightStartAfterMidnightBeforeDayStart() {
        val c = config(7 to 0, 1 to 30)
        assertFalse(c.isNight(at(0, 45)))
        assertTrue(c.isNight(at(1, 30)))
        assertTrue(c.isNight(at(6, 59)))
        assertFalse(c.isNight(at(7, 0)))
    }

    @Test fun dayAlarmFiresDuringTheDayWithMidnightNightStart() {
        val c = config(7 to 0, 0 to 0)
        val now = at(12, 0)
        assertTrue(LowBGCondition().shouldFire(lowAlarm(DayNightOption.DAY), lowData(now), now, c))
    }

    @Test fun dayAlarmIsHeldBackAtNightWithMidnightNightStart() {
        val c = config(7 to 0, 0 to 0)
        val now = at(3, 0)
        assertFalse(LowBGCondition().shouldFire(lowAlarm(DayNightOption.DAY), lowData(now), now, c))
    }

    @Test fun nightAlarmFollowsTheSameSchedule() {
        val c = config(7 to 0, 0 to 0)
        val night = at(3, 0)
        val day = at(12, 0)
        assertTrue(LowBGCondition().shouldFire(lowAlarm(DayNightOption.NIGHT), lowData(night), night, c))
        assertFalse(LowBGCondition().shouldFire(lowAlarm(DayNightOption.NIGHT), lowData(day), day, c))
    }
}
