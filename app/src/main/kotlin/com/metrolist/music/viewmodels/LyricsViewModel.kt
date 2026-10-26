/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import com.metrolist.music.ui.component.LyricsListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class LyricsViewModel @Inject constructor() : ViewModel() {
    companion object {
        private val timestampRegex = Regex("\\[\\d{1,2}:\\d{2}")
        private const val MIN_INTERVAL_GAP_MS = 4000L
        private const val LAST_WORD_HOLD_MS = 2000L
        private const val MS_PER_CHAR = 110L
        private const val MIN_LINE_MS = 1500L
        private const val MAX_LINE_MS = 8000L
    }

    private var processJob: kotlinx.coroutines.Job? = null

    private val _lines = MutableStateFlow<List<LyricsEntry>>(emptyList())
    val lines: StateFlow<List<LyricsEntry>> = _lines.asStateFlow()

    private val _mergedLyricsList = MutableStateFlow<List<LyricsListItem>>(emptyList())
    val mergedLyricsList: StateFlow<List<LyricsListItem>> = _mergedLyricsList.asStateFlow()

    fun processLyrics(
        lyrics: String?,
        enabledLanguages: List<String>,
        romanizeCyrillicByLine: Boolean,
        showIntervalIndicator: Boolean
    ) {
        processJob?.cancel()
        processJob = viewModelScope.launch {
            val processedLines = withContext(Dispatchers.Default) {
                if (lyrics == null || lyrics == LYRICS_NOT_FOUND) {
                    emptyList()
                } else {
                    val isLrc = timestampRegex.containsMatchIn(lyrics)
                    val parsedLines = if (isLrc) LyricsUtils.parseLyrics(lyrics) else emptyList()
                    
                    if (parsedLines.isNotEmpty()) {
                        listOf(LyricsEntry.HEAD_LYRICS_ENTRY) + parsedLines
                    } else {
                        // Fallback for unsynced or invalid LRC
                        val baseTime = 1000000L
                        lyrics.lines()
                            .filter { it.isNotBlank() && !timestampRegex.containsMatchIn(it) }
                            .mapIndexed { index, line ->
                                LyricsEntry(baseTime + index, line)
                            }
                    }
                }
            }
            
            _lines.value = processedLines
            updateMergedList(processedLines, showIntervalIndicator)

            // Romanize in the background after the UI has been updated
            if (lyrics != null && lyrics != LYRICS_NOT_FOUND && enabledLanguages.isNotEmpty()) {
                launch(Dispatchers.Default) {
                    processedLines.forEach { entry ->
                        if (entry == LyricsEntry.HEAD_LYRICS_ENTRY) return@forEach
                        entry.romanizedTextFlow.value = LyricsUtils.romanize(
                            text = lyrics,
                            line = entry.text,
                            enabledLanguages = enabledLanguages,
                            romanizeCyrillicByLine = romanizeCyrillicByLine
                        )
                    }
                }
            }
        }
    }

    private fun updateMergedList(lines: List<LyricsEntry>, showIntervalIndicator: Boolean) {
        val result = mutableListOf<LyricsListItem>()
        if (lines.isEmpty()) {
            _mergedLyricsList.value = result
            return
        }
        // Lines can overlap (background vocals, duets), so a break only starts once every line before it has ended
        var latestEnd = Long.MIN_VALUE
        lines.forEachIndexed { i, entry ->
            if (entry.text.isNotBlank()) {
                result.add(LyricsListItem.Line(i, entry))
            }
            if (showIntervalIndicator && i < lines.size - 1) {
                val nextStart = lines[i + 1].time
                latestEnd = maxOf(latestEnd, singingEndMs(entry, nextStart))
                val currentEnd = latestEnd

                if (currentEnd < nextStart) {
                    val gap = nextStart - currentEnd
                    if (gap > MIN_INTERVAL_GAP_MS) {
                        result.add(LyricsListItem.Indicator(i, gap, currentEnd, nextStart, lines[i + 1].agent))
                    }
                }
            }
        }
        _mergedLyricsList.value = result
    }

    // Line-synced lyrics only give start times, and word-synced ones often leave the last word running
    // until the next line, so in both cases the line is taken to end once it has plausibly been sung
    private fun singingEndMs(entry: LyricsEntry, nextStart: Long): Long {
        val words = entry.words
        if (!words.isNullOrEmpty()) {
            val end = (words.last().endTime * 1000).toLong()
            if (end < nextStart) return end
            return minOf(end, (words.last().startTime * 1000).toLong() + LAST_WORD_HOLD_MS)
        }
        if (entry.text.isBlank()) return entry.time
        // CJK characters are a syllable each, so they take longer to sing than a Latin letter
        val weight = entry.text.sumOf { c -> if (c.isWhitespace()) 0L else if (c.code >= 0x2E80) 3L else 1L }
        return minOf(nextStart, entry.time + (weight * MS_PER_CHAR).coerceIn(MIN_LINE_MS, MAX_LINE_MS))
    }
}
