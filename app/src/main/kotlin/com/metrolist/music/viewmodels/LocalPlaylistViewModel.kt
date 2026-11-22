/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.music.constants.AddToPlaylistPosition
import com.metrolist.music.constants.AddToPlaylistPositionKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.PlaylistSongSortDescendingKey
import com.metrolist.music.constants.PlaylistSongSortType
import com.metrolist.music.constants.PlaylistSongSortTypeKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistSong
import com.metrolist.music.extensions.reversed
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LocalPlaylistViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val syncUtils: SyncUtils,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val playlistId = savedStateHandle.get<String>("playlistId")!!
    val playlist =
        database
            .playlist(playlistId)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _onlinePlaylist = MutableStateFlow<PlaylistItem?>(null)
    val onlinePlaylist: StateFlow<PlaylistItem?> = _onlinePlaylist
    val playlistSongs: StateFlow<List<PlaylistSong>> =
        combine(
            database.playlistSongs(playlistId),
            context.dataStore.data
                .map {
                    Triple(
                        it[PlaylistSongSortTypeKey].toEnum(PlaylistSongSortType.CUSTOM),
                        it[PlaylistSongSortDescendingKey] ?: true,
                        it[HideVideoSongsKey] ?: false
                    )
                }.distinctUntilChanged(),
        ) { songs, (sortType, sortDescending, hideVideoSongs) ->
            val filteredSongs = if (hideVideoSongs) {
                songs.filter { !it.song.song.isVideo }
            } else {
                songs
            }
            when (sortType) {
                PlaylistSongSortType.CUSTOM -> filteredSongs
                PlaylistSongSortType.CREATE_DATE -> filteredSongs.sortedBy { it.map.id }
                PlaylistSongSortType.NAME -> {
                    val collator = Collator.getInstance(Locale.getDefault())
                    collator.strength = Collator.PRIMARY
                    filteredSongs.sortedWith(compareBy(collator) { it.song.song.title })
                }
                PlaylistSongSortType.ARTIST -> {
                    val collator = Collator.getInstance(Locale.getDefault())
                    collator.strength = Collator.PRIMARY
                    filteredSongs.sortedWith(
                        compareBy<PlaylistSong, String>(collator) { song -> song.song.artists.joinToString("") { it.name } }
                            .thenBy(collator) { it.song.album?.title.orEmpty() },
                    )
                }

                PlaylistSongSortType.PLAY_TIME -> filteredSongs.sortedBy { it.song.song.totalPlayTime }
            }.reversed(sortDescending && sortType != PlaylistSongSortType.CUSTOM)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val suggestionPool = MutableStateFlow<List<SongItem>>(emptyList())
    private var suggestionsToken: String? = null
    private var suggestionsJob: Job? = null
    private val _suggestionsLoading = MutableStateFlow(false)
    val suggestionsLoading: StateFlow<Boolean> = _suggestionsLoading

    // Songs already in the playlist drop out, so an added song is replaced by the next one in the pool
    val suggestions: StateFlow<List<SongItem>> =
        combine(suggestionPool, database.playlistSongs(playlistId)) { pool, songs ->
            val inPlaylist = songs.mapTo(HashSet()) { it.song.id }
            pool.filter { it.id !in inPlaylist }.take(SUGGESTION_COUNT)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun refreshSuggestions() = loadSuggestions(suggestionsToken)

    private fun loadSuggestions(nativeToken: String?) {
        suggestionsJob?.cancel()
        suggestionsJob =
            viewModelScope.launch(Dispatchers.IO) {
                _suggestionsLoading.value = true
                val prefs = context.dataStore.data.first()
                val playlistSongIds = database.playlistSongs(playlistId).first()
                    .filterNot { it.song.song.isLocal }
                    .map { it.song.id }

                val native = nativeToken?.let { YouTube.playlistSuggestions(it).getOrNull() }
                suggestionsToken = native?.continuation
                var pool = native?.songs.orEmpty()
                if (pool.size < SUGGESTION_POOL_SIZE) {
                    pool = pool + radioSongs(playlistSongIds.shuffled().take(SUGGESTION_SEEDS))
                }

                val inPlaylist = playlistSongIds.toHashSet()
                suggestionPool.value =
                    pool
                        .filterExplicit(prefs[HideExplicitKey] ?: false)
                        .filterVideoSongs(prefs[HideVideoSongsKey] ?: false)
                        .filter { it.id !in inPlaylist }
                        .distinctBy { it.id }
                _suggestionsLoading.value = false
            }
    }

    // A song's radio is YouTube Music's own list of similar songs
    private suspend fun radioSongs(seedIds: List<String>): List<SongItem> =
        coroutineScope {
            seedIds
                .map { id ->
                    async {
                        YouTube.next(WatchEndpoint(videoId = id, playlistId = "RDAMVM$id")).getOrNull()?.items.orEmpty()
                    }
                }.awaitAll()
                .flatten()
                .shuffled()
        }

    fun addSuggestion(song: SongItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = playlist.first { it != null } ?: return@launch
            val prepend =
                context.dataStore.data.first()[AddToPlaylistPositionKey]
                    .toEnum(AddToPlaylistPosition.BEGINNING)
                    .prepend
            database.insert(song.toMediaMetadata())
            database.addSongsToPlaylist(target, listOf(song.id to null), prepend = prepend)
            target.playlist.browseId?.let { syncUtils.scheduleAddToPlaylist(it, target.id, listOf(song.id)) }
        }
    }

    init {
        viewModelScope.launch {
            val sortedSongs =
                playlistSongs.first().sortedWith(compareBy({ it.map.position }, { it.map.id }))
            database.transaction {
                sortedSongs.forEachIndexed { index, playlistSong ->
                    if (playlistSong.map.position != index) {
                        update(playlistSong.map.copy(position = index))
                    }
                }
            }
        }

        viewModelScope.launch {
            val localPlaylist = playlist.first { it != null }
            val browseId = localPlaylist?.playlist?.browseId
            if (browseId != null) {
                val page = withContext(Dispatchers.IO) {
                    YouTube.playlist(browseId).getOrNull()
                }
                val online = page?.playlist
                _onlinePlaylist.value = online
                loadSuggestions(page?.continuation?.takeIf { online?.isEditable == true })
            } else {
                loadSuggestions(null)
            }
        }
    }

    private companion object {
        const val SUGGESTION_COUNT = 10
        const val SUGGESTION_POOL_SIZE = 30
        const val SUGGESTION_SEEDS = 3
    }
}
