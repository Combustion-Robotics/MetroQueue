package com.metrolist.music.ui.vibe.scene

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneRendererTest {
    @Test
    fun `every combination renders opaque and deterministic in both orientations`() {
        for (sky in Sky.entries) for (layout in Layout.entries) for (fg in Foreground.entries) {
            val spec = SceneSpec(sky, layout, fg, Particle.entries.toSet(), snowy = layout == Layout.Forest)
            for ((w, h) in listOf(320 to 180, 180 to 390)) {
                val a = SceneRenderer(spec, w, h)
                val b = SceneRenderer(spec, w, h)
                for (t in doubleArrayOf(0.0, 7.1, 33.0)) {
                    a.time = t
                    b.time = t
                    a.render()
                    b.render()
                    assertArrayEquals(a.pixels, b.pixels)
                    assertTrue(a.pixels.all { it ushr 24 == 0xFF })
                }
            }
        }
    }

    @Test
    fun `spec survives json and missing fields fall back to defaults`() {
        val json = Json { ignoreUnknownKeys = true }
        val spec = VibeScenes.starryLake
        assertEquals(spec, json.decodeFromString(SceneSpec.serializer(), json.encodeToString(SceneSpec.serializer(), spec)))
        assertEquals(SceneSpec(), json.decodeFromString(SceneSpec.serializer(), "{}"))
    }
}
