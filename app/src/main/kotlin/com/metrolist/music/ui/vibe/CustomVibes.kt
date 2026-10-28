/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.vibe

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID

/** The user's own vibes in a small JSON file; changes are published to [vibes] first, then written in the background. */
object CustomVibes {
    const val FILE_NAME = "vibes.json"
    private const val ID_PREFIX = "custom_"
    private const val TAG = "CustomVibes"

    // Unknown fields (from a newer version) are ignored, unknown enum values fall back to defaults
    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _vibes = MutableStateFlow<List<Vibe>>(emptyList())
    val vibes: StateFlow<List<Vibe>> = _vibes.asStateFlow()

    @Volatile
    private var file: File? = null

    fun initialize(context: Context) {
        if (file != null) return
        synchronized(this) {
            if (file != null) return
            val f = File(context.applicationContext.filesDir, FILE_NAME)
            if (f.exists()) {
                val stored = runCatching { f.readText() }.getOrNull()?.let(::decode)
                if (stored == null) Timber.tag(TAG).w("Discarding unreadable custom vibes")
                _vibes.value = stored.orEmpty()
            }
            file = f
        }
    }

    fun newId() = ID_PREFIX + UUID.randomUUID()

    fun isCustom(id: String) = id.startsWith(ID_PREFIX)

    fun save(context: Context, vibe: Vibe) {
        initialize(context)
        _vibes.update { list ->
            if (list.any { it.id == vibe.id }) list.map { if (it.id == vibe.id) vibe else it } else list + vibe
        }
        io.launch { write() }
    }

    fun delete(context: Context, id: String) {
        initialize(context)
        _vibes.update { list -> list.filterNot { it.id == id } }
        io.launch { write() }
    }

    /** The stored vibes as the JSON a backup holds. */
    fun export(context: Context): String {
        initialize(context)
        return json.encodeToString(_vibes.value)
    }

    /** Replaces all custom vibes with those of a backup and writes them right away; false if [text] can't be read. */
    fun restore(context: Context, text: String): Boolean {
        initialize(context)
        _vibes.value = decode(text) ?: return false
        write()
        return true
    }

    private fun decode(text: String): List<Vibe>? =
        try {
            json.decodeFromString<List<Vibe>>(text).filter { isCustom(it.id) }.distinctBy { it.id }
        } catch (e: Exception) {
            null
        }

    @Synchronized
    private fun write() {
        val target = file ?: return
        try {
            val temp = File(target.path + ".tmp")
            temp.writeText(json.encodeToString(_vibes.value))
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) temp.delete()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to save custom vibes")
        }
    }
}
