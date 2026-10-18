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
import kotlin.math.pow

/**
 * PCM16 (mono/stereo) processor for DJ-style transition effects. Idle it only copies its input.
 *
 * Each effect follows an envelope over the transition, measured in frames this processor has
 * handled, i.e. in media time of the stream: it pauses with playback and keeps its shape however
 * much audio the AudioTrack has buffered. An incoming player is [prime]d before it pre-rolls,
 * so its buffered first audio already has the effect's starting state.
 *
 * Commands come from the main thread as immutable objects through one volatile field; the
 * playback thread applies the latest one it has not seen yet at the next buffer. Starting,
 * switching and stopping effects ramp in and out over a few milliseconds to avoid clicks.
 */
@UnstableApi
class TransitionFxAudioProcessor : BaseAudioProcessor() {
    enum class Effect {
        /** Outgoing: high-pass sweep up. Incoming: low-pass opening up. */
        FILTER,

        /** Outgoing: feedback delay; the dry signal fades out, leaving the echo tail. */
        ECHO,

        /** Outgoing: high-pass sweep with a slight gain lift. Incoming: bass held back until the drop. */
        RISE,
    }

    enum class Role {
        OUTGOING,
        INCOMING,
    }

    private class Command(
        val effect: Effect?,
        val role: Role,
        // 0 while primed: hold the effect's starting state until start()
        val durationMs: Long,
        val echoDelayMs: Long,
    )

    @Volatile private var command: Command? = null

    private var sampleRate = 0
    private var channelCount = 0

    // Playback thread only
    private var seenCommand: Command? = null
    private var pendingCommand: Command? = null
    private var effect: Effect? = null
    private var role = Role.OUTGOING
    private var durationFrames = 0L
    private var positionFrames = 0L
    private var releasing = false
    private var amount = 0.0
    private var blockFramesLeft = 0
    private var mix = 0.0
    private var mixStep = 0.0
    private var gain = 1.0
    private var gainStep = 0.0

    private val filter = RetunableBiquadFilter(MAX_CHANNELS)

    private var delayLine = FloatArray(0)
    private var delayCapacityFrames = 0
    private var delayFrames = 1
    private var delayWriteFrame = 0
    private var delayDirty = false
    private val echoTone = DoubleArray(MAX_CHANNELS)

    /** Holds [effect]'s starting state, e.g. on an incoming player before it pre-rolls. */
    fun prime(
        effect: Effect,
        role: Role,
    ) {
        command = Command(effect, role, 0L, DEFAULT_ECHO_DELAY_MS)
    }

    /**
     * Runs [effect] over [durationMs] of media time. Continues a matching [prime], otherwise
     * starts from the current position.
     */
    fun start(
        effect: Effect,
        role: Role,
        durationMs: Long,
        echoDelayMs: Long = DEFAULT_ECHO_DELAY_MS,
    ) {
        command = Command(effect, role, durationMs.coerceAtLeast(1L), echoDelayMs)
    }

    /** Ramps any effect out and returns to pass-through. */
    fun stop() {
        command = STOP
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.channelCount !in 1..MAX_CHANNELS
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val latest = command
        if (latest !== seenCommand) {
            seenCommand = latest
            apply(latest)
        }

        val output = replaceOutputBuffer(remaining)
        if (effect != null) {
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            process(inputBuffer, output)
        }
        // Whatever is left (all of it when idle, or the rest after an effect ended) passes through
        output.put(inputBuffer)
        output.flip()
    }

    private fun apply(next: Command?) {
        val nextEffect = next?.effect
        // The echo has no incoming part
        if (next == null || nextEffect == null || (nextEffect == Effect.ECHO && next.role == Role.INCOMING)) {
            pendingCommand = null
            if (effect != null) releasing = true
            return
        }
        val continuesPrime = effect == nextEffect && role == next.role && !releasing && durationFrames == 0L
        if (effect != null && !continuesPrime) {
            // Fade the running effect out first; switching filters on audible signal would click
            pendingCommand = next
            releasing = true
            return
        }
        if (!continuesPrime) {
            clearState()
            effect = nextEffect
            role = next.role
        }
        durationFrames = if (next.durationMs > 0L) (next.durationMs * sampleRate / 1000).coerceAtLeast(1L) else 0L
        delayFrames = (next.echoDelayMs * sampleRate / 1000).toInt().coerceIn(1, (delayCapacityFrames - 1).coerceAtLeast(1))
        blockFramesLeft = 0
    }

    private fun process(
        input: ByteBuffer,
        output: ByteBuffer,
    ) {
        val channels = channelCount
        var index = input.position()
        val end = input.limit() - channels * Short.SIZE_BYTES
        while (index <= end) {
            if (blockFramesLeft == 0) {
                beginBlock()
                if (effect == null) break
            }
            val activeEffect = effect
            mix += mixStep
            gain += gainStep
            val readFrame = (delayWriteFrame - delayFrames).let { if (it < 0) it + delayCapacityFrames else it }
            for (channel in 0 until channels) {
                val dry = input.getShort(index) / PCM_SCALE
                index += Short.SIZE_BYTES
                val wet =
                    if (activeEffect == Effect.ECHO) {
                        echo(dry, channel, readFrame)
                    } else {
                        filter.process(dry, channel) * gain
                    }
                val mixed = dry + mix * (softClip(wet) - dry)
                output.putShort((mixed * PCM_SCALE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            if (activeEffect == Effect.ECHO) {
                delayWriteFrame = if (delayWriteFrame + 1 == delayCapacityFrames) 0 else delayWriteFrame + 1
            } else {
                filter.advance()
            }
            positionFrames++
            blockFramesLeft--
        }
        input.position(index)
    }

    private fun echo(
        dry: Double,
        channel: Int,
        readFrame: Int,
    ): Double {
        val delayed = delayLine[readFrame * channelCount + channel].toDouble()
        // Repeats lose some treble, like a tape echo, which also keeps the feedback tame
        var tone = echoTone[channel] + ECHO_TONE_COEFFICIENT * (delayed - echoTone[channel])
        if (abs(tone) < DENORMAL_THRESHOLD) tone = 0.0
        echoTone[channel] = tone
        // gain is the dry level, which also feeds the delay: once it has faded only the tail remains
        delayLine[delayWriteFrame * channelCount + channel] = (gain * dry + ECHO_FEEDBACK * tone).toFloat()
        delayDirty = true
        return gain * dry + ECHO_WET * delayed
    }

    /** Plans the next block: effect amount, mix and gain glide linearly across it, the filter retunes. */
    private fun beginBlock() {
        val rampFrames = (sampleRate * RAMP_MS / 1000).coerceAtLeast(1)
        val step = BLOCK_FRAMES.toDouble() / rampFrames
        amount = if (releasing) (amount - step).coerceAtLeast(0.0) else (amount + step).coerceAtMost(1.0)
        if (releasing && amount == 0.0 && mix < SILENT_MIX) {
            val next = pendingCommand
            goIdle()
            if (next == null) return
            apply(next)
            if (effect == null) return
            amount = step
        }
        if (role == Role.INCOMING && durationFrames > 0L && positionFrames >= durationFrames) {
            // The incoming envelope ends fully dry
            goIdle()
            return
        }

        val progress =
            if (durationFrames == 0L) {
                0.0
            } else {
                ((positionFrames + BLOCK_FRAMES).toDouble() / durationFrames).coerceAtMost(1.0)
            }
        var targetGain = 1.0
        var handBack = 1.0
        when (effect) {
            Effect.FILTER ->
                if (role == Role.OUTGOING) {
                    tuneFilter(RetunableBiquadFilter.Type.HIGH_PASS, sweep(40.0, 1_500.0, progress.pow(0.6)), 0.9)
                } else {
                    val opening = (progress / 0.9).coerceAtMost(1.0)
                    tuneFilter(RetunableBiquadFilter.Type.LOW_PASS, sweep(400.0, 20_000.0, opening.pow(1.5)), BUTTERWORTH_Q)
                    handBack = 1.0 - ramp(progress, 0.9, 1.0)
                }

            Effect.RISE ->
                if (role == Role.OUTGOING) {
                    tuneFilter(RetunableBiquadFilter.Type.HIGH_PASS, sweep(40.0, 1_000.0, progress), 1.0)
                    targetGain = 1.0 + (RISE_LIFT - 1.0) * ramp(progress, 0.0, 0.8)
                } else {
                    // Bass comes back in at the drop
                    tuneFilter(RetunableBiquadFilter.Type.HIGH_PASS, sweep(250.0, 20.0, ramp(progress, 0.7, 0.85)), BUTTERWORTH_Q)
                    handBack = 1.0 - ramp(progress, 0.85, 1.0)
                }

            Effect.ECHO -> {
                val fade = ramp(progress, 0.25, 0.6)
                targetGain = 1.0 - fade * fade * (3.0 - 2.0 * fade)
            }

            null -> Unit
        }
        mixStep = (amount * handBack - mix) / BLOCK_FRAMES
        gainStep = (targetGain - gain) / BLOCK_FRAMES
        blockFramesLeft = BLOCK_FRAMES
    }

    private fun tuneFilter(
        type: RetunableBiquadFilter.Type,
        cutoffHz: Double,
        q: Double,
    ) = filter.tune(type, sampleRate, cutoffHz, q, BLOCK_FRAMES)

    private fun clearState() {
        positionFrames = 0L
        durationFrames = 0L
        amount = 0.0
        mix = 0.0
        mixStep = 0.0
        gain = 1.0
        gainStep = 0.0
        blockFramesLeft = 0
        releasing = false
        filter.clear()
        clearEcho()
    }

    private fun clearEcho() {
        if (delayDirty) {
            delayLine.fill(0f)
            delayDirty = false
        }
        echoTone.fill(0.0)
        delayWriteFrame = 0
    }

    private fun goIdle() {
        clearState()
        effect = null
        pendingCommand = null
    }

    // A new format takes effect here, not in onConfigure, because the previous stream may
    // still be draining in between. A seek or new stream restarts the envelope from the new
    // position without stale history.
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        if (outputAudioFormat != AudioProcessor.AudioFormat.NOT_SET) {
            sampleRate = inputAudioFormat.sampleRate
            channelCount = inputAudioFormat.channelCount
            delayCapacityFrames = (sampleRate.toLong() * MAX_ECHO_DELAY_MS / 1000).toInt() + 1
            val delaySamples = delayCapacityFrames * channelCount
            if (delayLine.size < delaySamples) {
                delayLine = FloatArray(delaySamples)
                delayDirty = false
            }
        }
        if (releasing) {
            val next = pendingCommand
            goIdle()
            if (next != null) apply(next)
            return
        }
        positionFrames = 0L
        blockFramesLeft = 0
        filter.reset()
        clearEcho()
    }

    override fun onReset() {
        goIdle()
        sampleRate = 0
        channelCount = 0
        delayCapacityFrames = 0
    }

    private companion object {
        const val MAX_CHANNELS = 2
        const val PCM_SCALE = 32768.0
        const val BLOCK_FRAMES = 32
        const val RAMP_MS = 30
        const val BUTTERWORTH_Q = 0.7071
        const val DEFAULT_ECHO_DELAY_MS = 375L
        const val MAX_ECHO_DELAY_MS = 1_000L
        const val ECHO_FEEDBACK = 0.5
        const val ECHO_WET = 0.45
        const val ECHO_TONE_COEFFICIENT = 0.35
        const val DENORMAL_THRESHOLD = 1e-20
        const val SILENT_MIX = 1e-6

        // +2 dB
        const val RISE_LIFT = 1.2589
        const val SOFT_CLIP_KNEE = 0.9

        val STOP = Command(null, Role.OUTGOING, 0L, DEFAULT_ECHO_DELAY_MS)

        fun sweep(
            fromHz: Double,
            toHz: Double,
            position: Double,
        ): Double = fromHz * (toHz / fromHz).pow(position)

        /** 0 before [from], 1 after [to], linear in between. */
        fun ramp(
            value: Double,
            from: Double,
            to: Double,
        ): Double = ((value - from) / (to - from)).coerceIn(0.0, 1.0)

        // Keeps effect peaks (resonance, gain lift, echo build-up) inside full scale
        fun softClip(value: Double): Double {
            val magnitude = abs(value)
            if (magnitude <= SOFT_CLIP_KNEE) return value
            val over = (magnitude - SOFT_CLIP_KNEE) / (1.0 - SOFT_CLIP_KNEE)
            val clipped = SOFT_CLIP_KNEE + (1.0 - SOFT_CLIP_KNEE) * over / (1.0 + over)
            return if (value < 0.0) -clipped else clipped
        }
    }
}
