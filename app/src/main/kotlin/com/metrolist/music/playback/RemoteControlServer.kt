/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.constants.AutoRadioQueueKey
import com.metrolist.music.extensions.asUserPick
import com.metrolist.music.extensions.currentMetadata
import com.metrolist.music.extensions.getCurrentQueueIndex
import com.metrolist.music.extensions.getQueueWindows
import com.metrolist.music.extensions.isUserPick
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.move
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.playback.tv.LimitedInputStream
import com.metrolist.music.playback.tv.PairFinishRequest
import com.metrolist.music.playback.tv.PairFinishResponse
import com.metrolist.music.playback.tv.PairStartRequest
import com.metrolist.music.playback.tv.PairedDevice
import com.metrolist.music.playback.tv.QueueSnapshot
import com.metrolist.music.playback.tv.RemoteItemsRequest
import com.metrolist.music.playback.tv.RemoteState
import com.metrolist.music.playback.tv.RemoteTrack
import com.metrolist.music.playback.tv.TvInfo
import com.metrolist.music.playback.tv.TvPairing
import com.metrolist.music.playback.tv.TvProfileStore
import com.metrolist.music.playback.tv.toMediaMetadata
import com.metrolist.music.playback.tv.toRemoteTrack
import com.metrolist.music.ui.theme.DefaultThemeColor
import com.metrolist.music.ui.theme.extractThemeColor
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * LAN remote: serves assets/remote/index.html and a small JSON API. Browsers authenticate with
 * [browserKey] (`?key=`); phones paired through [pairing] (TV only) send their token as a bearer
 * token. `/api/ws` pushes the player state whenever it changes, so clients don't poll.
 */
class RemoteControlServer(
    private val service: MusicService,
    private val browserKey: String?,
    private val pairing: TvPairing?,
    private val tvId: String? = null,
    private val tvName: String? = null,
    private val profiles: TvProfileStore? = null,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val searchResults = ConcurrentHashMap<String, MediaMetadata>()
    @Volatile
    private var themeCache: Pair<String?, Map<String, Map<String, String>>>? = null
    private val page by lazy { service.assets.open("remote/index.html").bufferedReader().use { it.readText() } }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val stateVersion = MutableStateFlow(0L)
    private val playerListener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                if (events.containsAny(*PUSHED_EVENTS)) stateVersion.value++
            }
        }

    private val server =
        embeddedServer(CIO, port = PORT) {
            install(WebSockets) {
                pingPeriodMillis = 15_000L
                timeoutMillis = 30_000L
            }
            routing {
                get("/") {
                    if (call.authorized()) call.respondText(page, ContentType.Text.Html)
                }
                get("/api/info") {
                    if (!call.fromLan() || tvId == null) return@get call.respond(HttpStatusCode.NotFound)
                    call.respondJson(json.encodeToString(TvInfo(id = tvId, name = tvName.orEmpty(), paired = call.hasPairedToken())))
                }
                post("/api/pair/start") {
                    val pairing = pairing
                    if (!call.fromLan() || pairing == null) return@post call.respond(HttpStatusCode.NotFound)
                    val request = runCatching { json.decodeFromString<PairStartRequest>(call.receiveText()) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    call.respond(if (pairing.start(request.name)) HttpStatusCode.NoContent else HttpStatusCode.TooManyRequests)
                }
                post("/api/pair/finish") {
                    val pairing = pairing
                    if (!call.fromLan() || pairing == null || tvId == null) return@post call.respond(HttpStatusCode.NotFound)
                    val request = runCatching { json.decodeFromString<PairFinishRequest>(call.receiveText()) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    when (val result = pairing.finish(request.code, request.name)) {
                        is TvPairing.Result.Paired ->
                            call.respondJson(json.encodeToString(PairFinishResponse(result.token, tvId, tvName.orEmpty())))
                        TvPairing.Result.WrongCode -> call.respond(HttpStatusCode.Unauthorized)
                        TvPairing.Result.Expired -> call.respond(HttpStatusCode.Gone)
                        TvPairing.Result.Locked -> call.respond(HttpStatusCode.TooManyRequests)
                    }
                }
                get("/api/state") {
                    if (call.authorized()) call.respondJson(json.encodeToString(onMain { state() }))
                }
                get("/api/queue") {
                    if (call.authorized()) call.respondJson(json.encodeToString(onMain { queue() }))
                }
                get("/api/theme") {
                    if (call.authorized()) call.respondJson(json.encodeToString(theme(onMain { service.player.currentMetadata?.thumbnailUrl })))
                }
                get("/api/search") {
                    if (!call.authorized()) return@get
                    val query = call.request.queryParameters["q"].orEmpty()
                    val songs =
                        YouTube.search(query, FILTER_SONG).getOrNull()
                            ?.items
                            ?.filterIsInstance<SongItem>()
                            ?.map { it.toMediaMetadata() }
                            .orEmpty()
                    songs.forEach { searchResults[it.id] = it }
                    call.respondJson(json.encodeToString(songs.map { it.toRemoteTrack() }))
                }
                post("/api/cmd") {
                    if (!call.authorized()) return@post
                    val params = call.request.queryParameters
                    val ok =
                        onMain {
                            command(
                                action = params["action"].orEmpty(),
                                a = params["a"]?.toLongOrNull() ?: 0L,
                                b = params["b"]?.toLongOrNull() ?: 0L,
                                id = params["id"],
                            )
                        }
                    call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.BadRequest)
                }
                get("/api/snapshot") {
                    if (call.authorized()) call.respondJson(json.encodeToString(onMain { service.queueSnapshot() }))
                }
                post("/api/load") {
                    if (!call.authorized()) return@post
                    val snapshot = runCatching { json.decodeFromString<QueueSnapshot>(call.receiveText()) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val ok =
                        onMain {
                            if (!canControl()) return@onMain false
                            service.playSnapshot(snapshot)
                            service.remoteQueueLoaded.tryEmit(Unit)
                            true
                        }
                    call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.Conflict)
                }
                post("/api/items") {
                    if (!call.authorized()) return@post
                    val request = runCatching { json.decodeFromString<RemoteItemsRequest>(call.receiveText()) }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val ok = onMain { canControl() && addItems(request) }
                    call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.BadRequest)
                }
                // A paired phone's library, stored as that phone's profile. Body: (gzipped) LibrarySnapshot JSON.
                post("/api/profile") {
                    val profiles = profiles ?: return@post call.respond(HttpStatusCode.NotFound)
                    val device = if (call.fromLan()) call.pairedDevice() else null
                    if (device == null) return@post call.respond(HttpStatusCode.Forbidden)
                    val gzipped = call.request.headers[HttpHeaders.ContentEncoding].equals("gzip", ignoreCase = true)
                    val channel = call.receiveChannel()
                    val library =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                LimitedInputStream(channel.toInputStream(), MAX_PROFILE_UPLOAD_BYTES).use { raw ->
                                    val body = if (gzipped) LimitedInputStream(GZIPInputStream(raw), MAX_PROFILE_JSON_BYTES) else raw
                                    TvProfileStore.decode(body.buffered())
                                }
                            }.getOrNull()
                        } ?: return@post call.respond(HttpStatusCode.BadRequest)
                    profiles.save(device.id, device.name, library.capped())
                    call.respond(HttpStatusCode.NoContent)
                }
                webSocket("/api/ws") {
                    if (!call.hasAccess()) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "forbidden"))
                        return@webSocket
                    }
                    // Re-checked when paired devices change, so revoking a phone drops its connection
                    val devices: Flow<Any> = pairing?.devices ?: flowOf(Unit)
                    val push =
                        launch {
                            runCatching {
                                combine(stateVersion, devices) { version, _ -> version }.collect {
                                    if (!call.hasAccess()) {
                                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "revoked"))
                                        return@collect
                                    }
                                    send(Frame.Text(json.encodeToString(onMain { state() })))
                                    // Lets bursts of player events collapse into one update
                                    delay(PUSH_COALESCE_MS)
                                }
                            }
                        }
                    try {
                        for (frame in incoming) Unit
                    } finally {
                        push.cancel()
                    }
                }
            }
        }

    fun start() {
        server.start(wait = false)
        scope.launch {
            var attached: Player? = null
            try {
                service.playerFlow.collect { player ->
                    attached?.removeListener(playerListener)
                    attached = player
                    player?.addListener(playerListener)
                    stateVersion.value++
                }
            } finally {
                attached?.removeListener(playerListener)
            }
        }
        scope.launch { service.playerVolume.collect { stateVersion.value++ } }
    }

    fun stop() {
        scope.cancel()
        server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    private fun ApplicationCall.fromLan(): Boolean {
        val ip = request.local.remoteAddress
        // Only parse literal addresses; anything else would trigger a DNS lookup
        if (ip.isEmpty() || !(ip[0].isDigit() || ':' in ip)) return false
        val address = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return false
        return address.isLoopbackAddress ||
            address.isSiteLocalAddress ||
            address.isLinkLocalAddress ||
            // IPv6 unique local addresses, fc00::/7
            (address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC)
    }

    private suspend fun ApplicationCall.hasPairedToken(): Boolean = pairedDevice() != null

    private suspend fun ApplicationCall.pairedDevice(): PairedDevice? {
        val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer")?.trim().orEmpty()
        return pairing?.deviceFor(token)
    }

    private suspend fun ApplicationCall.hasAccess(): Boolean {
        if (!fromLan()) return false
        val key = browserKey?.takeIf { it.isNotEmpty() }
        val given = request.queryParameters["key"]
        if (key != null && given != null && MessageDigest.isEqual(given.toByteArray(), key.toByteArray())) return true
        return hasPairedToken()
    }

    private suspend fun ApplicationCall.authorized(): Boolean {
        if (hasAccess()) return true
        respond(HttpStatusCode.Forbidden)
        return false
    }

    private suspend fun ApplicationCall.respondJson(body: String) = respondText(body, ContentType.Application.Json)

    // ExoPlayer may only be touched from the main thread
    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    // Same Material 3 scheme the app builds from the artwork (see MainActivity / MetrolistTheme)
    private suspend fun theme(artUrl: String?): Map<String, Map<String, String>> {
        themeCache?.takeIf { it.first == artUrl }?.let { return it.second }
        val seed =
            artUrl?.let {
                runCatching {
                    service.imageLoader
                        .execute(ImageRequest.Builder(service).data(it).allowHardware(false).build())
                        .image
                        ?.toBitmap()
                        ?.extractThemeColor()
                }.getOrNull()
            } ?: DefaultThemeColor
        val schemes =
            mapOf(false to "light", true to "dark").entries.associate { (isDark, name) ->
                val scheme =
                    dynamicColorScheme(
                        seedColor = seed,
                        isDark = isDark,
                        specVersion = ColorSpec.SpecVersion.SPEC_2025,
                        style = PaletteStyle.TonalSpot,
                    )
                name to
                    mapOf(
                        "primary" to scheme.primary,
                        "on-primary" to scheme.onPrimary,
                        "primary-container" to scheme.primaryContainer,
                        "on-primary-container" to scheme.onPrimaryContainer,
                        "secondary-container" to scheme.secondaryContainer,
                        "on-secondary-container" to scheme.onSecondaryContainer,
                        "surface" to scheme.surface,
                        "surface-container-low" to scheme.surfaceContainerLow,
                        "surface-container" to scheme.surfaceContainer,
                        "surface-container-high" to scheme.surfaceContainerHigh,
                        "surface-container-highest" to scheme.surfaceContainerHighest,
                        "on-surface" to scheme.onSurface,
                        "on-surface-variant" to scheme.onSurfaceVariant,
                        "outline" to scheme.outline,
                        "outline-variant" to scheme.outlineVariant,
                        "inverse-surface" to scheme.inverseSurface,
                        "inverse-on-surface" to scheme.inverseOnSurface,
                    ).mapValues { (_, color) -> "#%06X".format(color.toArgb() and 0xFFFFFF) }
            }
        themeCache = artUrl to schemes
        return schemes
    }

    private fun queue(): List<RemoteTrack> =
        service.player.getQueueWindows().map { it.mediaItem.metadata!!.toRemoteTrack(pick = it.mediaItem.isUserPick) }

    private fun state(): RemoteState {
        val player = service.player
        val windows = player.getQueueWindows()
        return RemoteState(
            track = player.currentMetadata?.toRemoteTrack(artSize = 720),
            playing = player.playWhenReady,
            position = player.currentPosition,
            duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            shuffle = player.shuffleModeEnabled,
            repeat = player.repeatMode,
            volume = service.playerVolume.value,
            queueTitle = service.queueTitle,
            index = player.getCurrentQueueIndex(),
            queueHash = windows.map { it.uid to it.mediaItem.isUserPick }.hashCode(),
            playbackState = player.playbackState,
            speed = player.playbackParameters.speed,
        )
    }

    private fun canControl(): Boolean {
        val listenTogether = service.listenTogetherManager
        return !listenTogether.isInRoom || listenTogether.isHost
    }

    private fun addItems(request: RemoteItemsRequest): Boolean {
        val items =
            request.items.map { track ->
                track.toMediaMetadata().toMediaItem().let { if (track.pick) it.asUserPick() else it }
            }
        if (items.isEmpty()) return false
        val player = service.player
        when (request.mode) {
            RemoteItemsRequest.MODE_NEXT -> service.playNext(items)
            RemoteItemsRequest.MODE_ADD -> service.addToQueue(items)
            RemoteItemsRequest.MODE_AT -> {
                val windows = player.getQueueWindows()
                if (player.shuffleModeEnabled || request.index !in 0..windows.size) {
                    service.addToQueue(items)
                } else {
                    // Without shuffle the play order is the playlist order
                    player.addMediaItems(request.index, items)
                }
            }
            else -> return false
        }
        return true
    }

    private fun command(
        action: String,
        a: Long,
        b: Long,
        id: String?,
    ): Boolean {
        if (!canControl()) return false

        // shortcut: drives the local player only, so it does nothing useful while casting
        val player = service.player
        val windows = player.getQueueWindows()
        val mediaIndex = windows.getOrNull(a.toInt())?.firstPeriodIndex

        when (action) {
            "toggle" -> player.togglePlayPause()
            "resume" -> player.resume()
            "pause" -> player.pause()
            "next" -> {
                player.seekToNext()
                player.resume()
            }
            "prev" -> {
                player.seekToPrevious()
                player.resume()
            }
            "seek" -> player.seekTo(a)
            "volume" -> service.playerVolume.value = (a / 100f).coerceIn(0f, 1f)
            "shuffle" -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            "repeat" -> player.toggleRepeatMode()
            "setShuffle" -> player.shuffleModeEnabled = a != 0L
            "setRepeat" -> player.repeatMode = a.toInt().coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)
            "clear" -> service.clearQueue()
            "radio" -> service.startRadioSeamlessly()
            "jump" -> {
                player.seekToDefaultPosition(mediaIndex ?: return false)
                player.resume()
            }
            "jumpTo" -> {
                player.seekTo(mediaIndex ?: return false, b.coerceAtLeast(0L))
                player.resume()
            }
            "remove" -> player.removeMediaItem(mediaIndex ?: return false)
            "move" -> {
                val to = b.toInt()
                if (mediaIndex == null || to !in windows.indices) return false
                if (player.shuffleModeEnabled) {
                    val order = windows.map { it.firstPeriodIndex }.toMutableList().move(a.toInt(), to)
                    player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), System.currentTimeMillis()))
                } else {
                    player.moveMediaItem(a.toInt(), to)
                }
            }
            "play", "playNext", "add" -> {
                val song = searchResults[id] ?: return false
                when (action) {
                    "playNext" -> service.playNext(listOf(song.toMediaItem()))
                    "add" -> service.addToQueue(listOf(song.toMediaItem()))
                    else ->
                        service.playQueue(
                            if (service.dataStore.get(AutoRadioQueueKey, true)) {
                                YouTubeQueue(WatchEndpoint(videoId = song.id), song)
                            } else {
                                ListQueue(title = song.title, items = listOf(song.toMediaItem()))
                            },
                        )
                }
            }
            else -> return false
        }
        return true
    }

    private fun Player.resume() {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) prepare()
        playWhenReady = true
    }

    companion object {
        const val PORT = 8642
        private const val MAX_PROFILE_UPLOAD_BYTES = 16L * 1024 * 1024
        private const val MAX_PROFILE_JSON_BYTES = 64L * 1024 * 1024
        private const val PUSH_COALESCE_MS = 100L
        private val PUSHED_EVENTS =
            intArrayOf(
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
            )

        /** The phone's LAN address, e.g. http://192.168.1.23:8642/?key=..., or null when not on a network. */
        fun url(key: String): String? =
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.let { "http://${it.hostAddress}:$PORT/?key=$key" }
    }
}
