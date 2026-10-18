/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

import kotlin.math.abs

/** Turns a [CustomTransition] into the plan the engine plays; analysis, when known, fills in what is left on automatic. */
object CustomTransitionPlanner {
    /**
     * The overlap ends at [CustomTransition.outgoingEndMs] (or where the outgoing audio ends) and
     * starts its duration earlier. User positions are never moved, so the plan has no early start.
     *
     * @param outgoing analysis of the outgoing song's end; ignored when it doesn't match [trackDurationMs].
     * @param incoming analysis of the incoming song's start.
     * @param allowTempoNudge whether the incoming song may be played off its normal speed at all.
     */
    fun plan(
        transition: CustomTransition,
        trackDurationMs: Long,
        outgoing: MixAnalysis?,
        incoming: MixAnalysis?,
        allowTempoNudge: Boolean,
    ): TransitionPlan? {
        if (trackDurationMs <= CUT_END_MARGIN_MS) return null
        val tail = outgoing?.takeIf { abs(it.trackDurationMs - trackDurationMs) <= DURATION_TOLERANCE_MS }
        val latestStartMs = trackDurationMs - CUT_END_MARGIN_MS
        val endMs = (transition.outgoingEndMs ?: tail?.endOfAudioMs ?: trackDurationMs).coerceIn(0L, trackDurationMs)
        val incomingStartMs = transition.incomingStartMs?.coerceAtLeast(0L) ?: incoming?.let(SmartMixPlanner::introSkipMs)

        if (transition.style == TransitionStyle.CUT) {
            return TransitionPlan(
                startPositionMs = endMs.coerceAtMost(latestStartMs),
                durationMs = 0L,
                incomingStartMs = incomingStartMs,
                style = TransitionStyle.CUT,
                allowEarlyStart = false,
            )
        }

        val durationMs = transition.durationMs.coerceIn(CustomTransition.MIN_DURATION_MS, CustomTransition.MAX_DURATION_MS)
        val startMs = (endMs - durationMs).coerceIn(0L, latestStartMs)
        val tempoRatio =
            if (transition.tempoMatch && allowTempoNudge && tail != null && incoming != null) {
                SmartMixPlanner.tempoRatio(tail, incoming)
            } else {
                null
            }
        return TransitionPlan(
            startPositionMs = startMs,
            durationMs = (endMs - startMs).coerceAtLeast(CustomTransition.MIN_DURATION_MS),
            incomingStartMs = incomingStartMs,
            style = transition.style,
            allowEarlyStart = false,
            echoDelayMs = tail?.let(SmartMixPlanner::echoDelayMs),
            incomingSpeed = SmartMixPlanner.nudgeSpeed(tempoRatio),
        )
    }

    private const val CUT_END_MARGIN_MS = 150L
    private const val DURATION_TOLERANCE_MS = 2_000L
}
