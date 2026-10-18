/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CustomTransitionStoreTest {
    private val transitions =
        listOf(
            CustomTransition("a", "b", TransitionStyle.ECHO, 8_000L, outgoingEndMs = 200_500L, incomingStartMs = 1_250L, tempoMatch = false),
            CustomTransition("b", "c", TransitionStyle.CUT, 0L),
        )

    @Test
    fun `round trip through the file keeps every field`() {
        val dir = Files.createTempDirectory("mix").toFile()
        try {
            val file = File(dir, CustomTransitionStore.FILE_NAME)
            CustomTransitionStore(file).write(transitions)

            assertEquals(transitions, CustomTransitionStore(file).read())
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing or unreadable file reads as empty`() {
        val dir = Files.createTempDirectory("mix").toFile()
        try {
            val file = File(dir, CustomTransitionStore.FILE_NAME)
            assertEquals(emptyList<CustomTransition>(), CustomTransitionStore(file).read())
            file.writeText("not json")
            assertEquals(emptyList<CustomTransition>(), CustomTransitionStore(file).read())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `decode keeps one transition per pair and tolerates newer files`() {
        val text =
            """
            {"version":2,"extra":1,"transitions":[
              {"fromId":"a","toId":"b","style":"FADE","durationMs":4000},
              {"fromId":"a","toId":"b","style":"SPIN","durationMs":6000,"newField":true},
              {"fromId":"","toId":"b"}
            ]}
            """.trimIndent()

        val decoded = CustomTransitionStore.decode(text)!!

        assertEquals(listOf(CustomTransition("a", "b", TransitionStyle.FADE, 6_000L)), decoded)
        assertNull(CustomTransitionStore.decode("[]"))
    }
}
