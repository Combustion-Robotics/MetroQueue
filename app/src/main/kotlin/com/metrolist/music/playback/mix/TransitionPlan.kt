/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.mix

enum class TransitionStyle {
    FADE,

    /** Outgoing high-pass sweep, incoming low-pass opening up. */
    FILTER,

    /** Outgoing echo tail while its dry signal fades. */
    ECHO,

    /** Outgoing high-pass sweep with a slight lift, incoming bass held back until the drop. */
    RISE,

    /** No overlap: the incoming track starts at full volume at the start point. */
    CUT,
}

/**
 * How MusicService moves from the current track into the next one.
 *
 * @param startPositionMs position in the outgoing track at which the transition starts at the latest.
 * @param durationMs length of the overlap, in media time of the outgoing track.
 * @param incomingStartMs where the incoming track starts; null lets the engine skip its silent intro.
 * @param style how the two tracks are blended; every style but [TransitionStyle.CUT] keeps the fade curve.
 * @param allowEarlyStart whether the transition may start before [startPositionMs] once the
 *   outgoing track has audibly ended (faded out or gone silent).
 * @param echoDelayMs echo repeat interval for [TransitionStyle.ECHO] (e.g. synced to the tempo);
 *   null uses the default.
 * @param incomingSpeed playback speed of the incoming track during the overlap, e.g. 1.03 to match
 *   the outgoing tempo; it returns to normal speed in a few steps afterwards.
 */
data class TransitionPlan(
    val startPositionMs: Long,
    val durationMs: Long,
    val incomingStartMs: Long? = null,
    val style: TransitionStyle = TransitionStyle.FADE,
    val allowEarlyStart: Boolean = true,
    val echoDelayMs: Long? = null,
    val incomingSpeed: Float = 1f,
)
