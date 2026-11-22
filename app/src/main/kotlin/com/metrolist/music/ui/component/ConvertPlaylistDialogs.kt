/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.utils.PlaylistConversionState
import com.metrolist.music.utils.rememberPreference

@Composable
fun rememberIsYouTubeSignedIn(): Boolean {
    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    return "SAPISID" in parseCookieString(innerTubeCookie)
}

@Composable
fun ConvertToYouTubeConfirmDialog(
    playlistId: String,
    playlistName: String,
    onDismiss: () -> Unit,
    onStarted: () -> Unit,
) {
    val syncUtils = LocalSyncUtils.current
    DefaultDialog(
        onDismiss = onDismiss,
        icon = { Icon(painter = painterResource(R.drawable.upload), contentDescription = null) },
        title = { Text(stringResource(R.string.convert_to_youtube_playlist)) },
        content = {
            Text(
                text = stringResource(R.string.convert_to_youtube_playlist_confirm, playlistName),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(
                onClick = {
                    syncUtils.convertToYouTubePlaylist(playlistId, playlistName)
                    onStarted()
                },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

/** App-wide so progress and the result survive the menu or screen that started the conversion. */
@Composable
fun PlaylistConversionHost() {
    val syncUtils = LocalSyncUtils.current
    val context = LocalContext.current
    val state by syncUtils.playlistConversion.collectAsStateWithLifecycle()

    when (val s = state) {
        is PlaylistConversionState.Running -> {
            DefaultDialog(
                onDismiss = {},
                icon = { Icon(painter = painterResource(R.drawable.upload), contentDescription = null) },
                title = { Text(s.playlistName) },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.converting_playlist_progress, s.done, s.total),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (s.total > 0) {
                        LinearProgressIndicator(
                            progress = { s.done / s.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        is PlaylistConversionState.Done -> LaunchedEffect(s) {
            var message = context.getString(R.string.playlist_converted, s.playlistName)
            if (s.skipped > 0) {
                message += "\n" + context.resources.getQuantityString(R.plurals.playlist_convert_skipped, s.skipped, s.skipped)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            syncUtils.clearPlaylistConversion()
        }

        is PlaylistConversionState.Failed -> LaunchedEffect(s) {
            Toast.makeText(context, context.getString(R.string.playlist_convert_failed, s.playlistName), Toast.LENGTH_LONG).show()
            syncUtils.clearPlaylistConversion()
        }

        null -> Unit
    }
}
