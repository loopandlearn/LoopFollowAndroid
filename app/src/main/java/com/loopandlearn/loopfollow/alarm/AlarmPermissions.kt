package com.loopandlearn.loopfollow.alarm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.loopandlearn.loopfollow.engine.BannerManager
import com.loopandlearn.loopfollow.engine.BannerSeverity
import com.loopandlearn.loopfollow.engine.BannerSource
import com.loopandlearn.loopfollow.storage.Storage

/**
 * The system permissions an alarm needs to reach the user. The alarm sound plays without them; the notification
 * with its Snooze button needs notifications, and opening the Snoozer over the lock screen needs full-screen alerts.
 *
 * ANDROID-DEVIATION: Android lets the user turn off notifications (or just the Alarms channel) and, from Android 14,
 * full-screen alerts; Background Refresh and Alarm Settings show their state with a button to the system page, and
 * Home shows a one-time banner. iOS has no full-screen alert permission.
 *
 * ANDROID-DEVIATION: Android can pause a battery-optimized app in the background, which delays downloads and alarms.
 * Onboarding asks for unrestricted battery use, Background Refresh shows its state, and Home shows the same one-time
 * banner while it is not allowed. iOS has no such setting.
 */
object AlarmPermissions {
    data class State(val notifications: Boolean, val fullScreen: Boolean) {
        val allGranted: Boolean get() = notifications && fullScreen
    }

    fun state(context: Context) = State(notificationsAllowed(context), fullScreenAllowed(context))

    fun notificationsAllowed(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(AlarmNotifier.CHANNEL_ALARMS)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun fullScreenAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** The system dialog that asks to let LoopFollow run without battery restrictions. */
    fun batteryRequestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun fullScreenSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        } else {
            notificationSettingsIntent(context)
        }

    private var bannerShown = false

    /** Shows the Home banner once per app run while an enabled alarm cannot fully reach the user. */
    fun updateBanner(context: Context) {
        val state = state(context)
        val text = when {
            Storage.alarms.value.none { it.isEnabled } -> null
            !state.notifications ->
                "Notifications are off for LoopFollow, so alarms only play their sound. Turn them on in Alarm Settings (the gear on the Alarms tab)."
            !state.fullScreen ->
                "LoopFollow can't show alarms full screen over the lock screen. Allow it in Alarm Settings (the gear on the Alarms tab)."
            !batteryUnrestricted(context) ->
                "Alarms can be late. Allow unrestricted battery use in Settings > Background Refresh."
            else -> null
        }
        if (text == null) {
            BannerManager.clear(BannerSource.AlarmPermissions)
            return
        }
        if (bannerShown) return
        bannerShown = true
        BannerManager.report(BannerSource.AlarmPermissions, BannerSeverity.WARNING, text)
    }
}
