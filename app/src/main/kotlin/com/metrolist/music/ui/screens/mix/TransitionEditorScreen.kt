/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.mix

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.CrossfadeDurationKey
import com.metrolist.music.constants.CrossfadeEnabledKey
import com.metrolist.music.constants.MixMode
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.mix.CustomTransition
import com.metrolist.music.playback.mix.Harmony
import com.metrolist.music.playback.mix.MixAnalysis
import com.metrolist.music.playback.mix.MixAnalysisRepository
import com.metrolist.music.playback.mix.MixRegion
import com.metrolist.music.playback.mix.SmartMixPlanner
import com.metrolist.music.playback.mix.TransitionStyle
import com.metrolist.music.ui.menu.labelRes
import com.metrolist.music.ui.menu.setMixMode
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Which transition the full-screen editor shows, if any; there is one for the whole app. */
@Stable
class TransitionEditorState {
    var songs by mutableStateOf<Pair<MediaMetadata, MediaMetadata>?>(null)
        private set

    fun show(
        from: MediaMetadata,
        to: MediaMetadata,
    ) {
        songs = from to to
    }

    fun dismiss() {
        songs = null
    }
}

val LocalTransitionEditorState = compositionLocalOf { TransitionEditorState() }

/** Shows the transition editor over everything else while [LocalTransitionEditorState] has songs. */
@Composable
fun TransitionEditorHost() {
    val state = LocalTransitionEditorState.current
    val (from, to) = state.songs ?: return
    key(from.id, to.id) {
        TransitionEditor(from, to, state::dismiss)
    }
}

/**
 * Edits the custom transition from [from] into [to] on two waveform lanes. Values the user hasn't
 * set yet follow smart mix's suggestion once the songs' analysis is known (only from cached audio).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransitionEditor(
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
    val crossfadeSeconds by rememberPreference(CrossfadeDurationKey, defaultValue = com.metrolist.music.constants.DEFAULT_CROSSFADE_DURATION)

    var saved by remember { mutableStateOf(repository.get(from.id, to.id)) }
    var style by remember { mutableStateOf(saved?.style) }
    var durationMs by remember { mutableStateOf(saved?.durationMs) }
    var endMs by remember { mutableStateOf(saved?.outgoingEndMs) }
    var startMs by remember { mutableStateOf(saved?.incomingStartMs) }
    var tempoMatch by remember { mutableStateOf(saved?.tempoMatch ?: true) }
    var snapToBeats by rememberSaveable { mutableStateOf(true) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // null until the first answer. Audio that isn't cached yet (typically the next song, until
    // the player buffers it shortly before the transition) is asked for again while this is open.
    var tailOutcome by remember { mutableStateOf<MixAnalysisRepository.Outcome?>(null) }
    var headOutcome by remember { mutableStateOf<MixAnalysisRepository.Outcome?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (tailOutcome.isPending) tailOutcome = service.mixAnalysis(from.id, MixRegion.TAIL, from.durationMs ?: 0L)
            if (headOutcome.isPending) headOutcome = service.mixAnalysis(to.id, MixRegion.HEAD, to.durationMs ?: 0L)
            if (!tailOutcome.isPending && !headOutcome.isPending) break
            delay(ANALYSIS_RETRY_MS)
        }
    }
    val tail = (tailOutcome as? MixAnalysisRepository.Outcome.Ready)?.analysis
    val head = (headOutcome as? MixAnalysisRepository.Outcome.Ready)?.analysis

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
        (durationMs ?: suggestion?.durationMs?.takeIf { it > 0 } ?: (crossfadeSeconds * 1000).toLong())
            .coerceIn(CustomTransition.MIN_DURATION_MS, CustomTransition.MAX_DURATION_MS)
    // What the engine uses on Auto
    val autoEndMs = tail?.endOfAudioMs ?: fromDurationMs
    val autoStartMs = head?.let(SmartMixPlanner::introSkipMs) ?: 0L
    val cut = effectiveStyle == TransitionStyle.CUT

    // Follows playback, so the transition can be replayed once it has happened
    var previewMode by remember { mutableStateOf(playerConnection.player.previewMode(from.id, to.id)) }

    val model =
        if (fromDurationMs != null && autoEndMs != null) {
            TimelineModel(
                outgoingDurationMs = fromDurationMs,
                incomingDurationMs = toDurationMs,
                endMs = (endMs ?: autoEndMs).coerceIn(0L, fromDurationMs),
                durationMs = if (cut) 0L else effectiveDurationMs,
                incomingStartMs = startMs ?: autoStartMs,
                style = effectiveStyle,
                tail = tail,
                head = head,
                autoEndMs = autoEndMs,
                autoIncomingStartMs = autoStartMs,
            )
        } else {
            null
        }

    // Replaying goes back to where the transition starts on the timeline
    val canPreview = mixEnabled && (previewMode == PreviewMode.PREVIEW || (previewMode == PreviewMode.REPLAY && model != null))

    // Where the preview (or normal playback) is, on the outgoing song's time axis
    var playheadMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(model?.transitionStartMs, model?.incomingStartMs) {
        while (true) {
            val player = playerConnection.player
            previewMode = player.previewMode(from.id, to.id)
            playheadMs =
                when (player.currentMediaItem?.mediaId) {
                    from.id -> player.currentPosition
                    to.id -> model?.let { it.incomingToTimeline(player.currentPosition.toDouble()).roundToLong() }
                    else -> null
                }
            delay(if (player.isPlaying) PLAYHEAD_INTERVAL_MS else PLAYHEAD_IDLE_INTERVAL_MS)
        }
    }

    val dirty =
        style != saved?.style ||
            durationMs != saved?.durationMs ||
            endMs != saved?.outgoingEndMs ||
            startMs != saved?.incomingStartMs ||
            tempoMatch != (saved?.tempoMatch ?: true)

    fun edited() = CustomTransition(from.id, to.id, effectiveStyle, effectiveDurationMs, endMs, startMs, tempoMatch)

    fun close() {
        if (dirty) confirmDiscard = true else onDismiss()
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(text = stringResource(R.string.transition_title), style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = stringResource(R.string.transition_songs, from.title, to.title),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = ::close) {
                            Icon(painter = painterResource(R.drawable.close), contentDescription = stringResource(R.string.close))
                        }
                    },
                )
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        TextButton(
                            onClick = {
                                style = null
                                durationMs = null
                                endMs = null
                                startMs = null
                                tempoMatch = true
                                if (saved != null) {
                                    repository.remove(from.id, to.id)
                                    saved = null
                                }
                            },
                            enabled = saved != null || dirty,
                        ) {
                            Text(stringResource(R.string.reset))
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                val transition = edited()
                                coroutineScope.launch {
                                    repository.save(transition).join()
                                    saved = transition
                                    style = transition.style
                                    durationMs = transition.durationMs
                                    if (!service.previewTransition(from.id, to.id, model?.transitionStartMs)) {
                                        Toast.makeText(context, R.string.transition_preview_unavailable, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = canPreview,
                        ) {
                            if (previewMode == PreviewMode.REPLAY) {
                                Icon(
                                    painter = painterResource(R.drawable.replay),
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.transition_replay))
                            } else {
                                Text(stringResource(R.string.preview))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
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
            },
        ) { innerPadding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState()),
            ) {
                if (!mixEnabled) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.transition_mix_off_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            FilledTonalButton(
                                onClick = { coroutineScope.launch { context.setMixMode(MixMode.CROSSFADE) } },
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Text(stringResource(R.string.transition_turn_on_crossfade))
                            }
                        }
                    }
                }

                LaneHeader(from, tempoText(tailOutcome), MaterialTheme.colorScheme.primary)
                val timelineHeight = (LocalConfiguration.current.screenHeightDp * TIMELINE_HEIGHT_FRACTION).dp.coerceIn(220.dp, 420.dp)
                if (model != null) {
                    TransitionTimeline(
                        model = model,
                        snapToBeats = snapToBeats,
                        tailStatus = waveformStatus(tailOutcome),
                        headStatus = waveformStatus(headOutcome),
                        playheadMs = { playheadMs },
                        onEndChange = { endMs = it },
                        onDurationChange = { durationMs = it },
                        onIncomingStartChange = { startMs = it },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(timelineHeight)
                                .padding(horizontal = 8.dp),
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(timelineHeight)
                                .padding(horizontal = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Text(
                            text = waveformStatus(tailOutcome) ?: stringResource(R.string.transition_waveform_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LaneHeader(to, tempoText(headOutcome), MaterialTheme.colorScheme.tertiary)

                Column(Modifier.padding(horizontal = 16.dp)) {
                    HarmonyNote(tail, head)
                    Text(
                        text = stringResource(R.string.transition_editor_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!cut) {
                            Readout(
                                title = stringResource(R.string.transition_length),
                                value = stringResource(R.string.transition_seconds_precise, effectiveDurationMs / 1000f),
                                auto = durationMs == null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Readout(
                            title = stringResource(if (cut) R.string.transition_switch_at else R.string.transition_end_at),
                            value = model?.endMs?.let(::formatPosition) ?: "–",
                            auto = endMs == null,
                            modifier = Modifier.weight(1f),
                        )
                        Readout(
                            title = stringResource(R.string.transition_start_at),
                            value = formatPosition(startMs ?: autoStartMs),
                            auto = startMs == null,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(text = stringResource(R.string.mix_transition_style), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = style == null,
                            onClick = { style = null },
                            label = {
                                Text(
                                    suggestion?.let {
                                        stringResource(R.string.transition_chip_pair, stringResource(R.string.mix_transition_auto), stringResource(it.style.labelRes))
                                    } ?: stringResource(R.string.mix_transition_auto),
                                )
                            },
                        )
                        TransitionStyle.entries.forEach {
                            FilterChip(
                                selected = it == style,
                                onClick = { style = it },
                                label = { Text(stringResource(it.labelRes)) },
                            )
                        }
                    }

                    FilterChip(
                        selected = snapToBeats,
                        onClick = { snapToBeats = !snapToBeats },
                        label = { Text(stringResource(R.string.transition_snap)) },
                        enabled = model != null,
                    )

                    if (!cut) {
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

                    // Without waveforms the lanes are hard to aim at; sliders still set the same values
                    if (model == null && !cut) {
                        SliderSetting(
                            title = stringResource(R.string.transition_length),
                            valueMs = effectiveDurationMs,
                            rangeMs = CustomTransition.MIN_DURATION_MS..CustomTransition.MAX_DURATION_MS,
                            onChange = { durationMs = it },
                        )
                    }
                    if (model != null && tail?.waveform == null) {
                        SliderSetting(
                            title = stringResource(if (cut) R.string.transition_switch_at else R.string.transition_end_at),
                            valueMs = model.endMs,
                            rangeMs = (if (cut) 0L else model.durationMs)..model.outgoingDurationMs,
                            onChange = { endMs = it },
                        )
                    }
                    if (head?.waveform == null && toDurationMs != null && toDurationMs > MIN_INCOMING_LEFT_MS) {
                        SliderSetting(
                            title = stringResource(R.string.transition_start_at),
                            valueMs = startMs ?: autoStartMs,
                            rangeMs = 0L..toDurationMs - MIN_INCOMING_LEFT_MS,
                            onChange = { startMs = it },
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(if (previewMode != null) R.string.transition_note else R.string.transition_preview_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text(stringResource(R.string.transition_discard_title)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDiscard = false
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.transition_discard))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) {
                        Text(stringResource(R.string.transition_keep_editing))
                    }
                },
            )
        }
    }
}

@Composable
private fun LaneHeader(
    song: MediaMetadata,
    tempo: String,
    color: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        AsyncImage(
            model = song.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artists.joinToString { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(text = tempo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Readout(
    title: String,
    value: String,
    auto: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (auto) stringResource(R.string.transition_chip_pair, value, stringResource(R.string.mix_transition_auto)) else value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}

/** Fallback for a value that can't be dragged on a waveform. */
@Composable
private fun SliderSetting(
    title: String,
    valueMs: Long,
    rangeMs: LongRange,
    onChange: (Long) -> Unit,
) {
    if (rangeMs.last <= rangeMs.first) return
    Spacer(Modifier.height(8.dp))
    Text(text = title, style = MaterialTheme.typography.titleSmall)
    Slider(
        value = valueMs.coerceIn(rangeMs).toFloat(),
        onValueChange = { onChange((it / SLIDER_STEP_MS).roundToLong() * SLIDER_STEP_MS) },
        valueRange = rangeMs.first.toFloat()..rangeMs.last.toFloat(),
    )
}

private enum class PreviewMode { PREVIEW, REPLAY }

/** [PreviewMode.PREVIEW] while the transition is ahead, [PreviewMode.REPLAY] once [toId] plays right after [fromId]. */
private fun Player.previewMode(
    fromId: String,
    toId: String,
): PreviewMode? {
    val currentId = currentMediaItem?.mediaId
    val nextIndex = nextMediaItemIndex
    val previousIndex = previousMediaItemIndex
    return when {
        currentId == fromId && nextIndex != C.INDEX_UNSET && getMediaItemAt(nextIndex).mediaId == toId -> PreviewMode.PREVIEW
        currentId == toId && previousIndex != C.INDEX_UNSET && getMediaItemAt(previousIndex).mediaId == fromId -> PreviewMode.REPLAY
        else -> null
    }
}

private fun formatPosition(ms: Long): String {
    val tenths = (ms.coerceAtLeast(0L) + 50) / 100
    return "%d:%02d.%d".format(tenths / 600, tenths / 10 % 60, tenths % 10)
}

private val MediaMetadata.durationMs: Long?
    get() = duration.takeIf { it > 0 }?.times(1000L)

private val MixAnalysisRepository.Outcome?.isPending: Boolean
    get() = this == null || this == MixAnalysisRepository.Outcome.Pending

private val MixAnalysisRepository.Outcome?.waveformAnalysis: MixAnalysis?
    get() = (this as? MixAnalysisRepository.Outcome.Ready)?.analysis?.takeIf { it.waveform != null }

/** What a lane shows instead of its waveform, or null when it has one. */
@Composable
private fun waveformStatus(outcome: MixAnalysisRepository.Outcome?): String? =
    when {
        outcome.waveformAnalysis != null -> null
        outcome == null -> stringResource(R.string.transition_analysing)
        outcome == MixAnalysisRepository.Outcome.Unavailable -> stringResource(R.string.transition_waveform_unavailable)
        // Pending, or analysed before waveforms were kept and not cached any more
        else -> stringResource(R.string.transition_waveform_not_cached)
    }

/** Tempo, and the key when it is clear, e.g. "124 BPM · Am (8A)". */
@Composable
private fun tempoText(outcome: MixAnalysisRepository.Outcome?): String =
    when (outcome) {
        null -> stringResource(R.string.transition_analysing)
        MixAnalysisRepository.Outcome.Pending -> stringResource(R.string.transition_not_loaded)
        MixAnalysisRepository.Outcome.Unavailable -> stringResource(R.string.transition_bpm_unknown)
        is MixAnalysisRepository.Outcome.Ready -> {
            val tempo =
                outcome.analysis
                    .takeIf { it.hasConfidentTempo }
                    ?.let { stringResource(R.string.transition_bpm_value, it.bpm.roundToInt()) }
                    ?: stringResource(R.string.transition_bpm_unknown)
            outcome.analysis.musicalKey
                ?.takeIf { it.isConfident }
                ?.let { key ->
                    val keyText = stringResource(R.string.transition_key_value, key.name, key.camelotCode)
                    stringResource(R.string.transition_chip_pair, tempo, keyText)
                } ?: tempo
        }
    }

/** How the two songs' keys go together, when both are clear. */
@Composable
private fun HarmonyNote(
    tail: MixAnalysis?,
    head: MixAnalysis?,
) {
    val harmony = if (tail != null && head != null) SmartMixPlanner.harmony(tail, head) else null
    val fromKey = tail?.musicalKey
    val toKey = head?.musicalKey
    if (harmony == null || fromKey == null || toKey == null) return
    Text(
        text =
            stringResource(
                when (harmony) {
                    Harmony.COMPATIBLE -> R.string.transition_keys_match
                    Harmony.NEUTRAL -> R.string.transition_keys_close
                    Harmony.CLASH -> R.string.transition_keys_clash
                },
                fromKey.camelotCode,
                toKey.camelotCode,
            ),
        style = MaterialTheme.typography.bodyMedium,
        color =
            when (harmony) {
                Harmony.COMPATIBLE -> MaterialTheme.colorScheme.primary
                Harmony.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                Harmony.CLASH -> MaterialTheme.colorScheme.error
            },
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

private const val ANALYSIS_RETRY_MS = 5_000L
private const val PLAYHEAD_INTERVAL_MS = 50L
private const val PLAYHEAD_IDLE_INTERVAL_MS = 500L
private const val TIMELINE_HEIGHT_FRACTION = 0.42f
private const val MIN_INCOMING_LEFT_MS = 1_000L
private const val SLIDER_STEP_MS = 100L
