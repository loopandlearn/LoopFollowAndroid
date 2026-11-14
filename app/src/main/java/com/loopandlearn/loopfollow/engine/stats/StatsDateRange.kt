package com.loopandlearn.loopfollow.engine.stats

import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.nowSeconds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.floor

/** Calendar arithmetic on epoch seconds in the display time zone. */
object StatsCalendar {
    private fun zone(): ZoneId = DateTimeUtils.displayTimeZone().toZoneId()

    private fun zoned(t: Double): ZonedDateTime =
        Instant.ofEpochMilli(floor(t * 1000).toLong()).atZone(zone())

    private fun seconds(z: ZonedDateTime): Double = z.toInstant().toEpochMilli() / 1000.0

    fun localDate(t: Double): LocalDate = zoned(t).toLocalDate()

    fun startOfDay(date: LocalDate): Double = seconds(date.atStartOfDay(zone()))

    fun startOfDay(t: Double): Double = startOfDay(localDate(t))

    fun addDays(t: Double, days: Long): Double = seconds(zoned(t).plusDays(days))

    fun hour(t: Double): Int = zoned(t).hour

    fun daysBetween(from: Double, to: Double): Long = ChronoUnit.DAYS.between(localDate(from), localDate(to))
}

object StatsDateRange {
    /**
     * Start/end of the last complete N-day period: the end is 23:59:59 yesterday,
     * the start 00:00:00 of the day N days back.
     */
    fun lastComplete(days: Int): Pair<Double, Double> {
        val startOfToday = StatsCalendar.startOfDay(nowSeconds())
        val end = startOfToday - 1
        val endDayStart = StatsCalendar.startOfDay(end)
        val start = StatsCalendar.addDays(endDayStart, -(days - 1).toLong())
        return StatsCalendar.startOfDay(start) to end
    }
}
