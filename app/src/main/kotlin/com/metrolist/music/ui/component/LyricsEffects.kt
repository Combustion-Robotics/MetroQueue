/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.constants.LyricsEffects
import com.metrolist.music.constants.LyricsEffectsKey
import com.metrolist.music.utils.rememberEnumPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** The chosen lyrics effects level; Full drops to Subtle while battery saver is on. */
@Composable
internal fun rememberLyricsEffects(): LyricsEffects {
    val chosen by rememberEnumPreference(LyricsEffectsKey, LyricsEffects.FULL)
    val powerSave = rememberPowerSaveMode()
    return if (chosen == LyricsEffects.FULL && powerSave) LyricsEffects.SUBTLE else chosen
}

@Composable
private fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    var powerSave by remember { mutableStateOf(powerManager?.isPowerSaveMode == true) }
    DisposableEffect(context, powerManager) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    powerSave = powerManager?.isPowerSaveMode == true
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        powerSave = powerManager?.isPowerSaveMode == true
        onDispose { context.unregisterReceiver(receiver) }
    }
    return powerSave
}

/** [rememberMusicLevel] while the level isn't known: effects scaled by it stay as they are. */
internal const val MUSIC_LEVEL_NEUTRAL = 0.5f
private const val MUSIC_LEVEL_INTERVAL_MS = 33L
private const val MUSIC_LEVEL_PEAK_DECAY = 0.99f
private const val MUSIC_LEVEL_MIN_PEAK = 0.01f

/**
 * Loudness of the music now relative to its recent peak, smoothed, 0..1. Sampled at about 30 Hz
 * and metered only while [enabled], playing and on screen; [MUSIC_LEVEL_NEUTRAL] otherwise, or
 * when nothing can be measured (e.g. with audio offload).
 */
@Composable
internal fun rememberMusicLevel(enabled: Boolean): State<Float> {
    val level = remember { mutableFloatStateOf(MUSIC_LEVEL_NEUTRAL) }
    val playerConnection = LocalPlayerConnection.current ?: return level
    val service = playerConnection.service
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = enabled && isPlaying && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    DisposableEffect(service, active) {
        service.setAudioLevelMetering(active)
        onDispose { service.setAudioLevelMetering(false) }
    }
    LaunchedEffect(service, active) {
        level.floatValue = MUSIC_LEVEL_NEUTRAL
        if (!active) return@LaunchedEffect
        var peak = 0f
        var smoothed = MUSIC_LEVEL_NEUTRAL
        var lastSampleMs = 0L
        while (isActive) {
            withFrameMillis { now ->
                if (now - lastSampleMs >= MUSIC_LEVEL_INTERVAL_MS) {
                    lastSampleMs = now
                    val raw = service.audioLevel()
                    peak = maxOf(raw, peak * MUSIC_LEVEL_PEAK_DECAY)
                    val target = if (peak > MUSIC_LEVEL_MIN_PEAK) raw / peak else MUSIC_LEVEL_NEUTRAL
                    // Quick to rise, slow to fall, like a VU meter
                    smoothed += (target - smoothed) * if (target > smoothed) 0.45f else 0.12f
                    level.floatValue = smoothed
                }
            }
        }
    }
    return level
}

private const val FALLBACK_BREATH_MS = 2400.0
private const val TEMPO_RETRY_MS = 30_000L
private const val TEMPO_RETRIES = 6

/** Beat grid of a song, in track time: beats at [phaseMs] + k * [beatMs]. */
@Immutable
internal class SongBeat(
    val beatMs: Double,
    val phaseMs: Long,
)

/**
 * 0..1, peaking on the beat: one breath per beat, or per two beats for fast songs so it stays
 * calm. Without a known tempo it breathes slowly on its own.
 */
internal fun SongBeat?.breath(positionMs: Long): Float {
    val periodMs = when {
        this == null -> FALLBACK_BREATH_MS
        beatMs < 600.0 -> beatMs * 2
        else -> beatMs
    }
    val cycles = (positionMs - (this?.phaseMs ?: 0L)) / periodMs
    return (0.5 + 0.5 * cos(2 * PI * (cycles - floor(cycles)))).toFloat()
}

/**
 * Tempo of [mediaId] from what smart mix has already analysed; null while it isn't known. Only
 * stored analyses are read, rechecked now and then since smart mix may analyse the song later.
 */
@Composable
internal fun rememberSongBeat(mediaId: String?): SongBeat? {
    val service = LocalPlayerConnection.current?.service
    val beat by produceState<SongBeat?>(null, mediaId, service) {
        value = null
        if (mediaId == null || service == null) return@produceState
        repeat(TEMPO_RETRIES) {
            val analysis = service.knownTempo(mediaId)
            if (analysis != null) {
                value = SongBeat(analysis.beatMs, analysis.beatPhaseMs)
                return@produceState
            }
            delay(TEMPO_RETRY_MS)
        }
    }
    return beat
}

/** A short smooth swell peaking at 1 on each beat, 0 between beats. */
internal fun SongBeat.pulse(positionMs: Long): Float {
    val beats = (positionMs - phaseMs) / beatMs
    val wave = (0.5 + 0.5 * cos(2 * PI * (beats - floor(beats)))).toFloat()
    return wave * wave * wave
}

// Speeds (cycles per second, periods of about 9 to 15 s) and phases of each blob's drift; uneven
// so it never repeats visibly
private val BLOB_SPEED_X = floatArrayOf(0.095f, 0.072f, 0.083f)
private val BLOB_SPEED_Y = floatArrayOf(0.079f, 0.110f, 0.066f)
private val BLOB_PHASE = floatArrayOf(0f, 2.1f, 4.2f)

// The drift is redrawn at about 30 fps, into a layer a quarter of the size scaled back up
private const val AMBIENT_FRAME_NANOS = 30_000_000L
private const val AMBIENT_DOWNSCALE = 4
private const val AMBIENT_SWELL_ALPHA = 0.35f
private const val AMBIENT_SWELL_SCALE = 0.06f

/**
 * Soft blobs of the artwork colors drifting behind the lyrics, swelling a little on the beat
 * when the tempo is known. Plain radial gradients, no blur; it only moves while playing and
 * [animated].
 */
@Composable
internal fun LyricsAmbientBackground(
    colors: List<Color>,
    alpha: Float,
    animated: Boolean,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    if (colors.isEmpty()) return
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val beat = rememberSongBeat(mediaMetadata?.id)

    // Unit-radius gradients scaled when drawn, so their shaders are built once per color
    val brushes = remember(colors) {
        List(BLOB_PHASE.size) { i ->
            val color = colors[i % colors.size]
            Brush.radialGradient(
                0f to color,
                0.6f to color.copy(alpha = color.alpha * 0.45f),
                1f to color.copy(alpha = 0f),
                center = Offset.Zero,
                radius = 1f,
            )
        }
    }

    var timeSec by remember { mutableFloatStateOf(0f) }
    var pulse by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying, beat, animated) {
        if (!isPlaying || !animated) {
            pulse = 0f
            return@LaunchedEffect
        }
        var lastNanos = withFrameNanos { it }
        var pendingNanos = 0L
        while (isActive) {
            withFrameNanos { now ->
                pendingNanos += now - lastNanos
                lastNanos = now
                if (pendingNanos >= AMBIENT_FRAME_NANOS) {
                    timeSec += pendingNanos / 1_000_000_000f
                    pendingNanos = 0L
                }
                pulse = beat?.pulse(playerConnection.player.currentPosition) ?: 0f
            }
        }
    }

    // Blobs are drawn at the beat's peak alpha; the layer brings them down between beats
    val blobAlpha = alpha
    val peakAlpha = (blobAlpha * (1f + AMBIENT_SWELL_ALPHA)).coerceAtMost(1f)
    Spacer(
        modifier
            .graphicsLayer {
                // The swell only changes how the kept image is composited, so it can follow every frame
                val swell = pulse
                scaleX = 1f + AMBIENT_SWELL_SCALE * swell
                scaleY = scaleX
                this.alpha = if (peakAlpha > 0f) (blobAlpha * (1f + AMBIENT_SWELL_ALPHA * swell)).coerceAtMost(1f) / peakAlpha else 0f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .layout { measurable, constraints ->
                // Soft gradients look the same drawn small and scaled up with filtering, for a
                // fraction of the pixels; the kept layer is only redrawn when the drift moves
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val placeable = measurable.measure(
                    Constraints.fixed(
                        ((width + AMBIENT_DOWNSCALE - 1) / AMBIENT_DOWNSCALE).coerceAtLeast(1),
                        ((height + AMBIENT_DOWNSCALE - 1) / AMBIENT_DOWNSCALE).coerceAtLeast(1),
                    ),
                )
                layout(width, height) {
                    placeable.placeWithLayer(0, 0) {
                        scaleX = width.toFloat() / placeable.width
                        scaleY = height.toFloat() / placeable.height
                        transformOrigin = TransformOrigin(0f, 0f)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                }
            }
            .drawBehind {
                val t = timeSec
                val baseRadius = maxOf(size.width, size.height) * 0.5f
                for (i in brushes.indices) {
                    val x = size.width * (0.5f + 0.38f * sin(t * BLOB_SPEED_X[i] * 2 * PI.toFloat() + BLOB_PHASE[i]))
                    val y = size.height * (0.5f + 0.4f * cos(t * BLOB_SPEED_Y[i] * 2 * PI.toFloat() + BLOB_PHASE[i] * 1.3f))
                    withTransform({
                        translate(x, y)
                        scale(baseRadius, baseRadius, pivot = Offset.Zero)
                    }) {
                        drawCircle(brushes[i], radius = 1f, center = Offset.Zero, alpha = peakAlpha)
                    }
                }
            },
    )
}
