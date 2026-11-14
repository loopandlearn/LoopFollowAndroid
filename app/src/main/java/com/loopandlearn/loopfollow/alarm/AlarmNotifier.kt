package com.loopandlearn.loopfollow.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.loopandlearn.loopfollow.LoopFollowApplication
import com.loopandlearn.loopfollow.R
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.ui.MainActivity

/** Alarm notifications, with a Snooze/Acknowledge action, and informational notifications. */
object AlarmNotifier {
    const val CHANNEL_ALARMS = "alarms"
    const val CHANNEL_INFO = "info"
    private const val ALARM_NOTIFICATION_ID = 1001
    private const val INFO_NOTIFICATION_ID = 1002
    const val ACTION_SNOOZE = "com.loopandlearn.loopfollow.SNOOZE"

    private val context: Context get() = LoopFollowApplication.instance

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // The app plays the alarm sound itself, so the channel stays silent.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARMS, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(true)
                setBypassDnd(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INFO, "Information", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /**
     * Title, with the current BG, direction and delta as the subtitle line. An urgent notification is the alarm
     * notification; any other is posted separately, so the active alarm keeps its Snooze/Acknowledge action.
     */
    fun send(title: String, body: String? = null, actionTitle: String? = null, urgent: Boolean = false) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val subtitle = "${Observable.bgText.value} ${Observable.directionText.value} ${Observable.deltaText.value}"
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, if (urgent) CHANNEL_ALARMS else CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setSubText(subtitle)
            .setContentText(body ?: subtitle)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
        if (urgent) builder.setFullScreenIntent(open, true)
        if (actionTitle != null) {
            val snooze = PendingIntent.getBroadcast(
                context, 1, Intent(context, SnoozeReceiver::class.java).setAction(ACTION_SNOOZE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, actionTitle, snooze)
        }
        NotificationManagerCompat.from(context).notify(if (urgent) ALARM_NOTIFICATION_ID else INFO_NOTIFICATION_ID, builder.build())
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(ALARM_NOTIFICATION_ID)
    }
}

/** Handles the notification's Snooze/Acknowledge action. */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmNotifier.ACTION_SNOOZE) return
        AlarmManager.performSnooze()
        // A notification that outlived its alarm (the process restarted) has nothing to snooze and is just removed.
        AlarmNotifier.cancel()
    }
}
