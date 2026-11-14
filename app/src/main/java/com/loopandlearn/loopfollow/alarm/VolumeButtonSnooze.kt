package com.loopandlearn.loopfollow.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.loopandlearn.loopfollow.LoopFollowApplication
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Snoozes the sounding alarm when a volume button is pressed, while "Volume Buttons Snooze Alarms" is on.
 * Presses are seen as key events while the app is in front and as volume changes otherwise.
 */
// ANDROID-DEVIATION: a press is detected from volume key events and the system volume-changed broadcast; the
// broadcast is the only signal a background app gets for a volume button press.
object VolumeButtonSnooze {
    private const val ACTIVATION_DELAY_MS = 900L
    private const val COOLDOWN_MS = 500L
    private const val OWN_CHANGE_GRACE_MS = 1000L
    private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

    private var alarmStartedAt: Long? = null
    private var lastSnoozeAt = 0L
    private var ownVolumeChangeAt = 0L
    private var registered = false

    private val context: Context get() = LoopFollowApplication.instance

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (SystemClock.elapsedRealtime() - ownVolumeChangeAt < OWN_CHANGE_GRACE_MS) return
            onPress()
        }
    }

    fun start() {
        Observable.alarmSoundPlaying.flow.onEach { playing ->
            if (playing) alarmStarted() else alarmStopped()
        }.launchIn(MainScope())
    }

    /** Marks a volume change LoopFollow makes itself, so it is not taken for a button press. */
    fun noteOwnVolumeChange() {
        ownVolumeChangeAt = SystemClock.elapsedRealtime()
    }

    /** Handles a volume key press from the foreground activity; true when it snoozed the alarm. */
    fun onVolumeKey(): Boolean {
        if (alarmStartedAt == null) return false
        return onPress()
    }

    private fun onPress(): Boolean {
        val startedAt = alarmStartedAt ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now - startedAt <= ACTIVATION_DELAY_MS) return false
        if (now - lastSnoozeAt < COOLDOWN_MS) return true
        lastSnoozeAt = now
        LogManager.log(LogManager.Category.VOLUME_BUTTON_SNOOZE, "Snoozing alarm")
        AlarmManager.performSnooze()
        return true
    }

    private fun alarmStarted() {
        if (!Storage.alarmConfiguration.value.enableVolumeButtonSnooze) return
        LogManager.log(LogManager.Category.VOLUME_BUTTON_SNOOZE, "Alarm start detected, listening for volume buttons.")
        alarmStartedAt = SystemClock.elapsedRealtime()
        if (!registered) {
            context.registerReceiver(receiver, IntentFilter(VOLUME_CHANGED_ACTION), Context.RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }

    private fun alarmStopped() {
        alarmStartedAt = null
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }
}
