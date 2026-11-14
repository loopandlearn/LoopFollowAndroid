package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.info.InfoType
import com.loopandlearn.loopfollow.nightscout.EventType
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.nightscout.double
import com.loopandlearn.loopfollow.nightscout.int
import com.loopandlearn.loopfollow.nightscout.obj
import com.loopandlearn.loopfollow.storage.Observable
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Nightscout recomputes the database size on every data load, but it moves slowly, so a slow poll is enough. */
private const val DB_SIZE_INTERVAL = 6 * 60 * 60.0

fun MainEngine.scheduleDBSizeTask(initialDelay: Double = 5.0) {
    TaskScheduler.scheduleTask(TaskID.DB_SIZE, millisFromNow(initialDelay)) { dbSizeTaskAction() }
}

private fun MainEngine.dbSizeTaskAction() {
    if (!isNightscoutEnabled()) {
        TaskScheduler.rescheduleTaskIn(TaskID.DB_SIZE, 60.0)
        return
    }
    scope.launch {
        try {
            val json = NightscoutUtils.executeRequest(EventType.DB_SIZE, emptyMap()) as? JsonObject
            updateDBSize(json?.obj("dbsize"))
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.NIGHTSCOUT, "webLoadNSDBSize, error: ${e.message}")
        }
    }
    TaskScheduler.rescheduleTaskIn(TaskID.DB_SIZE, DB_SIZE_INTERVAL)
}

private fun MainEngine.updateDBSize(data: JsonObject?) {
    val usedMiB = data?.double("totalDataSize")
    if (data == null || usedMiB == null || usedMiB <= 0) {
        infoManager.clear(InfoType.DB_SIZE)
        Observable.dbSizePercentage.value = null
        return
    }
    val percentage = data.int("dataPercentage")
    Observable.dbSizePercentage.value = percentage?.toDouble()
    val used = Localizer.formatToLocalizedString(usedMiB, 0, 0)
    infoManager.update(InfoType.DB_SIZE, if (percentage != null) "$used MiB ($percentage%)" else "$used MiB")
}
