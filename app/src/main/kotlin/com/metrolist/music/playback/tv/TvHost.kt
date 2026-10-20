/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.core.content.getSystemService
import com.metrolist.music.R
import com.metrolist.music.constants.TvDeviceIdKey
import com.metrolist.music.constants.TvPairedDevicesKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** The name phones see for this TV, e.g. "Living room TV". */
fun Context.tvDeviceName(): String =
    runCatching { Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: Build.MODEL

/**
 * TV side of pairing: shows a short-lived 6-digit code for a phone that asks to pair and hands out
 * a long-lived token when the phone sends the right code. Only token hashes are stored.
 */
class TvPairing(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    data class Prompt(
        val code: String,
        val deviceName: String,
        val expiresAt: Long,
    )

    sealed interface Result {
        data class Paired(
            val token: String,
        ) : Result

        data object WrongCode : Result

        data object Expired : Result

        data object Locked : Result
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    private val storedDevices: StateFlow<List<PairedDevice>?> =
        context.dataStore.data
            .map { decode(it[TvPairedDevicesKey]) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    val devices: StateFlow<List<PairedDevice>> =
        storedDevices
            .map { it.orEmpty() }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Guarded by this
    private val startTimes = ArrayDeque<Long>()
    private val failureTimes = ArrayDeque<Long>()
    private var attemptsLeft = 0
    private var lockedUntil = 0L

    suspend fun deviceId(): String {
        context.dataStore.data.first()[TvDeviceIdKey]?.let { return it }
        val id = UUID.randomUUID().toString()
        context.safeDataStoreEdit { it[TvDeviceIdKey] = id }
        return id
    }

    /** Shows a new code on the TV. False when asked too often or while locked out. */
    fun start(deviceName: String): Boolean {
        val prompt =
            synchronized(this) {
                val now = SystemClock.elapsedRealtime()
                startTimes.removeOlderThan(now - START_WINDOW_MS)
                if (now < lockedUntil || startTimes.size >= MAX_STARTS_PER_WINDOW) return false
                startTimes.addLast(now)
                attemptsLeft = MAX_ATTEMPTS_PER_CODE
                Prompt(
                    code = "%06d".format(random.nextInt(1_000_000)),
                    deviceName = deviceName.cleanName(),
                    expiresAt = now + CODE_TTL_MS,
                ).also { _prompt.value = it }
            }
        scope.launch {
            delay(CODE_TTL_MS)
            _prompt.compareAndSet(prompt, null)
        }
        return true
    }

    fun cancel() {
        _prompt.value = null
    }

    suspend fun finish(
        code: String,
        deviceName: String,
    ): Result {
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            if (now < lockedUntil) return Result.Locked
            val current = _prompt.value
            if (current == null || now > current.expiresAt) {
                _prompt.value = null
                return Result.Expired
            }
            if (!MessageDigest.isEqual(code.trim().toByteArray(), current.code.toByteArray())) {
                failureTimes.removeOlderThan(now - LOCKOUT_WINDOW_MS)
                failureTimes.addLast(now)
                attemptsLeft--
                if (failureTimes.size >= MAX_FAILURES_PER_WINDOW) {
                    lockedUntil = now + LOCKOUT_MS
                    failureTimes.clear()
                    _prompt.value = null
                    return Result.Locked
                }
                if (attemptsLeft <= 0) {
                    _prompt.value = null
                    return Result.Expired
                }
                return Result.WrongCode
            }
            _prompt.value = null
        }

        val token = newToken()
        val device =
            PairedDevice(
                id = UUID.randomUUID().toString(),
                name = deviceName.cleanName(),
                tokenHash = hash(token),
                pairedAt = System.currentTimeMillis(),
            )
        val saved =
            context.safeDataStoreEdit {
                it[TvPairedDevicesKey] = json.encodeToString(decode(it[TvPairedDevicesKey]) + device)
            }
        if (!saved) return Result.Expired
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.tv_paired_with, device.name), Toast.LENGTH_SHORT).show()
        }
        return Result.Paired(token)
    }

    suspend fun isAuthorized(token: String): Boolean {
        if (token.isEmpty()) return false
        val tokenHash = hash(token)
        val devices = storedDevices.value ?: storedDevices.filterNotNull().first()
        return devices.any { MessageDigest.isEqual(it.tokenHash.toByteArray(), tokenHash.toByteArray()) }
    }

    suspend fun revoke(id: String) {
        context.safeDataStoreEdit { prefs ->
            prefs[TvPairedDevicesKey] = json.encodeToString(decode(prefs[TvPairedDevicesKey]).filterNot { it.id == id })
        }
    }

    private fun decode(raw: String?): List<PairedDevice> =
        raw?.let { runCatching { json.decodeFromString<List<PairedDevice>>(it) }.getOrNull() }.orEmpty()

    private fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun String.cleanName() = trim().take(MAX_NAME_LENGTH).ifEmpty { "Phone" }

    private fun ArrayDeque<Long>.removeOlderThan(time: Long) {
        while (isNotEmpty() && first() < time) removeFirst()
    }

    companion object {
        private const val CODE_TTL_MS = 2 * 60_000L
        private const val START_WINDOW_MS = 60_000L
        private const val MAX_STARTS_PER_WINDOW = 3
        private const val MAX_ATTEMPTS_PER_CODE = 5
        private const val LOCKOUT_WINDOW_MS = 10 * 60_000L
        private const val MAX_FAILURES_PER_WINDOW = 10
        private const val LOCKOUT_MS = 5 * 60_000L
        private const val MAX_NAME_LENGTH = 40

        fun hash(token: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(token.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

/** Advertises the TV's remote control server on the local network (DNS-SD). */
class TvAdvertiser(
    context: Context,
) {
    private val nsd = context.getSystemService<NsdManager>()
    private var listener: NsdManager.RegistrationListener? = null

    fun register(
        name: String,
        port: Int,
        id: String,
    ) {
        unregister()
        val nsd = nsd ?: return
        val info =
            NsdServiceInfo().apply {
                serviceName = name
                serviceType = TV_SERVICE_TYPE
                setPort(port)
                setAttribute(TV_TXT_ID, id)
                setAttribute(TV_TXT_VERSION, TV_PROTOCOL_VERSION.toString())
            }
        val registration =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Timber.tag(TAG).d("Advertising as ${serviceInfo.serviceName}")
                }

                override fun onRegistrationFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    Timber.tag(TAG).w("Advertising failed: $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

                override fun onUnregistrationFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) = Unit
            }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onSuccess { listener = registration }
            .onFailure { Timber.tag(TAG).w(it, "Advertising failed") }
    }

    fun unregister() {
        val registration = listener ?: return
        listener = null
        runCatching { nsd?.unregisterService(registration) }
    }

    private companion object {
        const val TAG = "TvAdvertiser"
    }
}
