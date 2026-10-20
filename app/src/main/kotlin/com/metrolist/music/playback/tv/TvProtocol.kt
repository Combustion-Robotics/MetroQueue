/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.tv

import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.utils.resize
import kotlinx.serialization.Serializable

/** DNS-SD type the TV advertises its remote control server under. */
const val TV_SERVICE_TYPE = "_metroqueue._tcp"
const val TV_PROTOCOL_VERSION = 1
const val TV_TXT_ID = "id"
const val TV_TXT_VERSION = "v"

@Serializable
data class RemoteArtist(
    val id: String? = null,
    val name: String,
)

/**
 * A queue entry. The browser remote uses the first fields; MetroQueue clients also get the
 * full metadata so the song can be shown, liked and played like a local one.
 */
@Serializable
data class RemoteTrack(
    val id: String,
    val title: String,
    val artist: String,
    val art: String?,
    val duration: Long,
    val pick: Boolean = false,
    val thumb: String? = null,
    val artists: List<RemoteArtist> = emptyList(),
    val albumId: String? = null,
    val albumTitle: String? = null,
    val explicit: Boolean = false,
    val videoType: String? = null,
    val setVideoId: String? = null,
    val episode: Boolean = false,
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
    val playbackState: Int = 1,
    val speed: Float = 1f,
)

@Serializable
data class TvInfo(
    val id: String,
    val name: String,
    val version: Int = TV_PROTOCOL_VERSION,
    val paired: Boolean = false,
)

@Serializable
data class PairStartRequest(
    val name: String,
)

@Serializable
data class PairFinishRequest(
    val code: String,
    val name: String,
)

@Serializable
data class PairFinishResponse(
    val token: String,
    val tvId: String,
    val tvName: String,
)

/** A phone paired with this TV. Only a hash of its token is kept. */
@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val tokenHash: String,
    val pairedAt: Long,
)

fun MediaMetadata.toRemoteTrack(
    pick: Boolean = false,
    artSize: Int = 120,
) = RemoteTrack(
    id = id,
    title = title,
    artist = artists.joinToString { it.name },
    art = thumbnailUrl?.resize(artSize, artSize),
    duration = duration * 1000L,
    pick = pick,
    thumb = thumbnailUrl,
    artists = artists.map { RemoteArtist(it.id, it.name) },
    albumId = album?.id,
    albumTitle = album?.title,
    explicit = explicit,
    videoType = musicVideoType,
    setVideoId = setVideoId,
    episode = isEpisode,
)

fun RemoteTrack.toMediaMetadata() =
    MediaMetadata(
        id = id,
        title = title,
        artists =
            artists
                .map { MediaMetadata.Artist(it.id, it.name) }
                .ifEmpty { listOf(MediaMetadata.Artist(null, artist)) },
        duration = (duration / 1000L).toInt(),
        thumbnailUrl = thumb ?: art,
        album = albumId?.let { MediaMetadata.Album(it, albumTitle.orEmpty()) },
        setVideoId = setVideoId,
        musicVideoType = videoType,
        explicit = explicit,
        isEpisode = episode,
    )
