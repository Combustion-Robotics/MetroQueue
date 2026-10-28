/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.vibe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.vibe.scene.VibeScene

private val CardShape = RoundedCornerShape(16.dp)

@Composable
fun VibesScreen(navController: NavController) {
    val vibes = rememberVibes()
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }

    // Lazy grid only composes visible cards, so only those run their scene animation.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
    ) {
        items(vibes, key = { it.id }) { vibe ->
            VibeCard(name = vibe.name, onClick = { pendingId = vibe.id }) {
                VibeScene(vibe.scene, Modifier.fillMaxSize())
            }
        }
        item(key = "create") {
            VibeCard(name = stringResource(R.string.create_vibe), onClick = { navController.navigate(VIBE_EDIT_ROUTE) }) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.add), contentDescription = null, modifier = Modifier.size(40.dp))
                }
            }
        }
    }

    vibes.firstOrNull { it.id == pendingId }?.let { vibe ->
        VibeStartDialog(
            vibe = vibe,
            onDismiss = { pendingId = null },
            onStart = { playMusic ->
                pendingId = null
                navController.openVibe(vibe.id, playMusic)
            },
        )
    }
}

@Composable
private fun VibeCard(name: String, onClick: () -> Unit, preview: @Composable () -> Unit) {
    Column(Modifier.clip(CardShape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(CardShape)) { preview() }
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun VibeStartDialog(vibe: Vibe, onDismiss: () -> Unit, onStart: (playMusic: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(vibe.name) },
        text = { Text(stringResource(R.string.vibe_start_question)) },
        confirmButton = {
            TextButton(onClick = { onStart(true) }) { Text(stringResource(R.string.vibe_play_music)) }
        },
        dismissButton = {
            TextButton(onClick = { onStart(false) }) { Text(stringResource(R.string.vibe_keep_playing)) }
        },
    )
}

/** Vibe list for the player menu's bottom sheet. */
@Composable
fun VibePickerSheet(onStart: (id: String, playMusic: Boolean) -> Unit) {
    val vibes = rememberVibes()
    var pending by remember { mutableStateOf<Vibe?>(null) }

    Text(
        text = stringResource(R.string.vibe_mode),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
    )
    vibes.forEach { vibe ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { pending = vibe }
                .padding(8.dp),
        ) {
            VibeScene(vibe.scene, Modifier.size(72.dp, 48.dp).clip(RoundedCornerShape(8.dp)), animate = false)
            Spacer(Modifier.width(16.dp))
            Text(vibe.name, style = MaterialTheme.typography.titleMedium)
        }
    }

    pending?.let { vibe ->
        VibeStartDialog(
            vibe = vibe,
            onDismiss = { pending = null },
            onStart = { playMusic -> onStart(vibe.id, playMusic) },
        )
    }
}

// shortcut: placeholder until the custom vibe editor lands.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VibeEditScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_vibe)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.vibe_editor_coming_soon))
        }
    }
}
