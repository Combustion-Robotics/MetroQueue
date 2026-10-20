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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.pages.SearchSummary
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.PauseSearchHistoryKey
import com.metrolist.music.db.entities.SearchHistory
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.OnlineSearchSuggestionViewModel
import com.metrolist.music.viewmodels.OnlineSearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Search bar pinned on top (system keyboard or voice), with suggestions and history as quick picks below it. */
@Composable
fun TvSearchScreen(viewModel: OnlineSearchSuggestionViewModel = hiltViewModel()) {
    val tv = LocalTvController.current
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val barState = remember { TvSearchBarState() }
    val viewState by viewModel.viewState.collectAsStateWithLifecycle()
    val focusMemory = rememberTvFocusMemory()
    val submit = rememberTvSearchSubmit()

    LaunchedEffect(query.text) { viewModel.query.value = query.text.trim() }

    Column(Modifier.fillMaxSize()) {
        TvSearchHeader(
            query = query,
            onQueryChange = { query = it },
            onSubmit = submit,
            barState = barState,
            focusMemory = focusMemory,
            showNewSearch = query.text.isNotBlank(),
            barModifier = Modifier.tvInitialFocus(skip = focusMemory.restorePending),
        )

        LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = TvDimens.SafeVertical * 2),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            val chips =
                if (query.text.isBlank()) {
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
                                    query = TextFieldValue(chip, TextRange(chip.length))
                                    submit(chip)
                                },
                                icon = if (query.text.isBlank()) R.drawable.history else R.drawable.search,
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
}

/** Search results as one row per result group (top result, songs, albums, artists, ...) under the search bar. */
@Composable
fun TvSearchResultScreen(viewModel: OnlineSearchViewModel = hiltViewModel()) {
    val playerConnection = LocalPlayerConnection.current
    val playingId = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value?.id
    val summaryPage = viewModel.summaryPage
    val focusMemory = rememberTvFocusMemory()
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(viewModel.query, TextRange(viewModel.query.length)))
    }
    val barState = remember { TvSearchBarState() }
    val submit = rememberTvSearchSubmit()
    val summaries = remember(summaryPage) { summaryPage?.summaries?.filter { it.items.isNotEmpty() }.orEmpty() }

    Column(Modifier.fillMaxSize()) {
        TvSearchHeader(
            query = query,
            onQueryChange = { query = it },
            onSubmit = submit,
            barState = barState,
            focusMemory = focusMemory,
            showNewSearch = true,
            barModifier = Modifier.tvInitialFocus(ready = summaryPage != null && summaries.isEmpty(), skip = focusMemory.restorePending),
        )

        if (summaryPage == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            TvSearchResults(summaries, playingId, focusMemory)
        }
    }
}

@Composable
private fun ColumnScope.TvSearchResults(
    summaries: List<SearchSummary>,
    playingId: String?,
    focusMemory: TvFocusMemory,
) {
    val tv = LocalTvController.current
    LazyColumn(
        contentPadding = PaddingValues(top = 8.dp, bottom = TvDimens.SafeVertical * 2),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
        if (summaries.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.no_results_found),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = TvDimens.SafeHorizontal / 2, top = 16.dp),
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

/** Saves [query] to the search history (unless paused) and shows its results in place of any previous ones. */
@Composable
private fun rememberTvSearchSubmit(): (String) -> Unit {
    val tv = LocalTvController.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val pauseSearchHistory by rememberPreference(PauseSearchHistoryKey, defaultValue = false)
    return { raw ->
        val text = raw.trim()
        if (text.isNotEmpty()) {
            if (!pauseSearchHistory) {
                coroutineScope.launch(Dispatchers.IO) {
                    runCatching { database.insert(SearchHistory(query = text)) }
                }
            }
            tv.showSearchResults(text)
        }
    }
}

/** The pinned top of both search screens: the search bar, voice search and "New search". */
@Composable
private fun TvSearchHeader(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onSubmit: (String) -> Unit,
    barState: TvSearchBarState,
    focusMemory: TvFocusMemory,
    showNewSearch: Boolean,
    barModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val voiceUnavailable = stringResource(R.string.tv_voice_search_unavailable)
    val voiceLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spoken =
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                onQueryChange(TextFieldValue(spoken, TextRange(spoken.length)))
                onSubmit(spoken)
            }
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = TvDimens.SafeHorizontal / 2, end = TvDimens.SafeHorizontal, top = TvDimens.SafeVertical, bottom = 8.dp),
    ) {
        TvSearchBar(
            value = query,
            onValueChange = onQueryChange,
            onSearch = onSubmit,
            state = barState,
            modifier =
                Modifier
                    .weight(1f)
                    .tvFocusMemory(focusMemory, "field")
                    .then(barModifier),
        )
        TvIconButton(
            icon = R.drawable.mic,
            contentDescription = stringResource(R.string.tv_voice_search),
            onClick = {
                try {
                    voiceLauncher.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                    )
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, voiceUnavailable, Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.tvFocusMemory(focusMemory, "voice"),
        )
        if (showNewSearch) {
            TvButton(
                onClick = {
                    onQueryChange(TextFieldValue())
                    barState.editing = true
                },
                icon = R.drawable.add,
                text = stringResource(R.string.tv_new_search),
                modifier = Modifier.tvFocusMemory(focusMemory, "new"),
            )
        }
    }
}

/** Whether a [TvSearchBar]'s text field is being edited (focused, with the keyboard up). */
@Stable
class TvSearchBarState {
    var editing by mutableStateOf(false)
}

/**
 * Search box for the D-pad. Focus normally rests on the bar itself; its text field only becomes
 * focusable (bringing up the keyboard) once the bar is selected. Back, the keyboard closing or moving
 * away ends editing and returns focus to the bar, so the keyboard never opens on focus alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvSearchBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSearch: (String) -> Unit,
    state: TvSearchBarState,
    modifier: Modifier = Modifier,
) {
    val barRequester = remember { FocusRequester() }
    val fieldRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var fieldFocused by remember { mutableStateOf(false) }
    val stopEditing: () -> Unit = {
        if (state.editing) {
            barRequester.requestFocus()
            state.editing = false
        }
    }

    // Editing can also be started from outside (e.g. "New search")
    LaunchedEffect(state.editing) {
        if (state.editing && !fieldFocused) {
            withFrameNanos { }
            fieldRequester.requestFocus()
            keyboard?.show()
        }
    }

    // Some keyboards swallow Back to hide themselves; treat the keyboard going away as the end of editing
    val imeVisible = WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(state.editing, imeVisible) {
        when {
            !state.editing -> imeSeen = false
            imeVisible -> imeSeen = true
            imeSeen -> stopEditing()
        }
    }

    TvSurface(
        onClick = {
            if (!state.editing) {
                // Select the old query so typing replaces it
                onValueChange(value.copy(selection = TextRange(0, value.text.length)))
                state.editing = true
            }
            fieldRequester.requestFocus()
            keyboard?.show()
        },
        modifier = modifier.height(64.dp).focusRequester(barRequester),
        shape = RoundedCornerShape(32.dp),
        containerColor =
            if (state.editing) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        focusedContentColor = MaterialTheme.colorScheme.onSurface,
        focusedScale = 1.02f,
        focusBorder = true,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        ) {
            Icon(painterResource(R.drawable.search), contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(onSearch = {
                        stopEditing()
                        onSearch(value.text)
                    }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search_yt_music),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                },
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(fieldRequester)
                        .focusProperties { canFocus = state.editing }
                        .onFocusChanged {
                            if (fieldFocused && !it.isFocused) state.editing = false
                            fieldFocused = it.isFocused
                        }.onPreviewKeyEvent { event ->
                            if (event.key != Key.Back) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyUp) stopEditing()
                            true
                        },
            )
        }
    }
}
