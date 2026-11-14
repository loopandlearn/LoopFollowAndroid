package com.loopandlearn.loopfollow.ui.remote

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.engine.LoopCarbTreatment
import com.loopandlearn.loopfollow.engine.TrioMealTreatment
import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.remote.RemoteCommandMessage
import com.loopandlearn.loopfollow.remote.TrioRemote
import com.loopandlearn.loopfollow.remote.loop.LoopCommands
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.components.LabeledValueRow
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.components.TextFieldRow
import com.loopandlearn.loopfollow.ui.components.formSection
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun f1(value: Double) = String.format(Locale.US, "%.1f", value)

/** A date and time in the display time zone, in the given date and time styles. */
fun displayDateTime(seconds: Double, dateStyle: Int = DateFormat.SHORT, timeStyle: Int = DateFormat.SHORT): String =
    DateFormat.getDateTimeInstance(dateStyle, timeStyle, Locale.getDefault())
        .apply { timeZone = DateTimeUtils.displayTimeZone() }
        .format(Date((seconds * 1000).toLong()))

/** A date dialog followed by a time dialog, in the display time zone; the result is clamped to [range]. */
// ANDROID-DEVIATION: Android date and time dialogs replace the iOS inline DatePicker, with the same allowed range.
private fun pickDateTime(context: Context, seconds: Double, range: ClosedFloatingPointRange<Double>, onPicked: (Double) -> Unit) {
    val tz = DateTimeUtils.displayTimeZone()
    val start = Calendar.getInstance(tz).apply { timeInMillis = (seconds * 1000).toLong() }
    val dialog = DatePickerDialog(context, { _, year, month, day ->
        TimePickerDialog(context, { _, hour, minute ->
            val picked = Calendar.getInstance(tz).apply {
                timeInMillis = (seconds * 1000).toLong()
                set(year, month, day, hour, minute)
            }
            onPicked((picked.timeInMillis / 1000.0).coerceIn(range))
        }, start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), AndroidDateFormat.is24HourFormat(context)).show()
    }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH))
    dialog.datePicker.minDate = (range.start * 1000).toLong()
    dialog.datePicker.maxDate = (range.endInclusive * 1000).toLong()
    dialog.show()
}

@Composable
private fun DateTimeRow(label: String, seconds: Double, range: ClosedFloatingPointRange<Double>, onChange: (Double) -> Unit) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    FormRow(onClick = { pickDateTime(context, seconds, range, onChange) }) {
        FormText(label)
        Spacer(Modifier.weight(1f))
        Text(displayDateTime(seconds), fontSize = 17.sp, color = colors.blue)
    }
}

/** Read-only macro rows shared by the edit form and the treatment detail view. */
@Composable
fun TRCMealMacroRows(carbs: Double, fat: Int, protein: Int, date: Double) {
    LabeledValueRow("Carbs", if (carbs == Math.rint(carbs)) String.format(Locale.US, "%.0f g", carbs) else String.format(Locale.US, "%.1f g", carbs))
    if (fat > 0) {
        FormDivider()
        LabeledValueRow("Fat", "$fat g")
    }
    if (protein > 0) {
        FormDivider()
        LabeledValueRow("Protein", "$protein g")
    }
    FormDivider()
    LabeledValueRow("Time", displayDateTime(date, DateFormat.MEDIUM, DateFormat.SHORT))
}

/** Edit form for a meal already logged in Trio. Trio replaces the meal with these values. */
@Composable
fun TrioMealEditScreen(meal: TrioMealTreatment, onBack: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val mealWithFatProtein by Storage.mealWithFatProtein.flow.collectAsStateWithLifecycle()
    val maxCarbs by Storage.maxCarbs.flow.collectAsStateWithLifecycle()
    val maxFat by Storage.maxFat.flow.collectAsStateWithLifecycle()
    val maxProtein by Storage.maxProtein.flow.collectAsStateWithLifecycle()
    var carbs by remember { mutableStateOf(meal.carbsForEdit.toDouble()) }
    var fat by remember { mutableStateOf(meal.fat.toDouble()) }
    var protein by remember { mutableStateOf(meal.protein.toDouble()) }
    var mealDate by remember { mutableStateOf(meal.date) }
    var isSending by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<RemoteAlert?>(null) }

    val showFatProtein = mealWithFatProtein || meal.fat > 0 || meal.protein > 0
    val carbsValue = carbs.roundToInt()
    val fatValue = if (showFatProtein) fat.roundToInt() else 0
    val proteinValue = if (showFatProtein) protein.roundToInt() else 0
    val now = nowSeconds()
    val dateRange = (now - TrioMealTreatment.PAST_EDIT_WINDOW)..(now + TrioMealTreatment.FUTURE_EDIT_WINDOW)
    val validation: (String) -> Unit = { alert = RemoteAlert("Validation Error", it) }

    // Values already in the meal stay editable even when they exceed the guardrail maxima.
    fun ceiling(limit: Double, current: Double) = maxOf(limit, current)

    fun send() {
        isSending = true
        scope.launch {
            val error = TrioRemote.editMeal(meal.mealID, carbsValue, fatValue, proteinValue, mealDate)
            isSending = false
            alert = if (error == null) {
                // ANDROID-DEVIATION: Shows "Command Sent" and returns to the treatment list; Android cannot receive
                // Trio's confirmation push, so the edited meal shows once Trio uploads it to Nightscout.
                RemoteAlert("Command Sent", RemoteCommandMessage.SENT, onDismiss = onSent)
            } else {
                RemoteAlert("Not Sent", error)
            }
        }
    }

    NavScreen("Edit Meal", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                FormList {
                    formSection(header = "Current") {
                        TRCMealMacroRows(meal.carbs, meal.fat, meal.protein, meal.date)
                    }
                    formSection(header = "New values", footer = "Trio replaces the meal, including any fat and protein entries, with these values.") {
                        QuantityInputRow("Carbs", carbs, { carbs = it }, "g", 0, 4, 0.0, ceiling(maxCarbs, meal.carbs), validation)
                        if (showFatProtein) {
                            FormDivider()
                            QuantityInputRow("Fat", fat, { fat = it }, "g", 0, 4, 0.0, ceiling(maxFat, meal.fat.toDouble()), validation)
                            FormDivider()
                            QuantityInputRow("Protein", protein, { protein = it }, "g", 0, 4, 0.0, ceiling(maxProtein, meal.protein.toDouble()), validation)
                        }
                        FormDivider()
                        DateTimeRow("Meal time", mealDate, dateRange) { mealDate = it }
                    }
                }
            }
            BottomActionButton("Update Meal", "Sending...", isSending) {
                if (alert != null) return@BottomActionButton
                if (!(carbsValue > 0 || fatValue > 0 || proteinValue > 0)) {
                    alert = RemoteAlert("Validation Error", "Enter at least one of carbs, fat, or protein. Use Delete meal to remove the meal.")
                    return@BottomActionButton
                }
                val lines = buildList {
                    add("Carbs: $carbsValue g")
                    if (showFatProtein) {
                        add("Fat: $fatValue g")
                        add("Protein: $proteinValue g")
                    }
                    add("Time: ${displayDateTime(mealDate)}")
                }
                alert = RemoteAlert("Update meal in Trio?", lines.joinToString("\n"), confirmTitle = "Update", onConfirm = { send() })
            }
        }
    }
    RemoteAlertDialog(alert) { alert = null }
}

private const val LOOP_EDIT_MAX_PAST_HOURS = 12
private const val LOOP_EDIT_MIN_ABSORPTION_HOURS = 0.5
private const val LOOP_EDIT_MAX_ABSORPTION_HOURS = 8

/** Edit form for a carb entry already logged in Loop. Loop replaces the entry, keeping its sync identifier. */
@Composable
fun LoopCarbEditScreen(carb: LoopCarbTreatment, onBack: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val maxCarbs by Storage.maxCarbs.flow.collectAsStateWithLifecycle()
    val maxFutureHours by Storage.loopMaxFutureCarbHours.flow.collectAsStateWithLifecycle()
    val totalMinutes = remember { (carb.absorptionMinutes ?: 180.0).roundToInt() }
    var carbsAmount by remember { mutableStateOf(carb.carbs) }
    var absorptionHours by remember { mutableIntStateOf(totalMinutes / 60) }
    var absorptionMinutes by remember { mutableIntStateOf(if (totalMinutes % 60 >= 30) 30 else 0) }
    var foodType by remember { mutableStateOf(carb.foodType ?: "") }
    var consumedDate by remember { mutableStateOf(carb.date) }
    var isSending by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<RemoteAlert?>(null) }

    val absorptionTimeValue = absorptionHours + absorptionMinutes / 60.0
    val now = nowSeconds()
    val dateRange = minOf(now - LOOP_EDIT_MAX_PAST_HOURS * 3600.0, consumedDate)..(now + maxFutureHours * 3600.0)

    fun send() {
        isSending = true
        scope.launch {
            val error = LoopCommands.sendCarbEdit(carb, carbsAmount, absorptionTimeValue, foodType.ifEmpty { null }, consumedDate)
            isSending = false
            alert = if (error == null) {
                // ANDROID-DEVIATION: Shows "Command Sent" and returns to the treatment list; Android cannot receive
                // Loop's confirmation push, so the edited entry shows once Loop uploads it to Nightscout.
                RemoteAlert("Command Sent", RemoteCommandMessage.SENT, onDismiss = onSent)
            } else {
                RemoteAlert("Not Sent", error)
            }
        }
    }

    fun validateAndConfirm() {
        if (alert != null) return
        if (carbsAmount <= 0) {
            alert = RemoteAlert("Validation Error", "Enter a carb amount, or use Delete carbs to remove the entry.")
            return
        }
        if (absorptionTimeValue < LOOP_EDIT_MIN_ABSORPTION_HOURS || absorptionTimeValue > LOOP_EDIT_MAX_ABSORPTION_HOURS) {
            alert = RemoteAlert(
                "Validation Error",
                "Please enter a valid absorption time between ${f1(LOOP_EDIT_MIN_ABSORPTION_HOURS)} and $LOOP_EDIT_MAX_ABSORPTION_HOURS hours",
            )
            return
        }
        val lines = buildList {
            add(String.format(Locale.US, "Carbs: %.0f g", carbsAmount))
            add("Absorption: ${f1(absorptionTimeValue)} h")
            if (foodType.isNotEmpty()) add("Food type: $foodType")
            add("Time: ${displayDateTime(consumedDate)}")
        }
        alert = RemoteAlert("Update carbs in Loop?", lines.joinToString("\n"), confirmTitle = "Update", onConfirm = { send() })
    }

    NavScreen("Edit Carbs", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                FormList {
                    formSection(header = "Current") {
                        LoopCarbRows(carb)
                    }
                    formSection(header = "New values", footer = "Loop replaces the entry with these values and keeps its history.") {
                        QuantityInputRow("Carbs", carbsAmount, { carbsAmount = it }, "g", 0, 4, 1.0, maxCarbs) {
                            alert = RemoteAlert("Validation Error", it)
                        }
                        FormDivider()
                        FormRow {
                            FormText("Absorption")
                            Spacer(Modifier.weight(1f))
                            InlineMenu(absorptionHours, (0..LOOP_EDIT_MAX_ABSORPTION_HOURS).toList(), { "$it hr" }) { absorptionHours = it }
                            Spacer(Modifier.width(12.dp))
                            InlineMenu(absorptionMinutes, listOf(0, 30), { "$it min" }) { absorptionMinutes = it }
                        }
                        FormDivider()
                        TextFieldRow(foodType, { foodType = it }, "Food type (optional)")
                        FormDivider()
                        DateTimeRow("Time", consumedDate, dateRange) { consumedDate = it }
                    }
                }
            }
            BottomActionButton("Update Carbs", "Sending...", isSending) { validateAndConfirm() }
        }
    }
    RemoteAlertDialog(alert) { alert = null }
}

/** A compact menu picker for use inside a row. */
@Composable
private fun <T> InlineMenu(selected: T, options: List<T>, label: (T) -> String, onSelect: (T) -> Unit) {
    val colors = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clickable { expanded = true }, verticalAlignment = Alignment.CenterVertically) {
            Text(label(selected), fontSize = 17.sp, color = colors.blue)
            Icon(Icons.Filled.UnfoldMore, null, tint = colors.blue, modifier = Modifier.size(16.dp))
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

/** Read-only rows for a Loop carb entry, shared by the edit form and the treatment detail view. */
@Composable
fun LoopCarbRows(carb: LoopCarbTreatment) {
    LabeledValueRow("Carbs", String.format(Locale.US, "%.0f g", carb.carbs))
    carb.absorptionHours?.let {
        FormDivider()
        LabeledValueRow("Absorption", "${f1(it)} h")
    }
    carb.foodType?.let {
        FormDivider()
        LabeledValueRow("Food type", it)
    }
}
