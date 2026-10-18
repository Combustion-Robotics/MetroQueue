/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CustomTransitionPlannerTest {
    @Test
    fun `pinned positions are used as set`() {
        val transition =
            CustomTransition("a", "b", TransitionStyle.FILTER, 6_000L, outgoingEndMs = 200_000L, incomingStartMs = 12_000L)

        val plan = CustomTransitionPlanner.plan(transition, TRACK_MS, tail(), head(), allowTempoNudge = true)!!

        assertEquals(194_000L, plan.startPositionMs)
        assertEquals(6_000L, plan.durationMs)
        assertEquals(12_000L, plan.incomingStartMs)
        assertEquals(TransitionStyle.FILTER, plan.style)
        assertFalse(plan.allowEarlyStart)
    }

    @Test
    fun `automatic positions come from the analysis, or the track end without it`() {
        val transition = CustomTransition("a", "b", TransitionStyle.FADE, 4_000L)

        val analysed = CustomTransitionPlanner.plan(transition, TRACK_MS, tail(), head(audioStartMs = 2_000L), allowTempoNudge = true)!!
        assertEquals(END_OF_AUDIO_MS - 4_000L, analysed.startPositionMs)
        assertEquals(1_900L, analysed.incomingStartMs)

        val plain = CustomTransitionPlanner.plan(transition, TRACK_MS, null, null, allowTempoNudge = true)!!
        assertEquals(TRACK_MS - 4_000L, plain.startPositionMs)
        assertEquals(4_000L, plain.durationMs)
        assertNull(plain.incomingStartMs)
        assertEquals(1f, plain.incomingSpeed)

        // Analysis of another version of the song is ignored
        val stale = CustomTransitionPlanner.plan(transition, TRACK_MS + 10_000L, tail(), null, allowTempoNudge = true)!!
        assertEquals(TRACK_MS + 6_000L, stale.startPositionMs)
    }

    @Test
    fun `tempo is matched only when asked for and allowed`() {
        val transition = CustomTransition("a", "b", TransitionStyle.ECHO, 8_000L)

        val matched = CustomTransitionPlanner.plan(transition, TRACK_MS, tail(bpm = 124f), head(bpm = 121f), allowTempoNudge = true)!!
        assertEquals(124f / 121f, matched.incomingSpeed, 0.0001f)
        assertEquals(Math.round(60_000.0 / 124 * 0.75), matched.echoDelayMs)

        val off = transition.copy(tempoMatch = false)
        assertEquals(1f, CustomTransitionPlanner.plan(off, TRACK_MS, tail(bpm = 124f), head(bpm = 121f), true)!!.incomingSpeed)
        assertEquals(1f, CustomTransitionPlanner.plan(transition, TRACK_MS, tail(bpm = 124f), head(bpm = 121f), false)!!.incomingSpeed)
        assertEquals(1f, CustomTransitionPlanner.plan(transition, TRACK_MS, tail(bpm = 124f), head(bpm = 100f), true)!!.incomingSpeed)
    }

    @Test
    fun `cut switches at the end point and early ends shorten the overlap`() {
        val cut = CustomTransition("a", "b", TransitionStyle.CUT, 5_000L, outgoingEndMs = 150_000L)
        val cutPlan = CustomTransitionPlanner.plan(cut, TRACK_MS, null, null, allowTempoNudge = true)!!
        assertEquals(150_000L, cutPlan.startPositionMs)
        assertEquals(0L, cutPlan.durationMs)

        val autoCut = CustomTransitionPlanner.plan(cut.copy(outgoingEndMs = null), TRACK_MS, null, null, true)!!
        assertEquals(TRACK_MS - 150L, autoCut.startPositionMs)

        val early = CustomTransition("a", "b", TransitionStyle.FADE, 8_000L, outgoingEndMs = 3_000L)
        val earlyPlan = CustomTransitionPlanner.plan(early, TRACK_MS, null, null, allowTempoNudge = true)!!
        assertEquals(0L, earlyPlan.startPositionMs)
        assertEquals(3_000L, earlyPlan.durationMs)
    }

    private fun tail(bpm: Float = 124f) =
        MixAnalysis(
            trackDurationMs = TRACK_MS,
            regionStartMs = TRACK_MS - 30_000L,
            regionEndMs = TRACK_MS,
            bpm = bpm,
            bpmConfidence = 0.9f,
            beatPhaseMs = TRACK_MS - 29_870L,
            firstBeatMs = TRACK_MS - 29_870L,
            audioStartMs = TRACK_MS - 30_000L,
            introEndMs = TRACK_MS - 30_000L,
            outroStartMs = END_OF_AUDIO_MS - 500L,
            endOfAudioMs = END_OF_AUDIO_MS,
            loudnessDb = -14f,
            bodyDb = -12f,
            peakDb = -0.5f,
        )

    private fun head(
        bpm: Float = 122f,
        audioStartMs: Long = 0L,
    ) = MixAnalysis(
        trackDurationMs = 200_000L,
        regionStartMs = 0L,
        regionEndMs = 30_000L,
        bpm = bpm,
        bpmConfidence = 0.9f,
        beatPhaseMs = audioStartMs + 40L,
        firstBeatMs = audioStartMs + 40L,
        audioStartMs = audioStartMs,
        introEndMs = audioStartMs + 300L,
        outroStartMs = 30_000L,
        endOfAudioMs = 30_000L,
        loudnessDb = -13f,
        bodyDb = -11f,
        peakDb = -0.3f,
    )

    private companion object {
        const val TRACK_MS = 240_000L
        const val END_OF_AUDIO_MS = 236_000L
    }
}
