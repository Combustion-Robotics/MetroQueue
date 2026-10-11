/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.RemoteControlEnabledKey
import com.metrolist.music.constants.RemoteControlKeyKey
import com.metrolist.music.playback.RemoteControlServer
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.IntegrationCard
import com.metrolist.music.ui.component.IntegrationCardItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val (remoteEnabled, onRemoteEnabledChange) = rememberPreference(RemoteControlEnabledKey, false)
    val (remoteKey, onRemoteKeyChange) = rememberPreference(RemoteControlKeyKey, "")
    val remoteUrl =
        remember(remoteEnabled, remoteKey) {
            if (remoteEnabled && remoteKey.isNotEmpty()) RemoteControlServer.url(remoteKey) else null
        }
    val setRemoteEnabled: (Boolean) -> Unit = { enabled ->
        if (enabled && remoteKey.isEmpty()) onRemoteKeyChange(UUID.randomUUID().toString().replace("-", ""))
        onRemoteEnabledChange(enabled)
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        IntegrationCard(
            title = stringResource(R.string.general),
            items = listOf(
                IntegrationCardItem(
                    icon = painterResource(R.drawable.discord),
                    title = { Text(stringResource(R.string.discord_integration)) },
                    onClick = { navController.navigate("settings/integrations/discord") }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.music_note),
                    title = { Text(stringResource(R.string.lastfm_integration)) },
                    onClick = {
                        navController.navigate("settings/integrations/lastfm")
                    }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.language),
                    title = { Text(stringResource(R.string.remote_control)) },
                    description = {
                        Text(
                            when {
                                remoteUrl != null -> stringResource(R.string.remote_control_url, remoteUrl)
                                remoteEnabled -> stringResource(R.string.remote_control_no_network)
                                else -> stringResource(R.string.remote_control_desc)
                            }
                        )
                    },
                    trailingContent = {
                        Switch(checked = remoteEnabled, onCheckedChange = setRemoteEnabled)
                    },
                    onClick = {
                        if (remoteUrl == null) {
                            setRemoteEnabled(!remoteEnabled)
                        } else {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Metrolist remote", remoteUrl))
                            Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            )
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.integrations)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        }
    )
}
