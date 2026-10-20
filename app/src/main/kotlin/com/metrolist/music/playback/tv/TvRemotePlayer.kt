/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.tv

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.metrolist.music.extensions.asUserPick
import com.metrolist.music.extensions.isUserPick
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A [Player] whose playback happens on a paired TV. State comes from the TV's push channel; every
 * call is sent to the TV's API. Swapped into the media session and the player UI while connected,
 * the way a Cast player would be, so notification, lock screen, volume keys and the app's player
 * all control the TV.
 */
@UnstableApi
class TvRemotePlayer(
    client: TvRemoteClient,
    private val scope: CoroutineScope,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    var client: TvRemoteClient = client
        internal set

    private var remote: RemoteState? = null
    private var receivedAt = 0L
    private var queueHash: Int? = null
    private var tracks: List<RemoteTrack> = emptyList()
    private var playlist: List<SimpleBasePlayer.MediaItemData> = emptyList()
    private var lastAudibleVolume = 1f

    private val applyLock = Mutex()
    private var pendingPush: RemoteState? = null
    private var syncing = false

    val queueTitle: String?
        get() = remote?.queueTitle

    private val _remoteVolume = MutableStateFlow(1f)

    /** The TV's app volume, 0..1, for the player menu's slider. */
    val remoteVolume: StateFlow<Float> = _remoteVolume.asStateFlow()

    /** Takes a pushed state; bursts collapse to the newest one. Main thread. */
    fun onPush(state: RemoteState) {
        pendingPush = state
        if (syncing) return
        syncing = true
        scope.launch {
            try {
                while (true) {
                    val next = pendingPush ?: break
                    pendingPush = null
                    applyState(next)
                    invalidateState()
                }
            } finally {
                syncing = false
            }
        }
    }

    suspend fun refresh() {
        applyState(client.state())
        invalidateState()
    }

    /** The TV can't be reached: show its last state as paused until it is back. */
    fun markOffline() {
        val state = remote ?: return
        remote = state.copy(position = currentPosition, playing = false)
        receivedAt = SystemClock.elapsedRealtime()
        invalidateState()
    }

    /** The last known queue and position, to continue on the phone. */
    fun lastSnapshot(): QueueSnapshot? {
        val state = remote ?: return null
        if (tracks.isEmpty()) return null
        return QueueSnapshot(
            title = state.queueTitle,
            items = tracks,
            index = state.index.coerceIn(tracks.indices),
            position = currentPosition,
            playing = state.playing,
        )
    }

    private suspend fun applyState(state: RemoteState) {
        applyLock.withLock {
            if (state.queueHash != queueHash) {
                val queue = runCatching { client.queue() }.getOrNull() ?: return
                setQueue(queue, state.queueHash)
            }
            remote = state
            receivedAt = SystemClock.elapsedRealtime()
            if (state.volume > 0f) lastAudibleVolume = state.volume
            _remoteVolume.value = state.volume
        }
    }

    private fun setQueue(
        queue: List<RemoteTrack>,
        hash: Int,
    ) {
        // Uids must be unique and stable, and the same song can be queued twice
        val occurrences = HashMap<String, Int>()
        playlist =
            queue.map { track ->
                val occurrence = (occurrences[track.id] ?: 0) + 1
                occurrences[track.id] = occurrence
                val item = track.toMediaMetadata().toMediaItem().let { if (track.pick) it.asUserPick() else it }
                SimpleBasePlayer.MediaItemData
                    .Builder("${track.id}#$occurrence")
                    .setMediaItem(item)
                    .setIsSeekable(true)
                    .setDurationUs(if (track.duration > 0) track.duration * 1000 else C.TIME_UNSET)
                    .build()
            }
        tracks = queue
        queueHash = hash
    }

    override fun getState(): SimpleBasePlayer.State {
        val builder =
            SimpleBasePlayer.State
                .Builder()
                .setAvailableCommands(COMMANDS)
                .setDeviceInfo(DEVICE_INFO)
        val state = remote
        if (state == null || playlist.isEmpty()) {
            return builder
                .setPlaybackState(Player.STATE_IDLE)
                .setDeviceVolume(((state?.volume ?: 1f) * MAX_VOLUME).roundToInt())
                .build()
        }
        val index = state.index.coerceIn(playlist.indices)
        val items =
            if (state.duration > 0) {
                playlist.toMutableList().also { it[index] = it[index].buildUpon().setDurationUs(state.duration * 1000).build() }
            } else {
                playlist
            }
        val advancing = state.playing && state.playbackState == Player.STATE_READY
        val elapsed = if (advancing) ((SystemClock.elapsedRealtime() - receivedAt) * state.speed).toLong() else 0L
        val position = state.position + elapsed
        return builder
            .setPlaylist(items)
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(
                if (advancing) SimpleBasePlayer.PositionSupplier.getExtrapolating(position, state.speed) else SimpleBasePlayer.PositionSupplier.getConstant(position),
            ).setPlayWhenReady(state.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(state.playbackState.takeIf { it in Player.STATE_IDLE..Player.STATE_ENDED } ?: Player.STATE_IDLE)
            .setShuffleModeEnabled(state.shuffle)
            .setRepeatMode(state.repeat)
            .setPlaybackParameters(PlaybackParameters(state.speed))
            .setDeviceVolume((state.volume * MAX_VOLUME).roundToInt())
            .setIsDeviceMuted(state.volume == 0f)
            .build()
    }

    /**
     * Sends a call to the TV. The returned future completes once the TV's new state is known, so
     * the UI keeps SimpleBasePlayer's optimistic placeholder state until then instead of flickering.
     */
    private fun send(block: suspend TvRemoteClient.() -> Unit): ListenableFuture<*> {
        val future = SettableFuture.create<Any?>()
        scope.launch {
            runCatching { client.block() }.onFailure { Timber.tag(TAG).w(it, "TV command failed") }
            runCatching { applyState(client.state()) }
            future.set(null)
        }
        return future
    }

    private fun setVolume(volume: Float) = send { command("volume", (volume.coerceIn(0f, 1f) * 100).roundToLong()) }

    private val volume: Float
        get() = remote?.volume ?: 1f

    override fun handleSetPlayWhenReady(playWhenReady: Boolean) = send { command(if (playWhenReady) "resume" else "pause") }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop() = send { command("pause") }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetRepeatMode(repeatMode: Int) = send { command("setRepeat", repeatMode.toLong()) }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean) = send { command("setShuffle", if (shuffleModeEnabled) 1L else 0L) }

    override fun handleSetDeviceVolume(
        deviceVolume: Int,
        flags: Int,
    ) = setVolume(deviceVolume.toFloat() / MAX_VOLUME)

    override fun handleIncreaseDeviceVolume(flags: Int) = setVolume(volume + 1f / MAX_VOLUME)

    override fun handleDecreaseDeviceVolume(flags: Int) = setVolume(volume - 1f / MAX_VOLUME)

    override fun handleSetDeviceMuted(
        muted: Boolean,
        flags: Int,
    ) = setVolume(if (muted) 0f else lastAudibleVolume)

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val currentIndex = remote?.index ?: return Futures.immediateVoidFuture()
        val position = positionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        return send {
            when {
                seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> command("next")
                mediaItemIndex == C.INDEX_UNSET -> Unit
                mediaItemIndex == currentIndex -> command("seek", position)
                else -> command("jumpTo", mediaItemIndex.toLong(), position)
            }
        }
    }

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int,
    ) = send {
        val count = toIndex - fromIndex
        repeat(count) { i ->
            if (newIndex > fromIndex) {
                command("move", fromIndex.toLong(), (newIndex + count - 1).toLong())
            } else {
                command("move", (fromIndex + i).toLong(), (newIndex + i).toLong())
            }
        }
    }

    override fun handleRemoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
    ) = send { repeat(toIndex - fromIndex) { command("remove", fromIndex.toLong()) } }

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        val mode = if (index >= mediaItemCount) RemoteItemsRequest.MODE_ADD else RemoteItemsRequest.MODE_AT
        val tracks = mediaItems.toRemoteTracks()
        return send { items(RemoteItemsRequest(tracks, mode, index)) }
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) = send {
        load(
            QueueSnapshot(
                items = mediaItems.toRemoteTracks(),
                index = startIndex.coerceAtLeast(0),
                position = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
            ),
        )
    }

    // Metadata refreshes only matter locally; the TV keeps its own copy
    override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun List<MediaItem>.toRemoteTracks() = mapNotNull { item -> item.metadata?.toRemoteTrack(pick = item.isUserPick) }

    companion object {
        private const val TAG = "TvRemotePlayer"
        const val MAX_VOLUME = 25

        private val DEVICE_INFO =
            DeviceInfo
                .Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                .setMinVolume(0)
                .setMaxVolume(MAX_VOLUME)
                .build()

        @Suppress("DEPRECATION")
        private val COMMANDS =
            Player.Commands
                .Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SET_SHUFFLE_MODE,
                    Player.COMMAND_SET_REPEAT_MODE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_SET_MEDIA_ITEM,
                    Player.COMMAND_CHANGE_MEDIA_ITEMS,
                    Player.COMMAND_GET_DEVICE_VOLUME,
                    Player.COMMAND_SET_DEVICE_VOLUME,
                    Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                    Player.COMMAND_ADJUST_DEVICE_VOLUME,
                    Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                    Player.COMMAND_RELEASE,
                ).build()
    }
}
