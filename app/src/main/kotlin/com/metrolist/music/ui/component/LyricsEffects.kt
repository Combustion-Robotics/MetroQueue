/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.metrolist.music.LocalPlayerConnection
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

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
