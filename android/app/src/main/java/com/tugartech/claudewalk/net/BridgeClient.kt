package com.tugartech.claudewalk.net

import android.util.Log
import com.tugartech.claudewalk.ConnectionState
import com.tugartech.claudewalk.WalkSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * WebSocket link to the bridge, with automatic reconnection.
 *
 * Losing signal on a walk is normal, not exceptional, so reconnection is the
 * default path rather than an error path. The bridge replays anything the phone
 * never acked, so a dropout costs nothing but delay.
 */
class BridgeClient(
    private val settings: WalkSettings,
    private val onFrame: (JSONObject) -> Unit,
    private val onConnectionState: (ConnectionState) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        // Keeps the connection alive through carrier NAT timeouts, and surfaces a
        // dead link quickly rather than waiting for a TCP timeout.
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var attempt = 0

    /**
     * Invalidates callbacks from sockets we have deliberately abandoned.
     *
     * Cancelling a socket fires onFailure, which would otherwise schedule its own
     * reconnect and race the intentional one. That produced two live sockets after
     * a session switch, and since the bridge broadcasts to every connected socket,
     * two sockets means every utterance is spoken twice.
     */
    @Volatile
    private var generation = 0

    private var socket: WebSocket? = null

    @Volatile
    private var wantConnection = false

    fun connect() {
        wantConnection = true
        openSocket()
    }

    fun disconnect() {
        wantConnection = false
        generation++
        socket?.close(1000, "client shutdown")
        socket = null
        onConnectionState(ConnectionState.DISCONNECTED)
    }

    /** Force a fresh connection, e.g. after switching session or editing the host. */
    fun reconnectNow() {
        attempt = 0
        generation++
        socket?.cancel()
        socket = null
        if (wantConnection) openSocket()
    }

    fun send(frame: JSONObject): Boolean = socket?.send(frame.toString()) ?: false

    private fun openSocket() {
        if (!settings.isConfigured) {
            onConnectionState(ConnectionState.DISCONNECTED)
            return
        }
        onConnectionState(ConnectionState.CONNECTING)

        val url = settings.webSocketUrl
        Log.i(TAG, "connecting to $url (gen ${generation + 1})")
        val request = Request.Builder().url(url).build()
        // Captured per socket. Any callback whose generation no longer matches
        // belongs to a socket we abandoned, and must not touch shared state or
        // schedule a reconnect.
        val gen = generation
        socket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) {
                    webSocket.cancel()
                    return
                }
                attempt = 0
                // Everything before a valid hello is dropped by the bridge.
                webSocket.send(
                    JSONObject()
                        .put("type", "hello")
                        .put("token", settings.token)
                        .toString(),
                )
                onConnectionState(ConnectionState.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                onFrame(frame)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation) return
                Log.i(TAG, "closed: $code $reason")
                onConnectionState(ConnectionState.DISCONNECTED)
                // 4001 is the bridge rejecting our token. Retrying cannot fix that,
                // and hammering it would just burn battery.
                if (code != 4001) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                Log.w(TAG, "failure: ${t.message}")
                onConnectionState(ConnectionState.DISCONNECTED)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!wantConnection) return
        val delayMs = min(MAX_BACKOFF_MS, (2.0.pow(attempt) * 1000).toLong())
        attempt = min(attempt + 1, MAX_ATTEMPT_EXPONENT)
        val gen = generation
        Thread {
            Thread.sleep(delayMs)
            // A switch during the backoff wins; this retry is stale.
            if (wantConnection && gen == generation) openSocket()
        }.start()
    }

    fun shutdown() {
        disconnect()
        client.dispatcher.executorService.shutdown()
    }

    private companion object {
        const val TAG = "BridgeClient"
        // Capped low on purpose. This is the wait after walking out of a dead
        // zone, and 30s of silence reads as "the thing is broken". The extra
        // retries cost little against a foreground service that is already awake.
        const val MAX_BACKOFF_MS = 8_000L
        const val MAX_ATTEMPT_EXPONENT = 3
    }
}
