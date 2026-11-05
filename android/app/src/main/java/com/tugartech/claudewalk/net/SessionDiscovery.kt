package com.tugartech.claudewalk.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DiscoveredSession(
    val port: Int,
    val sessionId: String,
    /**
     * How the session was started: "user" when launched through walk.ps1, and
     * "unknown" for anything else, which in practice means a subagent spawned it.
     * Stamped at launch rather than inferred, so it cannot be wrong, only absent.
     */
    val launchedBy: String = "unknown",
    val project: String,
    val cwd: String,
    /** False when the session took an auto-assigned port, so its hooks are inactive. */
    val hooks: Boolean,
    val phones: Int,
    /**
     * Utterances this session has spoken that the phone has not played yet.
     *
     * This is the bridge's own outstanding queue: the app acks only after playback
     * completes, so "outstanding" and "unheard" are the same fact. It needs no
     * separate bookkeeping and stays correct while you are on another session.
     */
    val unheard: Int,
)

/**
 * Finds live Claude Code sessions by scanning the bridge port range.
 *
 * Each session spawns its own bridge and claims the next free port, so there is
 * no central registry to consult and nothing extra to keep running. Ten probes
 * in parallel over a tailnet costs a few hundred milliseconds.
 */
class SessionDiscovery {

    private val client = OkHttpClient.Builder()
        .connectTimeout(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun scan(host: String, token: String): List<DiscoveredSession> = coroutineScope {
        (PORT_RANGE_START..PORT_RANGE_END)
            .map { port -> async(Dispatchers.IO) { probe(host, port, token) } }
            .awaitAll()
            .filterNotNull()
            .sortedBy { it.port }
    }

    private fun probe(host: String, port: Int, token: String): DiscoveredSession? = runCatching {
        val request = Request.Builder()
            .url("http://$host:$port/health?token=$token")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = JSONObject(response.body?.string().orEmpty())
            // A bridge answers {"ok":true} with no detail when the token is wrong.
            // Treat that as "something is listening but not ours to talk to".
            val project = body.optString("project")
            if (project.isEmpty()) return null

            DiscoveredSession(
                port = port,
                sessionId = body.optString("session_id"),
                project = project,
                cwd = body.optString("cwd"),
                launchedBy = body.optString("launched_by").ifEmpty { "unknown" },
                hooks = body.optBoolean("hooks"),
                phones = body.optInt("phones"),
                unheard = body.optInt("unacked"),
            )
        }
    }.getOrNull()

    companion object {
        const val PORT_RANGE_START = 8710
        const val PORT_RANGE_END = 8719
        private const val SCAN_TIMEOUT_MS = 1500L
    }
}
