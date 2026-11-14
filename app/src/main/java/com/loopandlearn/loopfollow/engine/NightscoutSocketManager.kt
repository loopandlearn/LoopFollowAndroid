package com.loopandlearn.loopfollow.engine

import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.nightscout.NightscoutUtils
import com.loopandlearn.loopfollow.storage.ObservableValue
import com.loopandlearn.loopfollow.storage.Storage
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Live Nightscout updates over Socket.IO while the app is in the foreground. */
object NightscoutSocketManager {
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED, ERROR }

    val state = ObservableValue(ConnectionState.DISCONNECTED)
    val isAuthenticated: Boolean get() = state.value == ConnectionState.AUTHENTICATED

    @Volatile private var socket: Socket? = null
    private var currentURL = ""
    private var currentToken = ""

    fun connectIfNeeded() {
        if (!Storage.webSocketEnabled.value) {
            disconnect()
            return
        }
        val url = NightscoutUtils.sanitizeConnectionInput(Storage.url.value)
        val token = NightscoutUtils.sanitizeConnectionInput(Storage.token.value)
        if (url.isEmpty()) {
            disconnect()
            return
        }
        if (state.value in setOf(ConnectionState.AUTHENTICATED, ConnectionState.CONNECTING, ConnectionState.CONNECTED)) {
            if (url == currentURL && token == currentToken) return
            disconnect()
        }
        currentURL = url
        currentToken = token
        connect()
    }

    fun disconnect() {
        val wasAuthenticated = isAuthenticated
        socket?.off()
        socket?.disconnect()
        socket = null
        setState(ConnectionState.DISCONNECTED)
        currentURL = ""
        currentToken = ""
        if (wasAuthenticated) rescheduleAll()
    }

    private fun rescheduleAll() {
        val now = System.currentTimeMillis()
        TaskScheduler.rescheduleTask(TaskID.FETCH_BG, now)
        TaskScheduler.rescheduleTask(TaskID.DEVICE_STATUS, now)
        TaskScheduler.rescheduleTask(TaskID.TREATMENTS, now)
        TaskScheduler.rescheduleTask(TaskID.PROFILE, now)
    }

    private fun setState(newState: ConnectionState) {
        MainEngine.scope.launch { state.value = newState }
    }

    private fun connect() {
        val options = IO.Options.builder()
            .setReconnection(true)
            .setReconnectionDelay(5000)
            .setReconnectionDelayMax(30000)
            .apply { if (currentToken.isNotEmpty()) setQuery("token=$currentToken") }
            .build()
        // A socket being replaced would keep reconnecting by itself.
        socket?.off()
        socket?.disconnect()
        socket = null
        val s = try {
            IO.socket(currentURL, options)
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.WEBSOCKET, "Invalid Nightscout URL for WebSocket")
            setState(ConnectionState.ERROR)
            return
        }
        socket = s
        setState(ConnectionState.CONNECTING)

        s.on(Socket.EVENT_CONNECT) {
            if (socket !== s) return@on
            LogManager.log(LogManager.Category.WEBSOCKET, "Socket connected, authorizing...")
            setState(ConnectionState.CONNECTED)
            val auth = JSONObject().put("client", "LoopFollow").put("history", 1)
            if (currentToken.isNotEmpty()) auth.put("secret", currentToken)
            s.emit("authorize", auth)
        }
        s.on(Socket.EVENT_DISCONNECT) { args ->
            if (socket !== s) return@on
            LogManager.log(LogManager.Category.WEBSOCKET, "Socket disconnected: ${args.firstOrNull() ?: "unknown"}")
            setState(ConnectionState.DISCONNECTED)
            MainEngine.scope.launch { MainEngine.refresh() }
        }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            if (socket !== s) return@on
            LogManager.log(LogManager.Category.WEBSOCKET, "Socket error: ${args.firstOrNull() ?: "unknown error"}")
            setState(ConnectionState.ERROR)
        }
        s.on("connected") {
            if (socket !== s) return@on
            LogManager.log(LogManager.Category.WEBSOCKET, "Authorized and receiving data")
            setState(ConnectionState.AUTHENTICATED)
        }
        s.on("dataUpdate") { args ->
            if (socket !== s) return@on
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            LogManager.log(LogManager.Category.WEBSOCKET, "Received dataUpdate (delta: ${payload.optBoolean("delta", false)})", isDebug = true)
            MainEngine.scope.launch { handleDataUpdate(payload) }
        }
        s.connect()
        LogManager.log(LogManager.Category.WEBSOCKET, "Connecting to Nightscout WebSocket")
    }

    private fun handleDataUpdate(data: JSONObject) {
        if (!data.optBoolean("delta", false)) {
            LogManager.log(LogManager.Category.WEBSOCKET, "Full data received, triggering all fetches")
            rescheduleAll()
            return
        }
        val now = System.currentTimeMillis()
        val triggered = mutableListOf<String>()
        if (data.has("sgvs") || data.has("mbgs")) {
            TaskScheduler.rescheduleTask(TaskID.FETCH_BG, now)
            triggered.add("BG")
        }
        if (data.has("devicestatus")) {
            TaskScheduler.rescheduleTask(TaskID.DEVICE_STATUS, now)
            triggered.add("DeviceStatus")
        }
        if (data.has("treatments")) {
            TaskScheduler.rescheduleTask(TaskID.TREATMENTS, now)
            triggered.add("Treatments")
        }
        if (data.has("profiles")) {
            TaskScheduler.rescheduleTask(TaskID.PROFILE, now)
            triggered.add("Profile")
        }
        if (triggered.isNotEmpty()) LogManager.log(LogManager.Category.WEBSOCKET, "Delta triggered: ${triggered.joinToString(", ")}", isDebug = true)
    }
}
