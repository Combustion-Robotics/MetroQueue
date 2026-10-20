/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.playback.RemoteControlServer
import com.metrolist.music.playback.tv.PairedTv
import com.metrolist.music.playback.tv.TvRemoteClient
import com.metrolist.music.playback.tv.TvRemoteManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Opens the "Play on TV" picker; tinted while playing on a TV. */
@Composable
fun TvDevicesButton(
    modifier: Modifier = Modifier,
    tintColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val manager = LocalPlayerConnection.current?.service?.tvRemote ?: return
    val status by manager.status.collectAsStateWithLifecycle()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { showPicker = true }, modifier = modifier) {
        Icon(
            painter = painterResource(R.drawable.tv),
            contentDescription = stringResource(R.string.play_on_tv),
            tint = if (status is TvRemoteManager.Status.Idle) tintColor else MaterialTheme.colorScheme.primary,
        )
    }
    if (showPicker) TvDevicePicker(manager, onDismiss = { showPicker = false })
}

/** "Playing on <TV>" pill shown in the player while connected; opens the picker. */
@Composable
fun TvConnectedIndicator(modifier: Modifier = Modifier) {
    val manager = LocalPlayerConnection.current?.service?.tvRemote ?: return
    val status by manager.status.collectAsStateWithLifecycle()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val text =
        when (val current = status) {
            is TvRemoteManager.Status.Connected -> stringResource(R.string.tv_playing_on, current.tv.name)
            is TvRemoteManager.Status.Reconnecting -> stringResource(R.string.tv_reconnecting, current.tv.name)
            else -> return
        }
    Surface(
        onClick = { showPicker = true },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Icon(painterResource(R.drawable.tv), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (showPicker) TvDevicePicker(manager, onDismiss = { showPicker = false })
}

private data class TvEntry(
    val id: String?,
    val name: String,
    val host: String,
    val port: Int,
    val paired: PairedTv?,
    val online: Boolean,
)

private data class PairingTarget(
    val name: String,
    val host: String,
    val port: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvDevicePicker(
    manager: TvRemoteManager,
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val coroutineScope = rememberCoroutineScope()
    val status by manager.status.collectAsStateWithLifecycle()
    val pairedTvs by manager.pairedTvs.collectAsStateWithLifecycle()
    val discovered by manager.discovery.tvs.collectAsStateWithLifecycle()
    val idle = status is TvRemoteManager.Status.Idle

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var pairing by remember { mutableStateOf<PairingTarget?>(null) }
    var code by remember { mutableStateOf("") }
    var manualAddress by remember { mutableStateOf<String?>(null) }
    var moveQueue by rememberSaveable { mutableStateOf(true) }
    val hasLocalQueue = remember { runCatching { playerConnection.localPlayer.mediaItemCount > 0 }.getOrDefault(false) }

    // Discovery only runs while the picker is open and nothing is connected
    DisposableEffect(idle) {
        if (idle) manager.discovery.start()
        onDispose { if (idle) manager.discovery.stop() }
    }

    val entries =
        remember(pairedTvs, discovered) {
            val online =
                discovered.map { tv ->
                    val paired = pairedTvs.firstOrNull { it.id == tv.id }
                    TvEntry(tv.id, tv.name, tv.host, tv.port, paired?.copy(host = tv.host, port = tv.port), online = true)
                }
            online +
                pairedTvs
                    .filter { tv -> online.none { it.id == tv.id } }
                    .map { TvEntry(it.id, it.name, it.host, it.port, it, online = false) }
        }

    fun launchAction(block: suspend () -> Unit) {
        busy = true
        error = null
        coroutineScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error =
                    when {
                        e is TvRemoteClient.HttpException && e.code == 401 -> R.string.tv_wrong_code
                        e is TvRemoteClient.HttpException && e.code == 410 -> R.string.tv_code_expired
                        e is TvRemoteClient.HttpException && e.code == 429 -> R.string.tv_too_many_attempts
                        e is TvRemoteManager.NotPairedException -> R.string.tv_pairing_removed
                        else -> R.string.tv_unreachable
                    }
            } finally {
                busy = false
            }
        }
    }

    fun connect(tv: PairedTv) =
        launchAction {
            manager.connect(tv, transfer = moveQueue && hasLocalQueue)
            onDismiss()
        }

    fun startPairing(
        name: String,
        host: String,
        port: Int,
    ) = launchAction {
        manager.requestCode(host, port)
        code = ""
        pairing = PairingTarget(name, host, port)
    }

    fun connectManually(address: String) =
        launchAction {
            val host = address.trim().substringBeforeLast(':').ifEmpty { address.trim() }
            val port = address.trim().substringAfterLast(':', "").toIntOrNull() ?: RemoteControlServer.PORT
            val info = manager.lookup(host, port)
            val paired = pairedTvs.firstOrNull { it.id == info.id }
            manualAddress = null
            if (paired != null) {
                manager.connect(paired.copy(host = host, port = port), transfer = moveQueue && hasLocalQueue)
                onDismiss()
            } else {
                manager.requestCode(host, port)
                code = ""
                pairing = PairingTarget(info.name, host, port)
            }
        }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.play_on_tv),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            when (val current = status) {
                is TvRemoteManager.Status.Connected, is TvRemoteManager.Status.Reconnecting -> {
                    val tv =
                        (current as? TvRemoteManager.Status.Connected)?.tv
                            ?: (current as TvRemoteManager.Status.Reconnecting).tv
                    ConnectedCard(
                        title =
                            if (current is TvRemoteManager.Status.Connected) {
                                stringResource(R.string.tv_playing_on, tv.name)
                            } else {
                                stringResource(R.string.tv_reconnecting, tv.name)
                            },
                        busy = busy,
                        onPlayHere = {
                            launchAction {
                                manager.disconnect(playHere = true)
                                onDismiss()
                            }
                        },
                        onDisconnect = {
                            launchAction {
                                manager.disconnect(playHere = false)
                                onDismiss()
                            }
                        },
                    )
                }

                is TvRemoteManager.Status.Connecting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
                }

                TvRemoteManager.Status.Idle -> {
                    val target = pairing
                    val address = manualAddress
                    when {
                        target != null ->
                            CodeEntry(
                                tvName = target.name,
                                code = code,
                                onCodeChange = { code = it.filter(Char::isDigit).take(6) },
                                busy = busy,
                                onPair = {
                                    launchAction {
                                        val tv = manager.pair(target.host, target.port, code)
                                        pairing = null
                                        manager.connect(tv, transfer = moveQueue && hasLocalQueue)
                                        onDismiss()
                                    }
                                },
                                onCancel = { pairing = null },
                            )

                        address != null ->
                            AddressEntry(
                                address = address,
                                onAddressChange = { manualAddress = it },
                                busy = busy,
                                onConnect = { connectManually(address) },
                                onCancel = { manualAddress = null },
                            )

                        else -> {
                            if (busy || discovered.isEmpty()) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
                            }
                            Text(
                                text = stringResource(if (entries.isEmpty()) R.string.tv_none_found else R.string.tv_searching),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                            entries.forEach { entry ->
                                TvEntryRow(
                                    entry = entry,
                                    enabled = !busy,
                                    onClick = {
                                        val paired = entry.paired
                                        if (paired != null) connect(paired) else startPairing(entry.name, entry.host, entry.port)
                                    },
                                    onForget = entry.paired?.let { paired -> { coroutineScope.launch { manager.forget(paired) } } },
                                )
                            }
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.tv_add_manually)) },
                                leadingContent = { Icon(painterResource(R.drawable.add), contentDescription = null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable(enabled = !busy) { manualAddress = "" },
                            )
                            if (hasLocalQueue) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { moveQueue = !moveQueue }
                                            .padding(horizontal = 24.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.tv_move_queue),
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(checked = moveQueue, onCheckedChange = { moveQueue = it })
                                }
                            }
                        }
                    }
                }
            }

            error?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TvEntryRow(
    entry: TvEntry,
    enabled: Boolean,
    onClick: () -> Unit,
    onForget: (() -> Unit)?,
) {
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                stringResource(
                    when {
                        entry.paired == null -> R.string.tv_tap_to_pair
                        entry.online -> R.string.tv_paired
                        else -> R.string.tv_offline
                    },
                ),
            )
        },
        leadingContent = {
            Icon(
                painterResource(R.drawable.tv),
                contentDescription = null,
                tint = if (entry.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent =
            onForget?.let { forget ->
                @Composable {
                    IconButton(onClick = forget) {
                        Icon(painterResource(R.drawable.delete), contentDescription = stringResource(R.string.tv_forget_device))
                    }
                }
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun ConnectedCard(
    title: String,
    busy: Boolean,
    onPlayHere: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.tv), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPlayHere, enabled = !busy) {
                    Icon(painterResource(R.drawable.smartphone), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tv_play_here))
                }
                TextButton(onClick = onDisconnect, enabled = !busy) {
                    Text(stringResource(R.string.disconnect))
                }
            }
        }
    }
}

@Composable
private fun CodeEntry(
    tvName: String,
    code: String,
    onCodeChange: (String) -> Unit,
    busy: Boolean,
    onPair: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.tv_enter_code, tvName),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text(stringResource(R.string.tv_code)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (code.length == 6) onPair() }),
            textStyle = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPair, enabled = code.length == 6 && !busy) {
                Text(stringResource(R.string.tv_pair))
            }
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun AddressEntry(
    address: String,
    onAddressChange: (String) -> Unit,
    busy: Boolean,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            label = { Text(stringResource(R.string.tv_address)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) onConnect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConnect, enabled = address.isNotBlank() && !busy) {
                Text(stringResource(R.string.connect))
            }
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
