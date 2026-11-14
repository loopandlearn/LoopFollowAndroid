package com.loopandlearn.loopfollow.ui.treatments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.loopandlearn.loopfollow.engine.LoopCarbTreatment
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.engine.TrioMealTreatment
import com.loopandlearn.loopfollow.engine.Treatment
import com.loopandlearn.loopfollow.engine.TreatmentColor
import com.loopandlearn.loopfollow.engine.TreatmentIcon
import com.loopandlearn.loopfollow.engine.TreatmentType
import com.loopandlearn.loopfollow.engine.cFormat
import com.loopandlearn.loopfollow.engine.formatTreatmentBG
import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.remote.RemoteCommandMessage
import com.loopandlearn.loopfollow.remote.RemoteCommandRefresh
import com.loopandlearn.loopfollow.remote.TrioRemote
import com.loopandlearn.loopfollow.remote.loop.LoopCommands
import com.loopandlearn.loopfollow.nightscout.EventType
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.nightscout.array
import com.loopandlearn.loopfollow.nightscout.bool
import com.loopandlearn.loopfollow.nightscout.double
import com.loopandlearn.loopfollow.nightscout.int
import com.loopandlearn.loopfollow.nightscout.obj
import com.loopandlearn.loopfollow.nightscout.objectList
import com.loopandlearn.loopfollow.nightscout.string
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.NavStack
import com.loopandlearn.loopfollow.ui.Route
import com.loopandlearn.loopfollow.ui.components.ButtonRow
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.LabeledValueRow
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.components.SegmentedPicker
import com.loopandlearn.loopfollow.ui.components.formSection
import com.loopandlearn.loopfollow.ui.remote.LoopCarbRows
import com.loopandlearn.loopfollow.ui.remote.RemoteAlert
import com.loopandlearn.loopfollow.ui.remote.RemoteAlertDialog
import com.loopandlearn.loopfollow.ui.remote.TRCMealMacroRows
import com.loopandlearn.loopfollow.ui.theme.AppColors
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class TreatmentFilter { ALL, CARB, BASAL, BOLUS_ALL, BOLUS_AUTOMATIC, BOLUS_SMB, BOLUS_MANUAL;

    fun displayName(automaticLabel: String) = when (this) {
        ALL -> "All"
        CARB -> "Carbs"
        BASAL -> "Basal"
        BOLUS_ALL -> "All Boluses"
        BOLUS_AUTOMATIC -> automaticLabel
        BOLUS_SMB -> "SMB"
        BOLUS_MANUAL -> "Manual"
    }

    companion object {
        val primary = listOf(ALL, CARB, BASAL)

        fun bolusFilters(isLoop: Boolean, isTrio: Boolean, automaticLabelIsSMB: Boolean): List<TreatmentFilter> = buildList {
            add(BOLUS_ALL)
            if (isTrio || isLoop || automaticLabelIsSMB) add(BOLUS_AUTOMATIC) else addAll(listOf(BOLUS_AUTOMATIC, BOLUS_SMB))
            add(BOLUS_MANUAL)
        }
    }
}

/** Paged treatment history for the Treatments tab. */
object TreatmentsModel {
    private const val PAGE_SIZE = 100
    var all by mutableStateOf<List<Treatment>>(emptyList())
    var initialLoading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var hasMore by mutableStateOf(true)
    var hasSMB by mutableStateOf(false)
    var hasAutomatic by mutableStateOf(false)
    var selected by mutableStateOf<Treatment?>(null)
    private val processed = HashSet<String>()
    private var oldest: Double? = null
    /** The carb entry refresh count the loaded list reflects. */
    var loadedAtRefresh = 0
        private set

    val rootMeals: Map<String, Treatment> get() = Treatment.rootMealsByFPUID(all)

    suspend fun loadInitial() {
        if (initialLoading) return
        initialLoading = true
        loadedAtRefresh = RemoteCommandRefresh.carbEntryRefreshes.value
        all = emptyList()
        processed.clear()
        oldest = null
        hasMore = true
        hasSMB = false
        hasAutomatic = false
        // The flag is cleared also when the caller's scope ends mid-fetch, so a later load can run.
        try {
            val (page, raw) = fetch(System.currentTimeMillis() / 1000.0)
            all = page
            oldest = page.lastOrNull()?.date
            hasMore = raw >= PAGE_SIZE
        } finally {
            initialLoading = false
        }
    }

    suspend fun loadMore() {
        val end = oldest ?: return
        if (loadingMore || !hasMore) return
        loadingMore = true
        try {
            val (page, raw) = fetch(end)
            all = all + page
            page.lastOrNull()?.let { oldest = it.date }
            hasMore = raw >= PAGE_SIZE
        } finally {
            loadingMore = false
        }
    }

    private suspend fun fetch(endDate: Double): Pair<List<Treatment>, Int> {
        if (!MainEngine.isNightscoutEnabled()) return emptyList<Treatment>() to 0
        val params = mapOf(
            "find[created_at][\$gte]" to NightscoutUtils.formatISO(endDate - 365 * 86400.0, fractional = true),
            "find[created_at][\$lt]" to NightscoutUtils.formatISO(endDate, fractional = true),
            "count" to "$PAGE_SIZE",
        )
        val entries = try {
            NightscoutUtils.executeRequest(EventType.TREATMENTS, params).objectList()
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "Failed to fetch treatments: ${e.message}")
            return emptyList<Treatment>() to 0
        }
        val result = mutableListOf<Treatment>()
        for (entry in entries) {
            entry.string("eventType") ?: continue
            val date = entry.string("created_at")?.let { NightscoutUtils.parseDate(it) } ?: continue
            val nsId = entry.string("_id") ?: "unknown-$date"
            if (!processed.add(nsId)) continue
            val history = Treatment.historyEntries(entry, date) { nearestBG(date) }
            result.addAll(history.entries)
            if (history.sawSMB) hasSMB = true
            if (history.sawAutomatic) hasAutomatic = true
        }
        return result.sortedByDescending { it.date } to entries.size
    }

    /** The reading within 10 minutes of [timestamp], else 0. */
    fun nearestBG(timestamp: Double): Int {
        val closest = MainEngine.bgData.minByOrNull { abs(it.date - timestamp) } ?: return 0
        return if (abs(closest.date - timestamp) < 600) closest.sgv else 0
    }
}

@Composable
fun TreatmentIconView(t: Treatment, colors: AppColors, size: Float = 20f) {
    val tint = when (t.color) {
        TreatmentColor.ORANGE -> colors.orange
        TreatmentColor.BLUE -> colors.blue
        TreatmentColor.PURPLE -> colors.purple
        TreatmentColor.GREEN -> colors.green
    }.copy(alpha = if (t.type == TreatmentType.TEMP_BASAL) 0.5f else 1f)
    Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
        when (t.icon) {
            TreatmentIcon.DOT -> Canvas(Modifier.size((size * 0.75f).dp)) { drawCircle(tint) }
            TreatmentIcon.TRIANGLE_DOWN -> Canvas(Modifier.size((size * 0.75f).dp)) {
                drawPath(Path().apply {
                    moveTo(0f, 0f)
                    lineTo(this@Canvas.size.width, 0f)
                    lineTo(this@Canvas.size.width / 2, this@Canvas.size.height)
                    close()
                }, tint)
            }
            TreatmentIcon.CHART -> Icon(Icons.AutoMirrored.Outlined.ShowChart, null, tint = tint, modifier = Modifier.size(size.dp))
            TreatmentIcon.SLIDERS -> Icon(Icons.Outlined.Tune, null, tint = tint, modifier = Modifier.size(size.dp))
            TreatmentIcon.SCOPE -> Icon(Icons.Outlined.TrackChanges, null, tint = tint, modifier = Modifier.size(size.dp))
        }
    }
}

private fun shortTime(t: Double) = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).apply { timeZone = DateTimeUtils.displayTimeZone() }.format(Date((t * 1000).toLong()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreatmentsScreen(nav: NavStack, onBack: (() -> Unit)?) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val device by Storage.device.flow.collectAsStateWithLifecycle()
    val tzEnabled by Storage.graphTimeZoneEnabled.flow.collectAsStateWithLifecycle()
    val tzId by Storage.graphTimeZoneIdentifier.flow.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(TreatmentFilter.ALL) }
    val model = TreatmentsModel

    val isLoop = device == "Loop"
    val isTrio = device == "Trio"
    val automaticLabel = when {
        isTrio -> "SMB"
        isLoop -> "Automatic"
        model.hasAutomatic -> "Automatic"
        model.hasSMB -> "SMB"
        else -> "Automatic"
    }
    val automaticIsSMB = automaticLabel == "SMB"
    val bolusFilters = TreatmentFilter.bolusFilters(isLoop, isTrio, automaticIsSMB)
    if (filter !in TreatmentFilter.primary + bolusFilters) filter = TreatmentFilter.BOLUS_ALL

    LaunchedEffect(Unit) { if (model.all.isEmpty()) model.loadInitial() }
    // ANDROID-DEVIATION: Reloads once the early Nightscout fetches after a remote meal or carb edit or delete have
    // finished; Android cannot receive the AID app's confirmation push that marks the command done.
    val carbEntryRefreshes by RemoteCommandRefresh.carbEntryRefreshes.flow.collectAsStateWithLifecycle()
    LaunchedEffect(carbEntryRefreshes) { if (carbEntryRefreshes != model.loadedAtRefresh) model.loadInitial() }

    val types: Set<TreatmentType>? = when (filter) {
        TreatmentFilter.ALL -> null
        TreatmentFilter.CARB -> setOf(TreatmentType.CARB)
        TreatmentFilter.BASAL -> setOf(TreatmentType.TEMP_BASAL)
        TreatmentFilter.BOLUS_ALL -> setOf(TreatmentType.BOLUS_AUTOMATIC, TreatmentType.BOLUS_MANUAL, TreatmentType.SMB)
        TreatmentFilter.BOLUS_AUTOMATIC -> if (automaticIsSMB) setOf(TreatmentType.BOLUS_AUTOMATIC, TreatmentType.SMB) else setOf(TreatmentType.BOLUS_AUTOMATIC)
        TreatmentFilter.BOLUS_SMB -> setOf(TreatmentType.SMB)
        TreatmentFilter.BOLUS_MANUAL -> setOf(TreatmentType.BOLUS_MANUAL)
    }
    val shown = model.all.filter { types == null || it.type in types }.sortedByDescending { it.date }
    val tz = DateTimeUtils.displayTimeZone()
    val dayFormat = SimpleDateFormat("EEEE MMM d", Locale.getDefault()).apply { timeZone = tz }
    val is24 = android.text.format.DateFormat.is24HourFormat(com.loopandlearn.loopfollow.LoopFollowApplication.instance)
    val hourFormat = SimpleDateFormat(if (is24) "HH:00" else "h a", Locale.getDefault()).apply { timeZone = tz }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        if (onBack != null) com.loopandlearn.loopfollow.ui.components.NavBar("", onBack)
        Text("Treatments", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = colors.primaryText, modifier = Modifier.padding(horizontal = 16.dp))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedPicker(TreatmentFilter.primary, filter, { it.displayName(automaticLabel) }, { filter = it }, Modifier.fillMaxWidth())
            SegmentedPicker(bolusFilters, filter, { it.displayName(automaticLabel) }, { filter = it }, Modifier.fillMaxWidth())
        }
        if (tzEnabled) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Language, null, tint = colors.secondaryText, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Time Zone Override On: $tzId", fontSize = 12.sp, color = colors.secondaryText)
            }
        }

        PullToRefreshBox(isRefreshing = model.initialLoading && model.all.isNotEmpty(), onRefresh = { scope.launch { model.loadInitial() } }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (model.initialLoading && model.all.isEmpty()) {
                    item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                } else if (shown.isEmpty()) {
                    item { Text("No recent treatments", color = colors.secondaryText, modifier = Modifier.padding(16.dp)) }
                } else {
                    val byDay = shown.groupBy { t ->
                        Calendar.getInstance(tz).apply { timeInMillis = (t.date * 1000).toLong() }.let { "${it.get(Calendar.YEAR)}-${it.get(Calendar.DAY_OF_YEAR)}" }
                    }
                    for ((_, dayTreatments) in byDay) {
                        item {
                            Text(
                                dayFormat.format(Date((dayTreatments.first().date * 1000).toLong())),
                                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText,
                                modifier = Modifier.fillMaxWidth().background(colors.background).padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
                            )
                        }
                        var previousHour: String? = null
                        for (t in dayTreatments) {
                            if (t.hourKey != previousHour) {
                                previousHour = t.hourKey
                                item(key = "hour-${t.hourKey}-${t.id}") {
                                    Text(hourFormat.format(Date((t.date * 1000).toLong())), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.secondaryText, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp))
                                }
                            }
                            item(key = t.id) {
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        model.selected = t
                                        nav.push(Route.TreatmentDetail)
                                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TreatmentIconView(t, colors)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row {
                                            Text(t.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
                                            t.subtitle?.let {
                                                Spacer(Modifier.width(6.dp))
                                                Text(it, fontSize = 15.sp, color = colors.secondaryText)
                                            }
                                        }
                                        Text(shortTime(t.date), fontSize = 12.sp, color = colors.secondaryText)
                                    }
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.gray)
                                }
                                HorizontalDivider(Modifier.padding(start = 50.dp), thickness = 0.5.dp, color = colors.separator)
                            }
                        }
                    }
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            if (model.loadingMore) {
                                CircularProgressIndicator()
                            } else {
                                Column(Modifier.clickable { scope.launch { model.loadMore() } }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Load More", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.blue)
                                    Text("Tap to load older treatments", fontSize = 12.sp, color = colors.secondaryText)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DeviceStatusAt(
    val iob: Double?, val cob: Double?, val eventualBG: Int?, val minBG: Int?, val maxBG: Int?,
    val overrideName: String?, val overrideMultiplier: Double?, val overrideRange: Pair<Int, Int>?, val recommendedBolus: Double?,
)

/** The device status record closest to [timestamp], within 15 minutes. */
private suspend fun deviceStatusAround(timestamp: Double): DeviceStatusAt? {
    val params = mapOf(
        "find[created_at][\$gte]" to NightscoutUtils.formatISO(timestamp - 1800, fractional = true),
        "find[created_at][\$lte]" to NightscoutUtils.formatISO(timestamp + 1800, fractional = true),
        "count" to "30",
    )
    val records = runCatching { NightscoutUtils.executeRequest(EventType.DEVICE_STATUS, params).objectList() }.getOrNull() ?: return null
    var best: DeviceStatusAt? = null
    var smallest = Double.POSITIVE_INFINITY
    fun ints(arr: JsonArray?): List<Int>? {
        arr ?: return null
        val out = arr.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull ?: return null }
        return if (out.all { it % 1.0 == 0.0 }) out.map { it.toInt() } else null
    }
    for (entry in records) {
        val ts = entry.string("created_at")?.let { NightscoutUtils.parseISO(it) } ?: entry.string("dateString")?.let { NightscoutUtils.parseDate(it) } ?: continue
        val diff = abs(ts - timestamp)
        if (diff >= smallest) continue
        smallest = diff
        var iob: Double? = null
        var cob: Double? = null
        var eventual: Int? = null
        var min: Int? = null
        var max: Int? = null
        var rec: Double? = null
        entry.obj("loop")?.let { loop ->
            iob = loop.obj("iob")?.double("iob")
            cob = loop.obj("cob")?.double("cob")
            ints(loop.obj("predicted")?.array("values"))?.takeIf { it.isNotEmpty() }?.let {
                eventual = it.last()
                min = it.min()
                max = it.max()
            }
            rec = loop.double("recommendedBolus")
        }
        entry.obj("openaps")?.let { openaps ->
            openaps.obj("suggested")?.let { s ->
                s.double("IOB")?.let { iob = it }
                s.double("COB")?.let { cob = it }
                s.int("eventualBG")?.let { eventual = it }
            }
            openaps.obj("enacted")?.let { e ->
                if (iob == null) iob = e.double("IOB")
                if (cob == null) cob = e.double("COB")
            }
        }
        var name: String? = null
        var mult: Double? = null
        var range: Pair<Int, Int>? = null
        val override = entry.obj("override")
        if (override != null && override.bool("active") == true) {
            name = override.string("name")
            mult = override.double("multiplier")
            val r = override.obj("currentCorrectionRange")
            val lo = r?.double("minValue")
            val hi = r?.double("maxValue")
            if (lo != null && hi != null) range = lo.toInt() to hi.toInt()
        }
        best = DeviceStatusAt(iob, cob, eventual, min, max, name, mult, range, rec)
    }
    return if (smallest < 15 * 60) best else null
}

@Composable
fun TreatmentDetailScreen(nav: NavStack, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val t = TreatmentsModel.selected ?: return
    val remoteType by Storage.remoteType.flow.collectAsStateWithLifecycle()
    val device by Storage.device.flow.collectAsStateWithLifecycle()
    val remoteCommands by Storage.remoteCommands.flow.collectAsStateWithLifecycle()
    val loopRemoteCommands by Storage.loopRemoteCommands.flow.collectAsStateWithLifecycle()
    val loopMaxFutureCarbHours by Storage.loopMaxFutureCarbHours.flow.collectAsStateWithLifecycle()
    var isSending by remember { mutableStateOf(false) }
    var commandError by remember { mutableStateOf<String?>(null) }
    var alert by remember { mutableStateOf<RemoteAlert?>(null) }
    val rootMeal = remember(t) { t.rootMeal(TreatmentsModel.rootMeals) }
    // The Trio meal remote commands act on: the root meal for an FPU child whose root is loaded.
    val commandMeal = t.trioMeal?.let { meal -> if (meal.isFPUChild) rootMeal?.trioMeal ?: meal else meal }
    val now = nowSeconds()

    // ANDROID-DEVIATION: A sent delete shows "Command Sent" and returns to the list, and a send failure shows in the
    // actions section; Android cannot receive the AID app's confirmation push, so nothing waits for a reply.
    fun sendDelete() {
        isSending = true
        commandError = null
        scope.launch {
            val error = when {
                commandMeal != null -> TrioRemote.deleteMeal(commandMeal.mealID)
                t.loopCarb != null -> LoopCommands.sendCarbDelete(t.loopCarb)
                else -> return@launch
            }
            isSending = false
            if (error == null) {
                alert = RemoteAlert("Command Sent", RemoteCommandMessage.SENT, onDismiss = onBack)
            } else {
                commandError = error
            }
        }
    }

    fun confirmDelete() {
        val isTrio = t.trioMeal != null
        alert = RemoteAlert(
            if (isTrio) "Delete this meal in Trio?" else "Delete this carb entry in Loop?",
            if (isTrio) {
                "This removes the carb entry and any fat/protein entries Trio created from it."
            } else {
                "This removes the carb entry from Loop. Loop recalculates carbs on board right away."
            },
            confirmTitle = if (isTrio) "Delete meal" else "Delete carbs",
            onConfirm = { sendDelete() },
        )
    }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<DeviceStatusAt?>(null) }
    val glucose = remember(t) { TreatmentsModel.nearestBG(t.date) }
    LaunchedEffect(t) {
        status = deviceStatusAround(t.date)
        loading = false
    }
    val title = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .apply { timeZone = DateTimeUtils.displayTimeZone() }.format(Date((t.date * 1000).toLong())).replace(" at ", " ")

    NavScreen(title, onBack = onBack) {
        FormList {
            formSection {
                FormRow {
                    TreatmentIconView(t, colors)
                    Spacer(Modifier.width(8.dp))
                    Text(t.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
                    t.subtitle?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(it, fontSize = 15.sp, color = colors.secondaryText)
                    }
                }
            }
            t.trioMeal?.let { meal ->
                val controlActive = TrioMealTreatment.remoteControlActive(remoteType, device)
                val actionsAvailable = TrioMealTreatment.remoteActionsAvailable(remoteType, device, remoteCommands)
                val footer = buildList {
                    if (meal.isFPUChild) add("One of the small carb entries Trio created from this meal's fat and protein. Editing or deleting affects the whole meal.")
                    if (controlActive && !actionsAvailable) add("Editing meals needs a newer Trio version.")
                }.joinToString("\n").ifEmpty { null }
                formSection(header = "Meal", footer = footer) {
                    TRCMealMacroRows(meal.carbs, meal.fat, meal.protein, meal.date)
                    meal.note?.let {
                        FormDivider()
                        LabeledValueRow("Note", it)
                    }
                }
                if (actionsAvailable) {
                    val target = commandMeal ?: meal
                    val withinWindow = target.isWithinEditWindow(now)
                    formSection(
                        header = "Remote actions",
                        footer = if (withinWindow) null else "Meals can be changed remotely up to 24 hours after and 12 hours before their time.",
                    ) {
                        commandError?.let { CommandErrorRow(it) }
                        if (!target.isFPUChild) {
                            ButtonRow("Edit meal", enabled = withinWindow && !isSending) { nav.push(Route.TrioMealEdit(target)) }
                            FormDivider()
                        }
                        ButtonRow("Delete meal", color = colors.red, enabled = withinWindow && !isSending) { confirmDelete() }
                    }
                }
            }
            t.loopCarb?.let { carb ->
                val controlActive = LoopCarbTreatment.remoteControlActive(remoteType, device)
                val actionsAvailable = LoopCarbTreatment.remoteActionsAvailable(remoteType, device, loopRemoteCommands)
                formSection(
                    header = "Carb entry",
                    footer = if (controlActive && !actionsAvailable) "Editing carbs needs the remote carb edit customization in Loop." else null,
                ) {
                    LoopCarbRows(carb)
                }
                if (actionsAvailable) {
                    val withinWindow = carb.isWithinEditWindow(now, loopMaxFutureCarbHours)
                    formSection(
                        header = "Remote actions",
                        footer = if (withinWindow) {
                            null
                        } else {
                            "Carb entries can be changed remotely up to 23 hours after and $loopMaxFutureCarbHours ${if (loopMaxFutureCarbHours == 1) "hour" else "hours"} before their time."
                        },
                    ) {
                        commandError?.let { CommandErrorRow(it) }
                        ButtonRow("Edit carbs", enabled = withinWindow && !isSending) { nav.push(Route.LoopCarbEdit(carb)) }
                        FormDivider()
                        ButtonRow("Delete carbs", color = colors.red, enabled = withinWindow && !isSending) { confirmDelete() }
                    }
                }
            }
            if (loading) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else {
                if (glucose > 0) {
                    formSection(header = "Glucose value") { FormRow { FormText(formatTreatmentBG(glucose.toDouble())) } }
                }
                val s = status
                if (s != null) {
                    if (s.iob != null || s.cob != null || s.eventualBG != null) {
                        formSection(header = "Device Data") {
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                s.iob?.let { Metric("IOB", cFormat(it, 2) + " U", Modifier.weight(1f)) }
                                s.cob?.let { Metric("COB", cFormat(it, 0) + " g", Modifier.weight(1f)) }
                                s.eventualBG?.takeIf { it > 0 }?.let { Metric("Eventual", formatTreatmentBG(it.toDouble()), Modifier.weight(1f)) }
                            }
                        }
                    }
                    val min = s.minBG ?: 0
                    val max = s.maxBG ?: 0
                    if (min > 0 || max > 0) {
                        formSection(header = "Prediction") {
                            if (min > 0) com.loopandlearn.loopfollow.ui.components.LabeledValueRow(if (max > 0) "Min" else "Minimum", formatTreatmentBG(min.toDouble()))
                            if (max > 0) com.loopandlearn.loopfollow.ui.components.LabeledValueRow(if (min > 0) "Max" else "Maximum", formatTreatmentBG(max.toDouble()))
                        }
                    }
                    s.overrideName?.let { name ->
                        formSection(header = "Active Override") {
                            FormRow { FormText(name) }
                            s.overrideMultiplier?.let { com.loopandlearn.loopfollow.ui.components.LabeledValueRow("Sensitivity", cFormat(it * 100, 0) + "%") }
                            s.overrideRange?.let { (lo, hi) -> com.loopandlearn.loopfollow.ui.components.LabeledValueRow("Target Range", "${formatTreatmentBG(lo.toDouble())} - ${formatTreatmentBG(hi.toDouble())}") }
                        }
                    }
                    s.recommendedBolus?.takeIf { it > 0 }?.let {
                        formSection(header = "Recommended Bolus") { FormRow { FormText(cFormat(it, 2) + " U") } }
                    }
                }
            }
        }
    }
    RemoteAlertDialog(alert) { alert = null }
}

/** The last failure to send this treatment's remote command. */
@Composable
private fun CommandErrorRow(message: String) {
    FormRow { Text(message, fontSize = 13.sp, color = LocalAppColors.current.red) }
    FormDivider()
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    val colors = LocalAppColors.current
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = colors.secondaryText)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
    }
}
