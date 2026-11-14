package com.loopandlearn.loopfollow.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.loopandlearn.loopfollow.LoopFollowApplication
import com.loopandlearn.loopfollow.R
import com.loopandlearn.loopfollow.alarm.AlarmNotifier
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.engine.TaskScheduler
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.liveactivity.LiveActivityNotification
import com.loopandlearn.loopfollow.storage.BackgroundRefreshType
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * Keeps the data pipeline and alarms running while the app is in the background,
 * and shows the latest glucose in its ongoing notification.
 */
// ANDROID-DEVIATION: A foreground service with an ongoing notification keeps downloads and alarms running in the
// background; iOS uses background audio and refresh.
class LoopFollowService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, ONGOING_ID, buildOngoing(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LoopFollow::Pipeline")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        MainEngine.bootstrap()
        OngoingNotification.observe()
        LogManager.log(LogManager.Category.GENERAL, "Background service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TaskScheduler.checkTasksNow()
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        Watchdog.cancel(this)
        OngoingNotification.stopObserving()
        LogManager.log(LogManager.Category.GENERAL, "Background service stopped")
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ONGOING = "ongoing"
        const val ONGOING_ID = 1

        fun start(context: Context) {
            if (Storage.backgroundRefreshType.value == BackgroundRefreshType.NONE) return
            createChannel(context)
            // The system refuses a foreground service start from the background unless the app is exempt
            // (boot, battery optimization off); the next launch starts it.
            try {
                ContextCompat.startForegroundService(context, Intent(context, LoopFollowService::class.java))
            } catch (e: IllegalStateException) {
                LogManager.log(LogManager.Category.GENERAL, "Background service not started: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LoopFollowService::class.java))
        }

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, "Glucose", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
            AlarmNotifier.createChannels(context)
        }

        /** The ongoing notification: the Live Activity card, or a minimal notification while it is off. */
        fun buildOngoing(context: Context): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(open)
            // ANDROID-DEVIATION: with the Live Activity off the notification stays, with minimal content; the
            // foreground service that keeps alarms running requires it.
            if (!Storage.laEnabled.value || !LiveActivityNotification.apply(context, builder)) {
                LiveActivityNotification.applyMinimal(context, builder)
            }
            return builder.build()
        }
    }
}

/** Refreshes the ongoing notification from the current values. */
object OngoingNotification {
    private val handler = Handler(Looper.getMainLooper())
    private val scheduledRefresh = Runnable { refresh() }
    private var scope: CoroutineScope? = null

    fun refresh() {
        val context = LoopFollowApplication.instance
        if (Storage.backgroundRefreshType.value == BackgroundRefreshType.NONE) return
        runCatching {
            NotificationManagerCompat.from(context).notify(LoopFollowService.ONGOING_ID, LoopFollowService.buildOngoing(context))
        }
        handler.removeCallbacks(scheduledRefresh)
        LiveActivityNotification.nextAdjustmentChange()?.let { at ->
            handler.postDelayed(scheduledRefresh, ((at - nowSeconds()) * 1000).toLong().coerceAtLeast(0) + 1000)
        }
    }

    /** Refreshes on changes to settings and state the card shows that have no refresh of their own. */
    @OptIn(FlowPreview::class)
    fun observe() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also {
            merge(
                Storage.laEnabled.flow, Storage.laSlots.flow, Storage.units.flow, Storage.timeInRangeModeRaw.flow,
                Storage.lowLine.flow, Storage.highLine.flow, Storage.showDisplayName.flow, Observable.bgStale.flow,
            ).debounce(300).onEach { refresh() }.launchIn(it)
        }
    }

    fun stopObserving() {
        scope?.cancel()
        scope = null
        handler.removeCallbacks(scheduledRefresh)
    }
}

/**
 * A system alarm a few minutes ahead, pushed back on every scheduler tick. It only
 * goes off when the pipeline has stopped ticking: it restarts the service and tells
 * the user the app was inactive.
 */
object Watchdog {
    private const val INACTIVE_MINUTES = 6
    private var lastScheduled = 0L

    fun reschedule(context: Context) {
        if (Storage.backgroundRefreshType.value == BackgroundRefreshType.NONE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastScheduled < 10_000) return
        lastScheduled = now
        val am = context.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + INACTIVE_MINUTES * 60_000L, pending(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context))
    }

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 7, Intent(context, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    class WatchdogReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            LogManager.log(LogManager.Category.GENERAL, "Watchdog: no scheduler tick for $INACTIVE_MINUTES minutes")
            AlarmNotifier.send("LoopFollow Background Refresh", "App inactive for $INACTIVE_MINUTES minutes. Open to resume.")
            LoopFollowService.start(context)
        }
    }
}

/** Starts the service after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Storage.url.value.isNotEmpty() || Storage.shareUserName.value.isNotEmpty()) LoopFollowService.start(context)
    }
}
