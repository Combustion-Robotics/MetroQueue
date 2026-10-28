/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.vibe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubePlaylistQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.vibe.scene.SceneSpec
import com.metrolist.music.ui.vibe.scene.VibeScenes
import kotlinx.coroutines.flow.first

data class Vibe(
    val id: String,
    val name: String,
    val scene: SceneSpec,
    val music: VibeMusic,
)

/** First set source wins: a local playlist, a YouTube playlist, else an endless radio from [seed]. */
data class VibeMusic(
    val localPlaylistId: String? = null,
    val playlistId: String? = null,
    val seed: String? = null,
)

const val VIBE_EDIT_ROUTE = "vibe/edit"
const val VIBE_PLAYER_ROUTE = "vibe/play"

fun NavController.openVibe(id: String, playMusic: Boolean) = navigate("$VIBE_PLAYER_ROUTE/$id?music=$playMusic")

@Composable
fun rememberVibes(): List<Vibe> {
    val context = LocalContext.current
    return remember(context) {
        listOf(
            Vibe("winter_dusk", context.getString(R.string.vibe_winter_dusk), VibeScenes.winterDusk, VibeMusic(seed = "lofi winter chill")),
            Vibe("rainy_city", context.getString(R.string.vibe_rainy_city), VibeScenes.rainyCity, VibeMusic(seed = "lofi rainy night city")),
            Vibe("starry_lake", context.getString(R.string.vibe_starry_lake), VibeScenes.starryLake, VibeMusic(seed = "ambient night sky chill")),
            Vibe("spring_blossoms", context.getString(R.string.vibe_spring_blossoms), VibeScenes.springBlossoms, VibeMusic(seed = "japanese lofi spring")),
        )
    }
}

suspend fun playVibeMusic(music: VibeMusic, playerConnection: PlayerConnection, database: MusicDatabase) {
    when {
        music.localPlaylistId != null -> {
            val songs = database.playlistSongs(music.localPlaylistId).first()
            if (songs.isNotEmpty()) playerConnection.playQueue(ListQueue(items = songs.map { it.song.toMediaItem() }))
        }

        music.playlistId != null -> playerConnection.playQueue(YouTubePlaylistQueue(music.playlistId))

        music.seed != null -> {
            // A random pick among the top results so the same vibe doesn't always start with the same song.
            val song = YouTube.search(music.seed, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                ?.items?.filterIsInstance<SongItem>()?.take(5)?.randomOrNull() ?: return
            playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
        }
    }
}
