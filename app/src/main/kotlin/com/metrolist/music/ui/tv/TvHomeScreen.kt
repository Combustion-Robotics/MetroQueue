/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.LocalItem
import com.metrolist.music.db.entities.Song
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.viewmodels.HomeViewModel

/** One row of the home feed; local and YouTube items are rendered by the same card. */
private sealed interface HomeRow {
    val key: String

    data class Local(
        override val key: String,
        val title: String,
        val items: List<LocalItem>,
    ) : HomeRow

    data class Remote(
        override val key: String,
        val title: String,
        val items: List<YTItem>,
    ) : HomeRow
}

@Composable
fun TvHomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val tv = LocalTvController.current
    val playerConnection = LocalPlayerConnection.current
    val playingId = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value?.id

    LaunchedEffect(Unit) { viewModel.loadHomeData() }

    val quickPicks by viewModel.quickPicks.collectAsStateWithLifecycle()
    val keepListening by viewModel.keepListening.collectAsStateWithLifecycle()
    val forgottenFavorites by viewModel.forgottenFavorites.collectAsStateWithLifecycle()
    val similar by viewModel.similarRecommendations.collectAsStateWithLifecycle()
    val accountPlaylists by viewModel.accountPlaylists.collectAsStateWithLifecycle()
    val homePage by viewModel.homePage.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    val quickPicksTitle = stringResource(R.string.quick_picks)
    val keepListeningTitle = stringResource(R.string.keep_listening)
    val forgottenTitle = stringResource(R.string.forgotten_favorites)
    val playlistsTitle = stringResource(R.string.your_youtube_playlists)
    val similarTo = stringResource(R.string.similar_to)

    val rows =
        buildList {
            quickPicks?.drop(1)?.takeIf { it.isNotEmpty() }?.let { add(HomeRow.Local("quick_picks", quickPicksTitle, it)) }
            keepListening?.takeIf { it.isNotEmpty() }?.let { add(HomeRow.Local("keep_listening", keepListeningTitle, it)) }
            accountPlaylists?.takeIf { it.isNotEmpty() }?.let { add(HomeRow.Remote("account_playlists", playlistsTitle, it)) }
            forgottenFavorites?.takeIf { it.isNotEmpty() }?.let { add(HomeRow.Local("forgotten", forgottenTitle, it)) }
            similar?.forEach { recommendation ->
                if (recommendation.items.isNotEmpty()) {
                    add(
                        HomeRow.Remote(
                            "similar_${recommendation.title.id}",
                            "$similarTo ${recommendation.title.title}",
                            recommendation.items,
                        ),
                    )
                }
            }
            homePage?.sections?.forEachIndexed { index, section ->
                if (section.items.isNotEmpty()) add(HomeRow.Remote("section_${index}_${section.title}", section.title, section.items))
            }
        }.distinctBy { it.key }

    val hero = quickPicks?.firstOrNull()
    val focusMemory = rememberTvFocusMemory()
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                if (lastVisible != null && lastVisible >= listState.layoutInfo.totalItemsCount - 3) {
                    viewModel.loadMoreYouTubeItems(viewModel.homePage.value?.continuation)
                }
            }
    }

    if (hero == null && rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isLoading || homePage == null) CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = TvDimens.SafeVertical, bottom = TvDimens.SafeVertical * 2),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (hero != null) {
            item(key = "hero") {
                TvHomeHero(
                    song = hero,
                    onPlay = { tv.playList(quickPicksTitle, quickPicks.orEmpty()) },
                    onRadio = { tv.play(YouTubeQueue.radio(hero.toMediaMetadata())) },
                    playModifier =
                        Modifier
                            .tvFocusMemory(focusMemory, "hero_play")
                            .tvInitialFocus(skip = focusMemory.restorePending),
                    radioModifier = Modifier.tvFocusMemory(focusMemory, "hero_radio"),
                )
            }
        }
        itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
            when (row) {
                is HomeRow.Local ->
                    TvRow(title = row.title, items = row.items, key = { it.id }) { item ->
                        TvMediaCard(
                            title = item.title,
                            subtitle = item.tvSubtitle(),
                            thumbnailUrl = item.tvThumbnail,
                            round = item is com.metrolist.music.db.entities.Artist,
                            active = item is Song && item.id == playingId,
                            onClick = { tv.open(item) },
                            modifier = Modifier.tvFocusMemory(focusMemory, "${row.key}/${item.id}"),
                        )
                    }
                is HomeRow.Remote ->
                    TvRow(title = row.title, items = row.items, key = { it.id }) { item ->
                        TvMediaCard(
                            title = item.title,
                            subtitle = item.tvSubtitle(),
                            thumbnailUrl = item.thumbnail,
                            round = item is com.metrolist.innertube.models.ArtistItem,
                            active = item.id == playingId,
                            onClick = { tv.open(item) },
                            modifier = Modifier.tvFocusMemory(focusMemory, "${row.key}/${item.id}"),
                        )
                    }
            }
        }
    }
}

/** The featured pick on top of Home: large artwork, title and the two main actions. */
@Composable
private fun TvHomeHero(
    song: Song,
    onPlay: () -> Unit,
    onRadio: () -> Unit,
    playModifier: Modifier,
    radioModifier: Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .padding(start = TvDimens.SafeHorizontal / 2, end = TvDimens.SafeHorizontal)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.35f),
                        ),
                    ),
                ).padding(24.dp),
    ) {
        TvArtwork(
            url = song.thumbnailUrl,
            sizePx = 480,
            modifier = Modifier.size(200.dp).clip(RoundedCornerShape(20.dp)),
        )
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.quick_picks),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = song.title,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artists.joinToString { it.name },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton(onClick = onPlay, icon = R.drawable.play, text = stringResource(R.string.play), modifier = playModifier)
                TvButton(onClick = onRadio, icon = R.drawable.radio, text = stringResource(R.string.start_radio), modifier = radioModifier)
            }
        }
    }
}
