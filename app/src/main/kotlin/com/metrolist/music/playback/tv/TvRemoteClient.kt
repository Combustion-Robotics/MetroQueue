/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/** HTTP + WebSocket client for a TV's remote control server (see RemoteControlServer). */
class TvRemoteClient(
    host: String,
    port: Int,
    private val token: String? = null,
) {
    class HttpException(
        val code: Int,
    ) : IOException("HTTP $code")

    val baseUrl = "http://${if (':' in host) "[${host.substringBefore('%')}]" else host}:$port"

    suspend fun info(): TvInfo = get("/api/info")

    suspend fun state(): RemoteState = get("/api/state")

    suspend fun queue(): List<RemoteTrack> = get("/api/queue")

    suspend fun snapshot(): QueueSnapshot = get("/api/snapshot")

    suspend fun pairStart(name: String) {
        post("/api/pair/start", json.encodeToString(PairStartRequest(name)))
    }

    suspend fun pairFinish(
        code: String,
        name: String,
    ): PairFinishResponse = json.decodeFromString<PairFinishResponse>(post("/api/pair/finish", json.encodeToString(PairFinishRequest(code, name))))

    suspend fun load(snapshot: QueueSnapshot) {
        post("/api/load", json.encodeToString(snapshot))
    }

    suspend fun items(request: RemoteItemsRequest) {
        post("/api/items", json.encodeToString(request))
    }

    /** Sends this phone's library (from [gzippedLibrarySnapshot]) to be kept as its profile on the TV. */
    suspend fun uploadProfile(gzippedJson: ByteArray) {
        val request =
            request("/api/profile")
                .header("Content-Encoding", "gzip")
                .post(gzippedJson.toRequestBody(JSON))
                .build()
        withContext(Dispatchers.IO) {
            uploadHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw HttpException(response.code)
            }
        }
    }

    suspend fun command(
        action: String,
        a: Long = 0L,
        b: Long = 0L,
    ) {
        post("/api/cmd?action=$action&a=$a&b=$b", "")
    }

    /** Opens the push channel; [onState] and [onClosed] run on OkHttp's thread. */
    fun openPush(
        onState: (RemoteState) -> Unit,
        onClosed: () -> Unit,
    ): WebSocket =
        pushHttp.newWebSocket(
            request("/api/ws").build(),
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    runCatching { json.decodeFromString<RemoteState>(text) }.onSuccess(onState)
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) = onClosed()

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) = onClosed()
            },
        )

    private suspend inline fun <reified T> get(path: String): T {
        val body = execute(request(path).get().build())
        return json.decodeFromString<T>(body)
    }

    private suspend fun post(
        path: String,
        body: String,
    ): String = execute(request(path).post(body.toRequestBody(JSON)).build())

    private fun request(path: String) =
        Request
            .Builder()
            .url(baseUrl + path)
            .apply { token?.let { header("Authorization", "Bearer $it") } }

    private suspend fun execute(request: Request): String =
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw HttpException(response.code)
                response.body?.string().orEmpty()
            }
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        // LAN only: fail fast so a TV that went away is noticed quickly
        private val http =
            OkHttpClient
                .Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()

        private val uploadHttp =
            http
                .newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

        // The server pings every 15 s; OkHttp's own pings detect a dead TV from this side
        private val pushHttp =
            http
                .newBuilder()
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
    }
}
