/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.vibe

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.ui.component.ItemThumbnail
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.YouTubeListItem
import com.metrolist.music.ui.vibe.scene.Foreground
import com.metrolist.music.ui.vibe.scene.Layout
import com.metrolist.music.ui.vibe.scene.Particle
import com.metrolist.music.ui.vibe.scene.SceneSpec
import com.metrolist.music.ui.vibe.scene.Sky
import com.metrolist.music.ui.vibe.scene.VibeScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.random.Random

private const val DEFAULT_RADIO_SEED = "lofi chill"

private val SceneSaver =
    Saver<SceneSpec, String>(
        save = { Json.encodeToString(SceneSpec.serializer(), it) },
        restore = { Json.decodeFromString(SceneSpec.serializer(), it) },
    )

/** Creates a vibe, edits a custom one ([vibeId]), or makes a new vibe from a preset ([vibeId] of a preset). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VibeEditScreen(navController: NavController, vibeId: String?) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val source = rememberVibes().firstOrNull { it.id == vibeId }
    val isNew = rememberSaveable { source == null || !CustomVibes.isCustom(source.id) }
    val id = rememberSaveable { source?.id?.takeUnless { isNew } ?: CustomVibes.newId() }

    var name by rememberSaveable { mutableStateOf(source?.name.orEmpty()) }
    var scene by rememberSaveable(stateSaver = SceneSaver) { mutableStateOf(source?.scene ?: SceneSpec(seed = Random.nextInt())) }
    var usePlaylist by rememberSaveable { mutableStateOf(source != null && source.music.seed == null) }
    var seed by rememberSaveable { mutableStateOf(source?.music?.seed ?: DEFAULT_RADIO_SEED) }
    var playlistId by rememberSaveable { mutableStateOf(source?.music?.localPlaylistId) }
    // A playlist picked from a YouTube search; exclusive with the library playlist above.
    val ytSource = source?.music?.takeIf { it.localPlaylistId == null }
    var ytPlaylistId by rememberSaveable { mutableStateOf(ytSource?.playlistId) }
    var ytPlaylistTitle by rememberSaveable { mutableStateOf(ytSource?.playlistTitle) }
    var ytPlaylistThumbnail by rememberSaveable { mutableStateOf(ytSource?.playlistThumbnail) }
    var query by rememberSaveable { mutableStateOf("") }
    // Restarting on each keystroke cancels the pending delay, which debounces the search.
    val results by produceState(emptyList<PlaylistItem>(), query) {
        if (query.isBlank()) {
            value = emptyList()
            return@produceState
        }
        delay(400)
        value =
            withContext(Dispatchers.IO) {
                listOf(YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST)
                    .flatMap { YouTube.search(query.trim(), it).getOrNull()?.items.orEmpty() }
                    .filterIsInstance<PlaylistItem>()
                    .distinctBy { it.id }
            }
    }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val playlists by remember(database) { database.playlistsByNameAsc() }.collectAsState(initial = emptyList())
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val canSave = name.isNotBlank() && if (usePlaylist) playlist != null || ytPlaylistId != null else seed.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isNew) R.string.create_vibe else R.string.edit_vibe)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(painterResource(R.drawable.delete), contentDescription = stringResource(R.string.delete))
                        }
                    }
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            val music =
                                when {
                                    !usePlaylist -> VibeMusic(seed = seed.trim())
                                    // The browse id lets a saved YouTube playlist play before its songs are stored.
                                    playlist != null -> VibeMusic(localPlaylistId = playlist.id, playlistId = playlist.playlist.browseId)
                                    else -> VibeMusic(playlistId = ytPlaylistId, playlistTitle = ytPlaylistTitle, playlistThumbnail = ytPlaylistThumbnail)
                                }
                            CustomVibes.save(context, Vibe(id, name.trim(), scene, music))
                            navController.navigateUp()
                        },
                    ) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            VibeScene(
                scene,
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.vibe_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ChipSection(R.string.vibe_sky, Sky.entries, ::skyLabel, { it == scene.sky }) { scene = scene.copy(sky = it) }
                ChipSection(R.string.vibe_scene, Layout.entries, ::layoutLabel, { it == scene.layout }) { scene = scene.copy(layout = it) }
                ChipSection(R.string.vibe_foreground, Foreground.entries, ::foregroundLabel, { it == scene.foreground }) {
                    scene = scene.copy(foreground = it)
                }
                ChipSection(R.string.vibe_effects, Particle.entries, ::particleLabel, { it in scene.particles }) {
                    scene = scene.copy(particles = if (it in scene.particles) scene.particles - it else scene.particles + it)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.vibe_snowy), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = scene.snowy, onCheckedChange = { scene = scene.copy(snowy = it) })
                }
                OutlinedButton(onClick = { scene = scene.copy(seed = Random.nextInt()) }) {
                    Icon(painterResource(R.drawable.shuffle), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.shuffle))
                }

                SectionTitle(R.string.vibe_music)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !usePlaylist, onClick = { usePlaylist = false }, label = { Text(stringResource(R.string.vibe_endless_radio)) })
                    FilterChip(selected = usePlaylist, onClick = { usePlaylist = true }, label = { Text(stringResource(R.string.vibe_playlist)) })
                }
                if (!usePlaylist) {
                    OutlinedTextField(
                        value = seed,
                        onValueChange = { seed = it },
                        label = { Text(stringResource(R.string.vibe_radio_seed)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    if (playlists.isEmpty()) {
                        Text(stringResource(R.string.vibe_no_playlists), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            playlists.forEach {
                                FilterChip(
                                    selected = it.id == playlistId,
                                    onClick = {
                                        playlistId = it.id
                                        ytPlaylistId = null
                                    },
                                    label = { Text(it.playlist.name) },
                                )
                            }
                        }
                    }
                    if (ytPlaylistId != null) {
                        ListItem(
                            title = ytPlaylistTitle.orEmpty(),
                            subtitle = stringResource(R.string.vibe_selected_playlist),
                            thumbnailContent = {
                                ItemThumbnail(
                                    thumbnailUrl = ytPlaylistThumbnail,
                                    isActive = false,
                                    isPlaying = false,
                                    shape = RoundedCornerShape(ThumbnailCornerRadius),
                                    modifier = Modifier.size(ListThumbnailSize),
                                )
                            },
                            isActive = true,
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.vibe_search_playlists)) },
                        leadingIcon = { Icon(painterResource(R.drawable.search), contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    results.forEach { item ->
                        YouTubeListItem(
                            item = item,
                            isActive = item.id == ytPlaylistId,
                            modifier =
                                Modifier.clickable {
                                    ytPlaylistId = item.id
                                    ytPlaylistTitle = item.title
                                    ytPlaylistThumbnail = item.thumbnail
                                    playlistId = null
                                },
                        )
                    }
                }
                Spacer(Modifier.windowInsetsBottomHeight(LocalPlayerAwareWindowInsets.current))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.delete_vibe_confirm, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        CustomVibes.delete(context, id)
                        navController.navigateUp()
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun <T> ChipSection(
    @StringRes title: Int,
    options: List<T>,
    label: (T) -> Int,
    selected: (T) -> Boolean,
    onClick: (T) -> Unit,
) {
    SectionTitle(title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = selected(option), onClick = { onClick(option) }, label = { Text(stringResource(label(option))) })
        }
    }
}

private fun skyLabel(sky: Sky) =
    when (sky) {
        Sky.Dusk -> R.string.vibe_sky_dusk
        Sky.Sunset -> R.string.vibe_sky_sunset
        Sky.Day -> R.string.vibe_sky_day
        Sky.Night -> R.string.vibe_sky_night
        Sky.NeonNight -> R.string.vibe_sky_neon_night
    }

private fun layoutLabel(layout: Layout) =
    when (layout) {
        Layout.Forest -> R.string.vibe_layout_forest
        Layout.Lake -> R.string.vibe_layout_lake
        Layout.City -> R.string.vibe_layout_city
        Layout.Blossoms -> R.string.vibe_layout_blossoms
        Layout.Hills -> R.string.vibe_layout_hills
    }

private fun foregroundLabel(foreground: Foreground) =
    when (foreground) {
        Foreground.None -> R.string.none
        Foreground.Rails -> R.string.vibe_foreground_rails
        Foreground.Shore -> R.string.vibe_foreground_shore
        Foreground.Rooftops -> R.string.vibe_foreground_rooftops
        Foreground.Window -> R.string.vibe_foreground_window
    }

private fun particleLabel(particle: Particle) =
    when (particle) {
        Particle.Snow -> R.string.vibe_effect_snow
        Particle.Petals -> R.string.vibe_effect_petals
        Particle.Rain -> R.string.vibe_effect_rain
        Particle.Fireflies -> R.string.vibe_effect_fireflies
        Particle.Stars -> R.string.vibe_effect_stars
        Particle.ShootingStars -> R.string.vibe_effect_shooting_stars
        Particle.Clouds -> R.string.vibe_effect_clouds
        Particle.Lightning -> R.string.vibe_effect_lightning
    }
