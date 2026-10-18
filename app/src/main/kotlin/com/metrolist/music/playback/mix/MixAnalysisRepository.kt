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

    suspend fun analysis(
        mediaId: String,
        region: MixRegion,
        durationHintMs: Long,
    ): Outcome =
        withContext(dispatcher) {
            store.get(mediaId, region)?.let { return@withContext Outcome.Ready(it) }
            val key = "${region.name}:$mediaId"
            if (key in unavailable) return@withContext Outcome.Unavailable
            when (val decoded = decoder.decode(mediaId, region == MixRegion.TAIL, REGION_MS, durationHintMs)) {
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
                    val pcm = decoded.pcm
                    val analysis = MixAnalyzer.analyze(pcm.samples, pcm.sampleRate, pcm.startMs, pcm.trackDurationMs)
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

    fun release() = dispatcher.close()

    private fun markUnavailable(key: String) {
        if (unavailable.size >= MAX_UNAVAILABLE) unavailable.clear()
        unavailable += key
    }

    companion object {
        /** Length of the analysed start and end of a song. */
        const val REGION_MS = 30_000L
        private const val STORE_FILE = "mix_analysis.json"
        private const val MAX_UNAVAILABLE = 200
        private const val MAX_CACHE_MISSES = 2
    }
}
