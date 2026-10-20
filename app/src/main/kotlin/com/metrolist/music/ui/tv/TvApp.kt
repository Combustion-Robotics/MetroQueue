/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.AutoRadioQueueKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.DownloadUtil
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.tv.TvProfile
import com.metrolist.music.playback.tv.TvProfileStore
import com.metrolist.music.ui.screens.LoginScreen
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch

private enum class TvTab(
    val route: String,
    val icon: Int,
    val label: Int,
) {
    HOME("home", R.drawable.home_filled, R.string.home),
    SEARCH("search", R.drawable.search, R.string.search),
    LIBRARY("library", R.drawable.library_music_filled, R.string.tv_library),
    SETTINGS("settings", R.drawable.settings, R.string.settings),
}

private const val SEEK_STEP_MS = 10_000L

/** Root of the 10-foot UI: a navigation rail, the screens, and the Now Playing / Queue overlays. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvApp(
    playerConnection: PlayerConnection?,
    database: MusicDatabase,
    downloadUtil: DownloadUtil,
    syncUtils: SyncUtils,
    profiles: TvProfileStore,
) {
    val navController = rememberNavController()
    val tvController = remember(navController) { TvController(navController) }
    val autoRadio by rememberPreference(AutoRadioQueueKey, defaultValue = true)
    SideEffect {
        tvController.playerConnection = playerConnection
        tvController.autoRadio = autoRadio
    }

    // Player UI only talks to the player once the service has created it
    val playerReady =
        playerConnection?.service?.isPlayerReady?.collectAsStateWithLifecycle()?.value == true
    val activeConnection = if (playerReady) playerConnection else null

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    var selectedTab by rememberSaveable { mutableStateOf(TvTab.HOME) }
    LaunchedEffect(route) {
        when {
            route == null -> Unit
            route.startsWith("search") -> selectedTab = TvTab.SEARCH
            route == "login" -> selectedTab = TvTab.SETTINGS
            else -> TvTab.entries.firstOrNull { it.route == route }?.let { selectedTab = it }
        }
    }
    val isTopLevel = TvTab.entries.any { it.route == route }

    val selectedRailItem = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    // Whether Back (rather than the D-pad) put focus on the rail; Back from there leaves the tab
    var railFromBack by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Something must hold focus for the D-pad to work; screens move it into their content once loaded
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { selectedRailItem.requestFocus() }
    }

    val coroutineScope = rememberCoroutineScope()
    val profileList by profiles.profiles.collectAsStateWithLifecycle()
    val activeProfile by profiles.active.collectAsStateWithLifecycle()
    var profilePickerVisible by rememberSaveable { mutableStateOf(false) }
    var askedForProfile by rememberSaveable { mutableStateOf(false) }
    // "Who's listening?" once per launch, as soon as there is a synced phone to choose
    LaunchedEffect(profileList) {
        val loaded = profileList ?: return@LaunchedEffect
        if (!askedForProfile) {
            askedForProfile = true
            if (loaded.isNotEmpty()) profilePickerVisible = true
        }
    }

    val selectTab: (TvTab) -> Unit = { tab ->
        if (tab.route != route) {
            navController.navigate(tab.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    CompositionLocalProvider(
        LocalDatabase provides database,
        LocalNavController provides navController,
        LocalPlayerConnection provides playerConnection,
        LocalPlayerAwareWindowInsets provides WindowInsets(top = 0.dp),
        LocalDownloadUtil provides downloadUtil,
        LocalSyncUtils provides syncUtils,
        LocalTvController provides tvController,
        LocalTvProfiles provides profiles,
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .tvThemedBackground()
                    .tvRemoteKeys(activeConnection, onMenu = { tvController.queueVisible = !tvController.queueVisible }),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(start = TvDimens.RailWidth)
                        .onFocusChanged {
                            contentHasFocus = it.hasFocus
                            if (it.hasFocus) railFromBack = false
                        }
                        .focusRestorer(FocusRequester.Default),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = TvTab.HOME.route,
                    enterTransition = { fadeIn(tween(220)) },
                    exitTransition = { fadeOut(tween(180)) },
                    popEnterTransition = { fadeIn(tween(220)) },
                    popExitTransition = { fadeOut(tween(180)) },
                ) {
                    composable(TvTab.HOME.route) { TvHomeScreen() }
                    composable(TvTab.SEARCH.route) { TvSearchScreen() }
                    composable(
                        route = SearchRoutes.ROUTE,
                        arguments = listOf(navArgument("query") { type = NavType.StringType }),
                    ) { TvSearchResultScreen() }
                    composable(TvTab.LIBRARY.route) { TvLibraryScreen() }
                    composable(TvTab.SETTINGS.route) { TvSettingsScreen() }
                    composable(
                        route = "album/{albumId}",
                        arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
                    ) { TvAlbumScreen() }
                    composable(
                        route = "artist/{artistId}",
                        arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
                    ) { TvArtistScreen() }
                    composable(
                        route = "online_playlist/{playlistId}",
                        arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                    ) { TvOnlinePlaylistScreen() }
                    composable(
                        route = "local_playlist/{playlistId}",
                        arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                    ) { TvLocalPlaylistScreen() }
                    composable(
                        route = "auto_playlist/{playlist}",
                        arguments = listOf(navArgument("playlist") { type = NavType.StringType }),
                    ) { TvAutoPlaylistScreen() }
                    composable(
                        route = "profile_playlist/{playlistId}",
                        arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                    ) { entry -> TvProfilePlaylistScreen(entry.arguments?.getString("playlistId").orEmpty()) }
                    composable("login") { LoginScreen(navController) }
                }
            }

            TvNavRail(
                selected = selectedTab,
                onSelect = selectTab,
                selectedRequester = selectedRailItem,
                playerConnection = activeConnection,
                onOpenNowPlaying = { tvController.nowPlayingVisible = true },
                showProfile = !profileList.isNullOrEmpty(),
                activeProfile = activeProfile?.profile,
                onOpenProfiles = { profilePickerVisible = true },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }

        if (activeConnection != null && tvController.nowPlayingVisible) {
            TvOverlay(
                playerConnection = activeConnection,
                onDismiss = { tvController.nowPlayingVisible = false },
                onMenu = { tvController.queueVisible = true },
            ) {
                TvNowPlaying(activeConnection, onOpenQueue = { tvController.queueVisible = true })
            }
        }
        if (activeConnection != null && tvController.queueVisible) {
            TvOverlay(
                playerConnection = activeConnection,
                onDismiss = { tvController.queueVisible = false },
                onMenu = { tvController.queueVisible = false },
            ) {
                TvQueue(activeConnection)
            }
        }

        LaunchedEffect(activeConnection) {
            activeConnection?.service?.remoteQueueLoaded?.collect { tvController.nowPlayingVisible = true }
        }

        if (profilePickerVisible) {
            TvProfilePicker(
                profiles = profileList.orEmpty(),
                activeId = activeProfile?.profile?.id,
                onSelect = { id ->
                    profilePickerVisible = false
                    coroutineScope.launch { profiles.select(id) }
                },
                onDismiss = { profilePickerVisible = false },
            )
        }

        // A phone asking to pair shows its code above everything, including the overlays
        val pairing = playerConnection?.service?.tvPairing
        val pairingPrompt = pairing?.prompt?.collectAsStateWithLifecycle()?.value
        if (pairing != null && pairingPrompt != null) {
            TvPairingDialog(prompt = pairingPrompt, onCancel = pairing::cancel)
        }

        // Back from a top-level screen first moves focus to the rail, then goes Home, then leaves the app.
        // From a rail reached with the D-pad, Back returns to the screen instead, like pressing Right.
        BackHandler(enabled = isTopLevel && (contentHasFocus || !railFromBack || selectedTab != TvTab.HOME)) {
            when {
                contentHasFocus -> {
                    railFromBack = true
                    runCatching { selectedRailItem.requestFocus() }
                }
                !railFromBack && focusManager.moveFocus(FocusDirection.Right) -> Unit
                selectedTab != TvTab.HOME -> selectTab(TvTab.HOME)
                else -> railFromBack = true
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvNavRail(
    selected: TvTab,
    onSelect: (TvTab) -> Unit,
    selectedRequester: FocusRequester,
    playerConnection: PlayerConnection?,
    onOpenNowPlaying: () -> Unit,
    showProfile: Boolean,
    activeProfile: TvProfile?,
    onOpenProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) TvDimens.RailExpandedWidth else TvDimens.RailWidth, tween(200), label = "railWidth")
    val scrim by animateColorAsState(
        if (expanded) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f) else Color.Transparent,
        tween(200),
        label = "railScrim",
    )
    val mediaMetadata = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        modifier =
            modifier
                .fillMaxHeight()
                .width(width)
                .background(scrim, RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                .onFocusChanged { expanded = it.hasFocus }
                .focusRestorer(selectedRequester)
                .padding(horizontal = 16.dp, vertical = TvDimens.SafeVertical),
    ) {
        if (showProfile) {
            TvSurface(
                onClick = onOpenProfiles,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
                containerColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.primary,
                focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                focusedScale = 1.03f,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                    TvProfileAvatar(profile = activeProfile, size = 44.dp)
                    if (expanded) {
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = activeProfile?.name ?: stringResource(R.string.tv_guest),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(R.string.tv_switch_profile),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TvTab.entries.forEach { tab ->
            val isSelected = tab == selected
            TvSurface(
                onClick = { onSelect(tab) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .then(if (isSelected) Modifier.focusRequester(selectedRequester) else Modifier),
                shape = CircleShape,
                containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.primary,
                contentColor =
                    if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                focusedScale = 1.03f,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                ) {
                    Icon(painterResource(tab.icon), contentDescription = stringResource(tab.label), modifier = Modifier.size(24.dp))
                    if (expanded) {
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = stringResource(tab.label),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))

        mediaMetadata?.let { metadata ->
            TvSurface(
                onClick = onOpenNowPlaying,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                containerColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.primary,
                focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                focusedScale = 1.03f,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
                    TvArtwork(url = metadata.thumbnailUrl, sizePx = 112, modifier = Modifier.size(56.dp))
                    if (expanded) {
                        Column(Modifier.padding(horizontal = 12.dp)) {
                            Text(
                                text = stringResource(R.string.now_playing),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = metadata.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-screen layer in its own window, so D-pad focus can't wander to the screen underneath and Back
 * simply closes it.
 */
@Composable
private fun TvOverlay(
    playerConnection: PlayerConnection,
    onDismiss: () -> Unit,
    onMenu: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().tvRemoteKeys(playerConnection, onMenu)) { content() }
    }
}

/**
 * Remote and keyboard media keys, handled here so they work on every screen; Menu (the Fire TV
 * "hamburger" key) toggles the queue.
 */
fun Modifier.tvRemoteKeys(
    playerConnection: PlayerConnection?,
    onMenu: () -> Unit,
): Modifier =
    onPreviewKeyEvent { event ->
        val handled =
            when (event.key) {
                Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.MediaNext, Key.MediaPrevious,
                Key.MediaFastForward, Key.MediaRewind, Key.Menu,
                -> true
                else -> false
            }
        if (!handled) return@onPreviewKeyEvent false
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
        val firstPress = event.nativeKeyEvent.repeatCount == 0
        if (event.key == Key.Menu) {
            if (firstPress) onMenu()
            return@onPreviewKeyEvent true
        }
        val connection = playerConnection ?: return@onPreviewKeyEvent false
        when (event.key) {
            Key.MediaPlayPause -> if (firstPress) connection.togglePlayPause()
            Key.MediaPlay -> if (firstPress) connection.play()
            Key.MediaPause -> if (firstPress) connection.pause()
            Key.MediaNext -> if (firstPress) connection.seekToNext()
            Key.MediaPrevious -> if (firstPress) connection.seekToPrevious()
            Key.MediaFastForward -> connection.seekTo(connection.player.currentPosition + SEEK_STEP_MS)
            Key.MediaRewind -> connection.seekTo((connection.player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L))
        }
        true
    }
