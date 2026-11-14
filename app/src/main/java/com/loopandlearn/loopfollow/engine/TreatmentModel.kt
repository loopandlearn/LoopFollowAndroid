package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.helpers.DateTimeUtils
import com.loopandlearn.loopfollow.nightscout.bool
import com.loopandlearn.loopfollow.nightscout.double
import com.loopandlearn.loopfollow.nightscout.string
import com.loopandlearn.loopfollow.storage.GlucoseConversion
import com.loopandlearn.loopfollow.storage.RemoteType
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.Calendar
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

enum class TreatmentType(val displayName: String) {
    CARB("Carbs"),
    BOLUS_MANUAL("Bolus"),
    BOLUS_AUTOMATIC("Automatic"),
    SMB("SMB"),
    TEMP_BASAL("Basal"),
    OVERRIDE("Override"),
    TEMP_TARGET("Temp Target"),
}

/** The symbol a treatment row shows. */
enum class TreatmentIcon { DOT, TRIANGLE_DOWN, CHART, SLIDERS, SCOPE }

enum class TreatmentColor { ORANGE, BLUE, PURPLE, GREEN }

/** Trio meal metadata from a Nightscout "Carb Correction" treatment. */
data class TrioMealTreatment(
    val nightscoutID: String?,
    val mealID: String,
    val fpuID: String?,
    val date: Double,
    val carbs: Double,
    val fat: Int,
    val protein: Int,
    val note: String?,
    val isFPUChild: Boolean,
) {
    val carbsForEdit: Int get() = carbs.roundToInt()

    fun isWithinEditWindow(now: Double): Boolean {
        val age = now - date
        return age <= PAST_EDIT_WINDOW && age >= -FUTURE_EDIT_WINDOW
    }

    companion object {
        const val PAST_EDIT_WINDOW = 24 * 3600.0
        const val FUTURE_EDIT_WINDOW = 12 * 3600.0
        val REQUIRED_REMOTE_COMMANDS = setOf("edit_meal", "delete_meal")

        private fun number(entry: JsonObject, key: String): Double? {
            val p = entry[key] as? JsonPrimitive ?: return null
            return if (p.isString) p.content.toDoubleOrNull() else p.doubleOrNull
        }

        private fun uuid(raw: String?): String? = raw?.let { runCatching { UUID.fromString(it).toString().uppercase() }.getOrNull() }

        fun from(entry: JsonObject, date: Double): TrioMealTreatment? {
            if (entry.string("enteredBy") != "Trio" || entry.string("eventType") != "Carb Correction") return null
            val mealID = uuid(entry.string("id")) ?: return null
            val carbs = number(entry, "carbs") ?: 0.0
            val fat = (number(entry, "fat") ?: 0.0).roundToInt()
            val protein = (number(entry, "protein") ?: 0.0).roundToInt()
            if (!(carbs > 0 || fat > 0 || protein > 0)) return null
            val fpuID = uuid(entry.string("fpuID"))
            val note = (entry.string("notes") ?: entry.string("foodType"))?.trim()?.ifEmpty { null }
            return TrioMealTreatment(entry.string("_id"), mealID, fpuID, date, carbs, fat, protein, note, fpuID == mealID)
        }

        fun remoteControlActive(remoteType: RemoteType, device: String) = remoteType == RemoteType.TRC && device == "Trio"

        fun remoteActionsAvailable(remoteType: RemoteType, device: String, remoteCommands: List<String>) =
            remoteControlActive(remoteType, device) && remoteCommands.containsAll(REQUIRED_REMOTE_COMMANDS)
    }
}

/** Loop carb entry metadata; syncIdentifier addresses it in remote commands. */
data class LoopCarbTreatment(
    val nightscoutID: String?,
    val syncIdentifier: String,
    val enteredBy: String,
    val date: Double,
    val carbs: Double,
    val absorptionMinutes: Double?,
    val foodType: String?,
) {
    val absorptionHours: Double? get() = absorptionMinutes?.let { it / 60 }

    fun isWithinEditWindow(now: Double, maxFutureHours: Int = Storage.loopMaxFutureCarbHours.value): Boolean {
        val age = now - date
        return age <= EDIT_WINDOW && age >= -maxFutureHours * 3600.0
    }

    companion object {
        const val EDIT_WINDOW = 23 * 3600.0
        val REQUIRED_REMOTE_COMMANDS = setOf("carbs-delete", "carbs-edit")

        fun from(entry: JsonObject, date: Double): LoopCarbTreatment? {
            val enteredBy = entry.string("enteredBy") ?: return null
            if (!enteredBy.startsWith("loop://") || entry.string("eventType") != "Carb Correction") return null
            val sync = entry.string("syncIdentifier")?.takeIf { it.isNotEmpty() } ?: return null
            val carbs = entry.double("carbs")?.takeIf { it > 0 } ?: return null
            return LoopCarbTreatment(
                entry.string("_id"), sync, enteredBy, date, carbs, entry.double("absorptionTime"),
                entry.string("foodType")?.trim()?.ifEmpty { null },
            )
        }

        fun remoteControlActive(remoteType: RemoteType, device: String) = remoteType == RemoteType.LOOP_APNS && device == "Loop"

        fun remoteActionsAvailable(remoteType: RemoteType, device: String, remoteCommands: List<String>) =
            remoteControlActive(remoteType, device) && remoteCommands.containsAll(REQUIRED_REMOTE_COMMANDS)
    }
}

/** What the Treatments list shows for one Nightscout treatment. */
data class TreatmentHistoryEntries(
    val entries: List<Treatment> = emptyList(),
    val sawSMB: Boolean = false,
    val sawAutomatic: Boolean = false,
) {
    val carb: Treatment? get() = entries.firstOrNull { it.type == TreatmentType.CARB }
    val bolus: Treatment? get() = entries.firstOrNull { it.type in setOf(TreatmentType.BOLUS_MANUAL, TreatmentType.BOLUS_AUTOMATIC, TreatmentType.SMB) }
}

/** A glucose value in mg/dL as "120 mg/dL" or "6.7 mmol/L". */
fun formatTreatmentBG(mgdl: Double): String =
    if (Storage.units.value == "mg/dL") "${mgdl.roundToInt()} mg/dL" else cFormat(mgdl * GlucoseConversion.MGDL_TO_MMOL, 1) + " mmol/L"

data class Treatment(
    val id: String,
    val type: TreatmentType,
    val date: Double,
    val title: String,
    val subtitle: String?,
    val icon: TreatmentIcon,
    val color: TreatmentColor,
    val bgValue: Int,
    val trioMeal: TrioMealTreatment? = null,
    val loopCarb: LoopCarbTreatment? = null,
) {
    /** "yyyy-M-d-H" in the display time zone. */
    val hourKey: String
        get() {
            val c = Calendar.getInstance(DateTimeUtils.displayTimeZone()).apply { timeInMillis = (date * 1000).toLong() }
            return "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH) + 1}-${c.get(Calendar.DAY_OF_MONTH)}-${c.get(Calendar.HOUR_OF_DAY)}"
        }

    /** The root meal for an FPU child, when [roots] holds it. */
    fun rootMeal(roots: Map<String, Treatment>): Treatment? {
        val child = trioMeal ?: return null
        if (!child.isFPUChild) return null
        return roots[child.fpuID ?: return null]
    }

    companion object {
        private fun u(insulin: Double) = cFormat(insulin, 2) + " U"

        fun carb(nightscoutID: String?, date: Double, carbs: Double, bgValue: Int, trioMeal: TrioMealTreatment? = null, loopCarb: LoopCarbTreatment? = null): Treatment {
            val subtitle = when {
                trioMeal?.isFPUChild == true -> "Carbs • FPU"
                carbs == 0.0 && trioMeal != null -> listOfNotNull(
                    if (trioMeal.fat > 0) "${trioMeal.fat} g fat" else null,
                    if (trioMeal.protein > 0) "${trioMeal.protein} g protein" else null,
                ).joinToString(" • ")
                else -> "Carbs"
            }
            return Treatment(
                "${nightscoutID ?: "unknown-$date"}-carb", TreatmentType.CARB, date,
                if (carbs > 0) "${carbs.toInt()}g" else "Meal", subtitle, TreatmentIcon.DOT, TreatmentColor.ORANGE, bgValue, trioMeal, loopCarb,
            )
        }

        /** The Treatments list entries for one Nightscout treatment (a Meal Bolus yields two). */
        fun historyEntries(entry: JsonObject, date: Double, nearestBG: () -> Int): TreatmentHistoryEntries {
            val eventType = entry.string("eventType") ?: return TreatmentHistoryEntries()
            val nsId = entry.string("_id") ?: "unknown-$date"
            val entries = mutableListOf<Treatment>()
            var sawSMB = false
            var sawAutomatic = false

            when (eventType) {
                "Carb Correction", "Meal Bolus" -> {
                    val trioMeal = if (eventType == "Carb Correction") TrioMealTreatment.from(entry, date) else null
                    val loopCarb = if (trioMeal == null) LoopCarbTreatment.from(entry, date) else null
                    val carbs = entry.double("carbs") ?: 0.0
                    if (carbs > 0 || trioMeal != null) entries.add(carb(nsId, date, carbs, nearestBG(), trioMeal, loopCarb))
                    val insulin = entry.double("insulin")
                    if (eventType == "Meal Bolus" && insulin != null && insulin > 0) {
                        entries.add(Treatment("$nsId-bolus", TreatmentType.BOLUS_MANUAL, date, u(insulin), "Bolus", TreatmentIcon.DOT, TreatmentColor.BLUE, nearestBG()))
                    }
                }
                "Correction Bolus", "Bolus", "External Insulin" -> {
                    val insulin = entry.double("insulin")
                    if (insulin != null && insulin > 0) {
                        val isAutomatic = entry.bool("automatic") ?: false
                        val isSMB = entry.bool("isSMB") ?: false
                        val isTrioSMBType = entry.string("type")?.lowercase() == "smb"
                        if (isAutomatic || isSMB || isTrioSMBType) {
                            sawSMB = isSMB || isTrioSMBType
                            sawAutomatic = isAutomatic
                            entries.add(Treatment("$nsId-smb", TreatmentType.BOLUS_AUTOMATIC, date, u(insulin), if (isSMB || isTrioSMBType) "SMB" else "Automatic Bolus", TreatmentIcon.TRIANGLE_DOWN, TreatmentColor.BLUE, nearestBG()))
                        } else {
                            entries.add(Treatment("$nsId-bolus", TreatmentType.BOLUS_MANUAL, date, u(insulin), "Bolus", TreatmentIcon.DOT, TreatmentColor.BLUE, nearestBG()))
                        }
                    }
                }
                "SMB" -> {
                    val insulin = entry.double("insulin")
                    if (insulin != null && insulin > 0) {
                        sawSMB = true
                        entries.add(Treatment("$nsId-smb", TreatmentType.SMB, date, u(insulin), "SMB", TreatmentIcon.TRIANGLE_DOWN, TreatmentColor.BLUE, nearestBG()))
                    }
                }
                "Temp Basal" -> {
                    entry.double("rate")?.let { rate ->
                        entries.add(Treatment("$nsId-basal", TreatmentType.TEMP_BASAL, date, cFormat(rate, 2) + " U/hr", "Temp Basal", TreatmentIcon.CHART, TreatmentColor.BLUE, 0))
                    }
                }
                "Temporary Override", "Exercise" -> {
                    val duration = entry.double("duration") ?: 0.0
                    if (duration == 0.0) return TreatmentHistoryEntries()
                    val reason = entry.string("notes")?.trim() ?: entry.string("reason")?.trim() ?: ""
                    val subtitle = listOfNotNull("Override", if (duration > 0) "${duration.toInt()}m" else null).joinToString(" • ")
                    entries.add(Treatment("$nsId-override", TreatmentType.OVERRIDE, date, reason.ifEmpty { "Temporary Override" }, subtitle, TreatmentIcon.SLIDERS, TreatmentColor.PURPLE, 0))
                }
                "Temporary Target" -> {
                    val duration = entry.double("duration") ?: 0.0
                    if (duration == 0.0) return TreatmentHistoryEntries()
                    val bottom = entry.double("targetBottom") ?: 0.0
                    val top = entry.double("targetTop") ?: 0.0
                    val target = if (bottom > 0) bottom else top
                    if (target <= 0) return TreatmentHistoryEntries()
                    val title = if (bottom > 0 && top > 0 && abs(top - bottom) >= 0.1) {
                        "${formatTreatmentBG(bottom)} - ${formatTreatmentBG(top)}"
                    } else {
                        formatTreatmentBG(target)
                    }
                    val reason = entry.string("reason")?.trim()
                    val subtitle = listOfNotNull("Temp Target", if (duration > 0) "${duration.toInt()}m" else null, reason?.ifEmpty { null }).joinToString(" • ")
                    entries.add(Treatment("$nsId-temp-target", TreatmentType.TEMP_TARGET, date, title, subtitle, TreatmentIcon.SCOPE, TreatmentColor.GREEN, 0))
                }
            }
            return TreatmentHistoryEntries(entries, sawSMB, sawAutomatic)
        }

        // Chart marks link to these list entries.
        fun bolus(entry: JsonObject, date: Double, sgv: Int): Treatment? = historyEntries(entry, date) { sgv }.bolus
        fun carb(entry: JsonObject, date: Double, sgv: Int): Treatment? = historyEntries(entry, date) { sgv }.carb
        fun firstEntry(entry: JsonObject, date: Double): Treatment? = historyEntries(entry, date) { 0 }.entries.firstOrNull()

        /** Trio root meals keyed by the fpuID their FPU children carry. */
        fun rootMealsByFPUID(treatments: Iterable<Treatment>): Map<String, Treatment> {
            val roots = HashMap<String, Treatment>()
            for (t in treatments) {
                val meal = t.trioMeal ?: continue
                if (!meal.isFPUChild && meal.fpuID != null) roots[meal.fpuID] = t
            }
            return roots
        }
    }
}
