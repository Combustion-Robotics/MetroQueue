/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.tv

import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.toMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * The phone's library for "Sync library to this TV": liked songs, saved playlists with their songs,
 * saved albums, followed artists and recent history, gzipped JSON. Library data only.
 */
suspend fun MusicDatabase.gzippedLibrarySnapshot(): ByteArray =
    withContext(Dispatchers.IO) {
        val playlists =
            playlistsByCreateDateAsc().first().take(LibrarySnapshot.MAX_PLAYLISTS).map { playlist ->
                ProfilePlaylist(
                    id = playlist.id,
                    name = playlist.playlist.name,
                    thumbnail = playlist.thumbnails.firstOrNull(),
                    songs = playlistSongs(playlist.id).first().take(LibrarySnapshot.MAX_SONGS).map { it.song.toSyncTrack() },
                )
            }
        val snapshot =
            LibrarySnapshot(
                likedSongs = likedSongsByCreateDateAsc().first().asReversed().map { it.toSyncTrack() },
                playlists = playlists,
                albums =
                    albumsLikedByCreateDateAsc().first().asReversed().map { album ->
                        ProfileAlbum(
                            id = album.id,
                            title = album.title,
                            artist = album.artists.joinToString { it.name }.ifBlank { null },
                            thumbnail = album.thumbnailUrl,
                            year = album.album.year,
                        )
                    },
                artists =
                    artistsBookmarkedByCreateDateAsc().first().asReversed().map { artist ->
                        ProfileArtist(artist.id, artist.title, artist.thumbnailUrl)
                    },
                history =
                    recentEvents(LibrarySnapshot.MAX_HISTORY * 4)
                        .distinctBy { it.song.id }
                        .take(LibrarySnapshot.MAX_HISTORY)
                        .map { it.song.toSyncTrack() },
            ).capped()
        ByteArrayOutputStream().use { bytes ->
            GZIPOutputStream(bytes).bufferedWriter().use { it.write(TvProfileStore.json.encodeToString(snapshot)) }
            bytes.toByteArray()
        }
    }

// The TV resizes thumbnails itself, so the pre-sized copy is left out to keep the upload small
private fun Song.toSyncTrack() = toMediaMetadata().toRemoteTrack().copy(art = null)
