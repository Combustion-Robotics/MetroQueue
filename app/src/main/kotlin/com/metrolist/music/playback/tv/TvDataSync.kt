/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.tv

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.CrossfadeDurationKey
import com.metrolist.music.constants.CrossfadeEnabledKey
import com.metrolist.music.constants.CrossfadeGaplessKey
import com.metrolist.music.constants.CrossfadeOnSkipKey
import com.metrolist.music.constants.DynamicThemeKey
import com.metrolist.music.constants.ExperimentalLyricsKey
import com.metrolist.music.constants.LyricsEffects
import com.metrolist.music.constants.LyricsEffectsKey
import com.metrolist.music.constants.MixMode
import com.metrolist.music.constants.MixModeKey
import com.metrolist.music.constants.MixTransitionStyle
import com.metrolist.music.constants.MixTransitionStyleKey
import com.metrolist.music.constants.PureBlackKey
import com.metrolist.music.constants.SelectedThemeColorKey
import com.metrolist.music.constants.SyncSettingsKey
import com.metrolist.music.constants.SyncTombstonesKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.mix.CustomTransition
import com.metrolist.music.playback.mix.CustomTransitionRepository
import com.metrolist.music.ui.menu.MixDurationRange
import com.metrolist.music.ui.vibe.CustomVibes
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A setting kept in sync, with how a value from another device is read (null = invalid). */
private class SyncedSetting<T : Any>(
    val key: Preferences.Key<T>,
    val decode: (JsonPrimitive) -> T?,
) {
    fun read(prefs: Preferences): JsonElement =
        when (val value = prefs[key]) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

    fun isValid(value: JsonElement) = value is JsonNull || (value as? JsonPrimitive)?.let(decode) != null

    fun write(
        prefs: MutablePreferences,
        value: JsonElement,
    ) {
        val decoded = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.let(decode)
        if (decoded == null) prefs.remove(key) else prefs[key] = decoded
    }
}

private fun bool(key: Preferences.Key<Boolean>) = SyncedSetting(key) { it.takeUnless(JsonPrimitive::isString)?.booleanOrNull }

private inline fun <reified E : Enum<E>> enumName(key: Preferences.Key<String>) =
    SyncedSetting(key) { value -> value.takeIf { it.isString }?.content?.takeIf { name -> enumValues<E>().any { it.name == name } } }

/** The settings that follow the user between devices: Mix, lyrics and theme. */
private val SYNCED_SETTINGS: List<SyncedSetting<*>> =
    listOf(
        bool(CrossfadeEnabledKey),
        enumName<MixMode>(MixModeKey),
        enumName<MixTransitionStyle>(MixTransitionStyleKey),
        SyncedSetting(CrossfadeDurationKey) { it.takeUnless(JsonPrimitive::isString)?.floatOrNull?.takeIf { d -> d in MixDurationRange } },
        bool(CrossfadeOnSkipKey),
        bool(CrossfadeGaplessKey),
        enumName<LyricsEffects>(LyricsEffectsKey),
        bool(ExperimentalLyricsKey),
        bool(PureBlackKey),
        bool(DynamicThemeKey),
        SyncedSetting(SelectedThemeColorKey) { it.takeUnless(JsonPrimitive::isString)?.intOrNull },
    )

private fun Long.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())

// Plays are stored in local wall-clock time; the wire format is epoch ms
private fun LocalDateTime.toEpochMilli(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun decodeTombstones(raw: String?): SyncTombstones =
    raw?.let { runCatching { TvDataSync.json.decodeFromString<SyncTombstones>(it) }.getOrNull() } ?: SyncTombstones()

/** Remembers a deletion so a sync removes the item on the other device too. */
suspend fun Context.markSyncDeleted(
    transition: String? = null,
    vibe: String? = null,
) {
    val now = System.currentTimeMillis()
    safeDataStoreEdit { prefs ->
        val tombstones = decodeTombstones(prefs[SyncTombstonesKey])
        prefs[SyncTombstonesKey] =
            TvDataSync.json.encodeToString(
                SyncTombstones.serializer(),
                SyncTombstones(
                    transitions = SyncMerge.cap(transition?.let { tombstones.transitions + (it to now) } ?: tombstones.transitions),
                    vibes = SyncMerge.cap(vibe?.let { tombstones.vibes + (it to now) } ?: tombstones.vibes),
                ),
            )
    }
}

private const val PLAYLIST_TOMBSTONES_FILE = "playlist_tombstones.json"
private val PLAYLIST_TOMBSTONES = MapSerializer(String.serializer(), Long.serializer())
private val playlistTombstonesLock = Any()

private fun Context.readPlaylistTombstones(): Map<String, Long> =
    synchronized(playlistTombstonesLock) {
        runCatching { TvDataSync.json.decodeFromString(PLAYLIST_TOMBSTONES, File(filesDir, PLAYLIST_TOMBSTONES_FILE).readText()) }
            .getOrDefault(emptyMap())
    }

private fun Context.writePlaylistTombstones(tombstones: Map<String, Long>) =
    synchronized(playlistTombstonesLock) {
        File(filesDir, PLAYLIST_TOMBSTONES_FILE).writeText(TvDataSync.json.encodeToString(PLAYLIST_TOMBSTONES, tombstones))
    }

/** Remembers that a local playlist was deleted, so a sync deletes it on the other device too. Blocking. */
fun Context.markPlaylistSyncDeleted(id: String) =
    synchronized(playlistTombstonesLock) {
        writePlaylistTombstones(SyncMerge.cap(readPlaylistTombstones() + (id to System.currentTimeMillis())))
    }

private val PSEUDO_PLAYLISTS =
    setOf(
        PlaylistEntity.LIKED_PLAYLIST_ID,
        PlaylistEntity.DOWNLOADED_PLAYLIST_ID,
        PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID,
        PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID,
    )

/** Local, editable playlists sync; YouTube ones sync through the account. */
private val PlaylistEntity.isSynced get() = browseId == null && isEditable && id !in PSEUDO_PLAYLISTS

// Covers picked from the gallery are content:// URIs, useless on another device
private fun String.isWebUrl() = startsWith("https://") || startsWith("http://")

private fun Song.toSyncPlaylistSong() =
    toMediaMetadata().let { m ->
        SyncPlaylistSong(
            id = m.id,
            title = m.title,
            artists = m.artists.map { RemoteArtist(it.id, it.name) },
            album = m.album?.let { SyncAlbum(it.id, it.title) },
            thumbnail = m.thumbnailUrl?.takeIf { it.isWebUrl() },
            durationMs = m.duration.coerceAtLeast(0) * 1000L,
            local = song.isLocal,
        )
    }

private fun SyncPlaylistSong.toMediaMetadata() =
    MediaMetadata(
        id = id,
        title = title,
        artists = artists.map { MediaMetadata.Artist(it.id, it.name) },
        duration = (durationMs / 1000L).toInt().takeIf { it > 0 } ?: -1,
        thumbnailUrl = thumbnail?.takeIf { it.isWebUrl() },
        album = album?.id?.let { MediaMetadata.Album(it, album.title) },
    )

/**
 * Two-way sync of MetroQueue-only data (play history, custom transitions and vibes, a few settings, local playlists)
 * with a paired device, as described in SYNC.md. Both the phone (client) and the TV (receiver) use it.
 */
class TvDataSync(
    private val context: Context,
    private val database: MusicDatabase,
    private val transitions: CustomTransitionRepository,
) {
    /** Stamps synced settings with the time they changed, so the newer value wins in a sync. */
    fun trackSettings(scope: CoroutineScope) =
        scope.launch(Dispatchers.IO) {
            context.dataStore.data
                .map { prefs -> SYNCED_SETTINGS.map { it.read(prefs) } }
                .distinctUntilChanged()
                .collect {
                    context.safeDataStoreEdit { prefs ->
                        val stored = decodeSettings(prefs[SyncSettingsKey])
                        val current = currentSettings(prefs, stored, System.currentTimeMillis())
                        if (current != stored) prefs[SyncSettingsKey] = json.encodeToString(SETTINGS_SERIALIZER, current)
                    }
                }
        }

    /** This device's data; history only from [historySince] (epoch ms) on, newest first. */
    suspend fun localState(historySince: Long): SyncState =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val prefs = context.dataStore.data.first()
            val events = database.eventsSince(historySince.coerceAtLeast(0L).toLocalDateTime(), MAX_HISTORY)
            CustomVibes.initialize(context)
            SyncState(
                now = now,
                history = events.map { SyncHistoryItem(it.event.songId, it.event.timestamp.toEpochMilli(), it.event.playTime) },
                songs = events.distinctBy { it.song.id }.map { it.song.toMediaMetadata().toRemoteTrack().copy(art = null) },
                transitions = transitions.all().values.toList(),
                vibes = CustomVibes.vibes.value,
                settings = currentSettings(prefs, decodeSettings(prefs[SyncSettingsKey]), now),
                deleted = decodeTombstones(prefs[SyncTombstonesKey]),
                playlists =
                    SyncMerge.playlistsWithTombstones(
                        localPlaylists(database.playlistEntitiesByNameAsc()).values,
                        context.readPlaylistTombstones(),
                    ),
            )
        }

    /**
     * Merges [state] from the other device into this one. The phone passes [remoteWinsTies] = true
     * (see [SyncMerge.merge]); the receiver false.
     */
    suspend fun apply(
        state: SyncState,
        remoteWinsTies: Boolean,
    ) = withContext(Dispatchers.IO) {
        val tombstones = decodeTombstones(context.dataStore.data.first()[SyncTombstonesKey])

        var deletedTransitions = tombstones.transitions
        val remoteTransitions =
            state.transitions
                .filter { it.fromId.isNotBlank() && it.toId.isNotBlank() }
                .map { it.copy(durationMs = it.durationMs.coerceIn(CustomTransition.MIN_DURATION_MS, CustomTransition.MAX_DURATION_MS)) }
                .associateBy { it.key }
        transitions.update { local ->
            SyncMerge
                .merge(local, tombstones.transitions, remoteTransitions, state.deleted.transitions, remoteWinsTies) { it.updatedAt }
                .also { deletedTransitions = it.deleted }
                .items
        }

        var deletedVibes = tombstones.vibes
        val remoteVibes = state.vibes.filter { CustomVibes.isCustom(it.id) }.associateBy { it.id }
        CustomVibes.update(context) { local ->
            SyncMerge
                .merge(local, tombstones.vibes, remoteVibes, state.deleted.vibes, remoteWinsTies) { it.updatedAt }
                .also { deletedVibes = it.deleted }
                .items
        }

        context.safeDataStoreEdit { prefs ->
            val local = currentSettings(prefs, decodeSettings(prefs[SyncSettingsKey]), System.currentTimeMillis())
            // A value never set over there (time 0) is no reason to reset this device's
            val remote =
                state.settings.filter { (name, setting) ->
                    setting.updatedAt > 0L && SYNCED_SETTINGS.any { it.key.name == name && it.isValid(setting.value) }
                }
            val merged = SyncMerge.merge(local, emptyMap(), remote, emptyMap(), remoteWinsTies) { it.updatedAt }.items
            SYNCED_SETTINGS.forEach { setting ->
                val value = merged[setting.key.name] ?: return@forEach
                if (value != local[setting.key.name]) setting.write(prefs, value.value)
            }
            // Stored as read back, so the settings tracker sees no change and keeps these times
            val stored = SYNCED_SETTINGS.associate { it.key.name to SyncSetting(it.read(prefs), merged[it.key.name]?.updatedAt ?: 0L) }
            prefs[SyncSettingsKey] = json.encodeToString(SETTINGS_SERIALIZER, stored)
            prefs[SyncTombstonesKey] = json.encodeToString(SyncTombstones.serializer(), SyncTombstones(deletedTransitions, deletedVibes))
        }

        applyPlaylists(state, remoteWinsTies)
        insertHistory(state)
    }

    private fun localPlaylists(entities: Collection<PlaylistEntity>): Map<String, SyncPlaylist> =
        entities.filter { it.isSynced }.associate { playlist ->
            playlist.id to
                SyncPlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    updatedAt = playlist.lastUpdateTime?.toEpochMilli() ?: 0L,
                    thumbnailUrl = playlist.thumbnailUrl?.takeIf { it.isWebUrl() },
                    songs = database.playlistSongsBlocking(playlist.id).map { it.song.toSyncPlaylistSong() },
                )
        }

    private suspend fun applyPlaylists(
        state: SyncState,
        remoteWinsTies: Boolean,
    ) {
        val entities = database.playlistEntitiesByNameAsc().associateBy { it.id }
        val local = localPlaylists(entities.values)
        // Never let another device overwrite a YouTube or built-in playlist that shares an id
        val blocked = entities.keys - local.keys + PSEUDO_PLAYLISTS
        val merged = SyncMerge.playlists(local, context.readPlaylistTombstones(), state.playlists, remoteWinsTies, blocked)
        database.withTransaction {
            (local.keys - merged.items.keys).forEach { delete(entities.getValue(it)) }
            merged.items.values.filter { it != local[it.id] }.forEach { playlist ->
                val existing = entities[playlist.id]
                val entity =
                    (existing ?: PlaylistEntity(id = playlist.id, name = playlist.name, bookmarkedAt = LocalDateTime.now())).copy(
                        name = playlist.name,
                        // A cover only this device can show stays until the other device sets a real one
                        thumbnailUrl = playlist.thumbnailUrl?.takeIf { it.isWebUrl() } ?: existing?.thumbnailUrl?.takeUnless { it.isWebUrl() },
                        lastUpdateTime = playlist.updatedAt.toLocalDateTime(),
                    )
                if (existing == null) insert(entity) else update(entity)
                clearPlaylist(playlist.id)
                playlist.songs.filter { it.id.isNotBlank() }.forEachIndexed { index, song ->
                    insert(song.toMediaMetadata()) { it.copy(isLocal = song.local) }
                    insert(PlaylistSongMap(playlistId = playlist.id, songId = song.id, position = index))
                }
            }
        }
        context.writePlaylistTombstones(merged.deleted)
    }

    private suspend fun insertHistory(state: SyncState) {
        val remote = state.history.take(MAX_HISTORY)
        if (remote.isEmpty()) return
        val songs = state.songs.associateBy { it.id }
        val local =
            database
                .eventsFrom(remote.minOf { it.timestamp }.toLocalDateTime())
                .mapTo(HashSet()) { it.songId to it.timestamp.toEpochMilli() }
        val added = SyncMerge.newHistory(local, remote).filter { it.songId in songs }
        if (added.isEmpty()) return
        val done = CompletableDeferred<Unit>()
        database.transaction {
            try {
                added.forEach { play ->
                    runCatching {
                        insert(songs.getValue(play.songId).toMediaMetadata())
                        insert(Event(songId = play.songId, timestamp = play.timestamp.toLocalDateTime(), playTime = play.playTime.coerceAtLeast(0L)))
                    }.onFailure { Timber.tag(TAG).w(it, "Skipping a synced play") }
                }
            } finally {
                done.complete(Unit)
            }
        }
        done.await()
    }

    private fun currentSettings(
        prefs: Preferences,
        stored: Map<String, SyncSetting>,
        now: Long,
    ): Map<String, SyncSetting> =
        SYNCED_SETTINGS.associate { setting ->
            val name = setting.key.name
            val value = setting.read(prefs)
            val known = stored[name]
            // First seen: its age is unknown, so it is older than any change made since (0 = never set)
            name to
                when {
                    known == null -> SyncSetting(value, if (value is JsonNull) 0L else 1L)
                    known.value != value -> SyncSetting(value, now)
                    else -> known
                }
        }

    private fun decodeSettings(raw: String?): Map<String, SyncSetting> =
        raw?.let { runCatching { json.decodeFromString(SETTINGS_SERIALIZER, it) }.getOrNull() }.orEmpty()

    companion object {
        private const val TAG = "TvDataSync"

        /** Plays sent per sync, the newest ones. */
        const val MAX_HISTORY = 5000

        val json =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                encodeDefaults = true
            }

        private val SETTINGS_SERIALIZER = MapSerializer(String.serializer(), SyncSetting.serializer())

        @OptIn(ExperimentalSerializationApi::class)
        fun decode(input: InputStream): SyncState = json.decodeFromStream(SyncState.serializer(), input)
    }
}
