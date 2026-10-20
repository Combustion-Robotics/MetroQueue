/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress

data class DiscoveredTv(
    val id: String?,
    val name: String,
    val host: String,
    val port: Int,
)

/**
 * Finds MetroQueue TVs on the local network. Discovery costs battery, so it only runs between
 * [start] and [stop] (reference counted: the device picker and a reconnect may both need it).
 */
class TvDiscovery(
    context: Context,
) {
    private val nsd = context.getSystemService<NsdManager>()
    private val _tvs = MutableStateFlow<List<DiscoveredTv>>(emptyList())
    val tvs: StateFlow<List<DiscoveredTv>> = _tvs.asStateFlow()

    private var users = 0
    private var listener: NsdManager.DiscoveryListener? = null

    // NsdManager resolves one service at a time before API 34
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (users++ > 0) return
        val nsd = nsd ?: return
        val discovery =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Timber.tag(TAG).w("Discovery failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) = enqueueResolve(serviceInfo)

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    _tvs.update { list -> list.filterNot { it.name == serviceInfo.serviceName } }
                }
            }
        runCatching { nsd.discoverServices(TV_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onSuccess { listener = discovery }
            .onFailure { Timber.tag(TAG).w(it, "Discovery failed") }
    }

    @Synchronized
    fun stop() {
        if (users == 0 || --users > 0) return
        listener?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        listener = null
        resolveQueue.clear()
        _tvs.value = emptyList()
    }

    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo) {
        if (listener == null) return
        resolveQueue.addLast(info)
        if (!resolving) resolveNext()
    }

    @Synchronized
    private fun resolveNext() {
        val info = resolveQueue.removeFirstOrNull()
        val nsd = nsd
        if (info == null || nsd == null || listener == null) {
            resolving = false
            return
        }
        resolving = true
        val callback =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) = resolveNext()

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    serviceInfo.toDiscoveredTv()?.let { tv ->
                        _tvs.update { list -> list.filterNot { it.name == tv.name || (tv.id != null && it.id == tv.id) } + tv }
                    }
                    resolveNext()
                }
            }
        @Suppress("DEPRECATION")
        runCatching { nsd.resolveService(info, callback) }.onFailure { resolveNext() }
    }

    private fun NsdServiceInfo.toDiscoveredTv(): DiscoveredTv? {
        val address: InetAddress? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                hostAddresses.firstOrNull { it is Inet4Address } ?: hostAddresses.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                host
            }
        val hostAddress = address?.hostAddress ?: return null
        return DiscoveredTv(
            id = attributes[TV_TXT_ID]?.let { String(it, Charsets.UTF_8) },
            name = serviceName,
            host = hostAddress,
            port = port,
        )
    }

    private companion object {
        const val TAG = "TvDiscovery"
    }
}
