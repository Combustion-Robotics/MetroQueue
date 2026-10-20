/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.PauseSearchHistoryKey
import com.metrolist.music.db.entities.SearchHistory
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.OnlineSearchSuggestionViewModel
import com.metrolist.music.viewmodels.OnlineSearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Search box (system keyboard or voice), with suggestions and history as quick picks below it. */
@Composable
fun TvSearchScreen(viewModel: OnlineSearchSuggestionViewModel = hiltViewModel()) {
    val tv = LocalTvController.current
    val database = LocalDatabase.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pauseSearchHistory by rememberPreference(PauseSearchHistoryKey, defaultValue = false)
    var query by rememberSaveable { mutableStateOf("") }
    val viewState by viewModel.viewState.collectAsStateWithLifecycle()
    val focusMemory = rememberTvFocusMemory()

    LaunchedEffect(query) { viewModel.query.value = query.trim() }

    val submit: (String) -> Unit = { raw ->
        val text = raw.trim()
        if (text.isNotEmpty()) {
            if (!pauseSearchHistory) {
                coroutineScope.launch(Dispatchers.IO) {
                    runCatching { database.insert(SearchHistory(query = text)) }
                }
            }
            tv.navigate(SearchRoutes.resultRoute(text))
        }
    }

    val voiceUnavailable = stringResource(R.string.tv_voice_search_unavailable)
    val voiceLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spoken =
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                query = spoken
                submit(spoken)
            }
        }
    val startVoiceSearch = {
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, voiceUnavailable, Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = TvDimens.SafeVertical * 2),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "title") { TvScreenTitle(stringResource(R.string.search)) }
        item(key = "field") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = TvDimens.SafeHorizontal / 2, end = TvDimens.SafeHorizontal),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_yt_music)) },
                    leadingIcon = { Icon(painterResource(R.drawable.search), contentDescription = null) },
                    textStyle = MaterialTheme.typography.titleLarge,
                    shape = RoundedCornerShape(28.dp),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                    modifier =
                        Modifier
                            .weight(1f)
                            .tvFocusMemory(focusMemory, "field")
                            .tvInitialFocus(skip = focusMemory.restorePending),
                )
                Spacer(Modifier.width(16.dp))
                TvIconButton(
                    icon = R.drawable.mic,
                    contentDescription = stringResource(R.string.tv_voice_search),
                    onClick = startVoiceSearch,
                    modifier = Modifier.tvFocusMemory(focusMemory, "voice"),
                )
            }
        }

        val chips =
            if (query.isBlank()) {
                viewState.history.map { it.query }
            } else {
                viewState.suggestions
            }.distinct()
        if (chips.isNotEmpty()) {
            item(key = "chips") {
                LazyRow(
                    contentPadding = PaddingValues(start = TvDimens.SafeHorizontal / 2, end = TvDimens.SafeHorizontal, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(chips, key = { it }) { chip ->
                        TvButton(
                            onClick = {
                                query = chip
                                submit(chip)
                            },
                            icon = if (query.isBlank()) R.drawable.history else R.drawable.search,
                            text = chip,
                            modifier = Modifier.tvFocusMemory(focusMemory, "chip/$chip"),
                        )
                    }
                }
            }
        }

        if (viewState.items.isNotEmpty()) {
            item(key = "items") {
                TvRow(title = stringResource(R.string.tv_top_matches), items = viewState.items, key = { it.id }) { item ->
                    TvMediaCard(
                        title = item.title,
                        subtitle = item.tvSubtitle(),
                        thumbnailUrl = item.thumbnail,
                        round = item is ArtistItem,
                        onClick = { tv.open(item) },
                        modifier = Modifier.tvFocusMemory(focusMemory, "item/${item.id}"),
                    )
                }
            }
        }
    }
}

/** Search results as one row per result group (top result, songs, albums, artists, ...). */
@Composable
fun TvSearchResultScreen(viewModel: OnlineSearchViewModel = hiltViewModel()) {
    val tv = LocalTvController.current
    val playerConnection = LocalPlayerConnection.current
    val playingId = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value?.id
    val summaryPage = viewModel.summaryPage
    val focusMemory = rememberTvFocusMemory()

    if (summaryPage == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val summaries = remember(summaryPage) { summaryPage.summaries.filter { it.items.isNotEmpty() } }

    LazyColumn(
        contentPadding = PaddingValues(bottom = TvDimens.SafeVertical * 2),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "title") { TvScreenTitle(stringResource(R.string.tv_search_results, viewModel.query)) }
        if (summaries.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.no_results_found),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = TvDimens.SafeHorizontal / 2),
                )
            }
        }
        itemsIndexed(summaries, key = { index, summary -> "$index/${summary.title}" }) { index, summary ->
            val firstId = summary.items.firstOrNull()?.id
            TvRow(title = summary.title, items = summary.items, key = { it.id }) { item ->
                TvMediaCard(
                    title = item.title,
                    subtitle = item.tvSubtitle(),
                    thumbnailUrl = item.thumbnail,
                    round = item is ArtistItem,
                    active = item.id == playingId,
                    onClick = { tv.open(item) },
                    modifier =
                        Modifier
                            .tvFocusMemory(focusMemory, "$index/${item.id}")
                            .then(
                                if (index == 0 && item.id == firstId) {
                                    Modifier.tvInitialFocus(skip = focusMemory.restorePending)
                                } else {
                                    Modifier
                                },
                            ),
                )
            }
        }
    }
}
