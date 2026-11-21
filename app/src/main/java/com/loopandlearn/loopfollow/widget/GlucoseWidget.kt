package com.loopandlearn.loopfollow.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.loopandlearn.loopfollow.ui.MainActivity
import com.loopandlearn.loopfollow.R
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.liveactivity.GlucoseSnapshot
import com.loopandlearn.loopfollow.liveactivity.LAColors
import com.loopandlearn.loopfollow.liveactivity.LAFormat
import com.loopandlearn.loopfollow.liveactivity.LiveActivityNotification
import com.loopandlearn.loopfollow.liveactivity.setTrendArrow

/**
 * Glucose, trend, delta and the reading's age on the home or lock screen. The background service redraws it with
 * each refresh of the ongoing notification; the age keeps counting on its own between redraws.
 */
// ANDROID-DEVIATION: iOS has no LoopFollow widget. Android app widgets have no refresh budget, so the widget is
// redrawn on each reading.
class GlucoseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {
        /** Redraws every placed widget from the current values. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, GlucoseWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_glucose)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)

            val s = GlucoseSnapshot.current()
            if (s == null) {
                views.setTextViewText(R.id.la_bg, "—")
                views.setTextViewText(R.id.la_delta, "")
                views.setViewVisibility(R.id.la_arrow, View.GONE)
                views.setViewVisibility(R.id.la_arrow_img1, View.GONE)
                views.setViewVisibility(R.id.la_arrow_img2, View.GONE)
                views.setViewVisibility(R.id.widget_age, View.GONE)
                views.setViewVisibility(R.id.widget_status, View.VISIBLE)
                views.setTextViewText(R.id.widget_status, "Waiting for glucose data")
                return views
            }

            val tint = if (s.isNotLooping) LAColors.NOT_LOOPING else LAColors.backgroundTint(s)
            views.setColorStateList(R.id.widget_bg, "setImageTintList", ColorStateList.valueOf(tint))
            views.setTextViewText(R.id.la_bg, LiveActivityNotification.glucoseText(s))
            setTrendArrow(views, s, android.graphics.Color.WHITE)
            views.setTextViewText(R.id.la_delta, LAFormat.delta(s))

            val ageMillis = ((nowSeconds() - s.updatedAt) * 1000).toLong().coerceAtLeast(0)
            views.setViewVisibility(R.id.widget_age, View.VISIBLE)
            views.setChronometer(R.id.widget_age, SystemClock.elapsedRealtime() - ageMillis, "%s ago", true)

            val status = if (s.isNotLooping) "⚠️ Not Looping" else s.cgmSensorState?.let { "CGM: $it" }
            views.setViewVisibility(R.id.widget_status, if (status != null) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_status, status ?: "")
            return views
        }
    }
}
