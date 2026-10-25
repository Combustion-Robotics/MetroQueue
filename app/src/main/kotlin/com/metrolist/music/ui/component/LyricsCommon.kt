/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.metrolist.music.lyrics.LyricsEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val BREATH_SCALE = 0.07f
private const val PAUSED_BREATH_POLL_MS = 100L

sealed class LyricsListItem {
    data class Line(val index: Int, val entry: LyricsEntry) : LyricsListItem()
    data class Indicator(
        val afterLineIndex: Int,
        val gapMs: Long,
        val gapStartMs: Long,
        val gapEndMs: Long,
        val nextAgent: String?
    ) : LyricsListItem()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun IntervalIndicator(
    gapStartMs: Long,
    gapEndMs: Long,
    currentPositionMs: () -> Long,
    visible: Boolean,
    color: Color,
    beat: SongBeat?,
    breathing: Boolean,
    isPlaying: Boolean,
    playbackPositionProvider: () -> Long,
    modifier: Modifier = Modifier
) {
    val alpha = remember { Animatable(0f) }

    // Follows the playback position, so it holds still while paused; then only seeks move it
    var breath by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible, beat, breathing, isPlaying) {
        if (!breathing) breath = 0f
        while (visible && breathing && isActive) {
            if (isPlaying) {
                withFrameMillis { breath = beat.breath(playbackPositionProvider()) }
            } else {
                breath = beat.breath(playbackPositionProvider())
                delay(PAUSED_BREATH_POLL_MS)
            }
        }
    }
    val rowHeightPx = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            rowHeightPx.animateTo(1f, tween(200))
            alpha.animateTo(1f, tween(200))
        } else {
            alpha.animateTo(0f, tween(200))
            rowHeightPx.animateTo(0f, tween(200))
        }
    }

    val density = LocalDensity.current
    val targetHeightDp = 72.dp

    Box(
        modifier = modifier
            .height(targetHeightDp * rowHeightPx.value)
            .padding(top = 16.dp * rowHeightPx.value)
            .graphicsLayer {
                this.alpha = alpha.value
                this.clip = true
            },
        contentAlignment = Alignment.Center
    ) {
        CircularWavyProgressIndicator(
            // Read while drawing; hidden indicators skip the position so they aren't redrawn every frame
            progress = {
                if ((visible || alpha.value > 0f) && gapEndMs > gapStartMs) {
                    ((currentPositionMs() - gapStartMs).toFloat() / (gapEndMs - gapStartMs).toFloat()).coerceIn(0f, 1f)
                } else 0f
            },
            modifier = Modifier
                .size(36.dp)
                .graphicsLayer {
                    this.alpha = alpha.value
                    val scale = 1f + BREATH_SCALE * breath
                    scaleX = scale
                    scaleY = scale
                },
            color = color,
            trackColor = color.copy(alpha = 0.2f),
        )
    }
}
