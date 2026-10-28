/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.vibe

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.ui.vibe.scene.VibeScene

private val BarShape = RoundedCornerShape(2.dp)
private val BarBackground = Color(0xCC17142A)
private val BarBorder = Color(0x59FFFFFF)

@Composable
fun VibePlayerScreen(vibeId: String, playMusic: Boolean) {
    val vibe = rememberVibes().firstOrNull { it.id == vibeId } ?: return
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val view = LocalView.current

    var musicStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (playMusic && !musicStarted) {
            playVibeMusic(vibe.music, playerConnection, database)
            musicStarted = true
        }
    }

    DisposableEffect(Unit) {
        val controller = WindowCompat.getInsetsController((view.context as Activity).window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    DisposableEffect(isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        VibeScene(vibe.scene, Modifier.fillMaxSize())
        val landscape = maxWidth > maxHeight
        VibeSongBar(
            vertical = landscape,
            isPlaying = isPlaying,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(20.dp)
                .align(if (landscape) Alignment.CenterStart else Alignment.BottomCenter)
                .then(if (landscape) Modifier.width(168.dp) else Modifier.widthIn(max = 480.dp).fillMaxWidth()),
        )
    }
}

@Composable
private fun VibeSongBar(vertical: Boolean, isPlaying: Boolean, modifier: Modifier) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val metadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val canSkipNext by playerConnection.canSkipNext.collectAsStateWithLifecycle()
    val listenTogetherManager = LocalListenTogetherManager.current
    val isGuest = listenTogetherManager?.let { it.isInRoom && !it.isHost } ?: false

    val card = modifier
        .shadow(16.dp, BarShape)
        .background(BarBackground, BarShape)
        .border(2.dp, BarBorder, BarShape)
        .padding(10.dp)

    val cover = @Composable { coverModifier: Modifier ->
        AsyncImage(
            model = metadata?.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = coverModifier.clip(BarShape).background(Color(0x33FFFFFF)),
        )
    }
    val texts = @Composable { textModifier: Modifier ->
        Column(textModifier) {
            Text(
                text = metadata?.title.orEmpty(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metadata?.artists?.joinToString { it.name }.orEmpty(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val controls = @Composable {
        IconButton(onClick = playerConnection::togglePlayPause) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
            )
        }
        IconButton(onClick = playerConnection::seekToNext, enabled = canSkipNext && !isGuest) {
            Icon(painterResource(R.drawable.skip_next), contentDescription = stringResource(R.string.next))
        }
    }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        if (vertical) {
            Column(card) {
                cover(Modifier.fillMaxWidth().aspectRatio(1f))
                Spacer(Modifier.height(8.dp))
                texts(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { controls() }
            }
        } else {
            Row(card, verticalAlignment = Alignment.CenterVertically) {
                cover(Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                texts(Modifier.weight(1f))
                controls()
            }
        }
    }
}
