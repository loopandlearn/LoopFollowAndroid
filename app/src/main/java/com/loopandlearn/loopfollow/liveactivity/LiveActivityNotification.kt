package com.loopandlearn.loopfollow.liveactivity

import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.SystemClock
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.loopandlearn.loopfollow.R
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage

/**
 * The Live Activity card as the content of the ongoing notification: the expanded view follows the iOS lock-screen
 * card, the collapsed view shows glucose, trend and delta.
 */
// ANDROID-DEVIATION: the Live Activity is the foreground service's ongoing notification, updated locally on each
// refresh. Android has no Live Activities, Dynamic Island, push-to-update or 8-hour lifetime, so there is no
// renewal or "Tap to update" overlay.
object LiveActivityNotification {
    /** Above this remaining time the countdown is left out and only the name or value shows. */
    const val TICKER_MAX_REMAINING = 2 * 3600.0

    private val slotIds = listOf(
        Triple(R.id.la_slot0, R.id.la_slot0_label, R.id.la_slot0_value),
        Triple(R.id.la_slot1, R.id.la_slot1_label, R.id.la_slot1_value),
        Triple(R.id.la_slot2, R.id.la_slot2_label, R.id.la_slot2_value),
        Triple(R.id.la_slot3, R.id.la_slot3_label, R.id.la_slot3_value),
    )

    /** Applies the card to [builder]; false when there is no reading to show yet. */
    fun apply(context: Context, builder: NotificationCompat.Builder): Boolean {
        val s = GlucoseSnapshot.current() ?: return false
        if (LiveUpdate.active) {
            applyLiveUpdate(context, builder, s)
            return true
        }
        builder
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed(context, s))
            .setCustomBigContentView(expanded(context, s))
            .setContentTitle(collapsedTitle(s))
            // ANDROID-DEVIATION: the notification header shows the reading's age, which the system keeps current.
            .setWhen((s.updatedAt * 1000).toLong())
            .setShowWhen(true)
        return true
    }

    /** The earliest moment the adjustments row changes on its own: a countdown appearing or ending. */
    fun nextAdjustmentChange(): Double? {
        val now = nowSeconds()
        return listOfNotNull(Observable.overrideEndAt.value, Observable.tempTargetEndAt.value)
            .flatMap { listOf(it - TICKER_MAX_REMAINING, it) }
            .filter { it > now }
            .minOrNull()
    }

    private fun collapsedTitle(s: GlucoseSnapshot): CharSequence =
        if (s.isNotLooping) "⚠️ Not Looping" else "${LAFormat.glucose(s)} ${LAFormat.trendArrow(s)} ${LAFormat.delta(s)}"

    // ANDROID-DEVIATION: a reading 12 minutes or older is struck through, as in the home screen BG display; the
    // notification stays posted for as long as the background service runs.
    internal fun glucoseText(s: GlucoseSnapshot): CharSequence {
        val text = LAFormat.glucose(s)
        if (!Observable.bgStale.value) return text
        return SpannableString(text).apply { setSpan(StrikethroughSpan(), 0, length, 0) }
    }

    // ANDROID-DEVIATION: the collapsed notification follows the Dynamic Island expanded leading region (BG and arrow in
    // the range color, delta with unit); Android notifications always have a collapsed form.
    private fun collapsed(context: Context, s: GlucoseSnapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_la_collapsed)
        val keyline = LAColors.keyline(s)
        if (s.isNotLooping) {
            views.setTextViewText(R.id.la_bg, "⚠️ Not Looping")
            views.setTextColor(R.id.la_bg, LAColors.keyline(s))
            views.setViewVisibility(R.id.la_arrow, View.GONE)
            views.setTextViewText(R.id.la_delta, s.cgmSensorState?.let { "CGM: $it" } ?: "")
            return views
        }
        views.setTextViewText(R.id.la_bg, glucoseText(s))
        views.setTextColor(R.id.la_bg, keyline)
        setTrendArrow(views, s, keyline)
        views.setTextViewText(R.id.la_delta, "${LAFormat.delta(s)} ${s.unit.raw}")
        return views
    }

    private fun expanded(context: Context, s: GlucoseSnapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_la_expanded)
        views.setColorStateList(R.id.la_card_bg, "setImageTintList", ColorStateList.valueOf(LAColors.backgroundTint(s)))

        views.setTextViewText(R.id.la_bg, glucoseText(s))
        setTrendArrow(views, s, android.graphics.Color.WHITE)
        views.setTextViewText(R.id.la_delta, "Delta: ${LAFormat.delta(s)}")

        val slots = LASettings.slots()
        slotIds.forEachIndexed { i, (container, label, value) ->
            val option = slots[i]
            if (option == LiveActivitySlotOption.NONE) {
                views.setViewVisibility(container, View.INVISIBLE)
            } else {
                views.setViewVisibility(container, View.VISIBLE)
                views.setTextViewText(label, option.gridLabel)
                views.setTextViewText(value, LAFormat.slotValue(option, s))
            }
        }

        applyAdjustments(views, s)

        val cgm = s.cgmSensorState?.let { "CGM: $it" }
        views.setViewVisibility(R.id.la_cgm, if (cgm != null) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.la_cgm, cgm ?: "")

        val updated = LAFormat.updated(s)
        views.setTextViewText(
            R.id.la_updated,
            if (Storage.showDisplayName.value) "${context.applicationInfo.loadLabel(context.packageManager)} — $updated" else "Last Update: $updated",
        )

        views.setViewVisibility(R.id.la_not_looping, if (s.isNotLooping) View.VISIBLE else View.GONE)
        if (s.isNotLooping) {
            views.setColorStateList(R.id.la_not_looping_bg, "setImageTintList", ColorStateList.valueOf(LAColors.NOT_LOOPING))
            views.setViewVisibility(R.id.la_not_looping_cgm, if (cgm != null) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.la_not_looping_cgm, cgm ?: "")
        }
        return views
    }

    /** The active override and/or temp target, each with a countdown while under two hours remain. */
    private fun applyAdjustments(views: RemoteViews, s: GlucoseSnapshot) {
        val now = nowSeconds()
        // Ends already in the past are stale data waiting for the next refresh.
        val overrideEnd = s.overrideEndAt?.takeIf { it > now }
        val tempTargetEnd = s.tempTargetEndAt?.takeIf { it > now }
        val overrideName = s.override?.takeUnless { s.overrideEndAt != null && overrideEnd == null }
        val tempTargetText = if (tempTargetEnd != null) LAFormat.tempTargetValue(s) else null

        if (overrideName == null && tempTargetText == null) {
            views.setViewVisibility(R.id.la_adjustments, View.GONE)
            return
        }
        views.setViewVisibility(R.id.la_adjustments, View.VISIBLE)

        views.setViewVisibility(R.id.la_override, if (overrideName != null) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.la_override, overrideName ?: "")
        countdown(views, R.id.la_override_timer, overrideEnd.takeIf { overrideName != null }, now)

        views.setViewVisibility(R.id.la_adjust_separator, if (overrideName != null && tempTargetText != null) View.VISIBLE else View.GONE)

        views.setViewVisibility(R.id.la_temp_target, if (tempTargetText != null) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.la_temp_target, tempTargetText?.let { "TT $it" } ?: "")
        countdown(views, R.id.la_temp_target_timer, tempTargetEnd.takeIf { tempTargetText != null }, now)
    }

    private fun countdown(views: RemoteViews, id: Int, end: Double?, now: Double) {
        if (end == null || end - now > TICKER_MAX_REMAINING) {
            views.setViewVisibility(id, View.GONE)
            views.setChronometer(id, SystemClock.elapsedRealtime(), null, false)
            return
        }
        views.setViewVisibility(id, View.VISIBLE)
        views.setChronometer(id, SystemClock.elapsedRealtime() + ((end - now) * 1000).toLong(), null, true)
        views.setChronometerCountDown(id, true)
    }

    // ANDROID-DEVIATION: as a Live Update the card uses the system's standard layout, since Android promotes only
    // notifications without custom views. The status bar chip holds glucose and trend, as the Dynamic Island's compact
    // leading region does; the card lists the grid slots, adjustments and CGM state as text lines.
    private fun applyLiveUpdate(context: Context, builder: NotificationCompat.Builder, s: GlucoseSnapshot) {
        val lines = liveUpdateLines(s)
        builder
            .setContentTitle(liveUpdateTitle(s))
            .setContentText(lines.firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setColor(LAColors.keyline(s))
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(LiveUpdate.chipText(s, Observable.bgStale.value))
            .setWhen((s.updatedAt * 1000).toLong())
            .setShowWhen(true)
    }

    private fun liveUpdateTitle(s: GlucoseSnapshot): CharSequence {
        val reading = SpannableStringBuilder(glucoseText(s)).append(" ${LAFormat.trendArrow(s)}  ${LAFormat.delta(s)} ${s.unit.raw}")
        return if (s.isNotLooping) SpannableStringBuilder("⚠️ Not Looping · ").append(reading) else reading
    }

    private fun liveUpdateLines(s: GlucoseSnapshot): List<String> = buildList {
        LASettings.slots().filter { it != LiveActivitySlotOption.NONE }
            .joinToString("   ") { "${it.gridLabel} ${LAFormat.slotValue(it, s)}" }
            .takeIf { it.isNotEmpty() }?.let(::add)
        val now = nowSeconds()
        val overrideEnd = s.overrideEndAt?.takeIf { it > now }
        s.override?.takeUnless { s.overrideEndAt != null && overrideEnd == null }?.let { name ->
            add(overrideEnd?.let { "$name until ${LAFormat.time(it)}" } ?: name)
        }
        s.tempTargetEndAt?.takeIf { it > now }?.let { end -> LAFormat.tempTargetValue(s)?.let { add("TT $it until ${LAFormat.time(end)}") } }
        s.cgmSensorState?.let { add("CGM: $it") }
        add("Last Update: ${LAFormat.updated(s)}")
    }

    /** The ongoing notification while the Live Activity is off or there is no reading yet. */
    fun applyMinimal(context: Context, builder: NotificationCompat.Builder) {
        builder
            .setContentTitle(context.applicationInfo.loadLabel(context.packageManager))
            .setContentText(if (Storage.laEnabled.value) "Waiting for glucose data" else "Running in the background for alarms")
            .setShowWhen(false)
    }
}

/** The notification as an Android 16 Live Update, promoted to the top of the shade and lock screen with a status bar chip. */
object LiveUpdate {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    val active: Boolean get() = supported && Storage.laStatusChip.value

    /** Glucose and trend; a stale reading is marked, since the chip has no strikethrough. The chip shows up to about 7 characters. */
    fun chipText(s: GlucoseSnapshot, stale: Boolean): String =
        if (stale) "⚠ ${LAFormat.glucose(s)}" else "${LAFormat.glucose(s)}${LAFormat.trendArrow(s)}"
}

/**
 * ANDROID-DEVIATION: The trend is drawn with arrow icons, as on the home screen; the notification font draws
 * the arrow characters as a short dash. An unknown trend shows the "–" text.
 */
internal fun setTrendArrow(views: RemoteViews, s: GlucoseSnapshot, color: Int) {
    val icons = trendIcons(s.trend)
    views.setViewVisibility(R.id.la_arrow, if (icons.isEmpty()) View.VISIBLE else View.GONE)
    views.setTextViewText(R.id.la_arrow, LAFormat.trendArrow(s))
    views.setTextColor(R.id.la_arrow, color)
    listOf(R.id.la_arrow_img1, R.id.la_arrow_img2).forEachIndexed { i, id ->
        val icon = icons.getOrNull(i)
        views.setViewVisibility(id, if (icon == null) View.GONE else View.VISIBLE)
        if (icon != null) {
            views.setImageViewResource(id, icon)
            views.setInt(id, "setColorFilter", color)
        }
    }
}

/** The arrow icons for [trend]: one or two, or none for an unknown trend. */
internal fun trendIcons(trend: GlucoseSnapshot.Trend): List<Int> = when (trend) {
    GlucoseSnapshot.Trend.UP_FAST -> listOf(R.drawable.la_arrow_up, R.drawable.la_arrow_up)
    GlucoseSnapshot.Trend.UP -> listOf(R.drawable.la_arrow_up)
    GlucoseSnapshot.Trend.UP_SLIGHT -> listOf(R.drawable.la_arrow_ne)
    GlucoseSnapshot.Trend.FLAT -> listOf(R.drawable.la_arrow_forward)
    GlucoseSnapshot.Trend.DOWN_SLIGHT -> listOf(R.drawable.la_arrow_se)
    GlucoseSnapshot.Trend.DOWN -> listOf(R.drawable.la_arrow_down)
    GlucoseSnapshot.Trend.DOWN_FAST -> listOf(R.drawable.la_arrow_down, R.drawable.la_arrow_down)
    GlucoseSnapshot.Trend.UNKNOWN -> emptyList()
}
