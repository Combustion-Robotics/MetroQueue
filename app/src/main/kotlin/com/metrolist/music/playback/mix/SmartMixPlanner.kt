/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Turns the analysis of the outgoing song's end (and, when known, the incoming song's start) into
 * a transition: where to start, for how long, where the next song starts, which style, and an
 * optional small tempo nudge on the incoming song.
 */
object SmartMixPlanner {
    /**
     * @param style the user's style, or null to choose one from the analysis.
     * @param allowTempoNudge whether the incoming song may play slightly faster or slower during
     *   the overlap to match the outgoing tempo.
     * @return null when [outgoing] doesn't belong to this track (another duration).
     */
    fun plan(
        outgoing: MixAnalysis,
        incoming: MixAnalysis?,
        trackDurationMs: Long,
        configuredDurationMs: Long,
        style: TransitionStyle?,
        allowTempoNudge: Boolean,
    ): TransitionPlan? {
        if (trackDurationMs <= 0 || abs(outgoing.trackDurationMs - trackDurationMs) > DURATION_TOLERANCE_MS) return null
        val endOfAudioMs = outgoing.endOfAudioMs.coerceAtMost(trackDurationMs)
        val latestStartMs = trackDurationMs - CUT_END_MARGIN_MS
        val earliestStartMs = outgoing.regionStartMs.coerceAtMost(latestStartMs)
        val outroMs = endOfAudioMs - outgoing.outroStartMs
        val fadesOut = outroMs >= FADE_OUT_MIN_MS
        val abruptEnd = outroMs < ABRUPT_END_MAX_MS

        val tempoRatio = incoming?.let { tempoRatio(outgoing, it) }
        val tempoMatch = tempoRatio != null && abs(tempoRatio - 1) <= TEMPO_MATCH_TOLERANCE
        val tempoClash = tempoRatio != null && !tempoMatch
        val levelClash = incoming != null && abs(outgoing.bodyDb - incoming.bodyDb) >= LEVEL_CLASH_DB
        val incomingStartsStrong = incoming != null && incoming.introEndMs - incoming.audioStartMs <= STRONG_START_MS

        val chosenStyle =
            style ?: when {
                fadesOut -> TransitionStyle.FADE
                tempoMatch -> TransitionStyle.FILTER
                tempoClash || levelClash -> TransitionStyle.ECHO
                abruptEnd && incomingStartsStrong -> TransitionStyle.CUT
                abruptEnd -> TransitionStyle.RISE
                else -> TransitionStyle.FADE
            }
        val incomingStartMs = incoming?.let(::introSkipMs)

        if (chosenStyle == TransitionStyle.CUT) {
            return TransitionPlan(
                startPositionMs = endOfAudioMs.coerceIn(earliestStartMs, latestStartMs),
                durationMs = 0L,
                incomingStartMs = incomingStartMs,
                style = chosenStyle,
                allowEarlyStart = false,
            )
        }

        val targetMs =
            when {
                tempoMatch -> (configuredDurationMs * 2).coerceAtMost(MAX_BLEND_MS)
                tempoClash -> configuredDurationMs.coerceAtMost(CLASH_MAX_MS)
                else -> configuredDurationMs
            }
        val beatMs = outgoing.beatMs
        var durationMs =
            if (tempoMatch) {
                // Whole bars (or beats, for short blends) of the outgoing song
                val unitMs = if (targetMs >= beatMs * BEATS_PER_BAR) beatMs * BEATS_PER_BAR else beatMs
                (floor(targetMs / unitMs).coerceAtLeast(1.0) * unitMs).roundToLong()
            } else {
                targetMs
            }

        // A fade-out is where the song is already leaving; skip at most part of it
        var startMs =
            if (fadesOut) {
                maxOf(outgoing.outroStartMs, endOfAudioMs - durationMs - MAX_OUTRO_SKIP_MS)
            } else {
                endOfAudioMs - durationMs
            }

        val incomingSpeed =
            if (allowTempoNudge && tempoRatio != null && tempoMatch &&
                abs(tempoRatio - 1) in MIN_TEMPO_NUDGE..MAX_TEMPO_NUDGE
            ) {
                tempoRatio.toFloat()
            } else {
                1f
            }

        if (tempoMatch && incoming != null && beatMs > 0) {
            // Land the incoming song's first beat on a beat of the outgoing one. Rounding down
            // keeps the overlap within the outgoing song's audio.
            val leadInMs = (incoming.firstBeatMs - (incomingStartMs ?: 0L)).coerceAtLeast(0L) / incomingSpeed.toDouble()
            val beats = floor((startMs + leadInMs - outgoing.beatPhaseMs) / beatMs)
            startMs = (outgoing.beatPhaseMs + beats * beatMs - leadInMs).roundToLong()
        }
        startMs = startMs.coerceIn(earliestStartMs, latestStartMs)
        // Don't keep the incoming song fading in long after the outgoing one has ended
        durationMs = durationMs.coerceAtMost(endOfAudioMs - startMs + END_OVERHANG_MS).coerceAtLeast(MIN_DURATION_MS)

        return TransitionPlan(
            startPositionMs = startMs,
            durationMs = durationMs,
            incomingStartMs = incomingStartMs,
            style = chosenStyle,
            allowEarlyStart = false,
            echoDelayMs = if (outgoing.hasConfidentTempo) (beatMs * ECHO_BEATS).roundToLong() else null,
            incomingSpeed = incomingSpeed,
        )
    }

    /**
     * Outgoing over incoming tempo, folded by octaves to the nearest 1.0 (half and double time
     * mix fine), or null unless both tempos are confident.
     */
    fun tempoRatio(
        outgoing: MixAnalysis,
        incoming: MixAnalysis,
    ): Double? {
        if (!outgoing.hasConfidentTempo || !incoming.hasConfidentTempo) return null
        var ratio = outgoing.bpm.toDouble() / incoming.bpm
        while (ratio > OCTAVE_FOLD) ratio /= 2
        while (ratio < 1 / OCTAVE_FOLD) ratio *= 2
        return ratio
    }

    // Same rules as the level-meter intro skip: only real leading silence, never half the song
    private fun introSkipMs(incoming: MixAnalysis): Long {
        val silenceMs = incoming.audioStartMs
        if (silenceMs < INTRO_SKIP_MIN_MS || silenceMs >= incoming.trackDurationMs / 2) return 0L
        return (silenceMs - INTRO_SKIP_PREROLL_MS).coerceAtMost(INTRO_SKIP_MAX_MS)
    }

    private const val DURATION_TOLERANCE_MS = 2_000L
    private const val CUT_END_MARGIN_MS = 150L
    private const val FADE_OUT_MIN_MS = 3_000L
    private const val ABRUPT_END_MAX_MS = 1_500L
    private const val STRONG_START_MS = 1_000L
    private const val TEMPO_MATCH_TOLERANCE = 0.06
    private const val MIN_TEMPO_NUDGE = 0.005
    private const val MAX_TEMPO_NUDGE = 0.05
    private const val OCTAVE_FOLD = 1.414
    private const val LEVEL_CLASH_DB = 8f
    private const val MAX_BLEND_MS = 16_000L
    private const val CLASH_MAX_MS = 4_000L
    private const val MIN_DURATION_MS = 1_000L
    private const val END_OVERHANG_MS = 500L
    private const val MAX_OUTRO_SKIP_MS = 8_000L
    private const val BEATS_PER_BAR = 4
    private const val ECHO_BEATS = 0.75
    private const val INTRO_SKIP_MIN_MS = 500L
    private const val INTRO_SKIP_MAX_MS = 10_000L
    private const val INTRO_SKIP_PREROLL_MS = 100L
}
