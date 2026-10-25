/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * PCM16 pass-through processor that measures levels for crossfade timing, only while armed:
 * - tail metering: how long the track has stayed well below the loudest part of its tail
 *   (a fade-out) or near silence, so a transition can start where the song audibly ends;
 * - intro scan: how much silence the stream starts with, so it can be skipped;
 * - current level: RMS of the latest buffer, for visuals that follow the music.
 *
 * Arming happens on the main thread and bumps a generation; the playback thread resets its
 * state when it sees a new one. Results are packed with their generation into single volatile
 * longs, so stale values from before a re-arm are never read.
 */
@UnstableApi
class LevelMeterAudioProcessor : BaseAudioProcessor() {
    data class IntroScan(
        val leadingSilenceMs: Long,
        val audible: Boolean,
    )

    private var sampleRate = 0
    private var channelCount = 0
    private var windowSampleTarget = 0

    // Written by the main thread only
    @Volatile private var tailGeneration = 0

    @Volatile private var tailArmed = false

    @Volatile private var introGeneration = 0

    @Volatile private var introArmed = false

    // generation in the high 32 bits, milliseconds (or flags) in the low 32 bits
    @Volatile private var quietResult = 0L

    @Volatile private var silentResult = 0L

    @Volatile private var introResult = 0L

    @Volatile private var levelArmed = false

    @Volatile private var latestLevel = 0f

    // Playback thread only
    private var seenTailGeneration = -1
    private var windowSamples = 0
    private var windowSumSquares = 0L
    private var loudestRms = 0.0
    private var quietFrames = 0L
    private var silentFrames = 0L
    private var seenIntroGeneration = -1
    private var leadingSilentFrames = 0L
    private var introAudible = false

    fun startTailMetering() {
        tailGeneration++
        tailArmed = true
    }

    fun stopTailMetering() {
        tailArmed = false
    }

    /** How long the tail has stayed far below its loudest part (or silent), in media time. */
    fun tailQuietMs(): Long = readMs(quietResult, tailGeneration)

    /** How long the tail has stayed near silence, in media time. */
    fun tailSilentMs(): Long = readMs(silentResult, tailGeneration)

    fun startIntroScan() {
        introGeneration++
        introArmed = true
    }

    fun stopIntroScan() {
        introArmed = false
    }

    fun startLevelMetering() {
        levelArmed = true
    }

    fun stopLevelMetering() {
        levelArmed = false
        latestLevel = 0f
    }

    /** RMS of the latest buffer, 0..1; 0 while not metering or nothing plays through this processor. */
    fun level(): Float = latestLevel

    fun introScan(): IntroScan {
        val result = introResult
        if ((result ushr 32).toInt() != introGeneration) return IntroScan(0L, false)
        return IntroScan(
            leadingSilenceMs = result and INTRO_MS_MASK,
            audible = (result and INTRO_AUDIBLE_FLAG) != 0L,
        )
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.channelCount <= 0
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        windowSampleTarget = (sampleRate.toLong() * channelCount * WINDOW_MS / 1000).toInt().coerceAtLeast(1)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (tailArmed || introArmed || levelArmed) {
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            if (tailArmed) measureTail(inputBuffer)
            if (introArmed) scanIntro(inputBuffer)
            if (levelArmed) measureLevel(inputBuffer)
        }

        replaceOutputBuffer(remaining).apply {
            put(inputBuffer)
            flip()
        }
    }

    private fun measureTail(buffer: ByteBuffer) {
        val generation = tailGeneration
        if (generation != seenTailGeneration) {
            seenTailGeneration = generation
            resetTail()
        }

        val limit = buffer.limit()
        var index = buffer.position()
        while (index + 1 < limit) {
            val sample = buffer.getShort(index).toInt()
            windowSumSquares += sample * sample
            windowSamples++
            if (windowSamples >= windowSampleTarget) closeWindow()
            index += 2
        }

        val tag = generation.toLong() shl 32
        quietResult = tag or framesToMs(quietFrames)
        silentResult = tag or framesToMs(silentFrames)
    }

    // Every LEVEL_STRIDE-th sample is plenty for a visual level and keeps this cheap
    private fun measureLevel(buffer: ByteBuffer) {
        val limit = buffer.limit()
        var index = buffer.position()
        var sumSquares = 0.0
        var count = 0
        while (index + 1 < limit) {
            val sample = buffer.getShort(index).toInt()
            sumSquares += (sample * sample).toDouble()
            count++
            index += 2 * LEVEL_STRIDE
        }
        if (count > 0) latestLevel = (sqrt(sumSquares / count) / Short.MAX_VALUE).toFloat()
    }

    private fun closeWindow() {
        val rms = sqrt(windowSumSquares.toDouble() / windowSamples)
        if (rms > loudestRms) loudestRms = rms
        val frames = (windowSamples / channelCount).toLong()
        val silent = rms < SILENCE_RMS
        silentFrames = if (silent) silentFrames + frames else 0L
        quietFrames = if (silent || rms < loudestRms * QUIET_RATIO) quietFrames + frames else 0L
        windowSamples = 0
        windowSumSquares = 0L
    }

    private fun scanIntro(buffer: ByteBuffer) {
        val generation = introGeneration
        if (generation != seenIntroGeneration) {
            seenIntroGeneration = generation
            leadingSilentFrames = 0L
            introAudible = false
        }
        if (introAudible) return

        val limit = buffer.limit()
        var index = buffer.position()
        var silentSamples = 0L
        while (index + 1 < limit) {
            if (abs(buffer.getShort(index).toInt()) >= INTRO_SILENCE_PEAK) {
                introAudible = true
                break
            }
            silentSamples++
            index += 2
        }
        leadingSilentFrames += silentSamples / channelCount

        val silenceMs = framesToMs(leadingSilentFrames).coerceAtMost(INTRO_MS_MASK)
        introResult = (generation.toLong() shl 32) or silenceMs or (if (introAudible) INTRO_AUDIBLE_FLAG else 0L)
    }

    private fun resetTail() {
        windowSamples = 0
        windowSumSquares = 0L
        loudestRms = 0.0
        quietFrames = 0L
        silentFrames = 0L
    }

    private fun framesToMs(frames: Long): Long = if (sampleRate > 0) frames * 1000 / sampleRate else 0L

    private fun readMs(result: Long, generation: Int): Long =
        if ((result ushr 32).toInt() == generation) result and 0xFFFFFFFFL else 0L

    // A seek or stream change invalidates both measurements; they restart from the next buffer.
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        seenTailGeneration = -1
        seenIntroGeneration = -1
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        windowSampleTarget = 0
        seenTailGeneration = -1
        seenIntroGeneration = -1
    }

    private companion object {
        const val WINDOW_MS = 200

        const val LEVEL_STRIDE = 4

        // Roughly -15 dB below the loudest window of the tail counts as faded out
        const val QUIET_RATIO = 0.18

        // About -50 dBFS
        const val SILENCE_RMS = 100.0

        // Same threshold the silence detector uses (about -42 dBFS peak)
        const val INTRO_SILENCE_PEAK = 256

        const val INTRO_AUDIBLE_FLAG = 1L shl 31
        const val INTRO_MS_MASK = INTRO_AUDIBLE_FLAG - 1
    }
}
