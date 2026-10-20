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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.metrolist.music.R
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.move
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.utils.makeTimeString

/** The play queue: select a song to play from there, or move / remove it with the buttons beside it. */
@Composable
fun TvQueue(playerConnection: PlayerConnection) {
    val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle()
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()
    val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentWindowIndex - 2).coerceAtLeast(0))
    // Index of the row that should take focus next: the playing song at first, a neighbour after a removal
    var focusIndex by remember { mutableStateOf<Int?>(currentWindowIndex.coerceAtLeast(0)) }

    fun move(
        from: Int,
        to: Int,
    ) {
        if (to !in queueWindows.indices) return
        val player = playerConnection.player
        if (!player.shuffleModeEnabled || player !is ExoPlayer) {
            player.moveMediaItem(from, to)
        } else {
            // In shuffle mode the list shows the shuffle order, so reorder that instead of the items
            player.setShuffleOrder(
                DefaultShuffleOrder(
                    queueWindows
                        .map { it.firstPeriodIndex }
                        .toMutableList()
                        .move(from, to)
                        .toIntArray(),
                    System.currentTimeMillis(),
                ),
            )
        }
    }

    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .tvThemedBackground()
                .padding(horizontal = TvDimens.SafeHorizontal, vertical = TvDimens.SafeVertical),
    ) {
        Column(Modifier.width(240.dp).padding(end = 24.dp)) {
            Text(
                text = stringResource(R.string.queue),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            queueTitle?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = pluralStringResource(R.plurals.n_song, queueWindows.size, queueWindows.size),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (queueWindows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.tv_queue_empty),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(queueWindows, key = { _, window -> window.uid.hashCode() }) { index, window ->
                val metadata = window.mediaItem.metadata
                val requester = remember { FocusRequester() }
                LaunchedEffect(focusIndex) {
                    if (focusIndex == index) {
                        withFrameNanos { }
                        runCatching { requester.requestFocus() }
                        focusIndex = null
                    }
                }
                val isCurrent = index == currentWindowIndex

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TvSurface(
                        onClick = {
                            if (isCurrent) {
                                playerConnection.togglePlayPause()
                            } else {
                                playerConnection.player.seekToDefaultPosition(window.firstPeriodIndex)
                                playerConnection.player.playWhenReady = true
                            }
                        },
                        modifier = Modifier.weight(1f).height(68.dp).focusRequester(requester),
                        shape = RoundedCornerShape(16.dp),
                        containerColor =
                            if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        contentColor =
                            if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        focusedScale = 1.02f,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        ) {
                            TvArtwork(
                                url = metadata?.thumbnailUrl,
                                sizePx = 120,
                                modifier = Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = metadata?.title.orEmpty(),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = metadata?.artists?.joinToString { it.name }.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (isCurrent) {
                                Icon(
                                    painter = painterResource(if (isPlaying) R.drawable.graphic_eq else R.drawable.pause),
                                    contentDescription = null,
                                    modifier = Modifier.padding(horizontal = 12.dp).size(24.dp),
                                )
                            }
                            metadata?.duration?.takeIf { it > 0 }?.let { seconds ->
                                Text(text = makeTimeString(seconds * 1000L), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    QueueAction(R.drawable.arrow_upward, R.string.tv_move_up, visible = index > 0) { move(index, index - 1) }
                    QueueAction(R.drawable.arrow_downward, R.string.tv_move_down, visible = index < queueWindows.lastIndex) {
                        move(index, index + 1)
                    }
                    QueueAction(R.drawable.delete, R.string.remove_from_queue, visible = !isCurrent) {
                        focusIndex = if (index < queueWindows.lastIndex) index else index - 1
                        playerConnection.player.removeMediaItem(window.firstPeriodIndex)
                    }
                }
            }
        }
    }
}

/** A small action next to a queue row; hidden ones keep their space so the columns stay aligned. */
@Composable
private fun QueueAction(
    icon: Int,
    label: Int,
    visible: Boolean,
    onClick: () -> Unit,
) {
    Box(Modifier.padding(start = 8.dp).size(48.dp)) {
        if (visible) {
            TvIconButton(icon = icon, contentDescription = stringResource(label), size = 48.dp, onClick = onClick)
        }
    }
}
