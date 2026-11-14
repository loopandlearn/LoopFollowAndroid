package com.loopandlearn.loopfollow.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.loopandlearn.loopfollow.engine.KnownShareServers
import com.loopandlearn.loopfollow.engine.ShareClient
import com.loopandlearn.loopfollow.engine.ShareError
import com.loopandlearn.loopfollow.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Dexcom Share credential entry with a debounced login check, shared by settings and onboarding. */
class DexcomSettingsModel(private val scope: CoroutineScope) {
    enum class StatusKind { IDLE, CHECKING, CONNECTED, ERROR }

    val isFreshSetup = Storage.shareUserName.value.isEmpty()

    var userName by mutableStateOf(Storage.shareUserName.value)
        private set
    var password by mutableStateOf(Storage.sharePassword.value)
        private set
    var server by mutableStateOf(Storage.shareServer.value)
        private set
    var statusKind by mutableStateOf(StatusKind.IDLE)
        private set
    var statusMessage by mutableStateOf("Enter your username and password")
        private set
    private var loginRejected by mutableStateOf(false)
    private var job: Job? = null

    val hasCredentials get() = userName.isNotEmpty() && password.isNotEmpty()

    /** Verified, or Dexcom couldn't be reached; a rejected login always blocks. */
    val canVerifyProceed get() = hasCredentials && statusKind != StatusKind.CHECKING && !loginRejected

    init {
        schedule()
    }

    fun onUserName(value: String) {
        if (value == userName) return
        userName = value
        Storage.shareUserName.value = value
        schedule()
    }

    fun onPassword(value: String) {
        if (value == password) return
        password = value
        Storage.sharePassword.value = value
        schedule()
    }

    fun onServer(value: String) {
        if (value == server) return
        server = value
        Storage.shareServer.value = value
        schedule()
    }

    private fun schedule() {
        job?.cancel()
        loginRejected = false
        if (!hasCredentials) {
            statusKind = StatusKind.IDLE
            statusMessage = "Enter your username and password"
            return
        }
        statusKind = StatusKind.CHECKING
        statusMessage = "Checking your account…"
        job = scope.launch {
            delay(1500)
            val serverURL = if (server == "US") KnownShareServers.US.url else KnownShareServers.NON_US.url
            try {
                ShareClient(userName, password, serverURL).fetchData(1)
                set(StatusKind.CONNECTED, "Connected", rejected = false)
            } catch (e: ShareError.LoginError) {
                set(StatusKind.ERROR, "Username or password not accepted", rejected = true)
            } catch (e: ShareError.HttpError) {
                set(StatusKind.ERROR, "Network error — check your connection", rejected = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Logged in, but without a recent reading; the credentials are valid.
                set(StatusKind.CONNECTED, "Connected", rejected = false)
            }
        }
    }

    private fun set(kind: StatusKind, message: String, rejected: Boolean) {
        statusKind = kind
        statusMessage = message
        loginRejected = rejected
    }
}

@Composable
fun rememberDexcomSettingsModel(): DexcomSettingsModel {
    val scope = rememberCoroutineScope()
    return remember { DexcomSettingsModel(scope) }
}
