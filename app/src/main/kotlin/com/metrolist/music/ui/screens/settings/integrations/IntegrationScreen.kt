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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.PhoneReceiverEnabledKey
import com.metrolist.music.constants.RemoteControlEnabledKey
import com.metrolist.music.playback.RemoteControlServer
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.IntegrationCard
import com.metrolist.music.ui.component.IntegrationCardItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val (remoteEnabled, onRemoteEnabledChange) = rememberPreference(RemoteControlEnabledKey, false)
    val remoteUrl = remember(remoteEnabled) { if (remoteEnabled) RemoteControlServer.url() else null }
    val (receiverEnabled, onReceiverEnabledChange) = rememberPreference(PhoneReceiverEnabledKey, true)
    val pairing = LocalPlayerConnection.current?.service?.tvPairing
    val pairedDevices = pairing?.devices?.collectAsStateWithLifecycle()?.value.orEmpty()
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val coroutineScope = rememberCoroutineScope()

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
                        Switch(checked = remoteEnabled, onCheckedChange = onRemoteEnabledChange)
                    },
                    onClick = {
                        if (remoteUrl == null) {
                            onRemoteEnabledChange(!remoteEnabled)
                        } else {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("MetroQueue remote", remoteUrl))
                            Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                        }
                    }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.smartphone),
                    title = { Text(stringResource(R.string.phone_receiver)) },
                    description = { Text(stringResource(R.string.phone_receiver_desc)) },
                    trailingContent = {
                        Switch(checked = receiverEnabled, onCheckedChange = onReceiverEnabledChange)
                    },
                    onClick = { onReceiverEnabledChange(!receiverEnabled) }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.computer),
                    title = { Text(stringResource(R.string.metroqueue_desktop)) },
                    description = { Text(stringResource(R.string.metroqueue_desktop_desc)) },
                    onClick = { uriHandler.openUri("https://github.com/Combustion-Robotics/metroqueue-desktop/releases/latest") }
                )
            )
        )

        if (pairedDevices.isNotEmpty()) {
            IntegrationCard(
                title = stringResource(R.string.paired_devices),
                items = pairedDevices.map { device ->
                    IntegrationCardItem(
                        icon = painterResource(R.drawable.computer),
                        title = { Text(device.name) },
                        description = { Text(stringResource(R.string.tv_paired_on, dateFormat.format(Date(device.pairedAt)))) },
                        trailingContent = {
                            TextButton(onClick = { coroutineScope.launch { pairing?.revoke(device.id) } }) {
                                Text(stringResource(R.string.tv_forget_device))
                            }
                        }
                    )
                }
            )
        }
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
