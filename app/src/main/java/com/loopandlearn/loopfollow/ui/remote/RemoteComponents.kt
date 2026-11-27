package com.loopandlearn.loopfollow.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import java.text.DecimalFormatSymbols
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * A number entry row with guardrails: the value only takes numbers within
 * [min]…[max]; leaving the field with a number outside them reports it and
 * resets the value to 0.
 */
@Composable
fun QuantityInputRow(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    unit: String,
    fractionDigits: Int,
    maxLength: Int,
    min: Double?,
    max: Double?,
    onValidationError: (String) -> Unit,
) {
    val colors = LocalAppColors.current
    val separator = DecimalFormatSymbols.getInstance().decimalSeparator
    fun format(v: Double) = Localizer.formatToLocalizedString(v, maxOf(fractionDigits, 3), fractionDigits)
    var text by remember { mutableStateOf(if (value == 0.0) "" else format(value)) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // The last value this field reported from typing; a value set from outside differs from it.
    var typedValue by remember { mutableStateOf<Double?>(null) }
    // ANDROID-DEVIATION: A value set from outside (quick pick, recommendation) replaces the text also while the
    // field is focused, so the number on screen is the number that would be sent. Typed text is never rewritten;
    // leaving the field checks it against the guardrails.
    LaunchedEffect(value) {
        if (focused && value == typedValue) return@LaunchedEffect
        val shown = text.replace(separator, '.').toDoubleOrNull() ?: 0.0
        if (!focused || shown != value) text = if (value == 0.0) "" else format(value)
    }
    fun within(v: Double) = (min == null || v >= min) && (max == null || v <= max)

    FormRow {
        FormText(label)
        Spacer(Modifier.weight(1f))
        BasicTextField(
            value = text,
            onValueChange = { new ->
                if (new.length > maxLength) return@BasicTextField
                text = new
                val sanitized = new.replace(separator, '.')
                if (sanitized.isEmpty()) {
                    typedValue = 0.0
                    onValueChange(0.0)
                } else {
                    sanitized.toDoubleOrNull()?.let {
                        if (within(it)) {
                            typedValue = it
                            onValueChange(it)
                        }
                    }
                }
            },
            singleLine = true,
            textStyle = TextStyle(fontSize = 17.sp, color = colors.primaryText, textAlign = TextAlign.End),
            cursorBrush = SolidColor(colors.blue),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (fractionDigits == 0) KeyboardType.Number else KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.width(110.dp).onFocusChanged { state ->
                if (state.isFocused && !focused && value == 0.0) text = ""
                if (focused && !state.isFocused) {
                    val sanitized = text.replace(separator, '.')
                    if (sanitized.isEmpty()) {
                        onValueChange(0.0)
                    } else {
                        val number = sanitized.toDoubleOrNull()
                        if (number == null) {
                            GuardrailCheck.failures++
                            onValidationError("Invalid number format")
                        } else if (within(number)) {
                            onValueChange(number)
                            text = format(number)
                        } else {
                            val step = 10.0.pow(-fractionDigits)
                            var message = "Value outside of guardrails: $text\n"
                            if (min != null) message += "Minimum: ${format(ceil(min / step) * step)}\n"
                            if (max != null) message += "Maximum: ${format(floor(max / step) * step)}"
                            GuardrailCheck.failures++
                            onValidationError(message)
                            onValueChange(0.0)
                            text = ""
                        }
                    }
                }
                focused = state.isFocused
            },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (text.isEmpty()) Text("0", fontSize = 17.sp, color = colors.gray, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    inner()
                }
            },
        )
        Spacer(Modifier.width(6.dp))
        FormText(unit, color = colors.secondaryText)
    }
}

/** Counts guardrail rejections, so an action can tell that leaving a field just rejected its value. */
object GuardrailCheck {
    var failures = 0
}

/**
 * Runs an action after the focused field has been left, unless leaving it made the guardrail check
 * reject the typed value; the user then sees that error.
 */
@Composable
fun rememberGuardedAction(): (() -> Unit) -> Unit {
    val focusManager = LocalFocusManager.current
    return { action ->
        val failuresBefore = GuardrailCheck.failures
        focusManager.clearFocus()
        if (GuardrailCheck.failures == failuresBefore) action()
    }
}

/** A prominent full-width button pinned under a form. */
@Composable
fun BottomActionButton(title: String, busyTitle: String, busy: Boolean, enabled: Boolean = true, color: Color? = null, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val guarded = rememberGuardedAction()
    Box(Modifier.fillMaxWidth().background(colors.groupedBackground).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Button(
            // ANDROID-DEVIATION: Leaving the field first applies its guardrail check to what was typed; when that
            // check rejects the value, the action stops so its error is what the user sees.
            onClick = { guarded(onClick) },
            enabled = enabled && !busy,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color ?: colors.blue, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Text(busyTitle, fontSize = 17.sp)
            } else {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Horizontal chips for quick picks. */
@Composable
fun QuickPickChips(items: List<Pair<String, () -> Unit>>) {
    val colors = LocalAppColors.current
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((label, action) in items) {
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).background(colors.blue.copy(alpha = 0.15f)).clickable(onClick = action).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                label.split("\n").forEachIndexed { i, line ->
                    Text(line, fontSize = if (i == 0) 15.sp else 11.sp, fontWeight = if (i == 0) FontWeight.Medium else FontWeight.Normal, color = colors.blue)
                }
            }
        }
    }
}

/** One alert at a time, like the iOS views' single `.alert`. */
data class RemoteAlert(
    val title: String,
    val message: String,
    val confirmTitle: String? = null,
    val onConfirm: (() -> Unit)? = null,
    val onDismiss: (() -> Unit)? = null,
    /** Shows the confirm button in red, for an action that changes dosing at once or removes something. */
    val destructive: Boolean = false,
)

@Composable
fun RemoteAlertDialog(alert: RemoteAlert?, onClose: () -> Unit) {
    alert ?: return
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(alert.title) },
        text = { Text(alert.message) },
        confirmButton = {
            TextButton(onClick = {
                onClose()
                (alert.onConfirm ?: alert.onDismiss)?.invoke()
            }) { Text(alert.confirmTitle ?: "OK", color = if (alert.destructive) LocalAppColors.current.red else Color.Unspecified) }
        },
        dismissButton = if (alert.onConfirm != null) {
            { TextButton(onClick = onClose) { Text("Cancel") } }
        } else {
            null
        },
    )
}
