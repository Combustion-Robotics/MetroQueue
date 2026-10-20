/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.R
import com.metrolist.music.constants.MixMode
import com.metrolist.music.constants.ShowLyricsKey
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.lyrics.lyricsTextLooksSynced
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.mix.CustomTransition
import com.metrolist.music.ui.menu.rememberMixSettings
import com.metrolist.music.ui.menu.setMixMode
import com.metrolist.music.ui.menu.transitionSummary
import com.metrolist.music.ui.theme.PlayerColorExtractor
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SEEK_BAR_STEP_MS = 10_000L

/** Fullscreen player: artwork, progress, transport, lyrics, Mix status and the next songs. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvNowPlaying(
    playerConnection: PlayerConnection,
    onOpenQueue: () -> Unit,
) {
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val playbackState by playerConnection.playbackState.collectAsStateWithLifecycle()
    val shuffle by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
    val repeatMode by playerConnection.repeatMode.collectAsStateWithLifecycle()
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle()
    val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle()
    val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle()
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()
    var showLyrics by rememberPreference(ShowLyricsKey, defaultValue = false)

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playerConnection) {
        while (true) {
            position = playerConnection.player.currentPosition
            duration = playerConnection.player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(500)
        }
    }

    // The screen doubles as the TV's "now playing" display, so don't let it sleep mid-song
    val view = LocalView.current
    DisposableEffect(view, isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }

    val metadata = mediaMetadata
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        TvArtworkBackdrop(metadata?.thumbnailUrl)
        if (metadata == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            TvNowPlayingContent(
                playerConnection = playerConnection,
                metadata = metadata,
                isPlaying = isPlaying,
                ended = playbackState == Player.STATE_ENDED,
                shuffle = shuffle,
                repeatMode = repeatMode,
                liked = currentSong?.song?.liked == true,
                queueTitle = queueTitle,
                queueWindows = queueWindows,
                currentWindowIndex = currentWindowIndex,
                showLyrics = showLyrics,
                onToggleLyrics = { showLyrics = !showLyrics },
                position = position,
                duration = duration,
                onSeek = { target ->
                    position = target
                    playerConnection.seekTo(target)
                },
                onOpenQueue = onOpenQueue,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvNowPlayingContent(
    playerConnection: PlayerConnection,
    metadata: MediaMetadata,
    isPlaying: Boolean,
    ended: Boolean,
    shuffle: Boolean,
    repeatMode: Int,
    liked: Boolean,
    queueTitle: String?,
    queueWindows: List<Timeline.Window>,
    currentWindowIndex: Int,
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = TvDimens.SafeHorizontal, vertical = TvDimens.SafeVertical),
    ) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AsyncImage(
                model = metadata.thumbnailUrl?.resize(720, 720),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .shadow(24.dp, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Spacer(Modifier.width(40.dp))
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Text(
                    text = queueTitle ?: stringResource(R.string.now_playing),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = metadata.title,
                    style = if (showLyrics) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metadata.artists.joinToString { it.name },
                    style = if (showLyrics) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedContent(
                    targetState = showLyrics,
                    transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                    label = "tvLyricsToggle",
                    modifier = Modifier.weight(1f, fill = showLyrics),
                ) { lyricsVisible ->
                    if (lyricsVisible) {
                        TvLyrics(
                            playerConnection = playerConnection,
                            position = position,
                            duration = duration,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column {
                            metadata.album?.title?.let { album ->
                                Text(
                                    text = album,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(20.dp))
                            TvMixStatus(playerConnection, queueWindows, currentWindowIndex)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TvSeekBar(position = position, duration = duration, onSeek = onSeek)
        Spacer(Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().focusRestorer(FocusRequester.Default),
        ) {
            TvIconButton(
                icon = if (shuffle) R.drawable.shuffle_on else R.drawable.shuffle,
                contentDescription = stringResource(R.string.shuffle),
                active = shuffle,
                onClick = { playerConnection.player.shuffleModeEnabled = !shuffle },
            )
            TvIconButton(
                icon = R.drawable.skip_previous,
                contentDescription = null,
                onClick = playerConnection::seekToPrevious,
            )
            TvIconButton(
                icon =
                    when {
                        ended -> R.drawable.replay
                        isPlaying -> R.drawable.pause
                        else -> R.drawable.play
                    },
                contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                size = 76.dp,
                emphasized = true,
                onClick = {
                    if (ended) {
                        playerConnection.seekTo(0)
                        playerConnection.play()
                    } else {
                        playerConnection.togglePlayPause()
                    }
                },
                modifier = Modifier.tvInitialFocus(),
            )
            TvIconButton(
                icon = R.drawable.skip_next,
                contentDescription = null,
                onClick = playerConnection::seekToNext,
            )
            TvIconButton(
                icon =
                    when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                        Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
                        else -> R.drawable.repeat
                    },
                contentDescription = null,
                active = repeatMode != Player.REPEAT_MODE_OFF,
                onClick = { playerConnection.player.toggleRepeatMode() },
            )
            Spacer(Modifier.width(24.dp))
            TvIconButton(
                icon = if (liked) R.drawable.favorite else R.drawable.favorite_border,
                contentDescription = null,
                active = liked,
                onClick = playerConnection::toggleLike,
            )
            TvIconButton(
                icon = R.drawable.lyrics,
                contentDescription = stringResource(R.string.lyrics),
                active = showLyrics,
                onClick = onToggleLyrics,
            )
            TvIconButton(
                icon = R.drawable.queue_music,
                contentDescription = stringResource(R.string.queue),
                onClick = onOpenQueue,
            )
        }

        val upNext =
            remember(queueWindows, currentWindowIndex) {
                queueWindows.drop(currentWindowIndex + 1).take(30)
            }
        if (upNext.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.tv_up_next),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.focusRestorer(FocusRequester.Default),
            ) {
                itemsIndexed(upNext, key = { _, window -> window.uid.hashCode() }) { _, window ->
                    TvUpNextItem(
                        window = window,
                        onClick = {
                            playerConnection.player.seekToDefaultPosition(window.firstPeriodIndex)
                            playerConnection.player.playWhenReady = true
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvUpNextItem(
    window: Timeline.Window,
    onClick: () -> Unit,
) {
    val metadata = window.mediaItem.metadata ?: return
    TvSurface(
        onClick = onClick,
        modifier = Modifier.width(260.dp).height(60.dp),
        shape = RoundedCornerShape(14.dp),
        containerColor = Color.White.copy(alpha = 0.08f),
        focusedContainerColor = MaterialTheme.colorScheme.primary,
        contentColor = Color.White,
        focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        focusedScale = 1.06f,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(6.dp)) {
            TvArtwork(
                url = metadata.thumbnailUrl,
                sizePx = 120,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(metadata.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    metadata.artists.joinToString { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Progress bar that seeks with D-pad left/right while focused (up/down leave it as usual). */
@Composable
private fun TvSeekBar(
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val trackHeight by animateDpAsState(if (focused) 10.dp else 5.dp, label = "tvSeekHeight")
    val activeColor by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary else Color.White,
        label = "tvSeekColor",
    )
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = makeTimeString(position),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.width(64.dp),
        )
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .onKeyEvent { event ->
                        if (duration <= 0) return@onKeyEvent false
                        val delta =
                            when (event.key) {
                                Key.DirectionLeft -> -SEEK_BAR_STEP_MS
                                Key.DirectionRight -> SEEK_BAR_STEP_MS
                                else -> return@onKeyEvent false
                            }
                        if (event.type == KeyEventType.KeyDown) onSeek((position + delta).coerceIn(0L, duration))
                        true
                    }.focusable(interactionSource = interactionSource)
                    .drawBehind {
                        val height = trackHeight.toPx()
                        val top = (size.height - height) / 2
                        val radius = CornerRadius(height / 2)
                        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, top), Size(size.width, height), radius)
                        drawRoundRect(activeColor, Offset(0f, top), Size(size.width * progress, height), radius)
                        if (focused) {
                            drawCircle(activeColor, radius = height * 1.2f, center = Offset(size.width * progress, size.height / 2))
                        }
                    },
        )
        Text(
            text = makeTimeString(duration),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.width(64.dp).padding(start = 12.dp),
        )
    }
}

/** Shows how the current song will blend into the next one; selecting it switches the Mix mode. */
@Composable
private fun TvMixStatus(
    playerConnection: PlayerConnection,
    queueWindows: List<Timeline.Window>,
    currentWindowIndex: Int,
) {
    val settings = rememberMixSettings() ?: return
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val customTransitions by playerConnection.service.customTransitions.transitions.collectAsStateWithLifecycle()
    val from = queueWindows.getOrNull(currentWindowIndex)?.mediaItem
    val to = from?.let { queueWindows.getOrNull(currentWindowIndex + 1)?.mediaItem }
    val custom =
        if (from != null && to != null) customTransitions[CustomTransition.pairKey(from.mediaId, to.mediaId)] else null
    val gapless = from?.mediaMetadata?.albumTitle?.let { it == to?.mediaMetadata?.albumTitle } == true
    val summary = transitionSummary(settings, custom, gapless)

    TvButton(
        onClick = {
            val modes = MixMode.entries
            coroutineScope.launch { context.setMixMode(modes[(settings.mode.ordinal + 1) % modes.size]) }
        },
        icon = R.drawable.linear_scale,
        text = "${stringResource(R.string.tv_mix)} · $summary",
        selected = settings.mode != MixMode.OFF,
    )
}

/** Synced lyrics follow playback; plain lyrics scroll along with the song's progress. */
@Composable
private fun TvLyrics(
    playerConnection: PlayerConnection,
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
) {
    val lyricsEntity by playerConnection.currentLyrics.collectAsStateWithLifecycle()
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle()
    val raw = lyricsEntity?.lyrics
    val synced = remember(raw) { lyricsTextLooksSynced(raw) }
    val lines by produceState(emptyList<LyricsEntry>(), raw) {
        value =
            withContext(Dispatchers.Default) {
                when {
                    raw == null || raw == LYRICS_NOT_FOUND -> emptyList()
                    synced -> LyricsUtils.parseLyrics(raw).filter { it.text.isNotBlank() }
                    else -> raw.lines().filter { it.isNotBlank() }.map { LyricsEntry(0L, it) }
                }
            }
    }

    val offset = currentSong?.song?.lyricsOffset ?: 0
    val currentIndex =
        when {
            lines.isEmpty() -> -1
            synced -> LyricsUtils.findCurrentLineIndex(lines, position + offset)
            duration > 0 -> ((position.toFloat() / duration) * lines.size).toInt().coerceIn(0, lines.lastIndex)
            else -> 0
        }
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex, lines) {
        if (currentIndex >= 0) listState.animateScrollToItem(currentIndex)
    }

    Box(modifier, contentAlignment = Alignment.CenterStart) {
        when {
            lyricsEntity == null -> CircularProgressIndicator()
            lines.isEmpty() ->
                Text(
                    text = stringResource(R.string.lyrics_not_found),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.6f),
                )
            else ->
                LazyColumn(
                    state = listState,
                    userScrollEnabled = false,
                    contentPadding = PaddingValues(top = 72.dp, bottom = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(lines) { index, line ->
                        val current = synced && index == currentIndex
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                            color = if (current || !synced) Color.White else Color.White.copy(alpha = 0.4f),
                        )
                    }
                }
        }
    }
}

/**
 * Artwork-coloured backdrop without a real-time blur: a tiny copy of the artwork stretched across the
 * screen (bilinear upscaling blurs it for free) under a gradient made from its palette.
 */
@Composable
private fun TvArtworkBackdrop(url: String?) {
    val context = LocalContext.current
    val fallback = MaterialTheme.colorScheme.surface
    var palette by remember { mutableStateOf<List<Color>?>(null) }
    LaunchedEffect(url) {
        if (url == null) {
            palette = null
            return@LaunchedEffect
        }
        palette =
            withContext(Dispatchers.IO) {
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(url)
                        .size(100, 100)
                        .allowHardware(false)
                        .memoryCacheKey("tv_palette_$url")
                        .build()
                val bitmap =
                    runCatching { context.imageLoader.execute(request).image?.toBitmap() }.getOrNull()
                        ?: return@withContext null
                val generated =
                    Palette
                        .from(bitmap)
                        .maximumColorCount(8)
                        .resizeBitmapArea(100 * 100)
                        .generate()
                PlayerColorExtractor.extractGradientColors(generated, fallback.toArgb())
            }
    }
    val top by animateColorAsState(palette?.getOrNull(0) ?: fallback, tween(700), label = "tvBackdropTop")
    val middle by animateColorAsState(palette?.getOrNull(1) ?: fallback, tween(700), label = "tvBackdropMiddle")

    if (url != null) {
        AsyncImage(
            model =
                ImageRequest
                    .Builder(context)
                    .data(url)
                    .size(32, 32)
                    .memoryCacheKey("tv_backdrop_$url")
                    .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low,
            alpha = 0.6f,
            modifier = Modifier.fillMaxSize(),
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        top.copy(alpha = 0.55f),
                        middle.copy(alpha = 0.75f),
                        Color.Black.copy(alpha = 0.92f),
                    ),
                ),
            ),
    )
}
