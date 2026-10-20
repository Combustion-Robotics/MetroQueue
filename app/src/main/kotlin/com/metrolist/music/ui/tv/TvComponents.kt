/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.ui.utils.resize

object TvDimens {
    /** Overscan-safe margins for the 10-foot UI (5% of a 960x540dp screen). */
    val SafeHorizontal = 48.dp
    val SafeVertical = 27.dp
    val RailWidth = 88.dp
    val RailExpandedWidth = 232.dp
    val CardWidth = 168.dp
    val ItemSpacing = 20.dp
}

private val FocusAnimation = tween<Float>(durationMillis = 160)

/**
 * The focus target every TV control is built on: scales up, recolours and optionally draws a border
 * while focused, and stays on top of its neighbours so the scaled content isn't covered.
 */
@Composable
fun TvSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    containerColor: Color = Color.Transparent,
    focusedContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    focusedContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    focusedScale: Float = 1.04f,
    focusBorder: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, FocusAnimation, label = "tvFocusScale")
    val container by animateColorAsState(if (focused) focusedContainerColor else containerColor, label = "tvFocusContainer")
    val contentTint by animateColorAsState(if (focused) focusedContentColor else contentColor, label = "tvFocusContent")
    val borderColor by animateColorAsState(
        if (focused && focusBorder) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "tvFocusBorder",
    )
    Surface(
        onClick = onClick,
        modifier =
            modifier
                .zIndex(if (focused) 1f else 0f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        enabled = enabled,
        shape = shape,
        color = container,
        contentColor = contentTint,
        shadowElevation = if (focused && focusBorder) 12.dp else 0.dp,
        border = if (focusBorder) BorderStroke(3.dp, borderColor) else null,
        interactionSource = interactionSource,
    ) {
        Box(contentAlignment = Alignment.Center) { content(focused) }
    }
}

/** A pill button: tonal at rest, filled with the theme's primary colour while focused. */
@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    text: String? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    TvSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = CircleShape,
        containerColor =
            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        focusedContainerColor = MaterialTheme.colorScheme.primary,
        contentColor =
            if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        focusedScale = 1.08f,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = if (text == null) 14.dp else 22.dp, vertical = 12.dp),
        ) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = text, modifier = Modifier.size(24.dp))
            }
            if (icon != null && text != null) Spacer(Modifier.width(10.dp))
            if (text != null) {
                Text(text = text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        }
    }
}

/** A round icon-only transport button. */
@Composable
fun TvIconButton(
    icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    active: Boolean = false,
    emphasized: Boolean = false,
) {
    TvSurface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        containerColor =
            when {
                emphasized -> MaterialTheme.colorScheme.primaryContainer
                active -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
            },
        focusedContainerColor = MaterialTheme.colorScheme.primary,
        contentColor =
            when {
                emphasized -> MaterialTheme.colorScheme.onPrimaryContainer
                active -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
        focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        focusedScale = 1.12f,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}

/** Square (or round, for artists) artwork card with a title under it, as used in every row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvMediaCard(
    title: String,
    subtitle: String?,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = TvDimens.CardWidth,
    round: Boolean = false,
    active: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Column(modifier = Modifier.width(width)) {
        TvSurface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(1f),
            shape = if (round) CircleShape else RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedScale = 1.08f,
            focusBorder = true,
            interactionSource = interactionSource,
        ) {
            TvArtwork(url = thumbnailUrl, sizePx = 400, modifier = Modifier.fillMaxSize())
            if (active) {
                Icon(
                    painter = painterResource(R.drawable.graphic_eq),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .size(32.dp)
                            .drawBehind { drawCircle(Color.Black.copy(alpha = 0.55f)) }
                            .padding(6.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = if (focused) Modifier.basicMarquee() else Modifier,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Artwork sized for its slot so the TV never decodes full-size images. */
@Composable
fun TvArtwork(
    url: String?,
    sizePx: Int,
    modifier: Modifier = Modifier,
) {
    if (url == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.music_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }
    } else {
        AsyncImage(
            model = url.resize(sizePx, sizePx),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

/** A titled horizontal row of cards; focus returns to the last card used when coming back to the row. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun <T> TvRow(
    title: String,
    items: List<T>,
    key: (T) -> String,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    val distinctItems = remember(items) { items.distinctBy(key) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = TvDimens.SafeHorizontal / 2),
        )
        LazyRow(
            contentPadding = PaddingValues(start = TvDimens.SafeHorizontal / 2, end = TvDimens.SafeHorizontal, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(TvDimens.ItemSpacing),
            modifier = Modifier.focusRestorer(FocusRequester.Default),
        ) {
            items(distinctItems, key = key) { itemContent(it) }
        }
    }
}

/** Large title used at the top of TV screens. */
@Composable
fun TvScreenTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = TvDimens.SafeHorizontal / 2, top = TvDimens.SafeVertical, bottom = 12.dp),
    )
}

/** Background with a soft glow in the current theme colour, which follows the playing artwork. */
@Composable
fun Modifier.tvThemedBackground(): Modifier {
    val glow by animateColorAsState(MaterialTheme.colorScheme.primaryContainer, tween(800), label = "tvGlow")
    val base by animateColorAsState(MaterialTheme.colorScheme.surface, tween(800), label = "tvBase")
    return drawBehind {
        drawRect(base)
        drawRect(
            Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * 0.2f, 0f),
                radius = size.maxDimension * 0.75f,
            ),
        )
    }
}

/**
 * Remembers which item last had focus on a screen, across navigation, so Back lands on the card the
 * user opened instead of the top of the screen.
 */
@Stable
class TvFocusMemory internal constructor(
    savedKey: String?,
) {
    var lastKey: String? = savedKey
        internal set

    // Only a screen coming back from the back stack restores; a fresh one keeps its default focus.
    var restorePending by mutableStateOf(savedKey != null)
        internal set

    internal fun onFocused(key: String) {
        lastKey = key
    }

    internal fun consume() {
        restorePending = false
    }

    companion object {
        val Saver =
            Saver<TvFocusMemory, String>(
                save = { it.lastKey },
                restore = { TvFocusMemory(it) },
            )
    }
}

@Composable
fun rememberTvFocusMemory(): TvFocusMemory = rememberSaveable(saver = TvFocusMemory.Saver) { TvFocusMemory(null) }

/** Tracks focus for [memory] under [key], and takes focus back when the screen is restored. */
@Composable
fun Modifier.tvFocusMemory(
    memory: TvFocusMemory,
    key: String,
): Modifier {
    val requester = remember { FocusRequester() }
    if (memory.restorePending && memory.lastKey == key) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
            memory.consume()
        }
    }
    return focusRequester(requester).onFocusChanged { if (it.isFocused) memory.onFocused(key) }
}

/** Requests focus for this element once [ready] turns true, unless [skip] (e.g. a restore is pending). */
@Composable
fun Modifier.tvInitialFocus(
    ready: Boolean = true,
    skip: Boolean = false,
): Modifier {
    val requester = remember { FocusRequester() }
    var done by rememberSaveable { mutableStateOf(false) }
    if (ready && !skip && !done) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
            done = true
        }
    }
    return focusRequester(requester)
}
