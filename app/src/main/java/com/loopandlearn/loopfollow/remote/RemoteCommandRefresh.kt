package com.loopandlearn.loopfollow.remote

import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.engine.TaskID
import com.loopandlearn.loopfollow.engine.TaskScheduler
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.remote.loop.LoopProfiles
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.ObservableValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** After a command is sent, fetches Nightscout early until the change shows up. */
object RemoteCommandRefresh {
    sealed class Change(val name: String) {
        data object Override : Change("override")
        data object TempTarget : Change("temp target")
        data class Bolus(val units: Double) : Change("bolus")
        data class Meal(val grams: Double, val bolus: Double) : Change("meal")

        // ANDROID-DEVIATION: Edits, deletes and profile switches are followed by early Nightscout fetches too;
        // Android cannot receive the AID app's confirmation push, so Nightscout is where their outcome shows.
        data object CarbEntry : Change("carb entry change")
        data class ProfileSwitch(val profileName: String) : Change("profile switch")
    }

    /** Counts carb entry edits and deletes whose early fetches have finished, so the Treatments list can reload. */
    val carbEntryRefreshes = ObservableValue(0)

    private val delays = listOf(10.0, 20.0, 30.0, 60.0, 90.0, 120.0)
    private var pending: Job? = null

    fun commandSent(change: Change) {
        pending?.cancel()
        LogManager.log(LogManager.Category.APNS, "Fetching Nightscout early until the ${change.name} shows up")
        val arrived = arrivalCheck(change)
        pending = MainEngine.scope.launch {
            var previous = 0.0
            for ((step, at) in delays.withIndex()) {
                delay(((at - previous) * 1000).toLong())
                previous = at
                if (arrived()) {
                    LogManager.log(LogManager.Category.APNS, "The ${change.name} showed up; ${delays.size - step} early fetches skipped")
                    break
                }
                val now = System.currentTimeMillis()
                if (change is Change.ProfileSwitch) TaskScheduler.rescheduleTask(TaskID.PROFILE, now)
                TaskScheduler.rescheduleTask(TaskID.TREATMENTS, now)
                TaskScheduler.rescheduleTask(TaskID.DEVICE_STATUS, now)
            }
            if (change == Change.CarbEntry) carbEntryRefreshes.value += 1
        }
    }

    private fun arrivalCheck(change: Change): () -> Boolean = when (change) {
        Change.Override -> {
            val before = Observable.override.value to Observable.overrideEndAt.value
            { (Observable.override.value to Observable.overrideEndAt.value) != before }
        }
        Change.TempTarget -> {
            val before = Observable.tempTarget.value to Observable.tempTargetEndAt.value
            { (Observable.tempTarget.value to Observable.tempTargetEndAt.value) != before }
        }
        is Change.Bolus -> newEntry(::boluses, change.units, 0.01)
        is Change.Meal -> {
            val carbsArrived = newEntry(::carbs, if (change.grams > 0) change.grams else null, 0.5)
            if (change.bolus > 0) {
                val bolusArrived = newEntry(::boluses, change.bolus, 0.01)
                ({ carbsArrived() && bolusArrived() })
            } else {
                carbsArrived
            }
        }
        Change.CarbEntry -> {
            val before = carbs().toSet()
            ({ carbs().toSet() != before })
        }
        is Change.ProfileSwitch -> ({ LoopProfiles.current.value?.activeName == change.profileName })
    }

    private fun newEntry(entries: () -> List<Pair<Double, Double>>, value: Double?, tolerance: Double): () -> Boolean {
        val known = entries().map { it.first }.toSet()
        return { entries().any { (date, v) -> date !in known && (value == null || abs(v - value) < tolerance) } }
    }

    private fun boluses() = (MainEngine.bolusData + MainEngine.smbData).map { it.date to it.value }

    private fun carbs() = MainEngine.carbData.map { it.date to it.value }
}
