package com.loopandlearn.loopfollow.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.engine.BannerManager
import com.loopandlearn.loopfollow.engine.BannerSeverity
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors

/** Dismissable banner shown at the top of the app, above the tab content. */
@Composable
fun BannerView() {
    val message by BannerManager.activeBanner.flow.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    AnimatedVisibility(visible = message != null) {
        val m = message ?: return@AnimatedVisibility
        val tint = when (m.severity) {
            BannerSeverity.INFO -> colors.blue
            BannerSeverity.WARNING -> colors.orange
            BannerSeverity.ERROR -> colors.red
        }
        val icon = when (m.severity) {
            BannerSeverity.INFO -> Icons.Filled.Info
            BannerSeverity.WARNING -> Icons.Filled.Warning
            BannerSeverity.ERROR -> Icons.Filled.Report
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .background(tint.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(m.text, fontSize = 16.sp, color = colors.primaryText, maxLines = 3, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.Cancel,
                contentDescription = "Dismiss",
                tint = colors.gray,
                modifier = Modifier.size(24.dp).clickableNoRipple { BannerManager.dismissCurrent() },
            )
        }
    }
}
