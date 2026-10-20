/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.LocalItem
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.utils.SearchRoutes

/** UI state shared by the TV screens: navigation, the Now Playing / Queue overlays and playback entry points. */
@Stable
class TvController(
    val navController: NavHostController,
) {
    var playerConnection: PlayerConnection? = null
    var autoRadio: Boolean = true

    var nowPlayingVisible by mutableStateOf(false)
    var queueVisible by mutableStateOf(false)

    fun navigate(route: String) {
        navController.navigate(route) { launchSingleTop = true }
    }

    /** Opens the results for [query], replacing earlier results so Back goes to the search screen. */
    fun showSearchResults(query: String) {
        navController.navigate(SearchRoutes.resultRoute(query)) { popUpTo("search") }
    }

    /** Starts [queue] and brings up Now Playing, like the TV apps users know. */
    fun play(queue: Queue) {
        val connection = playerConnection ?: return
        if (connection.shouldBlockPlaybackChanges?.invoke() == true) return
        connection.playQueue(queue)
        nowPlayingVisible = true
    }

    fun playSong(song: SongItem) {
        play(
            if (autoRadio) {
                YouTubeQueue(song.endpoint ?: WatchEndpoint(videoId = song.id), song.toMediaMetadata())
            } else {
                ListQueue(title = song.title, items = listOf(song.toMediaItem()))
            },
        )
    }

    fun playSong(song: Song) = playTrack(song.toMediaMetadata())

    /** Plays one song (as a radio when auto radio is on), or just shows Now Playing if it's already playing. */
    fun playTrack(song: MediaMetadata) {
        val connection = playerConnection
        if (connection != null && connection.mediaMetadata.value?.id == song.id) {
            nowPlayingVisible = true
            return
        }
        play(
            if (autoRadio) {
                YouTubeQueue.radio(song)
            } else {
                ListQueue(title = song.title, items = listOf(song.toMediaItem()))
            },
        )
    }

    /** Like [playList], for songs that aren't in the TV's database (e.g. a phone profile's). */
    fun playTracks(
        title: String?,
        songs: List<MediaMetadata>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
    ) {
        if (songs.isEmpty()) return
        val items = if (shuffle) songs.shuffled() else songs
        play(ListQueue(title = title, items = items.map { it.toMediaItem() }, startIndex = if (shuffle) 0 else startIndex))
    }

    /** Plays [songs] in order (or shuffled) starting at [startIndex]. */
    fun playList(
        title: String?,
        songs: List<Song>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
    ) {
        if (songs.isEmpty()) return
        val items = if (shuffle) songs.shuffled() else songs
        play(ListQueue(title = title, items = items.map { it.toMediaItem() }, startIndex = if (shuffle) 0 else startIndex))
    }

    fun open(item: YTItem) {
        when (item) {
            is SongItem -> playSong(item)
            is AlbumItem -> navigate("album/${item.id}")
            is ArtistItem -> navigate("artist/${item.id}")
            is PlaylistItem -> navigate("online_playlist/${item.id}")
            is PodcastItem -> navigate("online_playlist/${item.id}")
            is EpisodeItem ->
                play(ListQueue(title = item.title, items = listOf(item.toMediaMetadata().toMediaItem())))
        }
    }

    fun open(item: LocalItem) {
        when (item) {
            is Song -> playSong(item)
            is Album -> navigate("album/${item.id}")
            is Artist -> navigate("artist/${item.id}")
            is Playlist -> navigate("local_playlist/${item.id}")
        }
    }
}

val LocalTvController = staticCompositionLocalOf<TvController> { error("No TvController provided") }

fun YTItem.tvSubtitle(): String? =
    when (this) {
        is SongItem -> artists.joinToString { it.name }
        is AlbumItem -> artists?.joinToString { it.name } ?: year?.toString()
        is ArtistItem -> null
        is PlaylistItem -> author?.name ?: songCountText
        is PodcastItem -> author?.name
        is EpisodeItem -> author?.name
    }

fun LocalItem.tvSubtitle(): String? =
    when (this) {
        is Song -> artists.joinToString { it.name }
        is Album -> artists.joinToString { it.name }
        is Artist -> null
        is Playlist -> null
    }

/** Library playlists have no cover of their own; use the first song cover. */
val LocalItem.tvThumbnail: String?
    get() = if (this is Playlist) thumbnails.firstOrNull() else thumbnailUrl
