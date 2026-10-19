/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

import android.content.Context
import android.os.Process
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Song analyses for smart mix: from the on-disk cache, or decoded from cached audio and analysed
 * on a single low-priority thread. Nothing is downloaded for it.
 */
@UnstableApi
class MixAnalysisRepository(
    context: Context,
    caches: List<Cache>,
) {
    sealed interface Outcome {
        class Ready(
            val analysis: MixAnalysis,
        ) : Outcome

        /** The audio isn't cached yet; asking again later may succeed. */
        data object Pending : Outcome

        /** This region can't be analysed. */
        data object Unavailable : Outcome
    }

    private val store = MixAnalysisStore(File(context.noBackupFilesDir, STORE_FILE))
    private val decoder = MixAudioDecoder(caches)
    private val dispatcher =
        Executors
            .newSingleThreadExecutor { runnable ->
                Thread(
                    {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        runnable.run()
                    },
                    "MixAnalysis",
                )
            }.asCoroutineDispatcher()

    // Confined to the analysis thread: regions that failed to decode or analyse in this session,
    // and how often decoding a region ran into uncached data
    private val unavailable = HashSet<String>()
    private val cacheMisses = HashMap<String, Int>()

    /**
     * @param withWaveform whether a stored analysis without a [MixAnalysis.waveform] (from an
     *   older version) should get one, if the audio is cached.
     */
    suspend fun analysis(
        mediaId: String,
        region: MixRegion,
        durationHintMs: Long,
        withWaveform: Boolean = false,
    ): Outcome =
        withContext(dispatcher) {
            val stored = store.get(mediaId, region)
            if (stored != null && (stored.waveform != null || !withWaveform)) return@withContext Outcome.Ready(stored)
            val key = "${region.name}:$mediaId"
            if (stored != null) {
                if (key in unavailable) return@withContext Outcome.Ready(stored)
                val decoded = decode(mediaId, region, durationHintMs)
                if (decoded !is MixAudioDecoder.Result.Decoded) {
                    // Not cached may change later; anything else would fail again
                    if (decoded != MixAudioDecoder.Result.NotCached) markUnavailable(key)
                    return@withContext Outcome.Ready(stored)
                }
                val pcm = decoded.pcm
                // Only the waveform is added, so plans made from the stored analysis stay the same
                val updated = stored.copy(waveform = MixAnalyzer.waveform(pcm.samples, pcm.sampleRate, pcm.startMs))
                store.put(mediaId, region, updated)
                return@withContext Outcome.Ready(updated)
            }
            if (key in unavailable) return@withContext Outcome.Unavailable
            when (val decoded = decode(mediaId, region, durationHintMs)) {
                MixAudioDecoder.Result.NotCached -> Outcome.Pending
                // Retried once, as more may be cached by then; decoding is too costly to keep trying
                MixAudioDecoder.Result.CacheMiss -> {
                    val misses = (cacheMisses[key] ?: 0) + 1
                    if (misses >= MAX_CACHE_MISSES) {
                        cacheMisses.remove(key)
                        markUnavailable(key)
                        Outcome.Unavailable
                    } else {
                        if (cacheMisses.size >= MAX_UNAVAILABLE) cacheMisses.clear()
                        cacheMisses[key] = misses
                        Outcome.Pending
                    }
                }
                MixAudioDecoder.Result.Failed -> {
                    markUnavailable(key)
                    Outcome.Unavailable
                }
                is MixAudioDecoder.Result.Decoded -> {
                    val analysis = analyze(decoded.pcm, region)
                    if (analysis == null) {
                        markUnavailable(key)
                        Outcome.Unavailable
                    } else {
                        store.put(mediaId, region, analysis)
                        Outcome.Ready(analysis)
                    }
                }
            }
        }

    /**
     * Decodes [WAVEFORM_MS] for the waveform; the start of a song may only be cached a little past
     * the analysed region, which is enough for the analysis alone.
     */
    private suspend fun decode(
        mediaId: String,
        region: MixRegion,
        durationHintMs: Long,
    ): MixAudioDecoder.Result {
        val fromEnd = region == MixRegion.TAIL
        val result = decoder.decode(mediaId, fromEnd, WAVEFORM_MS, durationHintMs)
        if (fromEnd || result is MixAudioDecoder.Result.Decoded || result == MixAudioDecoder.Result.Failed) return result
        return decoder.decode(mediaId, false, REGION_MS, durationHintMs)
    }

    /** Analyses the [REGION_MS] at the start or end of [pcm]; the waveform covers all of it. */
    private fun analyze(
        pcm: DecodedPcm,
        region: MixRegion,
    ): MixAnalysis? {
        val regionSamples = (REGION_MS * pcm.sampleRate / 1000).toInt()
        val from =
            if (region == MixRegion.TAIL) {
                ((pcm.trackDurationMs - REGION_MS - pcm.startMs) * pcm.sampleRate / 1000).toInt().coerceIn(0, pcm.samples.size)
            } else {
                0
            }
        val to = if (region == MixRegion.TAIL) pcm.samples.size else minOf(regionSamples, pcm.samples.size)
        val samples = if (from == 0 && to == pcm.samples.size) pcm.samples else pcm.samples.copyOfRange(from, to)
        return MixAnalyzer
            .analyze(samples, pcm.sampleRate, pcm.startMs + from * 1000L / pcm.sampleRate, pcm.trackDurationMs)
            ?.copy(waveform = MixAnalyzer.waveform(pcm.samples, pcm.sampleRate, pcm.startMs))
    }

    fun release() = dispatcher.close()

    private fun markUnavailable(key: String) {
        if (unavailable.size >= MAX_UNAVAILABLE) unavailable.clear()
        unavailable += key
    }

    companion object {
        /** Length of the analysed start and end of a song. */
        const val REGION_MS = 30_000L

        /** Length of the start and end of a song kept as a waveform. */
        const val WAVEFORM_MS = 45_000L
        private const val STORE_FILE = "mix_analysis.json"
        private const val MAX_UNAVAILABLE = 200
        private const val MAX_CACHE_MISSES = 2
    }
}
