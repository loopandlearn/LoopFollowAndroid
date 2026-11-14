package com.loopandlearn.loopfollow.info

import com.loopandlearn.loopfollow.helpers.Localizer
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import com.loopandlearn.loopfollow.nightscout.double

open class Metric(val value: Double, val maxFractionDigits: Int, val minFractionDigits: Int) {
    open fun formattedValue(): String = Localizer.formatToLocalizedString(value, maxFractionDigits, minFractionDigits)
}

class InsulinMetric(value: Double) : Metric(value, 2, 0) {
    override fun formattedValue(): String {
        val decimals = if (abs(value) >= 10) 0 else 1
        return Localizer.formatToLocalizedString(value, decimals, 0)
    }

    companion object {
        fun from(element: JsonElement?, key: String): InsulinMetric? =
            (element as? JsonObject)?.double(key)?.let { InsulinMetric(it) }
    }
}

class CarbMetric(value: Double) : Metric(value, 0, 0) {
    companion object {
        fun from(element: JsonElement?, key: String): CarbMetric? =
            (element as? JsonObject)?.double(key)?.let { CarbMetric(it) }
    }
}

class InfoManager {
    private val state = MutableStateFlow(InfoType.entries.map { InfoData(it) })
    val tableData: StateFlow<List<InfoData>> = state.asStateFlow()

    fun update(type: InfoType, value: String, numericValue: Double? = null) {
        state.value = state.value.toMutableList().also {
            it[type.ordinal] = InfoData(type, value, numericValue)
        }
    }

    /** A glucose value in mg/dL, shown in the preferred unit. */
    fun updateGlucose(type: InfoType, mgdl: Double) {
        update(type, Localizer.formatQuantity(mgdl), com.loopandlearn.loopfollow.helpers.UnitSettingsStore.convertMgdlToDisplay(mgdl))
    }

    fun updateGlucose(type: InfoType, firstMgdl: Double, secondMgdl: Double, separator: InfoDataSeparator) {
        val first = Localizer.formatQuantity(firstMgdl)
        val second = Localizer.formatQuantity(secondMgdl)
        if (first != second) {
            update(type, "$first ${separator.raw} $second")
        } else {
            update(type, first)
        }
    }

    fun update(type: InfoType, value: Double, maxFractionDigits: Int = 1, minFractionDigits: Int = 0) {
        update(type, Localizer.formatToLocalizedString(value, maxFractionDigits, minFractionDigits), value)
    }

    fun update(
        type: InfoType,
        value: Double,
        enactedValue: Double,
        separator: InfoDataSeparator,
        maxFractionDigits: Int = 1,
        minFractionDigits: Int = 0,
    ) {
        val a = Localizer.formatToLocalizedString(value, maxFractionDigits, minFractionDigits)
        val b = Localizer.formatToLocalizedString(enactedValue, maxFractionDigits, minFractionDigits)
        update(type, "$a ${separator.raw} $b")
    }

    fun update(type: InfoType, metric: Metric) {
        update(type, metric.formattedValue(), metric.value)
    }

    fun clear(type: InfoType) = clear(listOf(type))

    fun clear(types: List<InfoType>) {
        state.value = state.value.toMutableList().also { list ->
            for (type in types) list[type.ordinal] = InfoData(type)
        }
    }

    fun value(type: InfoType): InfoData = state.value[type.ordinal]
}
