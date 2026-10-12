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
import com.metrolist.music.ui.theme.DefaultThemeColor
import com.metrolist.music.ui.theme.extractThemeColor
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class RemoteTrack(
    val id: String,
    val title: String,
    val artist: String,
    val art: String?,
    val duration: Long,
    val pick: Boolean = false,
)

@Serializable
data class RemoteState(
    val track: RemoteTrack?,
    val playing: Boolean,
    val position: Long,
    val duration: Long,
    val shuffle: Boolean,
    val repeat: Int,
    val volume: Float,
    val queueTitle: String?,
    val index: Int,
    val queueHash: Int,
)

/** LAN web remote: serves assets/remote/index.html and a small JSON API guarded by [key]. */
class RemoteControlServer(
    private val service: MusicService,
    private val key: String,
) {
    private val json = Json { encodeDefaults = true }
    private val searchResults = ConcurrentHashMap<String, MediaMetadata>()
    @Volatile
    private var themeCache: Pair<String?, Map<String, Map<String, String>>>? = null
    private val page by lazy { service.assets.open("remote/index.html").bufferedReader().use { it.readText() } }

    private val server =
        embeddedServer(CIO, port = PORT) {
            routing {
                get("/") {
                    if (call.authorized()) call.respondText(page, ContentType.Text.Html)
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
                    call.respondJson(json.encodeToString(songs.map { it.toRemote() }))
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
            }
        }

    fun start() {
        server.start(wait = false)
    }

    fun stop() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    private suspend fun ApplicationCall.authorized(): Boolean {
        val given = request.queryParameters["key"].orEmpty()
        if (MessageDigest.isEqual(given.toByteArray(), key.toByteArray())) return true
        respond(HttpStatusCode.Forbidden)
        return false
    }

    private suspend fun ApplicationCall.respondJson(body: String) = respondText(body, ContentType.Application.Json)

    // ExoPlayer may only be touched from the main thread
    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private fun MediaMetadata.toRemote(
        pick: Boolean = false,
        artSize: Int = 120,
    ) = RemoteTrack(
        id = id,
        title = title,
        artist = artists.joinToString { it.name },
        art = thumbnailUrl?.resize(artSize, artSize),
        duration = duration * 1000L,
        pick = pick,
    )

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
        service.player.getQueueWindows().map { it.mediaItem.metadata!!.toRemote(pick = it.mediaItem.isUserPick) }

    private fun state(): RemoteState {
        val player = service.player
        val windows = player.getQueueWindows()
        return RemoteState(
            track = player.currentMetadata?.toRemote(artSize = 720),
            playing = player.playWhenReady,
            position = player.currentPosition,
            duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            shuffle = player.shuffleModeEnabled,
            repeat = player.repeatMode,
            volume = service.playerVolume.value,
            queueTitle = service.queueTitle,
            index = player.getCurrentQueueIndex(),
            queueHash = windows.map { it.uid to it.mediaItem.isUserPick }.hashCode(),
        )
    }

    private fun command(
        action: String,
        a: Long,
        b: Long,
        id: String?,
    ): Boolean {
        val listenTogether = service.listenTogetherManager
        if (listenTogether.isInRoom && !listenTogether.isHost) return false

        // shortcut: drives the local player only, so it does nothing useful while casting
        val player = service.player
        val windows = player.getQueueWindows()
        val mediaIndex = windows.getOrNull(a.toInt())?.firstPeriodIndex

        when (action) {
            "toggle" -> player.togglePlayPause()
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
            "clear" -> service.clearQueue()
            "jump" -> {
                player.seekToDefaultPosition(mediaIndex ?: return false)
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

        /** The phone's LAN address, e.g. http://192.168.1.23:8642/?key=..., or null when not on a network. */
        fun url(key: String): String? =
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.let { "http://${it.hostAddress}:$PORT/?key=$key" }
    }
}
