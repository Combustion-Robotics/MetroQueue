/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.viewmodels.LibraryAlbumsViewModel
import com.metrolist.music.viewmodels.LibraryArtistsViewModel
import com.metrolist.music.viewmodels.LibraryPlaylistsViewModel

/** Liked songs and saved playlists, albums and artists, as rows. */
@Composable
fun TvLibraryScreen(
    playlistsViewModel: LibraryPlaylistsViewModel = hiltViewModel(),
    albumsViewModel: LibraryAlbumsViewModel = hiltViewModel(),
    artistsViewModel: LibraryArtistsViewModel = hiltViewModel(),
) {
    val profile = rememberActiveProfile()
    if (profile != null) {
        TvProfileLibrary(profile)
        return
    }
    val tv = LocalTvController.current
    val playlists by playlistsViewModel.allPlaylists.collectAsStateWithLifecycle()
    val albums by albumsViewModel.allAlbums.collectAsStateWithLifecycle()
    val artists by artistsViewModel.allArtists.collectAsStateWithLifecycle()
    val focusMemory = rememberTvFocusMemory()

    LazyColumn(
        contentPadding = PaddingValues(bottom = TvDimens.SafeVertical * 2),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "title") { TvScreenTitle(stringResource(R.string.tv_library)) }
        item(key = "playlists") {
            TvRow(
                title = stringResource(R.string.playlists),
                items = playlists,
                key = { it.id },
                leadingItem = {
                    TvMediaCard(
                        title = stringResource(R.string.liked_songs),
                        subtitle = null,
                        thumbnailUrl = null,
                        placeholderIcon = R.drawable.favorite,
                        onClick = { tv.navigate("auto_playlist/liked") },
                        modifier =
                            Modifier
                                .tvFocusMemory(focusMemory, "liked")
                                .tvInitialFocus(skip = focusMemory.restorePending),
                    )
                },
            ) { playlist ->
                TvMediaCard(
                    title = playlist.title,
                    subtitle = null,
                    thumbnailUrl = playlist.tvThumbnail,
                    placeholderIcon = R.drawable.queue_music,
                    onClick = { tv.open(playlist) },
                    modifier = Modifier.tvFocusMemory(focusMemory, "playlist/${playlist.id}"),
                )
            }
        }
        if (albums.isNotEmpty()) {
            item(key = "albums") {
                TvRow(title = stringResource(R.string.albums), items = albums, key = { it.id }) { album ->
                    TvMediaCard(
                        title = album.title,
                        subtitle = album.tvSubtitle(),
                        thumbnailUrl = album.thumbnailUrl,
                        placeholderIcon = R.drawable.album,
                        onClick = { tv.open(album) },
                        modifier = Modifier.tvFocusMemory(focusMemory, "album/${album.id}"),
                    )
                }
            }
        }
        if (artists.isNotEmpty()) {
            item(key = "artists") {
                TvRow(title = stringResource(R.string.artists), items = artists, key = { it.id }) { artist ->
                    TvMediaCard(
                        title = artist.title,
                        subtitle = null,
                        thumbnailUrl = artist.thumbnailUrl,
                        placeholderIcon = R.drawable.artist,
                        round = true,
                        onClick = { tv.open(artist) },
                        modifier = Modifier.tvFocusMemory(focusMemory, "artist/${artist.id}"),
                    )
                }
            }
        }
        if (playlists.isEmpty() && albums.isEmpty() && artists.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.library_playlist_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = TvDimens.SafeHorizontal / 2),
                )
            }
        }
    }
}
