package com.loopandlearn.loopfollow.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.storage.AppearanceMode
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.storage.UiColor

/** The iOS system palette, so colors read the same on both platforms. */
@Immutable
data class AppColors(
    val isDark: Boolean,
    val red: Color,
    val yellow: Color,
    val green: Color,
    val purple: Color,
    val gray: Color,
    val orange: Color,
    val blue: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val background: Color,
    val groupedBackground: Color,
    val cell: Color,
    val separator: Color,
) {
    fun resolve(c: UiColor): Color = when (c) {
        UiColor.PRIMARY -> primaryText
        UiColor.RED -> red
        UiColor.YELLOW -> yellow
        UiColor.GREEN -> green
        UiColor.PURPLE -> purple
        UiColor.GRAY -> gray
        UiColor.ORANGE -> orange
    }
}

val DarkAppColors = AppColors(
    isDark = true,
    red = Color(0xFFFF453A),
    yellow = Color(0xFFFFD60A),
    green = Color(0xFF30D158),
    purple = Color(0xFFBF5AF2),
    gray = Color(0xFF8E8E93),
    orange = Color(0xFFFF9F0A),
    blue = Color(0xFF0A84FF),
    primaryText = Color.White,
    secondaryText = Color(0x99EBEBF5),
    background = Color.Black,
    groupedBackground = Color.Black,
    cell = Color(0xFF1C1C1E),
    separator = Color(0x5C545458),
)

val LightAppColors = AppColors(
    isDark = false,
    red = Color(0xFFFF3B30),
    yellow = Color(0xFFFFCC00),
    green = Color(0xFF34C759),
    purple = Color(0xFFAF52DE),
    gray = Color(0xFF8E8E93),
    orange = Color(0xFFFF9500),
    blue = Color(0xFF007AFF),
    primaryText = Color.Black,
    secondaryText = Color(0x993C3C43),
    background = Color.White,
    groupedBackground = Color(0xFFF2F2F7),
    cell = Color.White,
    separator = Color(0x4A3C3C43),
)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun LoopFollowTheme(content: @Composable () -> Unit) {
    val mode by Storage.appearanceMode.flow.collectAsStateWithLifecycle()
    val dark = when (mode) {
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
    }
    val colors = if (dark) DarkAppColors else LightAppColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.blue,
            background = colors.background,
            surface = colors.background,
            surfaceContainer = colors.cell,
            onBackground = colors.primaryText,
            onSurface = colors.primaryText,
        )
    } else {
        lightColorScheme(
            primary = colors.blue,
            background = colors.background,
            surface = colors.background,
            surfaceContainer = colors.groupedBackground,
            onBackground = colors.primaryText,
            onSurface = colors.primaryText,
        )
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = naturalLineHeights) {
            CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified), content = content)
        }
    }
}

/** Text uses the font's own line height, like iOS. */
private val naturalLineHeights: Typography = Typography().let { t ->
    fun androidx.compose.ui.text.TextStyle.natural() = copy(lineHeight = TextUnit.Unspecified)
    t.copy(
        displayLarge = t.displayLarge.natural(), displayMedium = t.displayMedium.natural(), displaySmall = t.displaySmall.natural(),
        headlineLarge = t.headlineLarge.natural(), headlineMedium = t.headlineMedium.natural(), headlineSmall = t.headlineSmall.natural(),
        titleLarge = t.titleLarge.natural(), titleMedium = t.titleMedium.natural(), titleSmall = t.titleSmall.natural(),
        bodyLarge = t.bodyLarge.natural(), bodyMedium = t.bodyMedium.natural(), bodySmall = t.bodySmall.natural(),
        labelLarge = t.labelLarge.natural(), labelMedium = t.labelMedium.natural(), labelSmall = t.labelSmall.natural(),
    )
}
