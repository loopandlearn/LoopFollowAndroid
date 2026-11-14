package com.loopandlearn.loopfollow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors

/** An inline navigation bar: back button, centered title, trailing actions. */
@Composable
fun NavBar(
    title: String,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(colors.groupedBackground),
    ) {
        if (leading != null) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) { leading() }
        } else if (onBack != null) {
            Row(
                Modifier.align(Alignment.CenterStart).clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = colors.blue, modifier = Modifier.size(20.dp))
                Text(backLabel, color = colors.blue, fontSize = 17.sp)
            }
        }
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
        )
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A screen with a navigation bar above its content. */
@Composable
fun NavScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(LocalAppColors.current.groupedBackground)) {
        NavBar(title, onBack, leading = leading, actions = actions)
        Box(Modifier.fillMaxSize()) { content() }
    }
}
