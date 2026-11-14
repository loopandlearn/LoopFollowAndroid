package com.loopandlearn.loopfollow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.storage.GlucoseDisplayUnit
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/** A −/+ stepper, like a SwiftUI Stepper. */
@Composable
fun StepperButtons(canDecrement: Boolean, canIncrement: Boolean, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    val colors = LocalAppColors.current
    val bg = if (colors.isDark) Color(0xFF2C2C2E) else Color(0xFFE9E9EB)
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(46.dp).height(32.dp).clickable(enabled = canDecrement, onClick = onDecrement), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Remove, "Decrement", tint = if (canDecrement) colors.primaryText else colors.gray.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }
        VerticalDivider(Modifier.height(18.dp), color = colors.separator)
        Box(Modifier.width(46.dp).height(32.dp).clickable(enabled = canIncrement, onClick = onIncrement), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, "Increment", tint = if (canIncrement) colors.primaryText else colors.gray.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }
    }
}

/** A labeled row with its value and a stepper, for a Double. */
@Composable
fun StepperRow(title: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double, valueText: String, onChange: (Double) -> Unit) {
    val colors = LocalAppColors.current
    // Like a SwiftUI Stepper: adds or subtracts one step and clamps to the range; rounding only drops float noise.
    fun clean(v: Double) = round(v * 1e9) / 1e9
    FormRow {
        FormText(title)
        Spacer(Modifier.weight(1f))
        FormText(valueText, color = colors.secondaryText)
        Spacer(Modifier.width(10.dp))
        StepperButtons(
            canDecrement = value > range.start + 1e-9,
            canIncrement = value < range.endInclusive - 1e-9,
            onDecrement = { onChange(clean(value - step).coerceIn(range.start, range.endInclusive)) },
            onIncrement = { onChange(clean(value + step).coerceIn(range.start, range.endInclusive)) },
        )
    }
}

/** A labeled row with a menu of options, like a SwiftUI menu Picker. */
@Composable
fun <T> MenuPickerRow(title: String, selected: T, options: List<T>, label: (T) -> String, onSelect: (T) -> Unit) {
    val colors = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }
    FormRow(onClick = { expanded = true }) {
        FormText(title)
        Spacer(Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label(selected), fontSize = 17.sp, color = colors.secondaryText)
                Icon(Icons.Filled.UnfoldMore, null, tint = colors.secondaryText, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                for (option in options) {
                    DropdownMenuItem(
                        text = { Text(label(option) + if (option == selected) "  ✓" else "") },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

/** Glucose values offered in a range: every mg/dL, or every 0.1 mmol/L. */
fun bgPickerValues(range: ClosedFloatingPointRange<Double>): List<Double> {
    return if (UnitSettingsStore.glucoseUnit == GlucoseDisplayUnit.MMOLL) {
        val step = 0.1
        val lower = ceil((range.start / 18) / step) * step
        val upper = floor((range.endInclusive / 18) / step) * step
        val result = mutableListOf<Double>()
        var v = lower
        while (v <= upper + 1e-9) {
            result.add(v * 18)
            v += step
        }
        result
    } else {
        (range.start.toInt()..range.endInclusive.toInt()).map { it.toDouble() }
    }
}

/** Picks a glucose value (stored in mg/dL), shown in the preferred unit. */
@Composable
fun BGPickerRow(title: String, range: ClosedFloatingPointRange<Double>, value: Double, onChange: (Double) -> Unit) {
    val values = remember(range, UnitSettingsStore.glucoseUnit) { bgPickerValues(range) }
    val snapped = values.minByOrNull { abs(it - value) } ?: value
    val unit = UnitSettingsStore.glucoseUnit.raw
    MenuPickerRow(title, snapped, values, { "${Localizer.formatQuantity(it)} $unit" }, onChange)
}

/** An informational banner at the top of an editor. */
@Composable
fun InfoBanner(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.blue.copy(alpha = 0.2f))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = colors.blue, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 16.sp, color = colors.primaryText)
    }
}
