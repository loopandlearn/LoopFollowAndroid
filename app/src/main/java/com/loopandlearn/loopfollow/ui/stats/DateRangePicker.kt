package com.loopandlearn.loopfollow.ui.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.loopandlearn.loopfollow.engine.cFormat
import com.loopandlearn.loopfollow.engine.stats.DataAvailabilityInfo
import com.loopandlearn.loopfollow.engine.stats.DataQuality
import com.loopandlearn.loopfollow.engine.stats.StatsCalendar
import com.loopandlearn.loopfollow.engine.stats.StatsDateRange
import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.ui.theme.AppColors
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

private fun formatDate(seconds: Double, style: Int): String {
    val formatter = DateFormat.getDateInstance(style, Locale.getDefault())
    formatter.timeZone = DateTimeUtils.displayTimeZone()
    return formatter.format(Date((seconds * 1000).toLong()))
}

private fun statusIcon(quality: DataQuality): ImageVector = when (quality) {
    DataQuality.EXCELLENT -> Icons.Filled.CheckCircle
    DataQuality.GOOD -> Icons.Outlined.CheckCircle
    DataQuality.FAIR -> Icons.Outlined.WarningAmber
    DataQuality.POOR -> Icons.Outlined.Cancel
}

private fun statusColor(quality: DataQuality, colors: AppColors): Color = when (quality) {
    DataQuality.EXCELLENT -> colors.green
    DataQuality.GOOD -> colors.blue
    DataQuality.FAIR -> colors.orange
    DataQuality.POOR -> colors.red
}

/**
 * The collapsible period selector: start and end dates, quick ranges and the
 * data availability of the selected period. Dates are epoch seconds.
 */
@Composable
internal fun DateRangePicker(
    startDate: Double,
    endDate: Double,
    availability: DataAvailabilityInfo?,
    onDateChange: (start: Double, end: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    var isExpanded by remember { mutableStateOf(false) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    val dayCount = StatsCalendar.daysBetween(startDate, endDate) + 1
    val lastFullDay = StatsDateRange.lastComplete(1).second

    fun setDateRange(days: Int) {
        val (start, end) = StatsDateRange.lastComplete(days)
        showStartDatePicker = false
        showEndDatePicker = false
        onDateChange(start, end)
    }

    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.secondary(0.05f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.secondary(0.1f))
                .clickable {
                    isExpanded = !isExpanded
                    showStartDatePicker = false
                    showEndDatePicker = false
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.CalendarToday, null, tint = colors.blue, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${formatDate(startDate, DateFormat.SHORT)} - ${formatDate(endDate, DateFormat.SHORT)}",
                    fontSize = StatsFont.subheadline,
                    color = colors.primaryText,
                )
                Text("($dayCount days)", fontSize = StatsFont.caption2, color = colors.secondaryText)
            }
            if (availability != null) {
                val color = statusColor(availability.dataQuality, colors)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(statusIcon(availability.dataQuality), null, tint = color, modifier = Modifier.size(14.dp))
                    Text(cFormat(availability.coveragePercentage, 0) + "%", fontSize = StatsFont.caption, fontWeight = FontWeight.Medium, color = color)
                }
            }
            Icon(
                if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                null,
                tint = colors.secondaryText,
                modifier = Modifier.size(16.dp),
            )
        }

        AnimatedVisibility(isExpanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.secondary(0.05f))
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    DateField("Start Date", startDate) {
                        showStartDatePicker = !showStartDatePicker
                        showEndDatePicker = false
                    }
                    Spacer(Modifier.weight(1f))
                    DateField("End Date", endDate) {
                        showEndDatePicker = !showEndDatePicker
                        showStartDatePicker = false
                    }
                }

                if (showStartDatePicker) {
                    InlineDatePicker(selected = startDate, minDate = null, maxDate = StatsCalendar.localDate(endDate)) { date ->
                        showStartDatePicker = false
                        onDateChange(StatsCalendar.startOfDay(date), endDate)
                    }
                }

                if (showEndDatePicker) {
                    InlineDatePicker(
                        selected = endDate,
                        minDate = StatsCalendar.localDate(startDate),
                        maxDate = StatsCalendar.localDate(lastFullDay),
                    ) { date ->
                        showEndDatePicker = false
                        onDateChange(startDate, StatsCalendar.startOfDay(date.plusDays(1)) - 1)
                    }
                }

                HorizontalDivider(color = colors.separator)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (days in listOf(7, 14, 30, 90)) {
                        QuickSelectButton("${days}d") { setDateRange(days) }
                    }
                }

                if (availability != null) {
                    AvailabilityDetails(availability)
                }
            }
        }
    }
}

@Composable
private fun DateField(title: String, date: Double, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = StatsFont.caption, color = colors.secondaryText)
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colors.secondary(0.15f))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CalendarToday, null, tint = colors.blue, modifier = Modifier.size(12.dp))
            Text(formatDate(date, DateFormat.MEDIUM), fontSize = StatsFont.subheadline, color = colors.primaryText)
        }
    }
}

@Composable
private fun QuickSelectButton(title: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Text(
        title,
        fontSize = StatsFont.caption,
        fontWeight = FontWeight.Medium,
        color = colors.blue,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.blue.opacity(0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun AvailabilityDetails(availability: DataAvailabilityInfo) {
    val colors = LocalAppColors.current
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Data Availability", fontSize = StatsFont.subheadline, fontWeight = FontWeight.Medium, color = colors.primaryText)
            Spacer(Modifier.weight(1f))
            Text(
                cFormat(availability.coveragePercentage, 1) + "%",
                fontSize = StatsFont.subheadline,
                fontWeight = FontWeight.SemiBold,
                color = statusColor(availability.dataQuality, colors),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                "${availability.actualReadings} of ${availability.totalExpectedReadings} expected readings",
                fontSize = StatsFont.caption,
                color = colors.secondaryText,
            )
            Spacer(Modifier.weight(1f))
            if (availability.missingIntervals > 0) {
                Text("${availability.missingIntervals} gaps", fontSize = StatsFont.caption, color = colors.orange)
            }
        }
        Text("Expected: 1 CGM reading every 5 minutes", fontSize = StatsFont.caption2, color = colors.secondaryText)
    }
}

/** A calendar for one day; days outside [minDate]…[maxDate] are disabled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InlineDatePicker(selected: Double, minDate: LocalDate?, maxDate: LocalDate?, onPick: (LocalDate) -> Unit) {
    val selectedDate = StatsCalendar.localDate(selected)
    key(selectedDate, minDate, maxDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return (minDate == null || !date.isBefore(minDate)) && (maxDate == null || !date.isAfter(maxDate))
                }

                override fun isSelectableYear(year: Int): Boolean =
                    (minDate == null || year >= minDate.year) && (maxDate == null || year <= maxDate.year)
            },
        )
        LaunchedEffect(state.selectedDateMillis) {
            val millis = state.selectedDateMillis ?: return@LaunchedEffect
            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
            if (date != selectedDate) onPick(date)
        }
        DatePicker(
            state = state,
            title = null,
            headline = null,
            showModeToggle = false,
            colors = DatePickerDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
