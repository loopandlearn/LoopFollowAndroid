package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.helpers.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TaskID {
    PROFILE,
    DEVICE_STATUS,
    TREATMENTS,
    FETCH_BG,
    MIN_AGO_UPDATE,
    ALARM_CHECK,
    TELEMETRY,
    DB_SIZE,
    LOG_CLEANUP,
}

/**
 * Runs each task at its next run time. A task is parked once it fires and its
 * action reschedules it. Everything runs on the main thread.
 */
object TaskScheduler {
    private class ScheduledTask(var nextRun: Long, val action: () -> Unit)

    private const val PARKED = Long.MAX_VALUE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tasks = HashMap<TaskID, ScheduledTask>()
    private var timer: Job? = null

    /** Called on every scheduler tick, before tasks fire. */
    var onTick: (() -> Unit)? = null

    fun scheduleTask(id: TaskID, nextRunMillis: Long, action: () -> Unit) {
        scope.launch {
            tasks[id] = ScheduledTask(nextRunMillis, action)
            rescheduleTimer()
        }
    }

    fun rescheduleTask(id: TaskID, toMillis: Long) {
        scope.launch {
            val task = tasks[id] ?: return@launch
            task.nextRun = toMillis
            fireOverdueTasks()
            rescheduleTimer()
        }
    }

    fun rescheduleTaskIn(id: TaskID, seconds: Double) =
        rescheduleTask(id, System.currentTimeMillis() + (seconds * 1000).toLong())

    fun checkTasksNow() {
        scope.launch {
            fireOverdueTasks()
            rescheduleTimer()
        }
    }

    private fun rescheduleTimer() {
        timer?.cancel()
        timer = null
        val earliest = tasks.values.minByOrNull { it.nextRun } ?: return
        if (earliest.nextRun == PARKED) return
        val interval = (earliest.nextRun - System.currentTimeMillis()).coerceAtLeast(0)
        timer = scope.launch {
            delay(interval)
            fireOverdueTasks()
            rescheduleTimer()
        }
    }

    private fun fireOverdueTasks() {
        onTick?.invoke()
        val now = System.currentTimeMillis()
        for (id in TaskID.entries) {
            val task = tasks[id] ?: continue
            if (task.nextRun > now) continue
            task.nextRun = PARKED
            scope.launch {
                try {
                    task.action()
                } catch (e: Exception) {
                    LogManager.log(LogManager.Category.TASK_SCHEDULER, "Task $id failed: ${e.message}")
                    task.nextRun = System.currentTimeMillis() + 60_000
                    rescheduleTimer()
                }
            }
        }
    }
}
