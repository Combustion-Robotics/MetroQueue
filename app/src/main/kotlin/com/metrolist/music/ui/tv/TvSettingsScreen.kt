/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.BuildConfig
import com.metrolist.music.R
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.CrossfadeDurationKey
import com.metrolist.music.constants.DynamicThemeKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.MixMode
import com.metrolist.music.constants.MixTransitionStyle
import com.metrolist.music.constants.MixTransitionStyleKey
import com.metrolist.music.constants.PureBlackKey
import com.metrolist.music.constants.RemoteControlEnabledKey
import com.metrolist.music.constants.RemoteControlKeyKey
import com.metrolist.music.playback.RemoteControlServer
import com.metrolist.music.ui.menu.MixDurationRange
import com.metrolist.music.ui.menu.mixModeLabel
import com.metrolist.music.ui.menu.rememberMixSettings
import com.metrolist.music.ui.menu.setMixMode
import com.metrolist.music.ui.menu.transitionStyleLabel
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.safeDataStoreEdit
import com.metrolist.music.viewmodels.AccountSettingsViewModel
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

/** The settings that matter on a TV: account, sound, Mix, the phone remote, theme and version. */
@Composable
fun TvSettingsScreen(accountViewModel: AccountSettingsViewModel = hiltViewModel()) {
    val tv = LocalTvController.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusMemory = rememberTvFocusMemory()

    val (innerTubeCookie, onInnerTubeCookieChange) = rememberPreference(InnerTubeCookieKey, "")
    val accountName by rememberPreference(AccountNameKey, "")
    val isLoggedIn = remember(innerTubeCookie) { "SAPISID" in parseCookieString(innerTubeCookie) }
    var audioQuality by rememberEnumPreference(AudioQualityKey, AudioQuality.AUTO)
    val mixSettings = rememberMixSettings()
    var remoteEnabled by rememberPreference(RemoteControlEnabledKey, false)
    var remoteKey by rememberPreference(RemoteControlKeyKey, "")
    val remoteUrl = remember(remoteEnabled, remoteKey) { if (remoteEnabled && remoteKey.isNotEmpty()) RemoteControlServer.url(remoteKey) else null }
    var dynamicTheme by rememberPreference(DynamicThemeKey, true)
    var pureBlack by rememberPreference(PureBlackKey, false)

    LazyColumn(
        contentPadding = PaddingValues(start = TvDimens.SafeHorizontal / 2, end = TvDimens.SafeHorizontal, bottom = TvDimens.SafeVertical * 2),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "title") {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = TvDimens.SafeVertical, bottom = 12.dp),
            )
        }

        item(key = "account_header") { SettingsHeader(stringResource(R.string.account)) }
        if (isLoggedIn) {
            item(key = "account") {
                SettingRow(
                    icon = R.drawable.account,
                    title = stringResource(R.string.action_logout),
                    description = stringResource(R.string.tv_signed_in_as, accountName),
                    onClick = {
                        coroutineScope.launch { accountViewModel.logoutKeepData(context, onInnerTubeCookieChange) }
                    },
                    modifier =
                        Modifier
                            .tvFocusMemory(focusMemory, "account")
                            .tvInitialFocus(skip = focusMemory.restorePending),
                )
            }
        } else {
            item(key = "account") {
                SettingRow(
                    icon = R.drawable.login,
                    title = stringResource(R.string.action_login),
                    description = stringResource(R.string.tv_sign_in_desc),
                    onClick = { tv.navigate("login") },
                    modifier =
                        Modifier
                            .tvFocusMemory(focusMemory, "account")
                            .tvInitialFocus(skip = focusMemory.restorePending),
                )
            }
        }

        item(key = "audio_header") { SettingsHeader(stringResource(R.string.player_and_audio)) }
        item(key = "audio_quality") {
            SettingRow(
                icon = R.drawable.graphic_eq,
                title = stringResource(R.string.audio_quality),
                value =
                    stringResource(
                        when (audioQuality) {
                            AudioQuality.AUTO -> R.string.audio_quality_auto
                            AudioQuality.HIGH -> R.string.audio_quality_high
                            AudioQuality.LOW -> R.string.audio_quality_low
                        },
                    ),
                onClick = { audioQuality = AudioQuality.entries[(audioQuality.ordinal + 1) % AudioQuality.entries.size] },
                modifier = Modifier.tvFocusMemory(focusMemory, "audio_quality"),
            )
        }

        if (mixSettings != null) {
            item(key = "mix_mode") {
                SettingRow(
                    icon = R.drawable.linear_scale,
                    title = stringResource(R.string.tv_mix),
                    value = mixModeLabel(mixSettings.mode),
                    onClick = {
                        val modes = MixMode.entries
                        coroutineScope.launch { context.setMixMode(modes[(mixSettings.mode.ordinal + 1) % modes.size]) }
                    },
                    modifier = Modifier.tvFocusMemory(focusMemory, "mix_mode"),
                )
            }
            if (mixSettings.mode != MixMode.OFF) {
                item(key = "mix_style") {
                    SettingRow(
                        icon = R.drawable.tune,
                        title = stringResource(R.string.mix_transition_style),
                        value = transitionStyleLabel(mixSettings.style),
                        onClick = {
                            val styles = MixTransitionStyle.entries
                            val next = styles[(mixSettings.style.ordinal + 1) % styles.size]
                            coroutineScope.launch { context.safeDataStoreEdit { it[MixTransitionStyleKey] = next.name } }
                        },
                        modifier = Modifier.tvFocusMemory(focusMemory, "mix_style"),
                    )
                }
                item(key = "mix_duration") {
                    val seconds = mixSettings.durationSeconds.roundToInt()
                    val setSeconds: (Int) -> Unit = { value ->
                        val clamped = value.toFloat().coerceIn(MixDurationRange)
                        coroutineScope.launch { context.safeDataStoreEdit { it[CrossfadeDurationKey] = clamped } }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingRow(
                            icon = R.drawable.more_time,
                            title = stringResource(R.string.crossfade_duration),
                            value = pluralStringResource(R.plurals.seconds, seconds, seconds),
                            onClick = { setSeconds(if (seconds >= MixDurationRange.endInclusive.roundToInt()) 1 else seconds + 1) },
                            modifier = Modifier.weight(1f).tvFocusMemory(focusMemory, "mix_duration"),
                        )
                        Spacer(Modifier.width(12.dp))
                        TvButton(onClick = { setSeconds(seconds - 1) }, text = "−", modifier = Modifier.tvFocusMemory(focusMemory, "mix_minus"))
                        Spacer(Modifier.width(12.dp))
                        TvButton(onClick = { setSeconds(seconds + 1) }, text = "+", modifier = Modifier.tvFocusMemory(focusMemory, "mix_plus"))
                    }
                }
            }
        }

        item(key = "remote_header") { SettingsHeader(stringResource(R.string.remote_control)) }
        item(key = "remote") {
            SettingRow(
                icon = R.drawable.language,
                title = stringResource(R.string.remote_control),
                description =
                    when {
                        remoteUrl != null -> stringResource(R.string.tv_remote_control_url, remoteUrl)
                        remoteEnabled -> stringResource(R.string.remote_control_no_network)
                        else -> stringResource(R.string.tv_remote_control_desc)
                    },
                checked = remoteEnabled,
                onClick = {
                    if (!remoteEnabled && remoteKey.isEmpty()) remoteKey = UUID.randomUUID().toString().replace("-", "")
                    remoteEnabled = !remoteEnabled
                },
                modifier = Modifier.tvFocusMemory(focusMemory, "remote"),
            )
        }

        item(key = "theme_header") { SettingsHeader(stringResource(R.string.appearance)) }
        item(key = "dynamic_theme") {
            SettingRow(
                icon = R.drawable.palette,
                title = stringResource(R.string.enable_dynamic_theme),
                checked = dynamicTheme,
                onClick = { dynamicTheme = !dynamicTheme },
                modifier = Modifier.tvFocusMemory(focusMemory, "dynamic_theme"),
            )
        }
        item(key = "pure_black") {
            SettingRow(
                icon = R.drawable.contrast,
                title = stringResource(R.string.pure_black),
                checked = pureBlack,
                onClick = { pureBlack = !pureBlack },
                modifier = Modifier.tvFocusMemory(focusMemory, "pure_black"),
            )
        }

        item(key = "about_header") { SettingsHeader(stringResource(R.string.about)) }
        item(key = "about") {
            SettingRow(
                icon = R.drawable.info,
                title = stringResource(R.string.app_name),
                description = stringResource(R.string.version_format, BuildConfig.VERSION_NAME),
                onClick = {},
                modifier = Modifier.tvFocusMemory(focusMemory, "about"),
            )
        }
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(
    icon: Int,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    checked: Boolean? = null,
) {
    TvSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
        focusedScale = 1.02f,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                    )
                }
            }
            if (value != null) {
                Text(text = value, style = MaterialTheme.typography.titleSmall)
            }
            if (checked != null) {
                Spacer(Modifier.width(12.dp))
                // Display only: the row itself is the focus target and toggles the value
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    modifier = Modifier.focusProperties { canFocus = false },
                )
            }
        }
    }
}
