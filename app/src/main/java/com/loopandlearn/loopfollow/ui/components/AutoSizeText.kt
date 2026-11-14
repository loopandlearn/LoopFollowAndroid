package com.loopandlearn.loopfollow.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit

/**
 * Single-line text that shrinks to fit its width, down to [minScale] of
 * [maxFontSize], like SwiftUI's minimumScaleFactor.
 */
@Composable
fun AutoSizeText(
    text: String,
    maxFontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign = TextAlign.Center,
    textDecoration: TextDecoration? = null,
    minScale: Float = 0.5f,
) {
    var scale by remember(text, maxFontSize) { mutableFloatStateOf(1f) }
    var ready by remember(text, maxFontSize) { mutableFloatStateOf(0f) }
    Text(
        text,
        fontSize = maxFontSize * scale,
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        textDecoration = textDecoration,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.drawWithContent { if (ready > 0f) drawContent() },
        onTextLayout = { result ->
            if (result.didOverflowWidth && scale > minScale) {
                scale = maxOf(minScale, scale * 0.9f)
            } else {
                ready = 1f
            }
        },
    )
}
