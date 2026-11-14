package com.loopandlearn.loopfollow.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.engine.NightscoutSocketManager
import com.loopandlearn.loopfollow.nightscout.NightscoutError
import com.loopandlearn.loopfollow.nightscout.NightscoutException
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.nightscout.TokenProvisioning
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TOKEN_PATTERN = Regex("^[^-\\s]+-[0-9a-fA-F]{16}$")

/**
 * Nightscout URL/token entry and verification, shared by the settings screen and
 * onboarding so both validate the same way.
 */
class NightscoutSettingsModel(private val scope: CoroutineScope) {
    enum class StatusKind { IDLE, CHECKING, NEEDS_TOKEN, PENDING, CONNECTED, ERROR }

    private val initialURL = Storage.url.value
    private val initialToken = Storage.token.value
    val isFreshSetup = initialURL.isEmpty()

    var url by mutableStateOf(Storage.url.value)
        private set
    var token by mutableStateOf(Storage.token.value)
        private set
    var isConnected by mutableStateOf(false)
        private set
    var status by mutableStateOf("Checking...")
        private set
    var lastError by mutableStateOf<NightscoutError?>(null)
        private set
    var provisionedTokenPending by mutableStateOf(false)
        private set
    var isProvisioningToken by mutableStateOf(false)
        private set
    var tokenProvisionError by mutableStateOf<String?>(null)
        private set
    var tokenIsVerifiedSecret by mutableStateOf(false)
        private set
    private var isConfirmingProvisionedToken by mutableStateOf(false)

    private var checkJob: Job? = null
    private var secretCheckGeneration = 0

    private val errorIsTokenRelated get() = lastError == NightscoutError.TOKEN_REQUIRED || lastError == NightscoutError.INVALID_TOKEN
    val addressNeedsToken get() = !isConnected && errorIsTokenRelated

    init {
        checkStatus(0)
    }

    fun onURLInput(raw: String) {
        val value = NightscoutUtils.sanitizeConnectionInput(raw)
        val q = value.indexOf('?')
        if (q >= 0) {
            val tokenValue = value.substring(q + 1).split("&").map { it.split("=", limit = 2) }
                .firstOrNull { it.size == 2 && it[0].lowercase() == "token" }?.get(1)
            if (tokenValue != null && TOKEN_PATTERN.matches(tokenValue)) {
                applyToken(tokenValue)
                applyURL(value.substring(0, q))
                return
            }
        }
        var clean = value.replace(Regex("[^A-Za-z0-9:/._-]"), "").lowercase()
        while (clean.length > 8 && clean.endsWith("/")) clean = clean.dropLast(1)
        applyURL(clean)
    }

    fun onTokenInput(raw: String) = applyToken(raw)

    private fun applyURL(value: String) {
        if (value == url) return
        url = value
        Storage.url.value = value
        triggerCheck()
    }

    private fun applyToken(value: String) {
        if (value == token) return
        token = value
        Storage.token.value = NightscoutUtils.sanitizeConnectionInput(value)
        triggerCheck()
    }

    private fun triggerCheck() {
        provisionedTokenPending = false
        tokenIsVerifiedSecret = false
        status = "Checking..."
        checkStatus(2000)
    }

    private fun checkStatus(debounceMs: Long) {
        checkJob?.cancel()
        checkJob = scope.launch {
            delay(debounceMs)
            if (isConfirmingProvisionedToken) return@launch
            val result = NightscoutUtils.verifyURLAndToken()
            Storage.nsWriteAuth.value = result.writeAuth
            Storage.nsAdminAuth.value = result.adminAuth
            updateStatus(result.error)
        }
    }

    private fun updateStatus(error: NightscoutError?) {
        lastError = error
        if (error != null) {
            isConnected = false
            status = when (error) {
                NightscoutError.INVALID_URL -> "Invalid URL"
                NightscoutError.NETWORK_ERROR -> "Network Error"
                NightscoutError.INVALID_TOKEN -> "Invalid Token"
                NightscoutError.TOKEN_REQUIRED -> "Token Required"
                NightscoutError.SITE_NOT_FOUND -> "Site Not Found"
                NightscoutError.UNKNOWN -> "Unknown Error"
                NightscoutError.EMPTY_ADDRESS -> "Address Empty"
            }
            NightscoutSocketManager.disconnect()
            if (error == NightscoutError.INVALID_TOKEN || error == NightscoutError.TOKEN_REQUIRED) verifyTokenIsSecret() else tokenIsVerifiedSecret = false
        } else {
            isConnected = true
            tokenIsVerifiedSecret = false
            val auth = if (Storage.nsAdminAuth.value) "Admin" else "Read" + if (Storage.nsWriteAuth.value) " & Write" else ""
            status = "OK ($auth)"
            if (url != initialURL || token != initialToken) MainEngine.refresh()
        }
    }

    /** After a failed token check, finds out whether the token field holds the API secret. */
    private fun verifyTokenIsSecret() {
        val candidate = token
        if (candidate.isEmpty() || TOKEN_PATTERN.matches(candidate)) {
            tokenIsVerifiedSecret = false
            return
        }
        val generation = ++secretCheckGeneration
        val site = url
        scope.launch {
            val isSecret = TokenProvisioning.verifyAPISecret(site, candidate)
            if (generation == secretCheckGeneration && token == candidate) tokenIsVerifiedSecret = isSecret
        }
    }

    fun createReadOnlyToken(secret: String) {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return
        tokenProvisionError = null
        isProvisioningToken = true
        val site = url
        scope.launch {
            try {
                val newToken = TokenProvisioning.provisionReadOnlyToken(site, trimmed)
                isProvisioningToken = false
                confirmProvisionedToken(newToken)
            } catch (e: Exception) {
                isProvisioningToken = false
                tokenProvisionError = provisioningMessage(e)
            }
        }
    }

    /** A new token may take a moment to be accepted, so it is polled before reporting anything. */
    private fun confirmProvisionedToken(newToken: String) {
        isConfirmingProvisionedToken = true
        provisionedTokenPending = false
        isConnected = false
        status = "Finishing connection…"
        checkJob?.cancel()
        token = newToken
        Storage.token.value = newToken
        scope.launch {
            val maxAttempts = 8
            for (attempt in 0 until maxAttempts) {
                val result = NightscoutUtils.verifyURLAndToken()
                if (result.error == null) {
                    isConfirmingProvisionedToken = false
                    provisionedTokenPending = false
                    Storage.nsWriteAuth.value = result.writeAuth
                    Storage.nsAdminAuth.value = result.adminAuth
                    updateStatus(null)
                    return@launch
                }
                if (attempt + 1 < maxAttempts) {
                    status = "Finishing connection…"
                    delay((minOf(0.5 + attempt * 0.25, 2.0) * 1000).toLong())
                }
            }
            isConfirmingProvisionedToken = false
            provisionedTokenPending = true
            lastError = null
        }
    }

    val statusKind: StatusKind
        get() = when {
            isConfirmingProvisionedToken -> StatusKind.CHECKING
            url.isEmpty() -> StatusKind.IDLE
            isConnected -> StatusKind.CONNECTED
            provisionedTokenPending -> StatusKind.PENDING
            status == "Checking..." -> StatusKind.CHECKING
            addressNeedsToken -> StatusKind.NEEDS_TOKEN
            else -> StatusKind.ERROR
        }

    val friendlyStatus: String
        get() = when (statusKind) {
            StatusKind.IDLE -> "Enter your site address to connect."
            StatusKind.CHECKING -> if (isConfirmingProvisionedToken) "Finishing connection…" else "Checking your connection…"
            StatusKind.NEEDS_TOKEN -> "Site found — it needs a token."
            StatusKind.PENDING -> "Token created. Your site can take a few minutes to start accepting it — you can continue."
            StatusKind.CONNECTED -> when {
                Storage.nsAdminAuth.value -> "Connected — admin access"
                Storage.nsWriteAuth.value -> "Connected — read & write"
                else -> "Connected — read-only"
            }
            StatusKind.ERROR -> status
        }

    companion object {
        fun provisioningMessage(e: Exception): String = when ((e as? NightscoutException)?.error) {
            NightscoutError.INVALID_TOKEN -> "That API secret was rejected. Check it and try again."
            NightscoutError.INVALID_URL, NightscoutError.EMPTY_ADDRESS -> "Please enter a valid site URL first."
            NightscoutError.SITE_NOT_FOUND -> "Couldn't reach that site. Check the URL."
            NightscoutError.NETWORK_ERROR -> "Network error. Check your connection and try again."
            else -> "Could not create a token. Please try again."
        }
    }
}

@Composable
fun rememberNightscoutSettingsModel(): NightscoutSettingsModel {
    val scope = rememberCoroutineScope()
    return remember { NightscoutSettingsModel(scope) }
}
