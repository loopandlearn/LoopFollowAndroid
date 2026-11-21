package com.loopandlearn.loopfollow.dream

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.loopandlearn.loopfollow.R
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.liveactivity.GlucoseSnapshot
import com.loopandlearn.loopfollow.liveactivity.LAColors
import com.loopandlearn.loopfollow.liveactivity.LAFormat
import com.loopandlearn.loopfollow.liveactivity.LiveActivityNotification
import com.loopandlearn.loopfollow.liveactivity.trendIcons
import kotlin.random.Random

/**
 * A screensaver with the time, glucose in the range color, trend, delta and the reading's age. It redraws every
 * few seconds from the values the background service keeps current, and moves a little each minute so the
 * screen does not burn in.
 */
// ANDROID-DEVIATION: iOS has no screensaver for apps. Android shows a chosen screensaver while the phone charges.
class GlucoseDream : DreamService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastShift = 0L

    private val tick = object : Runnable {
        override fun run() {
            redraw()
            handler.postDelayed(this, REDRAW_MILLIS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(R.layout.dream_glucose)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        handler.post(tick)
    }

    override fun onDreamingStopped() {
        handler.removeCallbacks(tick)
        super.onDreamingStopped()
    }

    private fun redraw() {
        val bg = findViewById<TextView>(R.id.dream_bg) ?: return
        val arrow = findViewById<TextView>(R.id.dream_arrow)
        val images = listOf(findViewById<ImageView>(R.id.dream_arrow_img1), findViewById(R.id.dream_arrow_img2))
        val delta = findViewById<TextView>(R.id.dream_delta)
        val age = findViewById<TextView>(R.id.dream_age)
        val status = findViewById<TextView>(R.id.dream_status)

        val s = GlucoseSnapshot.current()
        if (s == null) {
            bg.text = "—"
            bg.setTextColor(android.graphics.Color.WHITE)
            arrow.visibility = View.GONE
            images.forEach { it.visibility = View.GONE }
            delta.text = ""
            age.text = "Waiting for glucose data"
            status.visibility = View.GONE
        } else {
            val color = LAColors.keyline(s)
            bg.text = LiveActivityNotification.glucoseText(s)
            bg.setTextColor(color)
            val icons = trendIcons(s.trend)
            arrow.visibility = if (icons.isEmpty()) View.VISIBLE else View.GONE
            arrow.text = LAFormat.trendArrow(s)
            arrow.setTextColor(color)
            images.forEachIndexed { i, view ->
                val icon = icons.getOrNull(i)
                view.visibility = if (icon == null) View.GONE else View.VISIBLE
                if (icon != null) {
                    view.setImageResource(icon)
                    view.setColorFilter(color)
                }
            }
            delta.text = "${LAFormat.delta(s)} ${s.unit.raw}"
            age.text = ageText(nowSeconds() - s.updatedAt)
            val statusText = if (s.isNotLooping) "⚠️ Not Looping" else s.cgmSensorState?.let { "CGM: $it" }
            status.visibility = if (statusText != null) View.VISIBLE else View.GONE
            status.text = statusText ?: ""
        }
        shiftIfDue()
    }

    private fun shiftIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastShift < SHIFT_MILLIS) return
        lastShift = now
        val content = findViewById<View>(R.id.dream_content) ?: return
        val range = SHIFT_DP * resources.displayMetrics.density
        content.translationX = Random.nextFloat() * 2 * range - range
        content.translationY = Random.nextFloat() * 2 * range - range
    }

    private fun ageText(seconds: Double): String {
        val minutes = (seconds / 60).toInt()
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            else -> "${minutes / 60} h ${minutes % 60} min ago"
        }
    }

    private companion object {
        const val REDRAW_MILLIS = 10_000L
        const val SHIFT_MILLIS = 60_000L
        const val SHIFT_DP = 24
    }
}
