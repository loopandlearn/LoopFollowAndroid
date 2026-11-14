package com.loopandlearn.loopfollow.helpers

import android.content.Context
import android.os.Build
import android.util.Log
import com.loopandlearn.loopfollow.BuildConfig
import com.loopandlearn.loopfollow.storage.Storage
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object LogManager {
    enum class Category(val raw: String) {
        NIGHTSCOUT("Nightscout"),
        APNS("APNS"),
        GENERAL("General"),
        TASK_SCHEDULER("Task Scheduler"),
        DEXCOM("Dexcom"),
        ALARM("Alarm"),
        VOLUME_BUTTON_SNOOZE("Volume Button Snooze"),
        DEVICE_STATUS("Device Status"),
        REMOTE("Remote"),
        WEBSOCKET("WebSocket"),
        TELEMETRY("Telemetry"),
    }

    private lateinit var logDirectory: File
    private val writer = Executors.newSingleThreadExecutor()
    private val lastLoggedTimestamps = HashMap<String, Long>()
    private var shouldLogVersionHeader = true
    private val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun init(context: Context) {
        logDirectory = File(context.filesDir, "Logs").apply { mkdirs() }
    }

    private fun formatted(category: Category, message: String): String {
        val timestamp = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date())
        return "[$timestamp] [${category.raw}] $message"
    }

    /** Logs a message with an optional rate limit keyed by [limitIdentifier]. */
    fun log(
        category: Category,
        message: String,
        isDebug: Boolean = false,
        limitIdentifier: String? = null,
        limitInterval: Double = 300.0,
    ) {
        val line = formatted(category, message)
        if (BuildConfig.DEBUG) Log.d("LoopFollow", line)

        if (category == Category.TASK_SCHEDULER && isDebug) return
        if (!::logDirectory.isInitialized) return

        if (limitIdentifier != null && !Storage.debugLogLevel.value) {
            synchronized(lastLoggedTimestamps) {
                val now = System.currentTimeMillis()
                val last = lastLoggedTimestamps[limitIdentifier]
                if (last != null && (now - last) / 1000.0 < limitInterval) return
                lastLoggedTimestamps[limitIdentifier] = now
            }
        }

        if (!isDebug || Storage.debugLogLevel.value) {
            writer.execute {
                val file = currentLogFile
                if (shouldLogVersionHeader || !file.exists() || file.length() == 0L) {
                    val header = listOf(
                        "LoopFollow Version: ${BuildConfig.VERSION_NAME}",
                        "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        "Device: ${Build.MANUFACTURER} ${Build.MODEL}",
                    ).joinToString(", ")
                    file.appendText(formatted(Category.GENERAL, header) + "\n")
                    shouldLogVersionHeader = false
                }
                file.appendText(line + "\n")
            }
        }
    }

    fun cleanupOldLogs() {
        if (!::logDirectory.isInitialized) return
        val today = day(Date())
        val yesterday = day(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time)
        logDirectory.listFiles()?.forEach { file ->
            if (!file.name.contains(today) && !file.name.contains(yesterday)) file.delete()
        }
    }

    fun logFile(date: Date): File = File(logDirectory, "LoopFollow ${day(date)}.log")

    /** SimpleDateFormat is not thread-safe; the writer thread and callers share this one. */
    private fun day(date: Date): String = synchronized(dayFormatter) { dayFormatter.format(date) }

    fun logFilesForTodayAndYesterday(): List<File> {
        val today = logFile(Date())
        val yesterday = logFile(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time)
        return listOf(today, yesterday).filter { it.exists() }
    }

    val currentLogFile: File get() = logFile(Date())
}
