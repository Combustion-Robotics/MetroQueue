/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Least-recently-used cache of song analyses in a small JSON file, keyed by song id and region.
 * Loaded on first use and rewritten (atomically, via a temporary file) after each new entry;
 * call it from a background thread.
 */
class MixAnalysisStore(
    private val file: File,
) {
    @Serializable
    private class StoreFile(
        val version: Int,
        val entries: List<Entry>,
    )

    @Serializable
    private class Entry(
        val key: String,
        val analysis: MixAnalysis,
    )

    // Access-ordered, so iteration starts at the least recently used entry
    private var entries: LinkedHashMap<String, MixAnalysis>? = null

    @Synchronized
    fun get(
        mediaId: String,
        region: MixRegion,
    ): MixAnalysis? = load()[key(mediaId, region)]

    @Synchronized
    fun put(
        mediaId: String,
        region: MixRegion,
        analysis: MixAnalysis,
    ) {
        val map = load()
        map[key(mediaId, region)] = analysis
        val iterator = map.entries.iterator()
        while (map.size > MAX_ENTRIES && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
        save(map)
    }

    private fun load(): LinkedHashMap<String, MixAnalysis> {
        entries?.let { return it }
        val map = LinkedHashMap<String, MixAnalysis>(16, 0.75f, true)
        try {
            if (file.exists()) {
                val stored = json.decodeFromString(StoreFile.serializer(), file.readText())
                // Analyses from another version of the analyzer may not match what it finds now
                if (stored.version == VERSION) stored.entries.forEach { map[it.key] = it.analysis }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Discarding unreadable mix analysis cache")
        }
        entries = map
        return map
    }

    private fun save(map: LinkedHashMap<String, MixAnalysis>) {
        try {
            val temp = File(file.path + ".tmp")
            temp.writeText(json.encodeToString(StoreFile.serializer(), StoreFile(VERSION, map.map { Entry(it.key, it.value) })))
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) temp.delete()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to save mix analysis cache")
        }
    }

    private fun key(
        mediaId: String,
        region: MixRegion,
    ) = "${region.name}:$mediaId"

    private companion object {
        const val TAG = "MixAnalysisStore"
        const val VERSION = 1
        const val MAX_ENTRIES = 500
        val json = Json { ignoreUnknownKeys = true }
    }
}
