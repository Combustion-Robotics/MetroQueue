/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.IOException
import java.nio.ByteOrder

/** Mono PCM in -1..1, starting at [startMs] of a track that is [trackDurationMs] long. */
class DecodedPcm(
    val samples: FloatArray,
    val sampleRate: Int,
    val startMs: Long,
    val trackDurationMs: Long,
)

/**
 * Decodes the start or the end of a song for analysis, from the download or player cache only:
 * nothing is fetched from the network. The audio is downmixed to mono and decimated to about
 * 11-16 kHz while decoding, so memory stays at a few MB for a 30 s region.
 */
@UnstableApi
class MixAudioDecoder(
    // In lookup order: a download is complete, the player cache may only hold parts
    private val caches: List<Cache>,
) {
    sealed interface Result {
        class Decoded(
            val pcm: DecodedPcm,
        ) : Result

        /** Not (fully) cached yet; may succeed later. */
        data object NotCached : Result

        /** Can't be decoded; retrying won't help. */
        data object Failed : Result
    }

    /**
     * Decodes [lengthMs] from the start of [mediaId], or up to its end when [fromEnd].
     * [durationHintMs] (or <= 0 if unknown) is used before the container reports a duration.
     * Blocking; call it from a background thread.
     */
    suspend fun decode(
        mediaId: String,
        fromEnd: Boolean,
        lengthMs: Long,
        durationHintMs: Long,
    ): Result {
        val (cache, contentLength) = findCache(mediaId, fromEnd, lengthMs, durationHintMs) ?: return Result.NotCached
        val source = CacheMediaDataSource(cache, mediaId, contentLength)
        var extractorToRelease: MediaExtractor? = null
        var codecToRelease: MediaCodec? = null
        try {
            val extractor = MediaExtractor().also { extractorToRelease = it }
            extractor.setDataSource(source)
            val trackIndex =
                (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return Result.Failed
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return Result.Failed
            extractor.selectTrack(trackIndex)

            val durationMs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION) / 1000
                } else {
                    durationHintMs
                }
            if (durationMs <= 0) return Result.Failed
            val regionStartUs = if (fromEnd) (durationMs - lengthMs).coerceAtLeast(0L) * 1000 else 0L
            if (regionStartUs > 0) extractor.seekTo(regionStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val codec = MediaCodec.createDecoderByType(mime).also { codecToRelease = it }
            codec.configure(format, null, null, 0)
            codec.start()

            val collector =
                PcmCollector(
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    lengthMs + if (fromEnd) TAIL_SLACK_MS else 0L,
                )
            val info = MediaCodec.BufferInfo()
            val startedAt = System.nanoTime()
            var inputDone = false
            var anchorUs = C.TIME_UNSET
            while (true) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() - startedAt > MAX_DECODE_NANOS) return Result.Failed

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex) ?: return Result.Failed
                        val size = extractor.readSampleData(inBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!collector.setFormat(codec.outputFormat)) return Result.Failed
                    continue
                }
                if (outIndex < 0) continue
                val endOfStream = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                val buffer = codec.getOutputBuffer(outIndex)
                // A seek lands on the sync sample before the region; drop what ends before it
                val bufferEndUs = info.presentationTimeUs + collector.durationUs(info.size)
                if (buffer != null && info.size > 0 && bufferEndUs > regionStartUs) {
                    if (anchorUs == C.TIME_UNSET) anchorUs = info.presentationTimeUs
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    if (!collector.add(buffer)) {
                        codec.releaseOutputBuffer(outIndex, false)
                        // The tail is longer than the container said; its timing can't be trusted
                        if (fromEnd) return Result.Failed
                        break
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (endOfStream) break
                if (!fromEnd && collector.collectedMs() >= lengthMs) break
            }
            // A read the cache couldn't serve ends the stream early; that region isn't usable
            if (source.readFailed) return Result.NotCached
            if (anchorUs == C.TIME_UNSET || collector.count == 0) return Result.Failed
            return Result.Decoded(collector.toPcm(anchorUs / 1000, durationMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (source.readFailed) return Result.NotCached
            Timber.tag(TAG).w(e, "Decoding %s for mix analysis failed", mediaId)
            return Result.Failed
        } finally {
            codecToRelease?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            extractorToRelease?.let { runCatching { it.release() } }
            source.close()
        }
    }

    private fun findCache(
        mediaId: String,
        fromEnd: Boolean,
        lengthMs: Long,
        durationHintMs: Long,
    ): Pair<Cache, Long>? {
        for (cache in caches) {
            val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(mediaId))
            if (contentLength <= 0) continue
            // Seeking into the tail needs the container index, which may sit anywhere: require the
            // whole file. The head needs the bytes up to the region end (estimated, with slack).
            val requiredBytes =
                if (fromEnd || durationHintMs <= 0) {
                    contentLength
                } else {
                    (contentLength * (lengthMs + HEAD_SLACK_MS) / durationHintMs + HEADER_SLACK_BYTES)
                        .coerceAtMost(contentLength)
                }
            if (cache.isCached(mediaId, 0, requiredBytes)) return cache to contentLength
        }
        return null
    }

    private class PcmCollector(
        private var sampleRate: Int,
        private var channelCount: Int,
        private val maxMs: Long,
    ) {
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var factor = decimationFactor(sampleRate)
        private var samples = FloatArray(capacity())
        var count = 0
            private set
        private var sum = 0f
        private var summed = 0

        private fun capacity() = (maxMs * (sampleRate / factor) / 1000).toInt() + 1

        fun setFormat(format: MediaFormat): Boolean {
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val pcmEncoding =
                if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
            if (channels <= 0 || rate <= 0) return false
            if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT && pcmEncoding != AudioFormat.ENCODING_PCM_FLOAT) return false
            if (rate != sampleRate) {
                // The rate must not change once samples are collected, or their timing breaks
                if (count > 0) return false
                sampleRate = rate
                factor = decimationFactor(rate)
                samples = FloatArray(capacity())
            }
            channelCount = channels
            encoding = pcmEncoding
            return true
        }

        fun durationUs(bytes: Int): Long {
            val bytesPerFrame = channelCount * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            return bytes / bytesPerFrame * 1_000_000L / sampleRate
        }

        fun collectedMs(): Long = count * 1000L / (sampleRate / factor)

        /** Returns false once [maxMs] is collected. */
        fun add(buffer: java.nio.ByteBuffer): Boolean {
            buffer.order(ByteOrder.nativeOrder())
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val floats = buffer.asFloatBuffer()
                while (floats.remaining() >= channelCount) {
                    var mono = 0f
                    repeat(channelCount) { mono += floats.get() }
                    if (!push(mono / channelCount)) return false
                }
            } else {
                val shorts = buffer.asShortBuffer()
                while (shorts.remaining() >= channelCount) {
                    var mono = 0f
                    repeat(channelCount) { mono += shorts.get() }
                    if (!push(mono / (channelCount * PCM16_SCALE))) return false
                }
            }
            return true
        }

        // Averaging each group of input samples is a crude low-pass, enough for level and onset analysis
        private fun push(value: Float): Boolean {
            sum += value
            if (++summed < factor) return true
            if (count == samples.size) return false
            samples[count++] = sum / factor
            sum = 0f
            summed = 0
            return true
        }

        fun toPcm(
            startMs: Long,
            trackDurationMs: Long,
        ) = DecodedPcm(samples.copyOf(count), sampleRate / factor, startMs, trackDurationMs)

        private fun decimationFactor(rate: Int) = (rate / TARGET_SAMPLE_RATE).coerceAtLeast(1)
    }

    /** Positioned reads over a cache-only [CacheDataSource]; a cache miss fails the read. */
    private class CacheMediaDataSource(
        cache: Cache,
        private val mediaId: String,
        private val length: Long,
    ) : MediaDataSource() {
        private val dataSource =
            CacheDataSource
                .Factory()
                .setCache(cache)
                .setCacheWriteDataSinkFactory(null)
                .createDataSource()

        // Position of the next byte the open data source returns, or -1 when closed
        private var openPosition = -1L

        @Volatile var readFailed = false
            private set

        // MediaExtractor may call back from a binder thread
        @Synchronized
        override fun readAt(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            size: Int,
        ): Int {
            if (position >= length) return -1
            if (size == 0) return 0
            try {
                if (position != openPosition) {
                    closeSource()
                    dataSource.open(
                        DataSpec
                            .Builder()
                            .setUri(mediaId)
                            .setKey(mediaId)
                            .setPosition(position)
                            .build(),
                    )
                    openPosition = position
                }
                val read = dataSource.read(buffer, offset, size)
                if (read == C.RESULT_END_OF_INPUT) {
                    closeSource()
                    return -1
                }
                openPosition += read
                return read
            } catch (e: IOException) {
                readFailed = true
                closeSource()
                throw e
            }
        }

        override fun getSize(): Long = length

        @Synchronized
        override fun close() = closeSource()

        private fun closeSource() {
            if (openPosition < 0) return
            openPosition = -1L
            runCatching { dataSource.close() }
        }
    }

    private companion object {
        const val TAG = "MixAudioDecoder"
        const val TARGET_SAMPLE_RATE = 11_025
        const val PCM16_SCALE = 32768f
        const val CODEC_TIMEOUT_US = 10_000L
        const val MAX_DECODE_NANOS = 10_000_000_000L
        const val HEAD_SLACK_MS = 10_000L
        const val HEADER_SLACK_BYTES = 256 * 1024L

        // A seek may land a little before the tail region
        const val TAIL_SLACK_MS = 5_000L
    }
}
