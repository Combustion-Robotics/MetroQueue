/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSkipPolicyTest {
    @Test
    fun `first skip blends`() {
        assertTrue(ManualSkipPolicy.shouldBlend(nowMs = 10_000L, lastSkipAtMs = null, transitionRunning = false))
    }

    @Test
    fun `skip while a transition runs cuts`() {
        assertFalse(ManualSkipPolicy.shouldBlend(nowMs = 10_000L, lastSkipAtMs = null, transitionRunning = true))
        assertFalse(ManualSkipPolicy.shouldBlend(nowMs = 60_000L, lastSkipAtMs = 10_000L, transitionRunning = true))
    }

    @Test
    fun `skip right after another skip cuts`() {
        assertFalse(ManualSkipPolicy.shouldBlend(nowMs = 10_300L, lastSkipAtMs = 10_000L, transitionRunning = false))
        assertFalse(
            ManualSkipPolicy.shouldBlend(
                nowMs = 10_000L + ManualSkipPolicy.RAPID_SKIP_WINDOW_MS - 1,
                lastSkipAtMs = 10_000L,
                transitionRunning = false,
            ),
        )
    }

    @Test
    fun `skip after a pause blends again`() {
        assertTrue(
            ManualSkipPolicy.shouldBlend(
                nowMs = 10_000L + ManualSkipPolicy.RAPID_SKIP_WINDOW_MS,
                lastSkipAtMs = 10_000L,
                transitionRunning = false,
            ),
        )
    }

    @Test
    fun `blend is capped at a short duration`() {
        assertEquals(ManualSkipPolicy.MAX_BLEND_MS, ManualSkipPolicy.blendDurationMs(8_000L))
        assertEquals(600L, ManualSkipPolicy.blendDurationMs(600L))
    }
}
