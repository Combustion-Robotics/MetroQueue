/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicalKeyTest {
    @Test
    fun `keys map onto the Camelot wheel`() {
        assertEquals("8B", major(0).camelotCode)
        assertEquals("8A", minor(9).camelotCode)
        assertEquals("9B", major(7).camelotCode)
        assertEquals("7B", major(5).camelotCode)
        assertEquals("1B", major(11).camelotCode)
        assertEquals("12B", major(4).camelotCode)
        assertEquals("5A", minor(0).camelotCode)
        assertEquals("11A", minor(6).camelotCode)
        assertEquals("F♯m", minor(6).name)
        assertEquals("E♭", major(3).name)
    }

    @Test
    fun `harmony follows the Camelot rules`() {
        // 8B against: itself, its relative minor, a fifth up and down
        assertEquals(Harmony.COMPATIBLE, major(0).harmonyWith(major(0)))
        assertEquals(Harmony.COMPATIBLE, major(0).harmonyWith(minor(9)))
        assertEquals(Harmony.COMPATIBLE, major(0).harmonyWith(major(7)))
        assertEquals(Harmony.COMPATIBLE, major(0).harmonyWith(major(5)))
        // 12B -> 1B wraps around
        assertEquals(Harmony.COMPATIBLE, major(4).harmonyWith(major(11)))
        // Two steps (8B -> 10B) and diagonal (8B -> 9A)
        assertEquals(Harmony.NEUTRAL, major(0).harmonyWith(major(2)))
        assertEquals(Harmony.NEUTRAL, major(0).harmonyWith(minor(4)))
        // A semitone, a tritone, the parallel minor
        assertEquals(Harmony.CLASH, major(0).harmonyWith(major(1)))
        assertEquals(Harmony.CLASH, major(0).harmonyWith(major(6)))
        assertEquals(Harmony.CLASH, major(0).harmonyWith(minor(0)))
    }

    private fun major(tonic: Int) = MusicalKey(tonic, minor = false, confidence = 1f)

    private fun minor(tonic: Int) = MusicalKey(tonic, minor = true, confidence = 1f)
}
