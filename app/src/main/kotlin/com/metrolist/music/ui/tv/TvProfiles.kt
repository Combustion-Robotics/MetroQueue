/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.playback.tv.TvProfile
import com.metrolist.music.playback.tv.TvProfileStore

val LocalTvProfiles = staticCompositionLocalOf<TvProfileStore?> { null }

/** The phone profile whose library the TV shows, or null for Guest (the TV's own library). */
@Composable
fun rememberActiveProfile(): TvProfileStore.Active? =
    LocalTvProfiles.current
        ?.active
        ?.collectAsStateWithLifecycle()
        ?.value

const val PROFILE_LIKED_ID = "__liked__"

fun profilePlaylistRoute(id: String) = "profile_playlist/${Uri.encode(id)}"

fun relativeTime(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** A round avatar with the profile's initial; Guest ([profile] null) gets a person icon. */
@Composable
fun TvProfileAvatar(
    profile: TvProfile?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) =
        when {
            profile == null -> colors.surfaceContainerHighest to colors.onSurfaceVariant
            else ->
                when ((profile.id.hashCode() and Int.MAX_VALUE) % 3) {
                    0 -> colors.primaryContainer to colors.onPrimaryContainer
                    1 -> colors.secondaryContainer to colors.onSecondaryContainer
                    else -> colors.tertiaryContainer to colors.onTertiaryContainer
                }
        }
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size).background(container, CircleShape)) {
        if (profile == null) {
            Icon(painterResource(R.drawable.person), contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
        } else {
            Text(
                text = profile.name.trim().take(1).uppercase(),
                style = if (size >= 64.dp) MaterialTheme.typography.displayMedium else MaterialTheme.typography.titleLarge,
                color = content,
            )
        }
    }
}

/** "Who's listening?": the synced phones as profiles, plus Guest for the TV's own library. */
@Composable
fun TvProfilePicker(
    profiles: List<TvProfile>,
    activeId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().tvThemedBackground(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.tv_whos_listening),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(40.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    contentPadding = PaddingValues(horizontal = TvDimens.SafeHorizontal, vertical = 16.dp),
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        TvProfileTile(
                            profile = profile,
                            title = profile.name,
                            subtitle = stringResource(R.string.tv_profile_synced, relativeTime(profile.syncedAt)),
                            onClick = { onSelect(profile.id) },
                            modifier =
                                if (profile.id == activeId || (activeId == null && profile == profiles.first())) {
                                    Modifier.tvInitialFocus()
                                } else {
                                    Modifier
                                },
                        )
                    }
                    item(key = "guest") {
                        TvProfileTile(
                            profile = null,
                            title = stringResource(R.string.tv_guest),
                            subtitle = stringResource(R.string.tv_guest_desc),
                            onClick = { onSelect(null) },
                            modifier = if (profiles.isEmpty()) Modifier.tvInitialFocus() else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvProfileTile(
    profile: TvProfile?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(168.dp)) {
        TvSurface(
            onClick = onClick,
            modifier = modifier.size(136.dp),
            shape = CircleShape,
            focusedContainerColor = Color.Transparent,
            focusedScale = 1.1f,
            focusBorder = true,
        ) {
            TvProfileAvatar(profile = profile, size = 136.dp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** The Library tab for a phone profile: its liked songs and playlists, albums and artists. */
@Composable
fun TvProfileLibrary(active: TvProfileStore.Active) {
    val tv = LocalTvController.current
    val focusMemory = rememberTvFocusMemory()
    val library = active.library

    LazyColumn(
        contentPadding = PaddingValues(bottom = TvDimens.SafeVertical * 2),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "title") {
            Column {
                TvScreenTitle(stringResource(R.string.tv_library))
                Text(
                    text = stringResource(R.string.tv_profile_library_from, active.profile.name, relativeTime(active.profile.syncedAt)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = TvDimens.SafeHorizontal / 2),
                )
            }
        }
        item(key = "playlists") {
            TvRow(
                title = stringResource(R.string.playlists),
                items = library.playlists,
                key = { it.id },
                leadingItem = {
                    TvMediaCard(
                        title = stringResource(R.string.liked_songs),
                        subtitle = null,
                        thumbnailUrl = null,
                        placeholderIcon = R.drawable.favorite,
                        onClick = { tv.navigate(profilePlaylistRoute(PROFILE_LIKED_ID)) },
                        modifier =
                            Modifier
                                .tvFocusMemory(focusMemory, "liked")
                                .tvInitialFocus(skip = focusMemory.restorePending),
                    )
                },
            ) { playlist ->
                TvMediaCard(
                    title = playlist.name,
                    subtitle = null,
                    thumbnailUrl = playlist.thumbnail ?: playlist.songs.firstOrNull()?.thumb,
                    placeholderIcon = R.drawable.queue_music,
                    onClick = { tv.navigate(profilePlaylistRoute(playlist.id)) },
                    modifier = Modifier.tvFocusMemory(focusMemory, "playlist/${playlist.id}"),
                )
            }
        }
        if (library.albums.isNotEmpty()) {
            item(key = "albums") {
                TvRow(title = stringResource(R.string.albums), items = library.albums, key = { it.id }) { album ->
                    TvMediaCard(
                        title = album.title,
                        subtitle = album.artist ?: album.year?.toString(),
                        thumbnailUrl = album.thumbnail,
                        placeholderIcon = R.drawable.album,
                        onClick = { tv.navigate("album/${album.id}") },
                        modifier = Modifier.tvFocusMemory(focusMemory, "album/${album.id}"),
                    )
                }
            }
        }
        if (library.artists.isNotEmpty()) {
            item(key = "artists") {
                TvRow(title = stringResource(R.string.artists), items = library.artists, key = { it.id }) { artist ->
                    TvMediaCard(
                        title = artist.name,
                        subtitle = null,
                        thumbnailUrl = artist.thumbnail,
                        placeholderIcon = R.drawable.artist,
                        round = true,
                        onClick = { tv.navigate("artist/${artist.id}") },
                        modifier = Modifier.tvFocusMemory(focusMemory, "artist/${artist.id}"),
                    )
                }
            }
        }
    }
}
