package com.loopandlearn.loopfollow.remote

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.storage.storageValue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

object RemoteCommandMessage {
    // ANDROID-DEVIATION: Points to the Nightscout upload. Android cannot receive the AID app's confirmation push.
    const val SENT = "Remote command sent. The result shows in LoopFollow once it has been uploaded to Nightscout."
}

/**
 * Runs a command send and the bookkeeping that follows a delivered push (history, one-time code, early fetches) to
 * completion, also when the screen that started it leaves the composition while the push is on its way.
 */
suspend fun <T> uninterruptedSend(block: suspend () -> T): T = withContext(NonCancellable) { block() }

sealed class AuthResult {
    data object Success : AuthResult()
    data object Canceled : AuthResult()
    data class Unavailable(val message: String) : AuthResult()
    data object Failed : AuthResult()
}

/** Device owner authentication: biometrics, or the screen lock PIN/pattern/password. */
// ANDROID-DEVIATION: BIOMETRIC_WEAK | DEVICE_CREDENTIAL is the Android counterpart of iOS deviceOwnerAuthentication.
object AuthService {
    fun authenticate(activity: FragmentActivity, reason: String, completion: (AuthResult) -> Unit) {
        val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            completion(AuthResult.Unavailable("Device authentication is not available. Please set up a fingerprint, face unlock, or a screen lock in Settings."))
            return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = completion(AuthResult.Success)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON -> completion(AuthResult.Canceled)
                    else -> completion(AuthResult.Failed)
                }
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("LoopFollow")
                .setSubtitle(reason)
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }
}

/** Insulin amounts rounded down to the pump's bolus increment. */
object BolusSteps {
    val step: Double get() = maxOf(0.001, Storage.bolusIncrement.value)

    val fractionDigits: Int
        get() {
            val inc = step
            if (inc >= 1) return 0
            var v = inc
            var digits = 0
            while (digits < 6 && abs(round(v) - v) > 1e-10) {
                v *= 10
                digits++
            }
            return digits.coerceIn(0, 5)
        }

    fun roundedToStep(value: Double): Double {
        val stepped = floor(value / step + 1e-10) * step
        val p = 10.0.pow(fractionDigits)
        return round(stepped * p) / p
    }
}

object RecommendedBolus {
    /** The latest recommendation, unless a bolus was given since it was calculated. */
    fun current(): Pair<Double, Double>? {
        val units = Observable.deviceRecBolus.value ?: return null
        val at = Observable.deviceRecBolusAt.value ?: return null
        val last = lastBolusAt()
        if (last != null && last >= at) return null
        return units to at
    }

    fun lastBolusAt(): Double? {
        val nightscout = MainEngine.bolusData.maxOfOrNull { it.date }
        val sentBolus = Storage.remoteBolusHistory.value.maxOfOrNull { it.date }
        val sentMeal = Storage.remoteMealHistory.value.filter { it.bolus > 0 }.maxOfOrNull { it.date }
        return listOfNotNull(nightscout, sentBolus, sentMeal).maxOrNull()
    }
}

@Serializable
data class RemoteBolusHistoryEntry(val units: Double, val date: Double, val dayOfWeek: Int, val minuteOfDay: Int)

@Serializable
data class RemoteMealHistoryEntry(val carbs: Double, val fat: Double, val protein: Double, val bolus: Double, val date: Double, val dayOfWeek: Int, val minuteOfDay: Int)

/** Weekday 1 (Sunday) … 7 and minute of day, as the iOS calendar reports them. */
private fun calendarParts(date: Double): Pair<Int, Int> {
    val c = Calendar.getInstance().apply { timeInMillis = (date * 1000).toLong() }
    return c.get(Calendar.DAY_OF_WEEK) to (c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
}

/** Scores past amounts by time of day, weekday and recency. */
private object QuickPickScore {
    const val SIGMA = 60.0
    const val HALF_LIFE = 10.0
    const val MIN_SCORE = 0.1
    const val MAX_RESULTS = 5
    const val MAX_ENTRIES = 500
    const val MAX_AGE_DAYS = 90.0

    fun timeOfDay(entryMinute: Int, nowMinute: Int): Double {
        val diff = abs(entryMinute - nowMinute)
        val circular = min(diff, 1440 - diff).toDouble()
        return exp(-(circular * circular) / (2 * SIGMA * SIGMA))
    }

    fun dayOfWeek(entry: Int, now: Int): Double {
        if (entry == now) return 1.0
        val nowWeekend = now == 1 || now == 7
        val entryWeekend = entry == 1 || entry == 7
        return if (nowWeekend == entryWeekend) 0.7 else 0.15
    }

    fun recency(daysAgo: Double) = 0.5.pow(daysAgo / HALF_LIFE)
}

object QuickPickBoluses {
    fun record(units: Double, at: Double = nowSeconds()) {
        val (dow, minute) = calendarParts(at)
        val cutoff = at - QuickPickScore.MAX_AGE_DAYS * 86400
        var history = (Storage.remoteBolusHistory.value + RemoteBolusHistoryEntry(units, at, dow, minute)).filter { it.date > cutoff }
        if (history.size > QuickPickScore.MAX_ENTRIES) history = history.sortedByDescending { it.date }.take(QuickPickScore.MAX_ENTRIES)
        Storage.remoteBolusHistory.value = history
    }

    fun compute(maxBolus: Double, now: Double = nowSeconds()): List<Double> {
        val step = BolusSteps.step
        val (nowDOW, nowMinute) = calendarParts(now)
        val groups = LinkedHashMap<Double, Double>()
        for (entry in Storage.remoteBolusHistory.value) {
            val p = 10.0.pow(BolusSteps.fractionDigits)
            val amount = round(floor(entry.units / step) * step * p) / p
            if (amount <= 0 || amount > maxBolus) continue
            val score = QuickPickScore.timeOfDay(entry.minuteOfDay, nowMinute) * QuickPickScore.dayOfWeek(entry.dayOfWeek, nowDOW) *
                QuickPickScore.recency((now - entry.date) / 86400)
            groups[amount] = (groups[amount] ?: 0.0) + score
        }
        return groups.filter { it.value >= QuickPickScore.MIN_SCORE }.entries.sortedByDescending { it.value }.take(QuickPickScore.MAX_RESULTS).map { it.key }
    }
}

data class QuickPickMeal(val carbs: Double, val fat: Double, val protein: Double, val bolus: Double)

object QuickPickMeals {
    fun record(carbs: Double, fat: Double, protein: Double, bolus: Double, at: Double = nowSeconds()) {
        val (dow, minute) = calendarParts(at)
        val cutoff = at - QuickPickScore.MAX_AGE_DAYS * 86400
        var history = (Storage.remoteMealHistory.value + RemoteMealHistoryEntry(carbs, fat, protein, bolus, at, dow, minute)).filter { it.date > cutoff }
        if (history.size > QuickPickScore.MAX_ENTRIES) history = history.sortedByDescending { it.date }.take(QuickPickScore.MAX_ENTRIES)
        Storage.remoteMealHistory.value = history
    }

    fun compute(maxCarbs: Double, includeFatProtein: Boolean, now: Double = nowSeconds()): List<QuickPickMeal> {
        val (nowDOW, nowMinute) = calendarParts(now)
        val scores = LinkedHashMap<Double, Double>()
        val best = HashMap<Double, Pair<RemoteMealHistoryEntry, Double>>()
        for (entry in Storage.remoteMealHistory.value) {
            val rounded = round(entry.carbs)
            if (rounded <= 0 || rounded > maxCarbs) continue
            val score = QuickPickScore.timeOfDay(entry.minuteOfDay, nowMinute) * QuickPickScore.dayOfWeek(entry.dayOfWeek, nowDOW) *
                QuickPickScore.recency((now - entry.date) / 86400)
            scores[rounded] = (scores[rounded] ?: 0.0) + score
            val current = best[rounded]
            if (current == null || score > current.second) best[rounded] = entry to score
        }
        return scores.filter { it.value >= QuickPickScore.MIN_SCORE }.entries.sortedByDescending { it.value }.take(QuickPickScore.MAX_RESULTS).map { (carbs, _) ->
            val b = best[carbs]?.first
            QuickPickMeal(carbs, if (includeFatProtein) b?.fat ?: 0.0 else 0.0, if (includeFatProtein) b?.protein ?: 0.0 else 0.0, b?.bolus ?: 0.0)
        }
    }
}

const val QUICK_PICK_BOLUS_INFO = "These buttons show your most-used recent bolus amounts.\n\nThey're based on what you've sent before at similar times on similar days — so if you usually give 4 units before breakfast on weekdays, that button will show up on weekday mornings."
const val QUICK_PICK_MEAL_INFO = "These buttons show your most-used recent meals.\n\nThey're based on what you've sent before at similar times on similar days — so if you usually send the same breakfast on weekday mornings, it'll appear as an option."

@Serializable
data class TempTargetPreset(val id: String = UUID.randomUUID().toString(), val name: String, val targetValue: Double, val durationValue: Double)

object TempTargetPresets {
    val presets = storageValue("tempTargetPresets", emptyList<TempTargetPreset>())

    fun add(name: String, targetMgdl: Double, minutes: Double) {
        presets.value = presets.value + TempTargetPreset(name = name, targetValue = targetMgdl, durationValue = minutes)
    }

    fun delete(id: String) {
        presets.value = presets.value.filterNot { it.id == id }
    }
}

/** "1h 30m" style, or a counting-down clock when under two hours. */
fun remainingTimeText(endAt: Double, now: Double = nowSeconds()): String {
    val remaining = endAt - now
    if (remaining <= 0) return ""
    val total = remaining.toLong()
    if (remaining <= 2 * 3600) {
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }
    val days = total / 86400
    val hours = total % 86400 / 3600
    val minutes = total % 3600 / 60
    val parts = listOfNotNull(if (days > 0) "${days}d" else null, if (hours > 0) "${hours}h" else null, if (minutes > 0) "${minutes}m" else null)
    return parts.take(2).joinToString(" ")
}
