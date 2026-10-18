/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.CrossfadeDurationKey
import com.metrolist.music.constants.CrossfadeEnabledKey
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.mix.CustomTransition
import com.metrolist.music.playback.mix.MixAnalysis
import com.metrolist.music.playback.mix.MixRegion
import com.metrolist.music.playback.mix.SmartMixPlanner
import com.metrolist.music.playback.mix.TransitionStyle
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Edits the custom transition from [from] into [to]. Values the user hasn't set yet follow smart
 * mix's suggestion once the songs' analysis is known (only from cached audio).
 */
@Composable
fun TransitionEditor(
    from: MediaMetadata,
    to: MediaMetadata,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val service = playerConnection.service
    val repository = service.customTransitions
    val coroutineScope = rememberCoroutineScope()
    // Defaults to on so the "Mix is off" note doesn't flash while the setting loads
    val mixEnabled by rememberPreference(CrossfadeEnabledKey, defaultValue = true)
    val crossfadeSeconds by rememberPreference(CrossfadeDurationKey, defaultValue = 5f)

    val saved = remember(from.id, to.id) { repository.get(from.id, to.id) }
    var style by remember(from.id, to.id) { mutableStateOf(saved?.style) }
    var durationMs by remember(from.id, to.id) { mutableStateOf(saved?.durationMs) }
    var endMs by remember(from.id, to.id) { mutableStateOf(saved?.outgoingEndMs) }
    var startMs by remember(from.id, to.id) { mutableStateOf(saved?.incomingStartMs) }
    var tempoMatch by remember(from.id, to.id) { mutableStateOf(saved?.tempoMatch ?: true) }

    var tail by remember(from.id, to.id) { mutableStateOf<MixAnalysis?>(null) }
    var head by remember(from.id, to.id) { mutableStateOf<MixAnalysis?>(null) }
    LaunchedEffect(from.id, to.id) {
        tail = service.mixAnalysis(from.id, MixRegion.TAIL, from.durationMs ?: 0L)
        head = service.mixAnalysis(to.id, MixRegion.HEAD, to.durationMs ?: 0L)
    }

    val fromDurationMs = from.durationMs ?: tail?.trackDurationMs
    val toDurationMs = to.durationMs ?: head?.trackDurationMs
    val suggestion =
        remember(tail, head, crossfadeSeconds) {
            tail?.let {
                SmartMixPlanner.plan(it, head, it.trackDurationMs, (crossfadeSeconds * 1000).toLong(), style = null, allowTempoNudge = true)
            }
        }
    val effectiveStyle = style ?: suggestion?.style ?: TransitionStyle.FADE
    val effectiveDurationMs =
        durationMs ?: (
            (suggestion?.durationMs?.takeIf { it > 0 } ?: (crossfadeSeconds * 1000).toLong()) / 1000 * 1000
        ).coerceIn(CustomTransition.MIN_DURATION_MS, CustomTransition.MAX_DURATION_MS)
    // What the engine uses on Auto
    val autoEndMs = tail?.endOfAudioMs ?: fromDurationMs
    val autoStartMs = head?.let(SmartMixPlanner::introSkipMs) ?: 0L

    val canPreview =
        remember(from.id, to.id) {
            val player = playerConnection.player
            val nextIndex = player.nextMediaItemIndex
            player.currentMediaItem?.mediaId == from.id &&
                nextIndex >= 0 &&
                player.getMediaItemAt(nextIndex).mediaId == to.id
        }

    fun edited() = CustomTransition(from.id, to.id, effectiveStyle, effectiveDurationMs, endMs, startMs, tempoMatch)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()),
    ) {
        Text(text = stringResource(R.string.transition_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.transition_songs, from.title, to.title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (tail != null || head != null) {
            Text(
                text = stringResource(R.string.transition_bpm, tail.bpmText(), head.bpmText()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(if (mixEnabled) R.string.transition_note else R.string.transition_mix_off_note),
            style = MaterialTheme.typography.bodySmall,
            color = if (mixEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )

        Spacer(Modifier.height(16.dp))
        Text(text = stringResource(R.string.mix_transition_style), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransitionStyle.entries.forEach {
                FilterChip(
                    selected = it == effectiveStyle,
                    onClick = { style = it },
                    label = { Text(stringResource(it.labelRes)) },
                )
            }
        }

        if (effectiveStyle != TransitionStyle.CUT) {
            Spacer(Modifier.height(8.dp))
            val seconds = (effectiveDurationMs / 1000).toInt()
            Text(text = stringResource(R.string.transition_length), style = MaterialTheme.typography.titleSmall)
            Text(
                text = pluralStringResource(R.plurals.seconds, seconds, seconds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = effectiveDurationMs / 1000f,
                onValueChange = { durationMs = it.roundToLong() * 1000 },
                valueRange = CustomTransition.MIN_DURATION_MS / 1000f..CustomTransition.MAX_DURATION_MS / 1000f,
                steps = ((CustomTransition.MAX_DURATION_MS - CustomTransition.MIN_DURATION_MS) / 1000 - 1).toInt(),
            )
        }

        Spacer(Modifier.height(8.dp))
        PositionSetting(
            title = stringResource(if (effectiveStyle == TransitionStyle.CUT) R.string.transition_switch_at else R.string.transition_end_at),
            autoDescription = stringResource(R.string.transition_end_auto),
            positionMs = endMs,
            autoPositionMs = autoEndMs,
            maxMs = fromDurationMs,
            onPositionChange = { endMs = it },
        )
        Spacer(Modifier.height(8.dp))
        PositionSetting(
            title = stringResource(R.string.transition_start_at),
            autoDescription = stringResource(R.string.transition_start_auto),
            positionMs = startMs,
            autoPositionMs = autoStartMs,
            maxMs = toDurationMs,
            onPositionChange = { startMs = it },
        )

        if (effectiveStyle != TransitionStyle.CUT) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.transition_match_tempo), style = MaterialTheme.typography.titleSmall)
                    val tempoKnown = tail?.hasConfidentTempo == true && head?.hasConfidentTempo == true
                    Text(
                        text =
                            stringResource(
                                if (tempoKnown) R.string.transition_match_tempo_desc else R.string.transition_match_tempo_unknown,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = tempoMatch, onCheckedChange = { tempoMatch = it })
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    repository.remove(from.id, to.id)
                    onDismiss()
                },
                enabled = saved != null,
            ) {
                Text(stringResource(R.string.reset))
            }
            Spacer(Modifier.weight(1f))
            if (canPreview && mixEnabled) {
                OutlinedButton(
                    onClick = {
                        val transition = edited()
                        coroutineScope.launch {
                            repository.save(transition).join()
                            if (service.previewTransition(from.id, to.id)) {
                                onDismiss()
                            } else {
                                Toast.makeText(context, R.string.transition_preview_unavailable, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.preview))
                }
                Spacer(Modifier.width(8.dp))
            }
            Button(
                onClick = {
                    repository.save(edited())
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

/** A position in a song: automatic, or set with a slider over the song's length when that is known. */
@Composable
private fun PositionSetting(
    title: String,
    autoDescription: String,
    positionMs: Long?,
    autoPositionMs: Long?,
    maxMs: Long?,
    onPositionChange: (Long?) -> Unit,
) {
    val rangeMs = maxMs?.takeIf { it > 0 }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (positionMs == null) autoDescription else makeTimeString(positionMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = stringResource(R.string.mix_transition_auto), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = positionMs == null,
            onCheckedChange = { auto ->
                onPositionChange(if (auto || rangeMs == null) null else (autoPositionMs ?: 0L).coerceIn(0L, rangeMs))
            },
            // Without the song's length there's nothing to pick from, but a set position can go back to Auto
            enabled = positionMs != null || rangeMs != null,
        )
    }
    if (positionMs != null && rangeMs != null) {
        Slider(
            value = positionMs.coerceIn(0L, rangeMs).toFloat(),
            onValueChange = { onPositionChange((it / 100).roundToLong() * 100) },
            valueRange = 0f..rangeMs.toFloat(),
        )
    }
}

private val MediaMetadata.durationMs: Long?
    get() = duration.takeIf { it > 0 }?.times(1000L)

private fun MixAnalysis?.bpmText(): String = this?.takeIf { it.hasConfidentTempo }?.bpm?.roundToInt()?.toString() ?: "–"

private val TransitionStyle.labelRes: Int
    get() =
        when (this) {
            TransitionStyle.FADE -> R.string.mix_transition_fade
            TransitionStyle.FILTER -> R.string.mix_transition_filter
            TransitionStyle.ECHO -> R.string.mix_transition_echo
            TransitionStyle.RISE -> R.string.mix_transition_rise
            TransitionStyle.CUT -> R.string.mix_transition_cut
        }
