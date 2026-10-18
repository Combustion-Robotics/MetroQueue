/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Low/high-pass biquad (RBJ cookbook, transposed direct form II) whose cutoff can move while
 * it runs, for filter sweeps. A retune glides to the new coefficients linearly over a number
 * of frames instead of jumping, which avoids zipper noise and clicks. Every intermediate filter
 * stays stable because the stable region of (a1, a2) is convex.
 */
class RetunableBiquadFilter(
    channelCount: Int,
) {
    enum class Type {
        LOW_PASS,
        HIGH_PASS,
    }

    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var targetB0 = 1.0
    private var targetB1 = 0.0
    private var targetB2 = 0.0
    private var targetA1 = 0.0
    private var targetA2 = 0.0

    private var stepB0 = 0.0
    private var stepB1 = 0.0
    private var stepB2 = 0.0
    private var stepA1 = 0.0
    private var stepA2 = 0.0
    private var stepsLeft = 0
    private var tuned = false

    private val z1 = DoubleArray(channelCount)
    private val z2 = DoubleArray(channelCount)

    /**
     * Moves the filter to [type] at [cutoffHz] over [glideFrames] frames. The first call after
     * construction or [clear] applies immediately.
     */
    fun tune(
        type: Type,
        sampleRate: Int,
        cutoffHz: Double,
        q: Double,
        glideFrames: Int,
    ) {
        val frequency = cutoffHz.coerceIn(MIN_CUTOFF_HZ, sampleRate * MAX_CUTOFF_RATIO)
        val w0 = 2.0 * PI * frequency / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        val edge = if (type == Type.LOW_PASS) (1.0 - cosW0) / 2.0 else (1.0 + cosW0) / 2.0
        targetB0 = edge / a0
        targetB1 = (if (type == Type.LOW_PASS) 2.0 * edge else -2.0 * edge) / a0
        targetB2 = edge / a0
        targetA1 = -2.0 * cosW0 / a0
        targetA2 = (1.0 - alpha) / a0

        if (!tuned || glideFrames <= 0) {
            tuned = true
            snapToTarget()
            return
        }
        stepB0 = (targetB0 - b0) / glideFrames
        stepB1 = (targetB1 - b1) / glideFrames
        stepB2 = (targetB2 - b2) / glideFrames
        stepA1 = (targetA1 - a1) / glideFrames
        stepA2 = (targetA2 - a2) / glideFrames
        stepsLeft = glideFrames
    }

    fun process(
        input: Double,
        channel: Int,
    ): Double {
        val output = b0 * input + z1[channel]
        z1[channel] = b1 * input - a1 * output + z2[channel]
        z2[channel] = b2 * input - a2 * output
        return output
    }

    /** Advances the coefficient glide by one frame; call once per frame, after every channel. */
    fun advance() {
        if (stepsLeft == 0) return
        if (--stepsLeft == 0) {
            snapToTarget()
            flushDenormals()
            return
        }
        b0 += stepB0
        b1 += stepB1
        b2 += stepB2
        a1 += stepA1
        a2 += stepA2
    }

    /** Clears the signal history but keeps the tuning. */
    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
        stepsLeft = 0
        if (tuned) snapToTarget()
    }

    /** Clears the history and the tuning; the next [tune] applies immediately. */
    fun clear() {
        reset()
        tuned = false
    }

    private fun snapToTarget() {
        b0 = targetB0
        b1 = targetB1
        b2 = targetB2
        a1 = targetA1
        a2 = targetA2
        stepsLeft = 0
    }

    // Decaying state on silent input would otherwise sink into slow subnormal arithmetic
    private fun flushDenormals() {
        for (channel in z1.indices) {
            if (abs(z1[channel]) < DENORMAL_THRESHOLD) z1[channel] = 0.0
            if (abs(z2[channel]) < DENORMAL_THRESHOLD) z2[channel] = 0.0
        }
    }

    private companion object {
        const val MIN_CUTOFF_HZ = 10.0
        const val MAX_CUTOFF_RATIO = 0.45
        const val DENORMAL_THRESHOLD = 1e-20
    }
}
