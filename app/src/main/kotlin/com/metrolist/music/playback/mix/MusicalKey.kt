/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** How well two keys sound together while they overlap. */
enum class Harmony {
    /** Same key, relative major/minor, or a fifth apart (same letter, neighbouring Camelot numbers). */
    COMPATIBLE,

    /** Two steps around the wheel, or a neighbouring number in the other mode: works, with some tension. */
    NEUTRAL,
    CLASH,
}

/**
 * A musical key: [tonic] as a pitch class (0 = C … 11 = B), major or minor, and how clearly the
 * analysed audio is in it (0..1).
 */
@Serializable
data class MusicalKey(
    val tonic: Int,
    val minor: Boolean,
    val confidence: Float,
) {
    val isConfident: Boolean
        get() = confidence >= CONFIDENT_KEY

    /** Position on the Camelot wheel, 1..12 (C major = 8B, A minor = 8A); neighbours are a fifth apart. */
    val camelotNumber: Int
        get() {
            val relativeMajor = if (minor) (tonic + 3) % 12 else tonic
            return (relativeMajor * 7 + 7) % 12 + 1
        }

    val camelotCode: String
        get() = "$camelotNumber${if (minor) 'A' else 'B'}"

    /** e.g. "E♭" or "F♯m". */
    val name: String
        get() = if (minor) MINOR_NAMES[tonic] + "m" else MAJOR_NAMES[tonic]

    fun harmonyWith(other: MusicalKey): Harmony {
        val steps = abs(camelotNumber - other.camelotNumber).let { minOf(it, 12 - it) }
        val sameMode = minor == other.minor
        return when {
            steps == 0 || (steps == 1 && sameMode) -> Harmony.COMPATIBLE
            steps == 1 || (steps == 2 && sameMode) -> Harmony.NEUTRAL
            else -> Harmony.CLASH
        }
    }

    companion object {
        const val CONFIDENT_KEY = 0.5f
        private val MAJOR_NAMES = arrayOf("C", "D♭", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        private val MINOR_NAMES = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B")
    }
}
