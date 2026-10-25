/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

/** How a user-initiated next/previous moves to its target when "Crossfade on manual skip" is on. */
object ManualSkipPolicy {
    /** Longest blend a skip gets, whatever the configured crossfade duration. */
    const val MAX_BLEND_MS = 1_000L

    /** A skip this soon after the previous one cuts straight to its target. */
    const val RAPID_SKIP_WINDOW_MS = 1_500L

    /**
     * Whether a skip at [nowMs] may blend into its target. It cuts instead while a transition is
     * still running or right after another skip, so skipping repeatedly never stacks fades.
     */
    fun shouldBlend(
        nowMs: Long,
        lastSkipAtMs: Long?,
        transitionRunning: Boolean,
    ): Boolean = !transitionRunning && (lastSkipAtMs == null || nowMs - lastSkipAtMs >= RAPID_SKIP_WINDOW_MS)

    fun blendDurationMs(configuredMs: Long): Long = configuredMs.coerceIn(0L, MAX_BLEND_MS)
}
