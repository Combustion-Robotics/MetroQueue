/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

import android.content.Context
import com.metrolist.music.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's custom transitions, keyed by [CustomTransition.pairKey]. The file is read once in the
 * background; all file access runs in order on one background worker, and changes are published
 * to [transitions] before they are written.
 */
@Singleton
class CustomTransitionRepository @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val store = CustomTransitionStore(File(context.filesDir, CustomTransitionStore.FILE_NAME))
    private val io = Dispatchers.IO.limitedParallelism(1)
    private var loaded = false

    private val _transitions = MutableStateFlow<Map<String, CustomTransition>>(emptyMap())
    val transitions: StateFlow<Map<String, CustomTransition>> = _transitions.asStateFlow()

    init {
        scope.launch(io) { ensureLoaded() }
    }

    fun get(
        fromId: String,
        toId: String,
    ): CustomTransition? = _transitions.value[CustomTransition.pairKey(fromId, toId)]

    fun save(transition: CustomTransition): Job =
        scope.launch(io) {
            val map = LinkedHashMap(ensureLoaded())
            // Re-inserted, so the most recently edited are kept when the cap is reached
            map.remove(transition.key)
            map[transition.key] = transition
            val iterator = map.keys.iterator()
            while (map.size > CustomTransitionStore.MAX_TRANSITIONS && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
            publish(map)
        }

    fun remove(
        fromId: String,
        toId: String,
    ): Job =
        scope.launch(io) {
            val key = CustomTransition.pairKey(fromId, toId)
            val current = ensureLoaded()
            if (key in current) publish(LinkedHashMap(current).apply { remove(key) })
        }

    /** The stored transitions as the JSON a backup holds. */
    suspend fun exportJson(): String =
        withContext(io) {
            CustomTransitionStore.encode(ensureLoaded().values)
        }

    /** Replaces all transitions with those of a backup; returns false (keeping the current ones) if it can't be read. */
    suspend fun restore(json: String): Boolean =
        withContext(io) {
            val restored = CustomTransitionStore.decode(json) ?: return@withContext false
            loaded = true
            publish(restored.associateByTo(LinkedHashMap()) { it.key })
            true
        }

    private fun ensureLoaded(): Map<String, CustomTransition> {
        if (!loaded) {
            _transitions.value = store.read().associateByTo(LinkedHashMap()) { it.key }
            loaded = true
        }
        return _transitions.value
    }

    private fun publish(map: Map<String, CustomTransition>) {
        _transitions.value = map
        store.write(map.values)
    }
}
