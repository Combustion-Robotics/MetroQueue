/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.tv

import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.metrolist.music.R
import com.metrolist.music.constants.PairedTvsKey
import com.metrolist.music.constants.TvAutoSyncLibraryKey
import com.metrolist.music.constants.TvLibrarySyncedKey
import com.metrolist.music.extensions.metadata
import com.metrolist.music.playback.MusicService
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.WebSocket
import timber.log.Timber

/**
 * Phone side of "Play on TV": pairs with TVs, connects to one and swaps a [TvRemotePlayer] in for
 * the local player while connected. Reconnects when the push channel drops and, if the TV stays
 * gone, continues locally (paused) from the TV's last known queue.
 */
@UnstableApi
class TvRemoteManager(
    private val service: MusicService,
    private val scope: CoroutineScope,
) {
    sealed interface Status {
        data object Idle : Status

        data class Connecting(
            val tv: PairedTv,
        ) : Status

        data class Connected(
            val tv: PairedTv,
        ) : Status

        data class Reconnecting(
            val tv: PairedTv,
        ) : Status
    }

    class NotPairedException : IllegalStateException("Not paired")

    private val json = Json { ignoreUnknownKeys = true }

    val discovery = TvDiscovery(service)

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _player = MutableStateFlow<TvRemotePlayer?>(null)

    /** The player standing in for the local one while connected. */
    val player: StateFlow<TvRemotePlayer?> = _player.asStateFlow()

    val pairedTvs: StateFlow<List<PairedTv>> =
        service.dataStore.data
            .map { decode(it[PairedTvsKey]) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Lazily, emptyList())

    /** When this phone's library was last synced to each TV, by TV id. */
    val librarySyncTimes: StateFlow<Map<String, Long>> =
        service.dataStore.data
            .map { prefs ->
                prefs[TvLibrarySyncedKey]
                    ?.let { runCatching { json.decodeFromString<Map<String, Long>>(it) }.getOrNull() }
                    .orEmpty()
            }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Lazily, emptyMap())

    private val _syncingLibrary = MutableStateFlow(false)
    val syncingLibrary: StateFlow<Boolean> = _syncingLibrary.asStateFlow()

    private var tv: PairedTv? = null
    private var socket: WebSocket? = null
    private var pushGeneration = 0
    private var reconnectJob: Job? = null

    val isConnected: Boolean
        get() = _player.value != null

    /** Identifies the TV at [host]. */
    suspend fun lookup(
        host: String,
        port: Int,
    ): TvInfo = TvRemoteClient(host, port).info()

    /** Asks [host] to show a pairing code. */
    suspend fun requestCode(
        host: String,
        port: Int,
    ) {
        TvRemoteClient(host, port).pairStart(service.deviceName())
    }

    suspend fun pair(
        host: String,
        port: Int,
        code: String,
    ): PairedTv {
        val response = TvRemoteClient(host, port).pairFinish(code, service.deviceName())
        val tv = PairedTv(response.tvId, response.tvName, response.token, host, port)
        savePaired(tv)
        return tv
    }

    suspend fun forget(tv: PairedTv) {
        if (this.tv?.id == tv.id) disconnect(playHere = false)
        service.safeDataStoreEdit { prefs ->
            prefs[PairedTvsKey] = json.encodeToString(decode(prefs[PairedTvsKey]).filterNot { it.id == tv.id })
        }
    }

    /** Connects to [target]; with [transfer], what plays on the phone moves to the TV. Main thread. */
    suspend fun connect(
        target: PairedTv,
        transfer: Boolean,
    ) {
        if (isConnected) teardown()
        _status.value = Status.Connecting(target)
        try {
            val client = TvRemoteClient(target.host, target.port, target.token)
            val info = client.info()
            if (!info.paired) {
                forget(target)
                throw NotPairedException()
            }
            if (transfer && service.player.mediaItemCount > 0) {
                client.load(service.queueSnapshot().copy(playing = true))
            }
            val remotePlayer = TvRemotePlayer(client, scope)
            remotePlayer.refresh()
            val connected = target.copy(name = info.name.ifBlank { target.name })
            tv = connected
            service.setSessionPlayer(remotePlayer)
            _player.value = remotePlayer
            openPush(client)
            _status.value = Status.Connected(connected)
            // Remembers where the TV was found and its current name
            savePaired(connected)
            if (service.dataStore.data.first()[TvAutoSyncLibraryKey] == true) {
                scope.launch { runCatching { syncLibrary(connected) }.onFailure { Timber.tag(TAG).w(it, "Library sync failed") } }
            }
        } catch (e: Exception) {
            if (_player.value == null) _status.value = Status.Idle
            throw e
        }
    }

    /** Leaves the TV; with [playHere] its queue continues on the phone and the TV pauses. */
    suspend fun disconnect(playHere: Boolean) {
        val remotePlayer = _player.value ?: return
        val snapshot =
            if (playHere) {
                runCatching { remotePlayer.client.snapshot() }.getOrNull() ?: remotePlayer.lastSnapshot()
            } else {
                null
            }
        if (playHere) runCatching { remotePlayer.client.command("pause") }
        teardown()
        snapshot?.let { service.playSnapshot(it.copy(playing = true)) }
    }

    /** Sends this phone's library to [target], which keeps it as this phone's profile. */
    suspend fun syncLibrary(target: PairedTv) {
        if (!_syncingLibrary.compareAndSet(expect = false, update = true)) return
        try {
            val upload = service.database.gzippedLibrarySnapshot()
            TvRemoteClient(target.host, target.port, target.token).uploadProfile(upload)
            service.safeDataStoreEdit { prefs ->
                val times =
                    prefs[TvLibrarySyncedKey]
                        ?.let { runCatching { json.decodeFromString<Map<String, Long>>(it) }.getOrNull() }
                        .orEmpty()
                prefs[TvLibrarySyncedKey] = json.encodeToString(times + (target.id to System.currentTimeMillis()))
            }
        } finally {
            _syncingLibrary.value = false
        }
    }

    /** Disconnects and leaves the TV playing. */
    fun leave() {
        if (isConnected) teardown()
    }

    fun playQueue(queue: Queue) =
        sendToTv {
            val snapshot =
                if (queue is YouTubeQueue) {
                    QueueSnapshot(endpoint = queue.currentEndpoint, preload = queue.preloadItem?.toRemoteTrack())
                } else {
                    val status = withContext(Dispatchers.IO) { queue.getInitialStatus() }
                    QueueSnapshot(
                        title = status.title,
                        items = status.items.toRemoteTracks(),
                        index = status.mediaItemIndex,
                        position = status.position,
                    )
                }
            load(snapshot)
        }

    fun playNext(items: List<MediaItem>) = sendToTv { items(RemoteItemsRequest(items.toRemoteTracks(), RemoteItemsRequest.MODE_NEXT)) }

    fun addToQueue(items: List<MediaItem>) = sendToTv { items(RemoteItemsRequest(items.toRemoteTracks(), RemoteItemsRequest.MODE_ADD)) }

    /** Replaces what comes after the TV's current song with a radio based on it. */
    fun startRadio() = sendToTv { command("radio") }

    fun release() {
        teardown()
    }

    private fun sendToTv(block: suspend TvRemoteClient.() -> Unit) {
        val client = _player.value?.client ?: return
        scope.launch {
            runCatching { client.block() }.onFailure {
                Timber.tag(TAG).w(it, "Sending to the TV failed")
                Toast.makeText(service, R.string.tv_command_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openPush(client: TvRemoteClient) {
        val generation = ++pushGeneration
        socket?.cancel()
        socket =
            client.openPush(
                onState = { state ->
                    scope.launch {
                        if (generation != pushGeneration) return@launch
                        _player.value?.onPush(state)
                        val current = tv
                        if (current != null && _status.value is Status.Reconnecting) _status.value = Status.Connected(current)
                    }
                },
                onClosed = {
                    scope.launch { if (generation == pushGeneration) reconnect() }
                },
            )
    }

    private fun reconnect() {
        val target = tv ?: return
        val remotePlayer = _player.value ?: return
        if (reconnectJob?.isActive == true) return
        _status.value = Status.Reconnecting(target)
        remotePlayer.markOffline()
        reconnectJob =
            scope.launch {
                // Discovery finds the TV again if its address changed
                discovery.start()
                try {
                    for (wait in RECONNECT_DELAYS_MS) {
                        delay(wait)
                        val host = discovery.tvs.value.firstOrNull { it.id == target.id }
                        val client =
                            TvRemoteClient(host?.host ?: target.host, host?.port ?: target.port, target.token)
                        val info = runCatching { client.info() }.getOrNull() ?: continue
                        if (!info.paired) break
                        remotePlayer.client = client
                        runCatching { remotePlayer.refresh() }
                        if (host != null && host.host != target.host) savePaired(target.copy(host = host.host, port = host.port))
                        openPush(client)
                        return@launch
                    }
                    fallBackToPhone(remotePlayer)
                } finally {
                    discovery.stop()
                }
            }
    }

    private fun fallBackToPhone(remotePlayer: TvRemotePlayer) {
        val snapshot = remotePlayer.lastSnapshot()
        reconnectJob = null
        teardown()
        snapshot?.let { service.playSnapshot(it.copy(playing = false)) }
        Toast.makeText(service, R.string.tv_connection_lost, Toast.LENGTH_LONG).show()
    }

    private fun teardown() {
        pushGeneration++
        socket?.close(1000, null)
        socket = null
        reconnectJob?.cancel()
        reconnectJob = null
        val remotePlayer = _player.value
        if (remotePlayer != null) {
            _player.value = null
            service.setSessionPlayer(null)
            remotePlayer.release()
        }
        tv = null
        _status.value = Status.Idle
    }

    private suspend fun savePaired(tv: PairedTv) {
        service.safeDataStoreEdit { prefs ->
            prefs[PairedTvsKey] = json.encodeToString(decode(prefs[PairedTvsKey]).filterNot { it.id == tv.id } + tv)
        }
    }

    private fun decode(raw: String?): List<PairedTv> =
        raw?.let { runCatching { json.decodeFromString<List<PairedTv>>(it) }.getOrNull() }.orEmpty()

    private fun List<MediaItem>.toRemoteTracks() = mapNotNull { it.metadata?.toRemoteTrack() }

    private companion object {
        const val TAG = "TvRemoteManager"
        val RECONNECT_DELAYS_MS = listOf(1_000L, 2_000L, 3_000L, 5_000L, 8_000L, 10_000L)
    }
}
