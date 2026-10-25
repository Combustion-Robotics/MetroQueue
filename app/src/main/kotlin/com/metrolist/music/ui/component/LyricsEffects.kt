/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalPlayerConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

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

// Speeds (cycles per second) and phases of each blob's slow drift; uneven so it never repeats visibly
private val BLOB_SPEED_X = floatArrayOf(0.050f, 0.037f, 0.043f)
private val BLOB_SPEED_Y = floatArrayOf(0.041f, 0.053f, 0.031f)
private val BLOB_PHASE = floatArrayOf(0f, 2.1f, 4.2f)

/**
 * Soft blobs of the artwork colors drifting slowly behind the lyrics, swelling a little on the
 * beat when the tempo is known. Plain radial gradients, no blur; it only moves while playing.
 */
@Composable
internal fun LyricsAmbientBackground(
    colors: List<Color>,
    alpha: Float,
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
                0.6f to color.copy(alpha = color.alpha * 0.35f),
                1f to color.copy(alpha = 0f),
                center = Offset.Zero,
                radius = 1f,
            )
        }
    }

    var timeSec by remember { mutableFloatStateOf(0f) }
    var pulse by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying, beat) {
        if (!isPlaying) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                timeSec += (now - lastNanos) / 1_000_000_000f
                lastNanos = now
                pulse = beat?.pulse(playerConnection.player.currentPosition) ?: 0f
            }
        }
    }

    Canvas(modifier) {
        val t = timeSec
        val beatPulse = pulse
        val baseRadius = maxOf(size.width, size.height) * 0.62f * (1f + 0.04f * beatPulse)
        for (i in brushes.indices) {
            val x = size.width * (0.5f + 0.38f * sin(t * BLOB_SPEED_X[i] * 2 * PI.toFloat() + BLOB_PHASE[i]))
            val y = size.height * (0.5f + 0.4f * cos(t * BLOB_SPEED_Y[i] * 2 * PI.toFloat() + BLOB_PHASE[i] * 1.3f))
            withTransform({
                translate(x, y)
                scale(baseRadius, baseRadius, pivot = Offset.Zero)
            }) {
                drawCircle(brushes[i], radius = 1f, center = Offset.Zero, alpha = (alpha * (1f + 0.25f * beatPulse)).coerceAtMost(1f))
            }
        }
    }
}
