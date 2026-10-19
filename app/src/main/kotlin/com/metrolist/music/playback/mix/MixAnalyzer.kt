/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

import com.metrolist.music.recognition.computeRfft
import kotlinx.serialization.Serializable
import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Which part of a song an analysis covers: the start, or the end. */
enum class MixRegion { HEAD, TAIL }

/**
 * What [MixAnalyzer] found in one region of a song. All positions are absolute track positions
 * in ms; the analysed region is [regionStartMs, regionEndMs).
 *
 * @param bpm tempo, or 0 when none was found.
 * @param bpmConfidence 0..1, how clearly the region has a steady beat.
 * @param beatPhaseMs a beat of the fitted beat grid (beats at beatPhaseMs + k * [beatMs]).
 * @param firstBeatMs first beat of the grid once the audio starts ([audioStartMs] without a tempo).
 * @param audioStartMs where leading silence ends.
 * @param introEndMs where the song first reaches its full level.
 * @param outroStartMs from here on the level stays well below the full level (a fade or a quiet ending).
 * @param endOfAudioMs where trailing silence starts.
 * @param loudnessDb median level of the audible part, dBFS RMS.
 * @param bodyDb "full" level (upper quartile of the audible part), dBFS RMS.
 * @param peakDb sample peak, dBFS.
 * @param waveform level envelope of the decoded audio, which may reach further into the song
 *   than the analysed region; null in analyses stored before it existed.
 */
@Serializable
data class MixAnalysis(
    val trackDurationMs: Long,
    val regionStartMs: Long,
    val regionEndMs: Long,
    val bpm: Float,
    val bpmConfidence: Float,
    val beatPhaseMs: Long,
    val firstBeatMs: Long,
    val audioStartMs: Long,
    val introEndMs: Long,
    val outroStartMs: Long,
    val endOfAudioMs: Long,
    val loudnessDb: Float,
    val bodyDb: Float,
    val peakDb: Float,
    val waveform: MixWaveform? = null,
) {
    val hasConfidentTempo: Boolean
        get() = bpm > 0f && bpmConfidence >= CONFIDENT_TEMPO

    val beatMs: Double
        get() = if (bpm > 0f) 60_000.0 / bpm else 0.0

    companion object {
        const val CONFIDENT_TEMPO = 0.5f
    }
}

/**
 * Peak level per [stepMs] from [startMs] on, for drawing: one byte per step (the square root of
 * the linear peak, so quiet parts stay visible), Base64 encoded to keep the store small.
 */
@Serializable
data class MixWaveform(
    val startMs: Long,
    val stepMs: Int,
    val data: String,
) {
    /** Levels in 0..1. */
    fun levels(): FloatArray {
        val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrDefault(ByteArray(0))
        return FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }
    }

    companion object {
        fun encode(
            startMs: Long,
            stepMs: Int,
            levels: FloatArray,
        ): MixWaveform {
            val bytes = ByteArray(levels.size) { (levels[it].coerceIn(0f, 1f) * 255).roundToInt().toByte() }
            return MixWaveform(startMs, stepMs, Base64.getEncoder().encodeToString(bytes))
        }
    }
}

/**
 * Finds tempo, beat grid and level structure (silence, intro, outro) in mono PCM. Pure Kotlin
 * and bounded: a 30 s region at ~12 kHz takes a few thousand small FFTs.
 */
object MixAnalyzer {
    /** Analyses [samples] (mono, -1..1) that start at [startMs] of a track; null if too short or silent. */
    fun analyze(
        samples: FloatArray,
        sampleRate: Int,
        startMs: Long,
        trackDurationMs: Long,
    ): MixAnalysis? {
        val frameLength = sampleRate * ENERGY_FRAME_MS / 1000
        if (frameLength <= 0) return null
        val frameCount = samples.size / frameLength
        if (frameCount < MIN_AUDIBLE_FRAMES) return null

        val power =
            DoubleArray(frameCount) { frame ->
                var sum = 0.0
                for (i in frame * frameLength until (frame + 1) * frameLength) {
                    val s = samples[i].toDouble()
                    sum += s * s
                }
                sum / frameLength
            }
        val audible = BooleanArray(frameCount) { toDb(power[it]) > SILENCE_DB }
        val smoothDb = smoothedDb(power)
        // Levels over ~1 s, so beats and the gaps between them don't skew them
        val audibleDb = smoothDb.filterIndexed { i, _ -> audible[i] }.sorted()
        if (audibleDb.size < MIN_AUDIBLE_FRAMES) return null
        val bodyDb = audibleDb[audibleDb.size * 3 / 4]
        val loudnessDb = audibleDb[audibleDb.size / 2]
        var peak = 0f
        for (s in samples) peak = maxOf(peak, abs(s))

        fun sustained(range: IntRange) = range.all { audible[it] }
        val firstAudible =
            (0..frameCount - SUSTAIN_FRAMES).firstOrNull { sustained(it until it + SUSTAIN_FRAMES) } ?: return null
        val lastAudible =
            (frameCount - 1 downTo firstAudible + SUSTAIN_FRAMES - 1).first {
                sustained(it - SUSTAIN_FRAMES + 1..it)
            }

        val fullLevel = bodyDb - OUTRO_DROP_DB
        val introEnd = (firstAudible..lastAudible).firstOrNull { smoothDb[it] >= fullLevel } ?: firstAudible
        var outroLast = (lastAudible downTo firstAudible).firstOrNull { smoothDb[it] >= fullLevel } ?: lastAudible
        // A fade crosses the threshold late (halfway, for a linear one); follow the decline back to
        // its top, in steps on a slower envelope so the beat's ripple doesn't stop the walk
        val slowDb = smoothedDb(power, FADE_SMOOTH_RADIUS_FRAMES)
        val fadeWalkLimit = (outroLast - MAX_FADE_WALK_FRAMES).coerceAtLeast(firstAudible)
        while (outroLast - FADE_STEP_FRAMES >= fadeWalkLimit &&
            slowDb[outroLast] < bodyDb - FADE_TOP_DB &&
            slowDb[outroLast - FADE_STEP_FRAMES] > slowDb[outroLast] + FADE_MIN_RISE_DB
        ) {
            outroLast -= FADE_STEP_FRAMES
        }

        fun frameMs(frame: Int) = startMs + frame.toLong() * ENERGY_FRAME_MS
        val audioStartMs = frameMs(firstAudible)
        val tempo = estimateTempo(samples, sampleRate, firstAudible * frameLength, (lastAudible + 1) * frameLength)
        val beatPhaseMs: Long
        val firstBeatMs: Long
        if (tempo != null) {
            val phaseMs = startMs + tempo.phaseMs
            beatPhaseMs = phaseMs.roundToLong()
            val beats = ceil((audioStartMs - BEAT_TOLERANCE_MS - phaseMs) / tempo.beatMs)
            firstBeatMs = (phaseMs + beats * tempo.beatMs).roundToLong().coerceAtLeast(startMs)
        } else {
            beatPhaseMs = audioStartMs
            firstBeatMs = audioStartMs
        }

        return MixAnalysis(
            trackDurationMs = trackDurationMs,
            regionStartMs = startMs,
            regionEndMs = startMs + samples.size * 1000L / sampleRate,
            bpm = tempo?.bpm?.toFloat() ?: 0f,
            bpmConfidence = tempo?.confidence?.toFloat() ?: 0f,
            beatPhaseMs = beatPhaseMs,
            firstBeatMs = firstBeatMs,
            audioStartMs = audioStartMs,
            introEndMs = frameMs(introEnd),
            outroStartMs = frameMs(outroLast + 1),
            endOfAudioMs = frameMs(lastAudible + 1),
            loudnessDb = loudnessDb.toFloat(),
            bodyDb = bodyDb.toFloat(),
            peakDb = (20 * log10(peak.toDouble() + 1e-9)).toFloat(),
        )
    }

    /** Peak envelope of [samples] (mono, -1..1) that start at [startMs] of a track. */
    fun waveform(
        samples: FloatArray,
        sampleRate: Int,
        startMs: Long,
    ): MixWaveform {
        val frameLength = (sampleRate * ENERGY_FRAME_MS / 1000).coerceAtLeast(1)
        val levels =
            FloatArray((samples.size + frameLength - 1) / frameLength) { frame ->
                var peak = 0f
                for (i in frame * frameLength until minOf((frame + 1) * frameLength, samples.size)) {
                    peak = maxOf(peak, abs(samples[i]))
                }
                sqrt(peak.coerceAtMost(1f))
            }
        return MixWaveform.encode(startMs, ENERGY_FRAME_MS, levels)
    }

    private class Tempo(
        val bpm: Double,
        val confidence: Double,
        // relative to the start of the analysed samples
        val phaseMs: Double,
    ) {
        val beatMs get() = 60_000.0 / bpm
    }

    /**
     * Autocorrelates a spectral-flux onset envelope over 70-180 BPM. Each candidate is scored on
     * its first few beat multiples, so the double and half tempo score alike; a mild prior
     * around 120 BPM settles those octave ambiguities.
     */
    private fun estimateTempo(
        samples: FloatArray,
        sampleRate: Int,
        from: Int,
        to: Int,
    ): Tempo? {
        val onset = onsetEnvelope(samples, from, to)
        val hopMs = HOP * 1000.0 / sampleRate
        val maxPeriod = 60_000.0 / MIN_BPM / hopMs
        val maxLag = ceil(maxPeriod * HARMONICS).toInt() + 1
        if (onset.size < maxLag * 2) return null

        val mean = onset.average()
        val centered = DoubleArray(onset.size) { onset[it] - mean }
        val acf =
            DoubleArray(maxLag + 1) { lag ->
                var sum = 0.0
                for (t in 0 until centered.size - lag) sum += centered[t] * centered[t + lag]
                sum / (centered.size - lag)
            }
        if (acf[0] <= 1e-12) return null
        for (lag in acf.indices.reversed()) acf[lag] /= acf[0]

        val candidateCount = ((MAX_BPM - MIN_BPM) / BPM_STEP).toInt() + 1
        val scores = DoubleArray(candidateCount)
        var best = 0
        var bestWeighted = Double.NEGATIVE_INFINITY
        for (c in 0 until candidateCount) {
            val bpm = MIN_BPM + c * BPM_STEP
            val period = 60_000.0 / bpm / hopMs
            var score = 0.0
            for (m in 1..HARMONICS) score += interpolate(acf, m * period)
            score /= HARMONICS
            scores[c] = score
            val octaves = log2(bpm / PRIOR_BPM)
            val weighted = score * exp(-0.5 * octaves * octaves / (PRIOR_WIDTH_OCTAVES * PRIOR_WIDTH_OCTAVES))
            if (weighted > bestWeighted) {
                bestWeighted = weighted
                best = c
            }
        }
        val median = scores.sorted()[candidateCount / 2]
        val confidence = ((scores[best] - median) / CONFIDENCE_FULL_SCALE).coerceIn(0.0, 1.0)

        // The ACF peak is only as sharp as one hop; refine period and phase together by fitting
        // the beat grid to the whole (lightly smoothed) onset envelope.
        val smoothOnset =
            DoubleArray(onset.size) { t ->
                val prev = onset[(t - 1).coerceAtLeast(0)]
                val next = onset[(t + 1).coerceAtMost(onset.size - 1)]
                0.25 * prev + 0.5 * onset[t] + 0.25 * next
            }
        val coarsePeriod = 60_000.0 / (MIN_BPM + best * BPM_STEP) / hopMs
        var period = coarsePeriod
        var phase = 0.0
        var bestFit = Double.NEGATIVE_INFINITY
        for (step in -REFINE_STEPS..REFINE_STEPS) {
            val candidate = coarsePeriod * (1 + step * REFINE_STEP)
            val (candidatePhase, fit) = fitPhase(smoothOnset, candidate)
            if (fit > bestFit) {
                bestFit = fit
                period = candidate
                phase = candidatePhase
            }
        }
        val bpm = 60_000.0 / (period * hopMs)
        val phaseMs = (from + phase * HOP + ONSET_LATENCY_SAMPLES) * 1000.0 / sampleRate

        return Tempo(bpm, confidence, phaseMs)
    }

    /** The grid offset (in frames) that collects the most onset strength per beat, and that mean. */
    private fun fitPhase(
        onset: DoubleArray,
        period: Double,
    ): Pair<Double, Double> {
        val means =
            DoubleArray(ceil(period).toInt()) { phase ->
                var sum = 0.0
                var beats = 0
                var position = phase.toDouble()
                while (position < onset.size - 1) {
                    sum += interpolate(onset, position)
                    beats++
                    position += period
                }
                if (beats > 0) sum / beats else 0.0
            }
        val best = means.indices.maxByOrNull { means[it] } ?: 0
        val left = means[(best - 1 + means.size) % means.size]
        val right = means[(best + 1) % means.size]
        val curvature = left - 2 * means[best] + right
        val offset = if (curvature < 0) 0.5 * (left - right) / curvature else 0.0
        return (best + offset) to means[best]
    }

    /** Half-wave rectified spectral flux of log-compressed spectra, minus its local mean. */
    private fun onsetEnvelope(
        samples: FloatArray,
        from: Int,
        to: Int,
    ): DoubleArray {
        val frames = ((to - from - FFT_SIZE) / HOP + 1).coerceAtLeast(0)
        val flux = DoubleArray(frames)
        val windowed = DoubleArray(FFT_SIZE)
        var previous: DoubleArray? = null
        for (f in 0 until frames) {
            val offset = from + f * HOP
            for (i in 0 until FFT_SIZE) windowed[i] = samples[offset + i] * HANN[i]
            val spectrum = computeRfft(windowed)
            for (k in spectrum.indices) spectrum[k] = ln(1.0 + LOG_COMPRESSION * spectrum[k])
            previous?.let { prev ->
                var sum = 0.0
                // Skip DC
                for (k in 1 until spectrum.size) {
                    val rise = spectrum[k] - prev[k]
                    if (rise > 0) sum += rise
                }
                flux[f] = sum
            }
            previous = spectrum
        }

        val onset = DoubleArray(frames)
        var windowSum = 0.0
        var windowStart = 0
        var windowEnd = 0
        for (f in 0 until frames) {
            while (windowEnd < frames && windowEnd <= f + LOCAL_MEAN_FRAMES) windowSum += flux[windowEnd++]
            while (windowStart < f - LOCAL_MEAN_FRAMES) windowSum -= flux[windowStart++]
            val localMean = windowSum / (windowEnd - windowStart)
            onset[f] = (flux[f] - localMean).coerceAtLeast(0.0)
        }
        return onset
    }

    private fun smoothedDb(
        power: DoubleArray,
        radius: Int = SMOOTH_RADIUS_FRAMES,
    ): DoubleArray {
        val result = DoubleArray(power.size)
        for (i in power.indices) {
            val start = (i - radius).coerceAtLeast(0)
            val end = (i + radius).coerceAtMost(power.size - 1)
            var sum = 0.0
            for (j in start..end) sum += power[j]
            result[i] = toDb(sum / (end - start + 1))
        }
        return result
    }

    private fun interpolate(
        values: DoubleArray,
        position: Double,
    ): Double {
        val index = floor(position).toInt()
        if (index < 0) return values.first()
        if (index >= values.size - 1) return values.last()
        val fraction = position - index
        return values[index] * (1 - fraction) + values[index + 1] * fraction
    }

    private fun toDb(power: Double) = 10 * log10(power + 1e-12)

    private const val ENERGY_FRAME_MS = 50
    private const val SILENCE_DB = -50.0
    private const val SUSTAIN_FRAMES = 6
    private const val MIN_AUDIBLE_FRAMES = 20
    private const val SMOOTH_RADIUS_FRAMES = 10

    // Below the full level by this much counts as a fade or quiet ending
    private const val OUTRO_DROP_DB = 6.0
    private const val FADE_TOP_DB = 1.5
    private const val FADE_SMOOTH_RADIUS_FRAMES = 20
    private const val FADE_STEP_FRAMES = 10
    private const val FADE_MIN_RISE_DB = 0.2
    private const val MAX_FADE_WALK_FRAMES = 300

    private const val FFT_SIZE = 512
    private const val HOP = 128
    private const val LOG_COMPRESSION = 1e4
    private const val LOCAL_MEAN_FRAMES = 12

    // A transient shows up in the flux once it enters the window, i.e. before the window's centre
    private const val ONSET_LATENCY_SAMPLES = FFT_SIZE * 5 / 8

    private const val MIN_BPM = 70.0
    private const val MAX_BPM = 180.0
    private const val BPM_STEP = 0.05
    private const val HARMONICS = 4
    private const val PRIOR_BPM = 120.0
    private const val PRIOR_WIDTH_OCTAVES = 1.0
    private const val CONFIDENCE_FULL_SCALE = 0.3
    private const val BEAT_TOLERANCE_MS = 40.0
    private const val REFINE_STEPS = 20
    private const val REFINE_STEP = 0.0005

    private val HANN = DoubleArray(FFT_SIZE) { i -> 0.5 * (1 - cos(2 * PI * i / FFT_SIZE)) }
}
