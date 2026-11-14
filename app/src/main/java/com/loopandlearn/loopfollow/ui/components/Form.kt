package com.loopandlearn.loopfollow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors

/** An inset-grouped list, like a SwiftUI Form. */
@Composable
fun FormList(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    val colors = LocalAppColors.current
    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.groupedBackground),
        content = {
            item { Spacer(Modifier.height(8.dp)) }
            content()
            item { Spacer(Modifier.height(32.dp)) }
        },
    )
}

/** One section of a form: header, rounded card of rows, footer. */
fun LazyListScope.formSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    item {
        FormSection(header, footer, content)
    }
}

@Composable
fun FormSection(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalAppColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (header != null) {
            Text(
                header.uppercase(),
                fontSize = 13.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
            )
        } else {
            Spacer(Modifier.height(20.dp))
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.cell),
            content = content,
        )
        if (footer != null) {
            Text(
                footer,
                fontSize = 13.sp,
                color = colors.secondaryText,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
    }
}

@Composable
fun FormDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = LocalAppColors.current.separator)
}

@Composable
fun FormRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun FormText(text: String, color: Color = LocalAppColors.current.primaryText, modifier: Modifier = Modifier, weight: FontWeight? = null) {
    Text(text, fontSize = 17.sp, color = color, modifier = modifier, fontWeight = weight)
}

@Composable
fun LabeledValueRow(label: String, value: String, valueColor: Color? = null) {
    FormRow {
        FormText(label)
        Spacer(Modifier.weight(1f))
        FormText(value, color = valueColor ?: LocalAppColors.current.secondaryText)
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    FormRow {
        FormText(label, modifier = Modifier.weight(1f), color = if (enabled) LocalAppColors.current.primaryText else LocalAppColors.current.gray)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = LocalAppColors.current.green,
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
fun NavigationRow(
    title: String,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    value: String? = null,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    FormRow(onClick = onClick) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint ?: colors.blue, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
        }
        FormText(title, modifier = Modifier.weight(1f))
        if (value != null) {
            FormText(value, color = colors.secondaryText)
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.gray.copy(alpha = 0.6f))
    }
}

@Composable
fun ButtonRow(title: String, color: Color = LocalAppColors.current.blue, enabled: Boolean = true, centered: Boolean = false, onClick: () -> Unit) {
    FormRow(onClick = if (enabled) onClick else null) {
        Text(
            title,
            fontSize = 17.sp,
            color = if (enabled) color else LocalAppColors.current.gray,
            modifier = Modifier.weight(1f),
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
    }
}

/** A plain single-line text field styled as a form row. */
@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secure: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    textAlign: TextAlign = TextAlign.Start,
) {
    val colors = LocalAppColors.current
    var revealed by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 17.sp, color = colors.gray, modifier = Modifier.fillMaxWidth(), textAlign = textAlign)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 17.sp, color = colors.primaryText, textAlign = textAlign),
                cursorBrush = SolidColor(colors.blue),
                visualTransformation = if (secure && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secure) KeyboardType.Password else keyboardType,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (secure) {
            Spacer(Modifier.width(8.dp))
            Icon(
                if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (revealed) "Hide" else "Show",
                tint = colors.gray,
                modifier = Modifier.size(22.dp).clickable { revealed = !revealed },
            )
        }
    }
}

@Composable
fun TextFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String? = null,
    secure: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    FormRow {
        if (label != null) {
            FormText(label)
            Spacer(Modifier.width(12.dp))
            FormTextField(value, onValueChange, placeholder, Modifier.weight(1f), secure, keyboardType, TextAlign.End)
        } else {
            FormTextField(value, onValueChange, placeholder, Modifier.weight(1f), secure, keyboardType)
        }
    }
}

/** Segmented control, like a SwiftUI segmented Picker. */
@Composable
fun <T> SegmentedPicker(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (colors.isDark) Color(0xFF2C2C2E) else Color(0xFFE3E3E8))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (option in options) {
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isSelected) (if (colors.isDark) Color(0xFF636366) else Color.White) else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, color = colors.primaryText)
            }
        }
    }
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}
