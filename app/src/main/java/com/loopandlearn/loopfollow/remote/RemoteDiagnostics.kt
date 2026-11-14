package com.loopandlearn.loopfollow.remote

import com.loopandlearn.loopfollow.engine.NSProfile
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.helpers.nowSeconds
import com.loopandlearn.loopfollow.nightscout.EventType
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.nightscout.objectList
import com.loopandlearn.loopfollow.storage.Storage

/** Checks the Nightscout profile history for setups that break remote commands. */
data class RemoteDiagnostics(
    val status: Status = Status.Unknown,
    val bundleMismatch: BundleMismatch? = null,
    val bouncingTokens: BouncingTokens? = null,
    val futureStartDate: Double? = null,
) {
    sealed class Status {
        data object Unknown : Status()
        data object Running : Status()
        data object Ok : Status()
        data class Failed(val message: String) : Status()
    }

    data class BundleMismatch(val expectedDevice: String, val observedBundleId: String)
    data class BouncingTokens(val distinctCount: Int, val recordsScanned: Int, val shifts: List<TokenShift>)
    data class TokenShift(val time: Double, val fromToken: String, val toToken: String, val bundleIdentifier: String?)

    val hasAnyWarning: Boolean get() = bundleMismatch != null || bouncingTokens != null || futureStartDate != null

    companion object {
        private const val HISTORY_CAP = 1000
        private const val FUTURE_START_TOLERANCE = 60.0

        suspend fun run(): RemoteDiagnostics {
            if (Storage.url.value.isEmpty()) return RemoteDiagnostics(Status.Ok)
            return try {
                val history = NightscoutUtils.executeRequest(EventType.PROFILE, mapOf("count" to "$HISTORY_CAP")).objectList().map { NSProfile(it) }
                evaluate(history).also {
                    LogManager.log(
                        LogManager.Category.NIGHTSCOUT,
                        "Remote diagnostics evaluated: records=${history.size} bundleMismatch=${it.bundleMismatch != null} bouncingTokens=${it.bouncingTokens != null} futureStartDate=${it.futureStartDate != null}",
                    )
                }
            } catch (e: Exception) {
                RemoteDiagnostics(Status.Failed(e.message ?: "Unknown error"))
            }
        }

        private fun timestamp(p: NSProfile): Double =
            p.startDate?.let { NightscoutUtils.parseDate(it) } ?: p.createdAt?.let { NightscoutUtils.parseDate(it) } ?: Double.NEGATIVE_INFINITY

        fun evaluate(history: List<NSProfile>, now: Double = nowSeconds()): RemoteDiagnostics {
            var result = RemoteDiagnostics(Status.Ok)
            val device = Storage.device.value
            history.firstOrNull()?.takeIf { device.isNotEmpty() }?.let { current ->
                val topLevel = current.bundleIdentifier?.trim() ?: ""
                val nested = current.loopBundleIdentifier?.trim() ?: ""
                if (device == "Loop" && nested.isEmpty() && topLevel.isNotEmpty()) {
                    result = result.copy(bundleMismatch = BundleMismatch("Loop", topLevel))
                } else if (device == "Trio" && topLevel.isEmpty() && nested.isNotEmpty()) {
                    result = result.copy(bundleMismatch = BundleMismatch("Trio", nested))
                }
            }

            data class Compressed(val token: String, val time: Double, val bundle: String?)
            val compressed = mutableListOf<Compressed>()
            for (record in history.sortedBy { timestamp(it) }) {
                val token = (record.deviceToken ?: record.loopDeviceToken)?.takeIf { it.isNotEmpty() } ?: continue
                if (compressed.lastOrNull()?.token != token) {
                    compressed.add(Compressed(token, timestamp(record), record.bundleIdentifier ?: record.loopBundleIdentifier))
                }
            }
            val distinct = compressed.map { it.token }.toSet()
            if (compressed.size > distinct.size) {
                val shifts = compressed.zipWithNext { a, b -> TokenShift(b.time, a.token, b.token, b.bundle) }
                result = result.copy(bouncingTokens = BouncingTokens(distinct.size, history.size, shifts))
            }

            val maxDate = history.mapNotNull { p -> p.startDate?.let { NightscoutUtils.parseDate(it) } }.maxOrNull()
            if (maxDate != null && maxDate > now + FUTURE_START_TOLERANCE) result = result.copy(futureStartDate = maxDate)
            return result
        }
    }
}
