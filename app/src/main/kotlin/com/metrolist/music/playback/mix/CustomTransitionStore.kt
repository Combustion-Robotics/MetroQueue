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
 * A transition the user set up from one song into another; it applies wherever the two play one
 * after the other. Positions are absolute positions in the respective song.
 *
 * @param outgoingEndMs where the outgoing song has faded out (for a cut: where it switches);
 *   null is where its audio ends.
 * @param incomingStartMs where the incoming song starts; null skips its silent intro.
 * @param tempoMatch whether the incoming song may play slightly faster or slower during the
 *   overlap when both tempos are known and close.
 */
@Serializable
data class CustomTransition(
    val fromId: String,
    val toId: String,
    val style: TransitionStyle = TransitionStyle.FADE,
    val durationMs: Long = DEFAULT_DURATION_MS,
    val outgoingEndMs: Long? = null,
    val incomingStartMs: Long? = null,
    val tempoMatch: Boolean = true,
) {
    val key: String get() = pairKey(fromId, toId)

    companion object {
        const val MIN_DURATION_MS = 1_000L
        const val MAX_DURATION_MS = 16_000L
        const val DEFAULT_DURATION_MS = 5_000L

        fun pairKey(
            fromId: String,
            toId: String,
        ) = "$fromId\n$toId"
    }
}

/**
 * The custom transitions in a small JSON file, written atomically via a temporary file. Not
 * thread-safe; call it from one background thread.
 */
class CustomTransitionStore(
    private val file: File,
) {
    @Serializable
    private class StoreFile(
        val version: Int = VERSION,
        val transitions: List<CustomTransition> = emptyList(),
    )

    fun read(): List<CustomTransition> {
        if (!file.exists()) return emptyList()
        val transitions = runCatching { file.readText() }.getOrNull()?.let(::decode)
        if (transitions == null) Timber.tag(TAG).w("Discarding unreadable custom transitions")
        return transitions.orEmpty()
    }

    fun write(transitions: Collection<CustomTransition>) {
        try {
            val temp = File(file.path + ".tmp")
            temp.writeText(encode(transitions))
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) temp.delete()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to save custom transitions")
        }
    }

    companion object {
        const val FILE_NAME = "mix_transitions.json"

        /** More than anyone sets up by hand; the oldest are dropped beyond it. */
        const val MAX_TRANSITIONS = 2_000

        private const val TAG = "CustomTransitionStore"
        private const val VERSION = 1

        // Unknown fields and styles (from a newer version) fall back to their defaults
        private val json =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            }

        fun encode(transitions: Collection<CustomTransition>): String =
            json.encodeToString(StoreFile.serializer(), StoreFile(VERSION, transitions.toList()))

        /** The stored transitions, one per pair (the last one wins), or null if [text] isn't such a file. */
        fun decode(text: String): List<CustomTransition>? =
            try {
                json
                    .decodeFromString(StoreFile.serializer(), text)
                    .transitions
                    .filter { it.fromId.isNotBlank() && it.toId.isNotBlank() }
                    .associateBy { it.key }
                    .values
                    .toList()
                    .takeLast(MAX_TRANSITIONS)
            } catch (e: Exception) {
                null
            }
    }
}
