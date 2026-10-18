/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionFxAudioProcessorTest {
    @Test
    fun `idle processor passes audio through bit-exact`() {
        val processor = configuredProcessor()
        val input = tone(TONE_LOW_HZ, 0.8, FRAMES_PER_BUFFER)

        assertArrayEquals(input, process(processor, input))
    }

    @Test
    fun `primed incoming filter attenuates a high tone`() {
        val processor = configuredProcessor()
        processor.prime(TransitionFxAudioProcessor.Effect.FILTER, TransitionFxAudioProcessor.Role.INCOMING)

        val input = tone(TONE_HIGH_HZ, 0.5, FRAMES_PER_BUFFER)
        // Let the attack ramp and the filter settle
        repeat(4) { process(processor, input) }
        val output = process(processor, input)

        assertTrue(rms(output) < rms(input) * 0.05)
    }

    @Test
    fun `incoming filter returns to bit-exact pass-through when its envelope ends`() {
        val processor = configuredProcessor()
        processor.start(TransitionFxAudioProcessor.Effect.FILTER, TransitionFxAudioProcessor.Role.INCOMING, durationMs = 200)

        val input = tone(TONE_HIGH_HZ, 0.5, FRAMES_PER_BUFFER)
        // 10 buffers of ~85 ms cover the 200 ms envelope
        repeat(10) { process(processor, input) }

        assertArrayEquals(input, process(processor, input))
    }

    @Test
    fun `stop releases an effect back to pass-through`() {
        val processor = configuredProcessor()
        processor.start(TransitionFxAudioProcessor.Effect.RISE, TransitionFxAudioProcessor.Role.OUTGOING, durationMs = 5_000)
        val input = tone(TONE_LOW_HZ, 0.5, FRAMES_PER_BUFFER)
        repeat(3) { process(processor, input) }

        processor.stop()
        repeat(2) { process(processor, input) }

        assertArrayEquals(input, process(processor, input))
    }

    @Test
    fun `loud effects stay finite and below full scale`() {
        for (effect in TransitionFxAudioProcessor.Effect.entries) {
            val processor = configuredProcessor()
            processor.start(effect, TransitionFxAudioProcessor.Role.OUTGOING, durationMs = 1_000)
            val input = squareWave(TONE_LOW_HZ, 1.0, FRAMES_PER_BUFFER)

            repeat(15) {
                val output = process(processor, input)
                assertTrue("$effect clipped", output.none { it == Short.MAX_VALUE || it == Short.MIN_VALUE })
            }
        }
    }

    @Test
    fun `retunable filter passes DC and stays stable while sweeping`() {
        val filter = RetunableBiquadFilter(1)
        var output = 0.0
        for (block in 0 until 2_000) {
            val cutoff = 40.0 * 500.0.pow((block % 200) / 200.0)
            filter.tune(RetunableBiquadFilter.Type.LOW_PASS, SAMPLE_RATE, cutoff, 0.9, 32)
            repeat(32) {
                output = filter.process(0.5, 0)
                filter.advance()
            }
        }

        assertTrue(output.isFinite())
        assertEquals(0.5, output, 1e-3)
    }

    private fun configuredProcessor(): TransitionFxAudioProcessor =
        TransitionFxAudioProcessor().apply {
            configure(AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT))
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }

    private fun process(
        processor: TransitionFxAudioProcessor,
        samples: ShortArray,
    ): ShortArray {
        val input =
            ByteBuffer
                .allocateDirect(samples.size * Short.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .apply {
                    samples.forEach { putShort(it) }
                    flip()
                }
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        return ShortArray(output.remaining() / Short.SIZE_BYTES) { output.short }
    }

    private fun tone(
        frequencyHz: Double,
        amplitude: Double,
        frames: Int,
    ): ShortArray =
        ShortArray(frames * CHANNELS) { index ->
            val frame = index / CHANNELS
            (sin(2 * PI * frequencyHz * frame / SAMPLE_RATE) * amplitude * Short.MAX_VALUE).toInt().toShort()
        }

    private fun squareWave(
        frequencyHz: Double,
        amplitude: Double,
        frames: Int,
    ): ShortArray =
        ShortArray(frames * CHANNELS) { index ->
            val frame = index / CHANNELS
            val high = sin(2 * PI * frequencyHz * frame / SAMPLE_RATE) >= 0
            ((if (high) amplitude else -amplitude) * Short.MAX_VALUE).toInt().toShort()
        }

    private fun rms(samples: ShortArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val FRAMES_PER_BUFFER = 4_096
        const val TONE_LOW_HZ = 220.0
        const val TONE_HIGH_HZ = 12_000.0
    }
}
