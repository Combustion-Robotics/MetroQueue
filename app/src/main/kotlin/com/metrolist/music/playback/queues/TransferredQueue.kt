/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata

/** A queue handed over from another device, optionally still continuing its radio through [next]. */
class TransferredQueue(
    private val title: String?,
    private val items: List<MediaItem>,
    private val startIndex: Int,
    private val position: Long,
    val next: YouTubeQueue?,
) : Queue {
    override val preloadItem: MediaMetadata? = null

    override suspend fun getInitialStatus() = Queue.Status(title, items, startIndex, position)

    override fun hasNextPage(): Boolean = next?.hasNextPage() == true

    override suspend fun nextPage(): List<MediaItem> = next?.nextPage().orEmpty()
}
