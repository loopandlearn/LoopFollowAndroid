package com.loopandlearn.loopfollow.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.engine.stats.AGPCalculator
import com.loopandlearn.loopfollow.engine.stats.AGPDataPoint
import com.loopandlearn.loopfollow.engine.stats.DataAvailabilityInfo
import com.loopandlearn.loopfollow.engine.stats.GRICalculationResult
import com.loopandlearn.loopfollow.engine.stats.GRICalculator
import com.loopandlearn.loopfollow.engine.stats.PeriodTIRCalculator
import com.loopandlearn.loopfollow.engine.stats.SimpleStats
import com.loopandlearn.loopfollow.engine.stats.SimpleStatsCalculator
import com.loopandlearn.loopfollow.engine.stats.StatsDataService
import com.loopandlearn.loopfollow.engine.stats.StatsDateRange
import com.loopandlearn.loopfollow.engine.stats.TIRDataPoint
import com.loopandlearn.loopfollow.helpers.GlycemicMetricMode
import com.loopandlearn.loopfollow.helpers.UnitSettingsStore
import com.loopandlearn.loopfollow.helpers.VariabilityMetricMode
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The statistics of one screen instance; the downloaded data itself is shared through StatsStore. */
private class StatisticsModel {
    val dataService = StatsDataService()
    private val simpleCalculator = SimpleStatsCalculator(dataService)

    var simpleStats by mutableStateOf(SimpleStats())
    var agpData by mutableStateOf<List<AGPDataPoint>>(emptyList())
    var tirData by mutableStateOf<List<TIRDataPoint>>(emptyList())
    var gri by mutableStateOf<GRICalculationResult?>(null)
    var dataAvailability by mutableStateOf<DataAvailabilityInfo?>(null)

    init {
        calculateAGP()
        calculateGRI()
        calculateTIR()
    }

    private fun calculateAGP() {
        agpData = AGPCalculator.calculate(dataService.getBGData())
    }

    /** Keeps the previous result when the range has no readings. */
    private fun calculateGRI() {
        val bgData = dataService.getBGData()
        if (bgData.isEmpty()) return
        gri = GRICalculator.calculate(bgData)
    }

    fun calculateTIR() {
        tirData = PeriodTIRCalculator.calculate(dataService.getBGData())
    }

    fun calculateStats() {
        simpleCalculator.calculate()?.let { simpleStats = it }
        calculateAGP()
        calculateGRI()
        calculateTIR()
        dataAvailability = dataService.getDataAvailability()
    }

    fun clearAllStats() {
        simpleStats = SimpleStats()
        agpData = emptyList()
        gri = null
        tirData = emptyList()
        dataAvailability = null
    }

    suspend fun updateDateRange(start: Double, end: Double) {
        dataService.updateDateRange(start, end)
        dataService.ensureDataAvailable()
        calculateStats()
    }
}

@Composable
fun StatisticsScreen(onBack: (() -> Unit)?) {
    val colors = LocalAppColors.current
    val model = remember { StatisticsModel() }
    val scope = rememberCoroutineScope()

    val initialRange = remember { StatsDateRange.lastComplete(7) }
    var startDate by remember { mutableStateOf(initialRange.first) }
    var endDate by remember { mutableStateOf(initialRange.second) }
    var isLoadingData by remember { mutableStateOf(false) }
    var showLoadingMessage by remember { mutableStateOf(false) }
    var loadingError by remember { mutableStateOf(false) }

    val showGMI by Storage.showGMI.flow.collectAsStateWithLifecycle()
    val showStdDev by Storage.showStdDev.flow.collectAsStateWithLifecycle()
    val units by Storage.units.flow.collectAsStateWithLifecycle()
    val useIFCC by Storage.useIFCC.flow.collectAsStateWithLifecycle()
    val timeInRangeMode by Storage.timeInRangeModeRaw.flow.collectAsStateWithLifecycle()

    fun reload() {
        loadingError = false
        isLoadingData = true
        scope.launch {
            model.updateDateRange(startDate, endDate)
            isLoadingData = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(isLoadingData) {
        showLoadingMessage = false
        if (!isLoadingData) return@LaunchedEffect
        loadingError = false
        delay(3_000)
        showLoadingMessage = true
        delay(27_000)
        if (isLoadingData) {
            isLoadingData = false
            loadingError = true
            model.clearAllStats()
        }
    }

    LaunchedEffect(units, useIFCC) {
        if (!isLoadingData) model.calculateStats()
    }

    LaunchedEffect(timeInRangeMode) { model.calculateTIR() }

    NavScreen(
        title = "",
        onBack = onBack,
        actions = {
            Text(
                "Refresh",
                color = colors.blue,
                fontSize = StatsFont.headline,
                modifier = Modifier.clickable { reload() }.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.background)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Statistics", fontSize = StatsFont.largeTitle, fontWeight = FontWeight.Bold, color = colors.primaryText)
                DateRangePicker(
                    startDate = startDate,
                    endDate = endDate,
                    availability = model.dataAvailability,
                    onDateChange = { start, end ->
                        startDate = start
                        endDate = end
                        reload()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                )
            }

            if (loadingError) {
                Column(
                    Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("#WeAreNotWaitingAnymore", fontSize = StatsFont.subheadline, fontWeight = FontWeight.Medium, color = colors.orange)
                    Text("...because the data could not be loaded", fontSize = StatsFont.caption, color = colors.secondaryText)
                }
            } else if (isLoadingData) {
                Column(
                    Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = colors.secondaryText, strokeWidth = 2.dp)
                        Text("Loading data...", fontSize = StatsFont.subheadline, color = colors.secondaryText)
                    }
                    if (showLoadingMessage) {
                        Text("#WeAreWaitingForData", fontSize = StatsFont.caption, color = colors.secondaryText)
                    }
                }
            }

            val contentAlpha = if (isLoadingData) 0.4f else 1f
            val sectionModifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().alpha(contentAlpha)

            Column(sectionModifier) {
                StatsGrid(
                    stats = model.simpleStats,
                    showGMI = showGMI,
                    showStdDev = showStdDev,
                    onToggleGMI = {
                        if (!isLoadingData) {
                            UnitSettingsStore.glycemicMetricMode = if (showGMI) GlycemicMetricMode.EHBA1C else GlycemicMetricMode.GMI
                        }
                    },
                    onToggleStdDev = {
                        if (!isLoadingData) {
                            UnitSettingsStore.variabilityMetricMode =
                                if (showStdDev) VariabilityMetricMode.CV else VariabilityMetricMode.STD_DEVIATION
                        }
                    },
                )
            }

            AGPView(model.agpData, sectionModifier)

            TIRView(
                model.tirData,
                onToggleMode = {
                    UnitSettingsStore.timeInRangeMode = UnitSettingsStore.timeInRangeMode.next
                    model.calculateTIR()
                },
                modifier = sectionModifier,
            )

            GRIView(model.gri, sectionModifier)
        }
    }
}
