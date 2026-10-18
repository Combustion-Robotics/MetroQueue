/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

enum class TransitionStyle {
    FADE,
}

/**
 * How MusicService moves from the current track into the next one.
 *
 * @param startPositionMs position in the outgoing track at which the transition starts at the latest.
 * @param durationMs length of the overlap, in media time of the outgoing track.
 * @param incomingStartMs where the incoming track starts; null lets the engine skip its silent intro.
 * @param allowEarlyStart whether the transition may start before [startPositionMs] once the
 *   outgoing track has audibly ended (faded out or gone silent).
 */
data class TransitionPlan(
    val startPositionMs: Long,
    val durationMs: Long,
    val incomingStartMs: Long? = null,
    val style: TransitionStyle = TransitionStyle.FADE,
    val allowEarlyStart: Boolean = true,
)
