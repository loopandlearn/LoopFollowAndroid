package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.nightscout.NightscoutError
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.storage.ObservableValue
import kotlinx.coroutines.launch
import java.util.UUID

/** Identifies who produced a banner message. Each source owns at most one message at a time. */
sealed class BannerSource {
    data object Nightscout : BannerSource()
    data object Dexcom : BannerSource()

    /** Shown once; dismissing it removes it. */
    data object AlarmPermissions : BannerSource()
}

enum class BannerSeverity { INFO, WARNING, ERROR }

data class BannerMessage(
    val id: String,
    val source: BannerSource,
    val severity: BannerSeverity,
    val text: String,
    val timestamp: Long,
)

/** App-wide banner state. Producers call report/clear; the view observes [activeBanner]. */
object BannerManager {
    /** How long a user dismissal suppresses a still-occurring error before it re-appears. */
    private const val DISMISS_COOLDOWN = 30 * 60 * 1000L

    /** Minimum time between Nightscout diagnostic probes (status.json calls). */
    private const val DIAGNOSTIC_INTERVAL = 5 * 60 * 1000L

    val activeBanner = ObservableValue<BannerMessage?>(null)

    private val messages = HashMap<BannerSource, BannerMessage>()
    private val dismissals = HashMap<BannerSource, Pair<Long, String>>()
    private var lastNightscoutDiagnostic: Long? = null

    fun report(source: BannerSource, severity: BannerSeverity = BannerSeverity.ERROR, text: String) {
        MainEngine.scope.launch {
            val existing = messages[source]
            if (existing == null || existing.text != text || existing.severity != severity) {
                dismissals.remove(source)
                messages[source] = BannerMessage(UUID.randomUUID().toString(), source, severity, text, System.currentTimeMillis())
            }
            publish()
        }
    }

    fun clear(source: BannerSource) {
        MainEngine.scope.launch {
            if (messages[source] == null && dismissals[source] == null) return@launch
            messages.remove(source)
            dismissals.remove(source)
            publish()
        }
    }

    fun dismissCurrent() {
        MainEngine.scope.launch {
            val current = activeBanner.value ?: return@launch
            if (current.source == BannerSource.AlarmPermissions) {
                messages.remove(current.source)
            } else {
                dismissals[current.source] = (System.currentTimeMillis() + DISMISS_COOLDOWN) to current.text
            }
            publish()
        }
    }

    /** Classifies a failed Nightscout fetch by probing status.json, so the banner can say why it failed. */
    fun reportNightscoutFetchFailure(underlying: Throwable) {
        MainEngine.scope.launch {
            val last = lastNightscoutDiagnostic
            if (last != null && System.currentTimeMillis() - last < DIAGNOSTIC_INTERVAL) return@launch
            lastNightscoutDiagnostic = System.currentTimeMillis()

            val result = NightscoutUtils.verifyURLAndToken()
            val error = result.error
            if (error != null) {
                if (error == NightscoutError.EMPTY_ADDRESS) {
                    clear(BannerSource.Nightscout)
                    return@launch
                }
                report(BannerSource.Nightscout, BannerSeverity.ERROR, "Nightscout: ${error.description}")
            } else {
                LogManager.log(
                    LogManager.Category.NIGHTSCOUT,
                    "Nightscout diagnostic OK but data fetch failed: $underlying",
                    limitIdentifier = "Nightscout diagnostic OK but data fetch failed",
                )
                report(
                    BannerSource.Nightscout,
                    BannerSeverity.WARNING,
                    "Nightscout: data download failed, but the server is reachable. Retrying automatically.",
                )
            }
        }
    }

    fun reportDexcomFailure(error: Throwable, nightscoutFallback: Boolean) {
        var text = when (error) {
            is ShareError.LoginError -> when {
                error.errorCode.contains("AccountNotFound") -> "Dexcom Share: account not found — check your username."
                error.errorCode.contains("PasswordInvalid") -> "Dexcom Share: incorrect username or password."
                error.errorCode.contains("MaxAttempts") -> "Dexcom Share: too many failed login attempts — temporarily locked out."
                else -> "Dexcom Share: login failed (${error.errorCode})."
            }
            is ShareError.HttpError -> "Dexcom Share: network error while downloading."
            else -> "Dexcom Share: could not download readings."
        }
        if (nightscoutFallback) text += " Using Nightscout as backup."
        report(BannerSource.Dexcom, if (nightscoutFallback) BannerSeverity.WARNING else BannerSeverity.ERROR, text)
    }

    /** Pushes the highest-priority non-dismissed message to the UI. */
    private fun publish() {
        val now = System.currentTimeMillis()
        val candidate = messages.values
            .filter { message ->
                val dismissal = dismissals[message.source] ?: return@filter true
                now >= dismissal.first || dismissal.second != message.text
            }
            .maxWithOrNull(compareBy<BannerMessage> { it.severity.ordinal }.thenBy { it.timestamp })
        if (activeBanner.value != candidate) activeBanner.value = candidate
    }
}
