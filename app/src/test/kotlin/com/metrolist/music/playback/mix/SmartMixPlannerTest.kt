/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SmartMixPlannerTest {
    @Test
    fun `matching tempos blend over whole bars with the beats aligned`() {
        val outgoing = tail(bpm = 124f)
        val incoming = head(bpm = 122f, audioStartMs = 1_200L, firstBeatMs = 1_350L)

        val plan = plan(outgoing, incoming, allowTempoNudge = true)

        assertEquals(TransitionStyle.FILTER, plan.style)
        val barMs = 4 * 60_000.0 / 124
        val bars = plan.durationMs / barMs
        assertEquals(Math.round(bars).toDouble(), bars, 0.01)
        assertTrue(plan.durationMs <= 2 * CONFIGURED_MS)
        assertEquals(124f / 122f, plan.incomingSpeed, 0.0001f)
        assertEquals(1_100L, plan.incomingStartMs)
        // The incoming first beat, reached after its lead-in at the nudged speed, sits on an outgoing beat
        val firstBeatAt = plan.startPositionMs + (1_350 - 1_100) / plan.incomingSpeed
        val offset = ((firstBeatAt - outgoing.beatPhaseMs) % outgoing.beatMs + outgoing.beatMs) % outgoing.beatMs
        assertTrue("beat offset $offset", minOf(offset, outgoing.beatMs - offset) < 2.0)
        assertTrue(plan.startPositionMs + plan.durationMs <= outgoing.endOfAudioMs)
    }

    @Test
    fun `no tempo nudge unless allowed`() {
        val plan = plan(tail(bpm = 124f), head(bpm = 122f), allowTempoNudge = false)

        assertEquals(1f, plan.incomingSpeed)
    }

    @Test
    fun `clashing tempos echo out quickly in time with the outgoing beat`() {
        val plan = plan(tail(bpm = 128f), head(bpm = 96f))

        assertEquals(TransitionStyle.ECHO, plan.style)
        assertTrue(plan.durationMs <= 4_000L)
        assertEquals(Math.round(60_000.0 / 128 * 0.75), plan.echoDelayMs)
        assertEquals(1f, plan.incomingSpeed)
    }

    @Test
    fun `a fade-out is faded from its start`() {
        val outgoing = tail(bpm = 0f, outroStartMs = TRACK_MS - 14_000L, endOfAudioMs = TRACK_MS - 2_000L)

        val plan = plan(outgoing, head(bpm = 0f))

        assertEquals(TransitionStyle.FADE, plan.style)
        assertEquals(TRACK_MS - 14_000L, plan.startPositionMs)
        assertEquals(CONFIGURED_MS, plan.durationMs)
    }

    @Test
    fun `an abrupt end into a strong start cuts where the audio ends`() {
        val outgoing = tail(bpm = 0f, outroStartMs = TRACK_MS - 1_200L, endOfAudioMs = TRACK_MS - 900L)

        val plan = plan(outgoing, head(bpm = 0f))

        assertEquals(TransitionStyle.CUT, plan.style)
        assertEquals(TRACK_MS - 900L, plan.startPositionMs)
        assertEquals(0L, plan.durationMs)
    }

    @Test
    fun `the user's style is kept`() {
        val plan = plan(tail(bpm = 128f), head(bpm = 96f), style = TransitionStyle.RISE)

        assertEquals(TransitionStyle.RISE, plan.style)
    }

    @Test
    fun `an analysis of another track length is ignored`() {
        val outgoing = tail(bpm = 120f).copy(trackDurationMs = TRACK_MS - 10_000L)

        assertNull(SmartMixPlanner.plan(outgoing, null, TRACK_MS, CONFIGURED_MS, null, false))
    }

    @Test
    fun `half time counts as the same tempo`() {
        val ratio = SmartMixPlanner.tempoRatio(tail(bpm = 140f), head(bpm = 71f))

        assertNotNull(ratio)
        assertTrue(abs(ratio!! - 140.0 / 142) < 1e-6)
    }

    @Test
    fun `matching tempos in compatible keys blend longest`() {
        val withoutKeys = plan(tail(bpm = 124f), head(bpm = 122f))
        val withKeys = plan(tail(bpm = 124f, key = A_MINOR), head(bpm = 122f, key = E_MINOR))

        assertEquals(TransitionStyle.FILTER, withKeys.style)
        assertTrue(withKeys.durationMs > withoutKeys.durationMs)
        assertTrue(withKeys.durationMs > 2 * CONFIGURED_MS)
        assertTrue(withKeys.durationMs <= 16_000L)
    }

    @Test
    fun `compatible keys blend longer without a tempo`() {
        val plan = plan(tail(bpm = 0f, outroStartMs = TRACK_MS - 4_500L, key = A_MINOR), head(bpm = 0f, key = C_MAJOR))

        assertEquals(TransitionStyle.FADE, plan.style)
        assertEquals(2 * CONFIGURED_MS, plan.durationMs)
    }

    @Test
    fun `clashing keys keep a matching tempo blend short`() {
        val plan = plan(tail(bpm = 124f, key = C_MAJOR), head(bpm = 122f, key = F_SHARP_MAJOR))

        assertEquals(TransitionStyle.FILTER, plan.style)
        assertTrue(plan.durationMs <= 4_000L)
    }

    @Test
    fun `clashing keys echo out quickly`() {
        val plan = plan(tail(bpm = 0f, outroStartMs = TRACK_MS - 4_500L, key = C_MAJOR), head(bpm = 0f, key = F_SHARP_MAJOR))

        assertEquals(TransitionStyle.ECHO, plan.style)
        assertTrue(plan.durationMs <= 4_000L)
    }

    @Test
    fun `keys are ignored unless both are confident`() {
        val unsure = F_SHARP_MAJOR.copy(confidence = 0.2f)
        val plan = plan(tail(bpm = 0f, outroStartMs = TRACK_MS - 4_500L, key = C_MAJOR), head(bpm = 0f, key = unsure))

        assertNull(SmartMixPlanner.harmony(tail(bpm = 0f, key = C_MAJOR), head(bpm = 0f, key = unsure)))
        assertEquals(TransitionStyle.FADE, plan.style)
        assertEquals(CONFIGURED_MS, plan.durationMs)
    }

    private fun plan(
        outgoing: MixAnalysis,
        incoming: MixAnalysis?,
        style: TransitionStyle? = null,
        allowTempoNudge: Boolean = false,
    ): TransitionPlan {
        val plan = SmartMixPlanner.plan(outgoing, incoming, TRACK_MS, CONFIGURED_MS, style, allowTempoNudge)
        assertNotNull(plan)
        assertTrue(plan!!.startPositionMs in outgoing.regionStartMs..TRACK_MS)
        return plan
    }

    private fun tail(
        bpm: Float,
        outroStartMs: Long = TRACK_MS - 3_500L,
        endOfAudioMs: Long = TRACK_MS - 2_500L,
        key: MusicalKey? = null,
    ) = MixAnalysis(
        trackDurationMs = TRACK_MS,
        regionStartMs = TRACK_MS - 30_000L,
        regionEndMs = TRACK_MS,
        bpm = bpm,
        bpmConfidence = if (bpm > 0f) 0.9f else 0f,
        beatPhaseMs = TRACK_MS - 29_870L,
        firstBeatMs = TRACK_MS - 29_870L,
        audioStartMs = TRACK_MS - 30_000L,
        introEndMs = TRACK_MS - 30_000L,
        outroStartMs = outroStartMs,
        endOfAudioMs = endOfAudioMs,
        loudnessDb = -14f,
        bodyDb = -12f,
        peakDb = -0.5f,
        musicalKey = key,
    )

    private fun head(
        bpm: Float,
        audioStartMs: Long = 0L,
        firstBeatMs: Long = 40L,
        key: MusicalKey? = null,
    ) = MixAnalysis(
        trackDurationMs = 200_000L,
        regionStartMs = 0L,
        regionEndMs = 30_000L,
        bpm = bpm,
        bpmConfidence = if (bpm > 0f) 0.9f else 0f,
        beatPhaseMs = firstBeatMs,
        firstBeatMs = firstBeatMs,
        audioStartMs = audioStartMs,
        introEndMs = audioStartMs + 300L,
        outroStartMs = 30_000L,
        endOfAudioMs = 30_000L,
        loudnessDb = -13f,
        bodyDb = -11f,
        peakDb = -0.3f,
        musicalKey = key,
    )

    private companion object {
        const val TRACK_MS = 240_000L
        const val CONFIGURED_MS = 6_000L
        val C_MAJOR = MusicalKey(0, minor = false, confidence = 0.9f)
        val F_SHARP_MAJOR = MusicalKey(6, minor = false, confidence = 0.9f)
        val A_MINOR = MusicalKey(9, minor = true, confidence = 0.9f)
        val E_MINOR = MusicalKey(4, minor = true, confidence = 0.9f)
    }
}
