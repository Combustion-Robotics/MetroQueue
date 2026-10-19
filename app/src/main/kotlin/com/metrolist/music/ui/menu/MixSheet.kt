/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.CrossfadeDurationKey
import com.metrolist.music.constants.CrossfadeEnabledKey
import com.metrolist.music.constants.CrossfadeGaplessKey
import com.metrolist.music.constants.MixMode
import com.metrolist.music.constants.MixModeKey
import com.metrolist.music.constants.MixTransitionStyle
import com.metrolist.music.constants.MixTransitionStyleKey
import com.metrolist.music.constants.mixMode
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.playback.mix.CustomTransition
import com.metrolist.music.playback.mix.TransitionStyle
import com.metrolist.music.ui.screens.mix.LocalTransitionEditorState
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The global Mix settings (Settings > Player > Mix). */
data class MixSettings(
    val mode: MixMode,
    val style: MixTransitionStyle,
    val durationSeconds: Float,
    val skipGaplessAlbums: Boolean,
)

/** Crossfade duration slider range in seconds, shared with the player settings. */
val MixDurationRange = 1f..15f
const val MIX_DURATION_STEPS = 14

/** The Mix settings, or null until they are loaded. */
@Composable
fun rememberMixSettings(): MixSettings? {
    val context = LocalContext.current
    val settings =
        remember(context) {
            context.dataStore.data
                .map {
                    MixSettings(
                        mode = it.mixMode(),
                        style = it[MixTransitionStyleKey].toEnum(MixTransitionStyle.AUTO),
                        durationSeconds = it[CrossfadeDurationKey] ?: 5f,
                        skipGaplessAlbums = it[CrossfadeGaplessKey] ?: true,
                    )
                }.distinctUntilChanged()
        }
    return settings.collectAsStateWithLifecycle(initialValue = null).value
}

/** Writes the mode the way the player settings do: the crossfade switch turns Mix on and off. */
suspend fun Context.setMixMode(mode: MixMode) {
    safeDataStoreEdit {
        it[MixModeKey] = mode.name
        it[CrossfadeEnabledKey] = mode != MixMode.OFF
    }
}

/**
 * Quick Mix settings from the player menu, plus the transition from the current song into the
 * next one in the queue.
 */
@Composable
fun MixSheet(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current ?: return
    val transitionEditorState = LocalTransitionEditorState.current
    val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle()
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()
    val settings = rememberMixSettings() ?: return

    val current = queueWindows.getOrNull(currentWindowIndex)?.mediaItem?.metadata
    val next = if (current != null) queueWindows.getOrNull(currentWindowIndex + 1)?.mediaItem?.metadata else null

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()),
    ) {
        Text(text = stringResource(R.string.transitions), style = MaterialTheme.typography.titleLarge)

        Spacer(Modifier.height(16.dp))
        Text(text = stringResource(R.string.mix_mode), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MixMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == settings.mode,
                    onClick = { coroutineScope.launch { context.setMixMode(mode) } },
                    label = { Text(mixModeLabel(mode)) },
                )
            }
        }
        if (settings.mode == MixMode.SMART) {
            Text(
                text = stringResource(R.string.mix_smart_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (settings.mode != MixMode.OFF) {
            Spacer(Modifier.height(16.dp))
            Text(text = stringResource(R.string.mix_transition_style), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MixTransitionStyle.entries.forEach { style ->
                    FilterChip(
                        selected = style == settings.style,
                        onClick = {
                            coroutineScope.launch { context.safeDataStoreEdit { it[MixTransitionStyleKey] = style.name } }
                        },
                        label = { Text(transitionStyleLabel(style)) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            val seconds = settings.durationSeconds.roundToInt()
            Text(text = stringResource(R.string.crossfade_duration), style = MaterialTheme.typography.titleSmall)
            Text(
                text = pluralStringResource(R.plurals.seconds, seconds, seconds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = settings.durationSeconds,
                onValueChange = { value ->
                    if (value != settings.durationSeconds) {
                        coroutineScope.launch { context.safeDataStoreEdit { it[CrossfadeDurationKey] = value } }
                    }
                },
                valueRange = MixDurationRange,
                steps = MIX_DURATION_STEPS,
            )
        }

        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = {
                if (current != null && next != null) transitionEditorState.show(current, next)
            },
            enabled = next != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = painterResource(R.drawable.linear_scale),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.transition_customize_next))
        }
        Text(
            text =
                if (current != null && next != null) {
                    stringResource(R.string.transition_songs, current.title, next.title)
                } else {
                    stringResource(R.string.transition_no_next)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.mix_all_settings))
        }
    }
}

/**
 * A slim pill between the playing song and the next one in the queue, summarising the transition
 * between them (e.g. "Fade · 5 s" or "Custom · Filter · 8 s").
 */
@Composable
fun TransitionChip(
    settings: MixSettings,
    custom: CustomTransition?,
    gaplessAlbum: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlighted = custom != null && settings.mode != MixMode.OFF
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (highlighted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.linear_scale),
                    contentDescription = stringResource(R.string.transition_to_next_song),
                    tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = transitionSummary(settings, custom, gaplessAlbum),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// Mirrors MusicService.planAutoTransition: custom > gapless album > smart mix > crossfade
@Composable
private fun transitionSummary(
    settings: MixSettings,
    custom: CustomTransition?,
    gaplessAlbum: Boolean,
): String =
    when {
        settings.mode == MixMode.OFF -> stringResource(R.string.transition_chip_off)
        custom != null -> {
            val style = stringResource(custom.style.labelRes)
            val text =
                if (custom.style == TransitionStyle.CUT) {
                    style
                } else {
                    stringResource(R.string.transition_chip_pair, style, secondsText(custom.durationMs / 1000f))
                }
            stringResource(R.string.transition_chip_custom, text)
        }
        settings.skipGaplessAlbums && gaplessAlbum -> stringResource(R.string.transition_chip_gapless)
        settings.mode == MixMode.SMART ->
            if (settings.style == MixTransitionStyle.AUTO) {
                stringResource(R.string.mix_mode_smart)
            } else {
                stringResource(R.string.transition_chip_pair, stringResource(R.string.mix_mode_smart), transitionStyleLabel(settings.style))
            }
        settings.style == MixTransitionStyle.CUT -> transitionStyleLabel(settings.style)
        // Crossfade plays Auto as a fade
        else -> {
            val style = if (settings.style == MixTransitionStyle.AUTO) MixTransitionStyle.FADE else settings.style
            stringResource(R.string.transition_chip_pair, transitionStyleLabel(style), secondsText(settings.durationSeconds))
        }
    }

@Composable
private fun secondsText(seconds: Float): String = stringResource(R.string.transition_seconds_short, seconds.roundToInt())

@Composable
fun mixModeLabel(mode: MixMode): String =
    stringResource(
        when (mode) {
            MixMode.OFF -> R.string.mix_mode_off
            MixMode.CROSSFADE -> R.string.crossfade
            MixMode.SMART -> R.string.mix_mode_smart
        },
    )

@Composable
fun transitionStyleLabel(style: MixTransitionStyle): String =
    stringResource(
        when (style) {
            MixTransitionStyle.AUTO -> R.string.mix_transition_auto
            MixTransitionStyle.FADE -> R.string.mix_transition_fade
            MixTransitionStyle.FILTER -> R.string.mix_transition_filter
            MixTransitionStyle.ECHO -> R.string.mix_transition_echo
            MixTransitionStyle.RISE -> R.string.mix_transition_rise
            MixTransitionStyle.CUT -> R.string.mix_transition_cut
        },
    )
