/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.tv

import com.metrolist.music.playback.mix.CustomTransition
import com.metrolist.music.ui.vibe.Vibe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Version of the [SyncState] format served at `/api/sync` and advertised as [TvInfo.sync]. */
const val SYNC_VERSION = 1

/** One play: [timestamp] is when it was played (epoch ms), [playTime] how long (ms). */
@Serializable
data class SyncHistoryItem(
    val songId: String,
    val timestamp: Long,
    val playTime: Long = 0L,
)

@Serializable
data class SyncSetting(
    val value: JsonElement = JsonNull,
    val updatedAt: Long = 0L,
)

/** When items were deleted (epoch ms), by transition pair key or vibe id. */
@Serializable
data class SyncTombstones(
    val transitions: Map<String, Long> = emptyMap(),
    val vibes: Map<String, Long> = emptyMap(),
)

@Serializable
data class SyncAlbum(
    val id: String? = null,
    val title: String = "",
)

/** A playlist entry; [local] = a local file of the sending device (other devices may not play it). */
@Serializable
data class SyncPlaylistSong(
    val id: String,
    val title: String = "",
    val artists: List<RemoteArtist> = emptyList(),
    val album: SyncAlbum? = null,
    val thumbnail: String? = null,
    val durationMs: Long = 0L,
    val local: Boolean = false,
)

/** A local, editable playlist; [deleted] = tombstone, deleted at [updatedAt]. */
@Serializable
data class SyncPlaylist(
    val id: String,
    val name: String = "",
    val updatedAt: Long = 0L,
    val deleted: Boolean = false,
    val thumbnailUrl: String? = null,
    val songs: List<SyncPlaylistSong> = emptyList(),
)

/** MetroQueue-only data exchanged with a paired receiver (see SYNC.md). */
@Serializable
data class SyncState(
    val version: Int = SYNC_VERSION,
    val now: Long = 0L,
    val history: List<SyncHistoryItem> = emptyList(),
    val songs: List<RemoteTrack> = emptyList(),
    val transitions: List<CustomTransition> = emptyList(),
    val vibes: List<Vibe> = emptyList(),
    val settings: Map<String, SyncSetting> = emptyMap(),
    val deleted: SyncTombstones = SyncTombstones(),
    val playlists: List<SyncPlaylist> = emptyList(),
)

/** Last-writer-wins merging, the same on both sides, so a sync converges. */
object SyncMerge {
    const val MAX_TOMBSTONES = 500

    class Merged<T>(
        val items: Map<String, T>,
        val deleted: Map<String, Long>,
    )

    /**
     * Keeps the newer copy of each item and drops items deleted at or after their last change.
     * On equal times [remoteWinsTies] decides; the phone passes true so it adopts the receiver's
     * copy and then sends that back, so both end up equal. Tombstones of surviving items are
     * dropped and the rest capped to the newest [MAX_TOMBSTONES].
     */
    fun <T> merge(
        local: Map<String, T>,
        localDeleted: Map<String, Long>,
        remote: Map<String, T>,
        remoteDeleted: Map<String, Long>,
        remoteWinsTies: Boolean,
        updatedAt: (T) -> Long,
    ): Merged<T> {
        val deleted = HashMap(localDeleted)
        remoteDeleted.forEach { (key, time) -> deleted.merge(key, time, ::maxOf) }
        val items = LinkedHashMap<String, T>()
        for (key in local.keys + remote.keys) {
            val mine = local[key]
            val theirs = remote[key]
            val winner =
                when {
                    mine == null -> theirs!!
                    theirs == null -> mine
                    updatedAt(theirs) > updatedAt(mine) -> theirs
                    updatedAt(theirs) == updatedAt(mine) && remoteWinsTies -> theirs
                    else -> mine
                }
            val deletedAt = deleted[key]
            if (deletedAt == null || updatedAt(winner) > deletedAt) items[key] = winner
        }
        return Merged(items, cap(deleted.filterKeys { it !in items }))
    }

    /**
     * [merge] for playlists, whose tombstones travel in the same list ([SyncPlaylist.deleted]).
     * Remote playlists with an id in [blocked] (not a synced playlist here) are ignored.
     */
    fun playlists(
        local: Map<String, SyncPlaylist>,
        localDeleted: Map<String, Long>,
        remote: List<SyncPlaylist>,
        remoteWinsTies: Boolean,
        blocked: Set<String> = emptySet(),
    ): Merged<SyncPlaylist> {
        val (remoteDeleted, remoteItems) = remote.filter { it.id.isNotBlank() && it.id !in blocked }.partition { it.deleted }
        return merge(
            local,
            localDeleted,
            remoteItems.associateBy { it.id },
            remoteDeleted.groupingBy { it.id }.fold(0L) { time, it -> maxOf(time, it.updatedAt) },
            remoteWinsTies,
        ) { it.updatedAt }
    }

    /** Playlists as sent: the items, then a tombstone per deletion. */
    fun playlistsWithTombstones(
        items: Collection<SyncPlaylist>,
        deleted: Map<String, Long>,
    ): List<SyncPlaylist> = items + deleted.map { (id, time) -> SyncPlaylist(id, updatedAt = time, deleted = true) }

    /** The newest [MAX_TOMBSTONES] tombstones. */
    fun cap(tombstones: Map<String, Long>): Map<String, Long> =
        if (tombstones.size <= MAX_TOMBSTONES) {
            tombstones
        } else {
            tombstones.entries.sortedByDescending { it.value }.take(MAX_TOMBSTONES).associate { it.key to it.value }
        }

    /** Plays in [remote] not yet in [local], one per (songId, timestamp). */
    fun newHistory(
        local: Set<Pair<String, Long>>,
        remote: List<SyncHistoryItem>,
    ): List<SyncHistoryItem> =
        remote
            .filter { it.songId.isNotBlank() && it.timestamp > 0 && (it.songId to it.timestamp) !in local }
            .distinctBy { it.songId to it.timestamp }
}
