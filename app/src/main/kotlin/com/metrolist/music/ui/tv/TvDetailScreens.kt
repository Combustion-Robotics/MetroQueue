/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.LocalAlbumRadio
import com.metrolist.music.playback.queues.YouTubePlaylistQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.playback.tv.toMediaMetadata
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.viewmodels.AlbumViewModel
import com.metrolist.music.viewmodels.ArtistViewModel
import com.metrolist.music.viewmodels.AutoPlaylistViewModel
import com.metrolist.music.viewmodels.LocalPlaylistViewModel
import com.metrolist.music.viewmodels.OnlinePlaylistViewModel

/** A song as shown in a TV track list, whatever it was loaded from. */
private data class TvTrack(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
)

private fun Song.toTvTrack() =
    TvTrack(id, title, artists.joinToString { it.name }, thumbnailUrl, song.duration.takeIf { it > 0 })

private fun SongItem.toTvTrack() =
    TvTrack(id, title, artists.joinToString { it.name }, thumbnail, duration)

@Composable
fun TvAlbumScreen(viewModel: AlbumViewModel = hiltViewModel()) {
    val tv = LocalTvController.current
    val albumWithSongs by viewModel.albumWithSongs.collectAsStateWithLifecycle()
    val album = albumWithSongs
    if (album == null) {
        TvLoading()
        return
    }
    val songs = album.songs
    TvCollectionScreen(
        title = album.album.title,
        subtitle = listOfNotNull(album.artists.joinToString { it.name }.ifBlank { null }, album.album.year?.toString()).joinToString(" · "),
        thumbnailUrl = album.album.thumbnailUrl,
        tracks = songs.map { it.toTvTrack() },
        onPlay = { index -> if (songs.isNotEmpty()) tv.play(LocalAlbumRadio(album, startIndex = index)) },
        onShuffle = { tv.playList(album.album.title, songs, shuffle = true) },
    )
}

@Composable
fun TvOnlinePlaylistScreen(viewModel: OnlinePlaylistViewModel = hiltViewModel()) {
    val tv = LocalTvController.current
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val songs by viewModel.playlistSongs.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val current = playlist
    if (current == null) {
        if (error != null && !isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TvButton(
                    onClick = viewModel::retry,
                    icon = R.drawable.replay,
                    text = stringResource(R.string.retry),
                    modifier = Modifier.tvInitialFocus(),
                )
            }
        } else {
            TvLoading()
        }
        return
    }
    TvCollectionScreen(
        title = current.title,
        subtitle = listOfNotNull(current.author?.name, current.songCountText).joinToString(" · "),
        thumbnailUrl = current.thumbnail,
        tracks = songs.map { it.toTvTrack() },
        onPlay = { index ->
            if (songs.isNotEmpty()) {
                tv.play(
                    YouTubePlaylistQueue(
                        playlistId = current.id,
                        playlistTitle = current.title,
                        initialSongs = songs,
                        initialContinuation = viewModel.continuation,
                        startIndex = index,
                    ),
                )
            }
        },
        onShuffle = {
            if (songs.isNotEmpty()) tv.play(ListQueue(title = current.title, items = songs.shuffled().map { it.toMediaItem() }))
        },
        onNearEnd = viewModel::loadMoreSongs,
    )
}

@Composable
fun TvLocalPlaylistScreen(viewModel: LocalPlaylistViewModel = hiltViewModel()) {
    val tv = LocalTvController.current
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val playlistSongs by viewModel.playlistSongs.collectAsStateWithLifecycle()
    val current = playlist
    if (current == null) {
        TvLoading()
        return
    }
    val songs = playlistSongs.map { it.song }
    TvCollectionScreen(
        title = current.playlist.name,
        subtitle = null,
        thumbnailUrl = current.thumbnails.firstOrNull(),
        tracks = songs.map { it.toTvTrack() },
        onPlay = { index -> tv.playList(current.playlist.name, songs, startIndex = index) },
        onShuffle = { tv.playList(current.playlist.name, songs, shuffle = true) },
    )
}

/** A playlist (or the liked songs, [PROFILE_LIKED_ID]) from the active phone profile. */
@Composable
fun TvProfilePlaylistScreen(playlistId: String) {
    val tv = LocalTvController.current
    val profile = rememberActiveProfile()
    val likedTitle = stringResource(R.string.liked_songs)
    val playlist =
        remember(profile, playlistId) {
            profile?.library?.playlists?.firstOrNull { it.id == playlistId }
        }
    val tracks =
        remember(profile, playlist) {
            val source = if (playlistId == PROFILE_LIKED_ID) profile?.library?.likedSongs else playlist?.songs
            source.orEmpty().map { it.toMediaMetadata() }
        }
    val title = playlist?.name ?: likedTitle
    TvCollectionScreen(
        title = title,
        subtitle = profile?.profile?.name,
        thumbnailUrl = playlist?.thumbnail ?: tracks.firstOrNull()?.thumbnailUrl,
        tracks =
            remember(tracks) {
                tracks.map { TvTrack(it.id, it.title, it.artists.joinToString { a -> a.name }, it.thumbnailUrl, it.duration.takeIf { d -> d > 0 }) }
            },
        onPlay = { index -> tv.playTracks(title, tracks, startIndex = index) },
        onShuffle = { tv.playTracks(title, tracks, shuffle = true) },
    )
}

/** Liked songs (the only auto playlist on TV). */
@Composable
fun TvAutoPlaylistScreen(viewModel: AutoPlaylistViewModel = hiltViewModel()) {
    val tv = LocalTvController.current
    val songs by viewModel.likedSongs.collectAsStateWithLifecycle()
    val title = stringResource(R.string.liked_songs)
    TvCollectionScreen(
        title = title,
        subtitle = null,
        thumbnailUrl = songs.firstOrNull()?.thumbnailUrl,
        tracks = songs.map { it.toTvTrack() },
        onPlay = { index -> tv.playList(title, songs, startIndex = index) },
        onShuffle = { tv.playList(title, songs, shuffle = true) },
    )
}

@Composable
fun TvArtistScreen(viewModel: ArtistViewModel = hiltViewModel()) {
    val tv = LocalTvController.current
    val playerConnection = LocalPlayerConnection.current
    val playingId = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value?.id
    val artistPage = viewModel.artistPage
    val focusMemory = rememberTvFocusMemory()
    if (artistPage == null) {
        TvLoading()
        return
    }
    val artist = artistPage.artist

    LazyColumn(
        contentPadding = PaddingValues(top = TvDimens.SafeVertical, bottom = TvDimens.SafeVertical * 2),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = TvDimens.SafeHorizontal / 2, end = TvDimens.SafeHorizontal),
            ) {
                TvArtwork(
                    url = artist.thumbnail,
                    sizePx = 400,
                    placeholderIcon = R.drawable.artist,
                    modifier = Modifier.size(160.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(32.dp))
                Column {
                    Text(
                        text = artist.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    artistPage.subscriberCountText?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        artist.shuffleEndpoint?.let { endpoint ->
                            TvButton(
                                onClick = { tv.play(YouTubeQueue(endpoint)) },
                                icon = R.drawable.shuffle,
                                text = stringResource(R.string.shuffle),
                                modifier =
                                    Modifier
                                        .tvFocusMemory(focusMemory, "shuffle")
                                        .tvInitialFocus(skip = focusMemory.restorePending),
                            )
                        }
                        artist.radioEndpoint?.let { endpoint ->
                            TvButton(
                                onClick = { tv.play(YouTubeQueue(endpoint)) },
                                icon = R.drawable.radio,
                                text = stringResource(R.string.start_radio),
                                modifier = Modifier.tvFocusMemory(focusMemory, "radio"),
                            )
                        }
                    }
                }
            }
        }
        itemsIndexed(artistPage.sections, key = { index, section -> "$index/${section.title}" }) { index, section ->
            TvRow(title = section.title, items = section.items, key = { it.id }) { item ->
                TvMediaCard(
                    title = item.title,
                    subtitle = item.tvSubtitle(),
                    thumbnailUrl = item.thumbnail,
                    round = item is ArtistItem,
                    active = item.id == playingId,
                    onClick = { tv.open(item) },
                    modifier = Modifier.tvFocusMemory(focusMemory, "$index/${item.id}"),
                )
            }
        }
    }
}

@Composable
private fun TvLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Album / playlist layout: cover and actions on the left, the focusable track list on the right. */
@Composable
private fun TvCollectionScreen(
    title: String,
    subtitle: String?,
    thumbnailUrl: String?,
    tracks: List<TvTrack>,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onNearEnd: (() -> Unit)? = null,
) {
    val playerConnection = LocalPlayerConnection.current
    val playingId = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value?.id
    val focusMemory = rememberTvFocusMemory()
    val listState = rememberLazyListState()

    if (onNearEnd != null) {
        LaunchedEffect(listState) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .collect { last ->
                    if (last != null && last >= listState.layoutInfo.totalItemsCount - 5) onNearEnd()
                }
        }
    }

    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(start = TvDimens.SafeHorizontal / 2, end = TvDimens.SafeHorizontal, top = TvDimens.SafeVertical),
    ) {
        Column(Modifier.width(280.dp).fillMaxHeight()) {
            TvArtwork(
                url = thumbnailUrl,
                sizePx = 560,
                placeholderIcon = R.drawable.queue_music,
                modifier =
                    Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    onClick = { onPlay(0) },
                    icon = R.drawable.play,
                    text = stringResource(R.string.play),
                    enabled = tracks.isNotEmpty(),
                    modifier =
                        Modifier
                            .tvFocusMemory(focusMemory, "play")
                            .tvInitialFocus(ready = tracks.isNotEmpty(), skip = focusMemory.restorePending),
                )
                TvButton(
                    onClick = onShuffle,
                    icon = R.drawable.shuffle,
                    enabled = tracks.isNotEmpty(),
                    modifier = Modifier.tvFocusMemory(focusMemory, "shuffle"),
                )
            }
        }
        Spacer(Modifier.width(24.dp))
        if (tracks.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.playlist_is_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = TvDimens.SafeVertical * 2),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                itemsIndexed(tracks, key = { index, track -> "$index/${track.id}" }) { index, track ->
                    TvTrackRow(
                        index = index,
                        track = track,
                        active = track.id == playingId,
                        onClick = { onPlay(index) },
                        modifier = Modifier.tvFocusMemory(focusMemory, "track/$index/${track.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvTrackRow(
    index: Int,
    track: TvTrack,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(14.dp),
        containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        focusedScale = 1.02f,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        ) {
            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                if (active) {
                    Icon(painterResource(R.drawable.graphic_eq), contentDescription = null, modifier = Modifier.size(20.dp))
                } else {
                    Text(text = "${index + 1}", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.width(8.dp))
            TvArtwork(
                url = track.thumbnailUrl,
                sizePx = 112,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track.subtitle.isNotBlank()) {
                    Text(track.subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            track.durationSeconds?.let { seconds ->
                Text(makeTimeString(seconds * 1000L), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
