package com.navis.companion.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.navis.companion.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Manages WebSocket connection to NAVIS backend (/ws/events) for real-time hazard notifications.
 */
class NavisWebSocketManager(
    private val serverBaseUrl: String,
    private val apiToken: String = BuildConfig.NAVIS_API_TOKEN,
    private val onHazardReceived: (HazardEventEnvelope) -> Unit,
    private val onStatusReceived: (StatusEventEnvelope) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit
) {
    private val tag = "NavisWS"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var shouldReconnect = true

    fun connect() {
        shouldReconnect = true
        // Convert http:// to ws:// and https:// to wss://
        val wsUrl = serverBaseUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/') + "/ws/events"

        Log.i(tag, "Connecting to NAVIS WebSocket: $wsUrl")
        val requestBuilder = Request.Builder().url(wsUrl)
        if (apiToken.isNotBlank()) requestBuilder.header("X-API-Token", apiToken)
        val request = requestBuilder.build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "WebSocket connection established with NAVIS backend")
                isConnected = true
                onConnectionChanged(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JsonParser.parseString(text).asJsonObject
                    val type = json.get("type")?.asString ?: "hazard"

                    if (type == "hazard") {
                        val hazard = gson.fromJson(json, HazardEventEnvelope::class.java)
                        onHazardReceived(hazard)
                    } else if (type == "status") {
                        val status = gson.fromJson(json, StatusEventEnvelope::class.java)
                        onStatusReceived(status)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error parsing WebSocket event: ${e.message} payload=$text")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "WebSocket connection failed: ${t.message}. Reconnecting in 3s...")
                isConnected = false
                onConnectionChanged(false)
                if (shouldReconnect) {
                    Thread {
                        try {
                            Thread.sleep(3000)
                            connect()
                        } catch (ignored: Exception) {}
                    }.start()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "WebSocket closed: $reason (code: $code)")
                isConnected = false
                onConnectionChanged(false)
            }
        })
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "App closed")
        webSocket = null
        isConnected = false
        onConnectionChanged(false)
    }
}
