package com.loopandlearn.loopfollow

import android.app.Application
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.TelemetryClient
import com.loopandlearn.loopfollow.storage.Prefs

class LoopFollowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        LogManager.init(this)
        LogManager.cleanupOldLogs()
        TelemetryClient.start()
        com.loopandlearn.loopfollow.alarm.VolumeButtonSnooze.start()
    }

    companion object {
        lateinit var instance: LoopFollowApplication
            private set
    }
}
