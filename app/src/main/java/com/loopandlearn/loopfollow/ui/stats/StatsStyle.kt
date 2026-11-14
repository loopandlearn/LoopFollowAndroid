package com.loopandlearn.loopfollow.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.theme.AppColors
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors

internal object StatsFont {
    val largeTitle = 34.sp
    val title2 = 22.sp
    val headline = 17.sp
    val subheadline = 15.sp
    val caption = 12.sp
    val caption2 = 11.sp
}

internal val AppColors.systemGray6: Color get() = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
internal val AppColors.indigo: Color get() = if (isDark) Color(0xFF5E5CE6) else Color(0xFF5856D6)
internal val AppColors.pink: Color get() = if (isDark) Color(0xFFFF375F) else Color(0xFFFF2D55)

/** The secondary label color with its own alpha scaled by [opacity]. */
internal fun AppColors.secondary(opacity: Float): Color = secondaryText.copy(alpha = secondaryText.alpha * opacity)

internal fun Color.opacity(value: Float): Color = copy(alpha = alpha * value)

/**
 * Reads the settings that change how the statistics are formatted, so the
 * calling composable recomposes when any of them changes.
 */
@Composable
internal fun observeDisplaySettings(): List<Any> {
    val units by Storage.units.flow.collectAsStateWithLifecycle()
    val ifcc by Storage.useIFCC.flow.collectAsStateWithLifecycle()
    val range by Storage.timeInRangeModeRaw.flow.collectAsStateWithLifecycle()
    val low by Storage.lowLine.flow.collectAsStateWithLifecycle()
    val high by Storage.highLine.flow.collectAsStateWithLifecycle()
    return listOf(units, ifcc, range, low, high)
}

/** A rounded gray card, optionally with the up/down chevron that marks it as tappable. */
@Composable
internal fun StatsCard(
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.systemGray6),
    ) {
        Box(Modifier.fillMaxWidth().padding(16.dp), content = content)
        if (interactive) {
            Icon(
                Icons.Filled.UnfoldMore,
                contentDescription = null,
                tint = colors.secondary(0.5f),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(12.dp),
            )
        }
    }
}

/** Calls [onTripleTap] after three taps in quick succession. */
internal fun Modifier.tripleTap(onTripleTap: () -> Unit): Modifier = pointerInput(Unit) {
    var count = 0
    var lastTap = 0L
    detectTapGestures {
        val now = System.currentTimeMillis()
        count = if (now - lastTap <= viewConfiguration.doubleTapTimeoutMillis) count + 1 else 1
        lastTap = now
        if (count == 3) {
            count = 0
            onTripleTap()
        }
    }
}
