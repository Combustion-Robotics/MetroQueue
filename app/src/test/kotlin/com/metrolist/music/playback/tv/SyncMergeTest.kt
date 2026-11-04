/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private data class Item(
        val value: String,
        val updatedAt: Long,
    )

    private fun merge(
        local: Map<String, Item>,
        remote: Map<String, Item>,
        localDeleted: Map<String, Long> = emptyMap(),
        remoteDeleted: Map<String, Long> = emptyMap(),
        remoteWinsTies: Boolean = true,
    ) = SyncMerge.merge(local, localDeleted, remote, remoteDeleted, remoteWinsTies) { it.updatedAt }

    @Test
    fun newerCopyWinsEitherWay() {
        val merged = merge(mapOf("a" to Item("mine", 20), "b" to Item("mine", 5)), mapOf("a" to Item("theirs", 10), "b" to Item("theirs", 6)))
        assertEquals("mine", merged.items.getValue("a").value)
        assertEquals("theirs", merged.items.getValue("b").value)
    }

    @Test
    fun itemsOnOneSideAreKept() {
        val merged = merge(mapOf("a" to Item("mine", 0)), mapOf("b" to Item("theirs", 0)))
        assertEquals(setOf("a", "b"), merged.items.keys)
    }

    @Test
    fun tiesConvergeOnTheReceiversCopy() {
        // The phone adopts the receiver's copy, then sends it; the receiver keeps its own
        val phone = merge(mapOf("a" to Item("phone", 7)), mapOf("a" to Item("receiver", 7)), remoteWinsTies = true)
        assertEquals("receiver", phone.items.getValue("a").value)
        val receiver = merge(mapOf("a" to Item("receiver", 7)), phone.items, remoteWinsTies = false)
        assertEquals(phone.items, receiver.items)
    }

    @Test
    fun deletionBeatsOlderOrEqualChanges() {
        val merged = merge(mapOf("a" to Item("mine", 10), "b" to Item("mine", 10)), emptyMap(), remoteDeleted = mapOf("a" to 10, "b" to 9))
        assertEquals(setOf("b"), merged.items.keys)
        // The tombstone of the item that came back is dropped; the other one is kept
        assertEquals(mapOf("a" to 10L), merged.deleted)
    }

    @Test
    fun remoteItemDeletedHereStaysDeleted() {
        val merged = merge(emptyMap(), mapOf("a" to Item("theirs", 3)), localDeleted = mapOf("a" to 4))
        assertTrue(merged.items.isEmpty())
        assertEquals(mapOf("a" to 4L), merged.deleted)
    }

    @Test
    fun tombstonesKeepTheNewestDeletionTime() {
        val merged = merge(emptyMap(), emptyMap(), localDeleted = mapOf("a" to 4), remoteDeleted = mapOf("a" to 9))
        assertEquals(mapOf("a" to 9L), merged.deleted)
    }

    @Test
    fun tombstonesAreCapped() {
        val many = (1..SyncMerge.MAX_TOMBSTONES + 50).associate { "k$it" to it.toLong() }
        val merged = merge(emptyMap(), emptyMap(), remoteDeleted = many)
        assertEquals(SyncMerge.MAX_TOMBSTONES, merged.deleted.size)
        assertEquals(51L, merged.deleted.values.min())
    }

    @Test
    fun mergingIsIdempotent() {
        val local = mapOf("a" to Item("mine", 2), "c" to Item("mine", 1))
        val remote = mapOf("a" to Item("theirs", 3), "b" to Item("theirs", 1))
        val once = merge(local, remote, remoteDeleted = mapOf("c" to 5))
        val twice = merge(once.items, remote, localDeleted = once.deleted, remoteDeleted = mapOf("c" to 5))
        assertEquals(once.items, twice.items)
        assertEquals(once.deleted, twice.deleted)
    }

    @Test
    fun historyIsAUnionByPlay() {
        val local = setOf("song1" to 100L)
        val remote =
            listOf(
                SyncHistoryItem("song1", 100L, 30_000),
                SyncHistoryItem("song1", 200L, 30_000),
                SyncHistoryItem("song1", 200L, 31_000),
                SyncHistoryItem("song2", 100L),
                SyncHistoryItem("", 300L),
                SyncHistoryItem("song3", 0L),
            )
        assertEquals(
            listOf(SyncHistoryItem("song1", 200L, 30_000), SyncHistoryItem("song2", 100L)),
            SyncMerge.newHistory(local, remote),
        )
    }
}
