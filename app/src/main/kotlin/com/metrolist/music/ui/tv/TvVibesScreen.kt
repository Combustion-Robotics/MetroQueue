/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.ui.vibe.Vibe
import com.metrolist.music.ui.vibe.VibeSongBar
import com.metrolist.music.ui.vibe.playVibeMusic
import com.metrolist.music.ui.vibe.rememberVibes
import com.metrolist.music.ui.vibe.scene.VibeScene
import kotlinx.coroutines.launch

/** Vibe cards; picking one asks what should play, then shows its scene full screen. */
@Composable
fun TvVibesScreen() {
    val vibes = rememberVibes()
    val playerConnection = LocalPlayerConnection.current
    val database = LocalDatabase.current
    val tv = LocalTvController.current
    val coroutineScope = rememberCoroutineScope()
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var playingId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TvScreenTitle(stringResource(R.string.vibes))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(240.dp),
            contentPadding =
                PaddingValues(
                    start = TvDimens.SafeHorizontal / 2,
                    end = TvDimens.SafeHorizontal,
                    top = 14.dp,
                    bottom = TvDimens.SafeVertical * 2,
                ),
            horizontalArrangement = Arrangement.spacedBy(TvDimens.ItemSpacing),
            verticalArrangement = Arrangement.spacedBy(TvDimens.ItemSpacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(vibes, key = { _, vibe -> vibe.id }) { index, vibe ->
                TvVibeCard(
                    vibe = vibe,
                    onClick = { pendingId = vibe.id },
                    modifier = if (index == 0) Modifier.tvInitialFocus() else Modifier,
                )
            }
        }
    }

    vibes.firstOrNull { it.id == pendingId }?.let { vibe ->
        TvVibeStartDialog(
            vibe = vibe,
            onDismiss = { pendingId = null },
            onStart = { playMusic ->
                pendingId = null
                playingId = vibe.id
                if (playMusic && playerConnection != null) {
                    coroutineScope.launch { playVibeMusic(vibe.music, playerConnection, database) }
                }
            },
        )
    }

    vibes.firstOrNull { it.id == playingId }?.let { vibe ->
        TvVibePlayer(
            vibe = vibe,
            playerConnection = playerConnection,
            onMenu = { tv.queueVisible = !tv.queueVisible },
            onDismiss = { playingId = null },
        )
    }
}

@Composable
private fun TvVibeCard(
    vibe: Vibe,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column {
        TvSurface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedScale = 1.06f,
            focusBorder = true,
        ) { focused ->
            // Only the focused card animates, to keep weaker TV hardware smooth.
            VibeScene(vibe.scene, Modifier.fillMaxSize(), animate = focused)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = vibe.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TvVibeStartDialog(
    vibe: Vibe,
    onDismiss: () -> Unit,
    onStart: (playMusic: Boolean) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .widthIn(max = 640.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
                        .padding(horizontal = 48.dp, vertical = 40.dp),
            ) {
                Text(
                    text = vibe.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.vibe_start_question),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvButton(
                        onClick = { onStart(true) },
                        icon = R.drawable.play,
                        text = stringResource(R.string.vibe_play_music),
                        modifier = Modifier.tvInitialFocus(),
                    )
                    TvButton(
                        onClick = { onStart(false) },
                        text = stringResource(R.string.vibe_keep_playing),
                    )
                }
            }
        }
    }
}

/** The scene full screen with the song bar on the left; Back closes it, media keys work as everywhere. */
@Composable
private fun TvVibePlayer(
    vibe: Vibe,
    playerConnection: PlayerConnection?,
    onMenu: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val isPlaying = playerConnection?.isPlaying?.collectAsStateWithLifecycle()?.value == true
        val view = LocalView.current
        DisposableEffect(view, isPlaying) {
            view.keepScreenOn = isPlaying
            onDispose { view.keepScreenOn = false }
        }

        Box(Modifier.fillMaxSize().background(Color.Black).tvRemoteKeys(playerConnection, onMenu)) {
            VibeScene(vibe.scene, Modifier.fillMaxSize())
            VibeSongBar(
                vertical = true,
                isPlaying = isPlaying,
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(horizontal = TvDimens.SafeHorizontal, vertical = TvDimens.SafeVertical)
                        .width(180.dp),
            ) {
                TvIconButton(
                    icon = if (isPlaying) R.drawable.pause else R.drawable.play,
                    contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                    onClick = { playerConnection?.togglePlayPause() },
                    size = 48.dp,
                    modifier = Modifier.tvInitialFocus(),
                )
                TvIconButton(
                    icon = R.drawable.skip_next,
                    contentDescription = stringResource(R.string.next),
                    onClick = { playerConnection?.seekToNext() },
                    size = 48.dp,
                )
            }
        }
    }
}
