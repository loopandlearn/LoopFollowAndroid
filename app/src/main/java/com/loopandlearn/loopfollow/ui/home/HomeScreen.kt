package com.loopandlearn.loopfollow.ui.home

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.info.InfoData
import com.loopandlearn.loopfollow.info.InfoColorLevel
import com.loopandlearn.loopfollow.info.item
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.charts.BGChart
import com.loopandlearn.loopfollow.ui.charts.ChartConfig
import com.loopandlearn.loopfollow.ui.components.AutoSizeText
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onSetupNightscout: () -> Unit, onSetupDexcom: () -> Unit, onStatsTap: () -> Unit) {
    val url by Storage.url.flow.collectAsStateWithLifecycle()
    val shareUser by Storage.shareUserName.flow.collectAsStateWithLifecycle()
    val sharePassword by Storage.sharePassword.flow.collectAsStateWithLifecycle()
    val loading by Observable.isInitialLoading.flow.collectAsStateWithLifecycle()
    val configured = url.isNotEmpty() || (shareUser.isNotEmpty() && sharePassword.isNotEmpty())

    Box(Modifier.fillMaxSize().background(LocalAppColors.current.background)) {
        when {
            !configured -> FirstTimeSetup(onSetupNightscout, onSetupDexcom)
            loading -> LoadingOverlay()
            else -> MainHomeView(onStatsTap)
        }
    }
}

@Composable
private fun FirstTimeSetup(onSetupNightscout: () -> Unit, onSetupDexcom: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SetupButton("Setup Nightscout", colors.blue, onSetupNightscout)
        Spacer(Modifier.height(10.dp))
        SetupButton("Setup Dexcom Share", colors.green, onSetupDexcom)
    }
}

@Composable
private fun SetupButton(title: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        modifier = Modifier.size(200.dp, 50.dp).shadow(4.dp, RoundedCornerShape(12.dp)),
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LoadingOverlay() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = LocalAppColors.current.gray)
        Spacer(Modifier.height(16.dp))
        Text("Loading...", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = LocalAppColors.current.secondaryText)
    }
}

@Composable
fun MainHomeView(onStatsTap: () -> Unit) {
    val showSmallGraph by Storage.showSmallGraph.flow.collectAsStateWithLifecycle()
    val smallGraphHeight by Storage.smallGraphHeight.flow.collectAsStateWithLifecycle()
    val hideInfoTable by Storage.hideInfoTable.flow.collectAsStateWithLifecycle()
    val showStats by Storage.showStats.flow.collectAsStateWithLifecycle()
    val url by Storage.url.flow.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (url.isNotEmpty() && !hideInfoTable) {
            BGAndInfoRow()
        } else {
            Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) { BGDisplay(Modifier.fillMaxWidth()) }
        }

        BGChart(ChartConfig.MAIN, Modifier.fillMaxWidth().weight(1f))

        if (showSmallGraph) {
            BGChart(ChartConfig.SMALL, Modifier.fillMaxWidth().height(smallGraphHeight.dp))
        }

        if (showStats) {
            StatsView(onStatsTap)
        }
    }
}

/**
 * The BG block sets the row's height; the info table gets that height and scrolls when it has more rows.
 *
 * ANDROID-DEVIATION: the BG block takes at least 55% of the width and the table the rest, up to 250 dp,
 * so the BG reading stays large on narrow phones and with larger text.
 */
@Composable
private fun BGAndInfoRow() {
    Layout(
        content = {
            BGDisplay()
            InfoTable()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gap = 10.dp.roundToPx()
        val available = (constraints.maxWidth - gap).coerceAtLeast(0)
        val tableWidth = minOf(250.dp.roundToPx(), (available * 0.45f).toInt())
        val bgWidth = (constraints.maxWidth - gap - tableWidth).coerceAtLeast(0)
        val bgHeight = measurables[0].minIntrinsicHeight(bgWidth)
        val bg = measurables[0].measure(Constraints.fixed(bgWidth, bgHeight))
        val table = measurables[1].measure(Constraints(minWidth = tableWidth, maxWidth = tableWidth, minHeight = 0, maxHeight = bg.height))
        layout(constraints.maxWidth, bg.height) {
            bg.place(0, 0)
            table.place(bgWidth + gap, 0)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BGDisplay(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val serverText by Observable.serverText.flow.collectAsStateWithLifecycle()
    val bgText by Observable.bgText.flow.collectAsStateWithLifecycle()
    val bgTextColor by Observable.bgTextColor.flow.collectAsStateWithLifecycle()
    val bgStale by Observable.bgStale.flow.collectAsStateWithLifecycle()
    val directionText by Observable.directionText.flow.collectAsStateWithLifecycle()
    val deltaText by Observable.deltaText.flow.collectAsStateWithLifecycle()
    val minAgoText by Observable.minAgoText.flow.collectAsStateWithLifecycle()
    val loopStatusText by Observable.loopStatusText.flow.collectAsStateWithLifecycle()
    val loopStatusColor by Observable.loopStatusColor.flow.collectAsStateWithLifecycle()
    val predictionText by Observable.predictionText.flow.collectAsStateWithLifecycle()
    val predictionColor by Observable.predictionColor.flow.collectAsStateWithLifecycle()
    val isNotLooping by Observable.isNotLooping.flow.collectAsStateWithLifecycle()
    val sensorState by Observable.cgmSensorState.flow.collectAsStateWithLifecycle()
    val snapshot by com.loopandlearn.loopfollow.engine.RetroSelection.snapshot.flow.collectAsStateWithLifecycle()
    val isStale = snapshot == null && bgStale

    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            MainEngine.refresh()
            scope.launch {
                delay(800)
                refreshing = false
            }
        },
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxWidth().then(if (snapshot != null) Modifier.retroCard() else Modifier).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(serverText, fontSize = 13.sp, color = colors.primaryText)

            val snap = snapshot
            AutoSizeText(
                snap?.bgText ?: bgText,
                maxFontSize = 85.sp,
                fontWeight = FontWeight.Black,
                color = colors.resolve(snap?.bgColor ?: bgTextColor),
                textDecoration = if (isStale) TextDecoration.LineThrough else null,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                DirectionArrows(snap?.directionText ?: directionText, colors.primaryText)
                Spacer(Modifier.size(8.dp))
                Text(snap?.deltaText ?: deltaText, fontSize = 32.sp, color = colors.primaryText, maxLines = 1)
            }

            if (snap != null) {
                Row(
                    Modifier.background(colors.gray.copy(alpha = 0.3f), androidx.compose.foundation.shape.CircleShape).padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.History, null, tint = colors.primaryText, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(snap.timeText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
                }
                Text(snap.predictionText, fontSize = 17.sp, color = colors.resolve(snap.predictionColor))
                return@Column
            }

            Text(minAgoText, fontSize = 17.sp, color = colors.primaryText)

            sensorState?.let {
                Text(
                    "CGM: ${it.summary}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.orange,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (isNotLooping) {
                Text(
                    loopStatusText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.resolve(loopStatusColor),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    Text(loopStatusText, fontSize = 17.sp, color = colors.resolve(loopStatusColor))
                    Spacer(Modifier.size(8.dp))
                    Text(predictionText, fontSize = 17.sp, color = colors.resolve(predictionColor))
                }
            }
        }
    }
}

@Composable
fun InfoTable(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val tableData by MainEngine.infoManager.tableData.collectAsStateWithLifecycle()
    val items by Storage.infoDisplayItems.flow.collectAsStateWithLifecycle()
    val tzEnabled by Storage.graphTimeZoneEnabled.flow.collectAsStateWithLifecycle()
    val tzId by Storage.graphTimeZoneIdentifier.flow.collectAsStateWithLifecycle()

    val snapshot by com.loopandlearn.loopfollow.engine.RetroSelection.snapshot.flow.collectAsStateWithLifecycle()
    // Scrubbing: the value at the scrubbed time, or "—" when unknown.
    val rows = items.filter { it.isVisible }.map { item ->
        val snap = snapshot
        if (snap == null) tableData[item.type.ordinal] else snap.rows[item.type].let { InfoData(item.type, it?.value ?: "", it?.numericValue) }
    }

    Column(modifier.then(if (snapshot != null) Modifier.retroCard() else Modifier).verticalScroll(rememberScrollState())) {
        if (tzEnabled) {
            InfoRow("Time Zone", tzId, null)
            HorizontalDivider(thickness = 0.5.dp, color = colors.separator, modifier = Modifier.padding(start = 8.dp))
        }
        rows.forEachIndexed { index, row ->
            InfoRow(row.type.displayName, row.value, valueColor(row, items))
            if (index < rows.size - 1) {
                HorizontalDivider(thickness = 0.5.dp, color = colors.separator, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun valueColor(row: InfoData, items: List<com.loopandlearn.loopfollow.info.InfoDisplayItem>): Color? {
    val numeric = row.numericValue ?: return null
    val config = row.type.colorConfig ?: return null
    val level = items.item(row.type)?.coloring?.level(numeric, config.direction) ?: return null
    val colors = LocalAppColors.current
    return when (level) {
        InfoColorLevel.URGENT -> colors.red
        InfoColorLevel.WARNING -> colors.yellow
        InfoColorLevel.NORMAL -> colors.green
    }
}

@Composable
private fun InfoRow(name: String, value: String, valueColor: Color?) {
    val colors = LocalAppColors.current
    // A placeholder for any field without a value, so the row reads as "no data".
    val display = value.ifEmpty { "—" }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 21.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, fontSize = 17.sp, color = colors.primaryText, maxLines = 1)
        Spacer(Modifier.weight(1f))
        AutoSizeText(display, maxFontSize = 17.sp, color = valueColor ?: colors.primaryText, textAlign = TextAlign.End, minScale = 0.5f)
    }
}

/** Trend arrows drawn as icons, so they stay bold in every font. */
@Composable
fun DirectionArrows(text: String, color: Color, size: androidx.compose.ui.unit.Dp = 64.dp, textSize: androidx.compose.ui.unit.TextUnit = 60.sp) {
    val icons = when (text) {
        "→" -> listOf(Icons.AutoMirrored.Filled.ArrowForward)
        "↗" -> listOf(Icons.Filled.NorthEast)
        "↘︎", "↘" -> listOf(Icons.Filled.SouthEast)
        "↑" -> listOf(Icons.Filled.ArrowUpward)
        "↓" -> listOf(Icons.Filled.ArrowDownward)
        "↑↑" -> List(2) { Icons.Filled.ArrowUpward }
        "↓↓" -> List(2) { Icons.Filled.ArrowDownward }
        else -> emptyList()
    }
    if (icons.isEmpty()) {
        Text(text, fontSize = textSize, fontWeight = FontWeight.Black, color = color, maxLines = 1)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(-size * 0.22f)) {
        icons.forEach { androidx.compose.material3.Icon(it, contentDescription = text, tint = color, modifier = Modifier.size(size)) }
    }
}

/** Gray gradient card behind the displays while they show a scrubbed time. */
@Composable
fun Modifier.retroCard(): Modifier {
    val gray = LocalAppColors.current.gray
    return this.background(
        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(gray.copy(alpha = 0.4f), gray.copy(alpha = 0.12f))),
        RoundedCornerShape(14.dp),
    )
}
