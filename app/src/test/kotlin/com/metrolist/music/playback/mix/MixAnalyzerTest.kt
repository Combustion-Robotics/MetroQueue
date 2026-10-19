/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class MixAnalyzerTest {
    @Test
    fun `click track tempo and beat grid are found`() {
        for (bpm in listOf(92.0, 100.0, 120.0, 128.0, 140.0)) {
            val beatMs = 60_000.0 / bpm
            val firstClickMs = 230.0
            val samples = signal(30_000) { ms -> pad(ms) + click(ms, firstClickMs, beatMs) }

            val analysis = analyze(samples)

            assertEquals("bpm for $bpm", bpm, analysis.bpm.toDouble(), 2.0)
            assertTrue("confidence for $bpm: ${analysis.bpmConfidence}", analysis.hasConfidentTempo)
            assertTrue("beat phase for $bpm: ${analysis.beatPhaseMs}", gridError(analysis.beatPhaseMs, firstClickMs, beatMs) <= 30.0)
            assertTrue("first beat for $bpm: ${analysis.firstBeatMs}", gridError(analysis.firstBeatMs, firstClickMs, beatMs) <= 30.0)
        }
    }

    @Test
    fun `noise has no confident tempo`() {
        val random = Random(7)
        val samples = signal(30_000) { (random.nextDouble() - 0.5) * 0.4 }

        assertTrue(!analyze(samples).hasConfidentTempo)
    }

    @Test
    fun `fade-out and trailing silence are detected`() {
        val beatMs = 500.0
        // 18 s of music, a 9 s linear fade, then 3 s of silence
        val samples =
            signal(30_000) { ms ->
                val gain =
                    when {
                        ms < 18_000 -> 1.0
                        ms < 27_000 -> 1.0 - (ms - 18_000) / 9_000.0
                        else -> 0.0
                    }
                gain * (pad(ms) + click(ms, 0.0, beatMs))
            }
        val startMs = 150_000L

        val analysis = analyze(samples, startMs)

        assertEquals(startMs + 18_000.0, analysis.outroStartMs.toDouble(), 2_500.0)
        assertTrue("end of audio ${analysis.endOfAudioMs}", analysis.endOfAudioMs in startMs + 24_000..startMs + 27_100)
        assertTrue(analysis.outroStartMs < analysis.endOfAudioMs - 3_000)
    }

    @Test
    fun `abrupt ending has its outro at the end of the audio`() {
        val samples = signal(30_000) { ms -> if (ms < 28_000) pad(ms) + click(ms, 0.0, 500.0) else 0.0 }

        val analysis = analyze(samples)

        assertEquals(28_000.0, analysis.endOfAudioMs.toDouble(), 100.0)
        assertTrue(analysis.endOfAudioMs - analysis.outroStartMs <= 1_000)
    }

    @Test
    fun `leading silence and a quiet intro are detected`() {
        // 2.5 s silence, 4 s quiet intro (-20 dB), then the full song
        val samples =
            signal(30_000) { ms ->
                when {
                    ms < 2_500 -> 0.0
                    ms < 6_500 -> 0.1 * pad(ms)
                    else -> pad(ms) + click(ms, 6_500.0, 500.0)
                }
            }

        val analysis = analyze(samples)

        assertEquals(2_500.0, analysis.audioStartMs.toDouble(), 100.0)
        assertEquals(6_500.0, analysis.introEndMs.toDouble(), 600.0)
        assertEquals(30_000.0, analysis.endOfAudioMs.toDouble(), 100.0)
    }

    @Test
    fun `silence is not analysed`() {
        assertEquals(null, MixAnalyzer.analyze(FloatArray(SAMPLE_RATE * 10), SAMPLE_RATE, 0L, 10_000L))
    }

    @Test
    fun `waveform holds the peak of each step`() {
        // 1 s at half level, then 1 s of silence
        val samples = signal(2_000) { ms -> if (ms < 1_000) 0.5 * sin(2 * PI * 440 * ms / 1000) else 0.0 }

        val waveform = MixAnalyzer.waveform(samples, SAMPLE_RATE, 5_000L)
        val levels = waveform.levels()

        assertEquals(5_000L, waveform.startMs)
        assertEquals(2_000 / waveform.stepMs, levels.size)
        assertEquals(sqrt(0.5f), levels[5], 0.01f)
        assertEquals(0f, levels.last(), 0.01f)
    }

    private fun analyze(
        samples: FloatArray,
        startMs: Long = 0L,
    ): MixAnalysis {
        val analysis = MixAnalyzer.analyze(samples, SAMPLE_RATE, startMs, startMs + samples.size * 1000L / SAMPLE_RATE)
        assertNotNull(analysis)
        return analysis!!
    }

    private fun signal(
        durationMs: Int,
        value: (Double) -> Double,
    ): FloatArray =
        FloatArray(SAMPLE_RATE * durationMs / 1000) { i ->
            value(i * 1000.0 / SAMPLE_RATE).coerceIn(-1.0, 1.0).toFloat()
        }

    // A sustained chord under the beat, so the signal never drops to silence between clicks
    private fun pad(ms: Double): Double {
        val t = ms / 1000
        return 0.08 * (sin(2 * PI * 220 * t) + sin(2 * PI * 277 * t) + sin(2 * PI * 330 * t))
    }

    // Decaying noise-like burst (a hi-hat/kick stand-in) on every beat from firstMs
    private fun click(
        ms: Double,
        firstMs: Double,
        beatMs: Double,
    ): Double {
        if (ms < firstMs) return 0.0
        val sinceBeat = (ms - firstMs) % beatMs
        if (sinceBeat > 60) return 0.0
        val decay = exp(-sinceBeat / 12)
        return 0.7 * decay * (sin(2 * PI * 1_700 * ms / 1000) + 0.6 * sin(2 * PI * 90 * ms / 1000))
    }

    private fun gridError(
        positionMs: Long,
        firstMs: Double,
        beatMs: Double,
    ): Double {
        val offset = ((positionMs - firstMs) % beatMs + beatMs) % beatMs
        return minOf(offset, beatMs - offset).let { abs(it) }
    }

    private companion object {
        const val SAMPLE_RATE = 12_000
    }
}
