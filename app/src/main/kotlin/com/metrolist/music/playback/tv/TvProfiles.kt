/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.tv

import android.content.Context
import com.metrolist.music.constants.TvActiveProfileKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import timber.log.Timber
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ProfilePlaylist(
    val id: String,
    val name: String,
    val thumbnail: String? = null,
    val songs: List<RemoteTrack> = emptyList(),
)

@Serializable
data class ProfileAlbum(
    val id: String,
    val title: String,
    val artist: String? = null,
    val thumbnail: String? = null,
    val year: Int? = null,
)

@Serializable
data class ProfileArtist(
    val id: String,
    val name: String,
    val thumbnail: String? = null,
)

/** A phone's library as synced to a TV. Library data only, never account credentials. */
@Serializable
data class LibrarySnapshot(
    val likedSongs: List<RemoteTrack> = emptyList(),
    val playlists: List<ProfilePlaylist> = emptyList(),
    val albums: List<ProfileAlbum> = emptyList(),
    val artists: List<ProfileArtist> = emptyList(),
    val history: List<RemoteTrack> = emptyList(),
) {
    /** Bounds what one phone can make the TV store and load. */
    fun capped() =
        LibrarySnapshot(
            likedSongs = likedSongs.take(MAX_SONGS),
            playlists = playlists.take(MAX_PLAYLISTS).map { it.copy(songs = it.songs.take(MAX_SONGS)) },
            albums = albums.take(MAX_ITEMS),
            artists = artists.take(MAX_ITEMS),
            history = history.take(MAX_HISTORY),
        )

    companion object {
        const val MAX_SONGS = 5000
        const val MAX_PLAYLISTS = 200
        const val MAX_ITEMS = 2000
        const val MAX_HISTORY = 500
    }
}

/** One synced phone, shown as a profile on the TV. Its id is the paired phone's id. */
@Serializable
data class TvProfile(
    val id: String,
    val name: String,
    val syncedAt: Long,
)

/**
 * TV side of profiles: each paired phone's [LibrarySnapshot] is kept as a JSON file in app storage
 * (`tv_profiles/<id>.json`, plus an index), so nothing touches the Room schema. The active profile
 * (none = Guest, the TV's own library) is remembered across launches.
 */
@Singleton
class TvProfileStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        class Active(
            val profile: TvProfile,
            val library: LibrarySnapshot,
        ) {
            val likedIds: Set<String> = library.likedSongs.mapTo(HashSet()) { it.id }
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val dir = File(context.filesDir, "tv_profiles")
        private val indexFile = File(dir, "profiles.json")
        private val mutex = Mutex()

        private val _profiles = MutableStateFlow<List<TvProfile>?>(null)

        /** All profiles, newest sync first; null until loaded. */
        val profiles: StateFlow<List<TvProfile>?> = _profiles.asStateFlow()

        private val activeId: StateFlow<String?> =
            context.dataStore.data
                .map { it[TvActiveProfileKey]?.takeIf(String::isNotEmpty) }
                .distinctUntilChanged()
                .stateIn(scope, SharingStarted.Eagerly, null)

        private val _active = MutableStateFlow<Active?>(null)

        /** The selected profile with its library, or null for Guest. */
        val active: StateFlow<Active?> = _active.asStateFlow()

        init {
            scope.launch { _profiles.value = mutex.withLock { readIndex() } }
            scope.launch {
                // A re-sync changes the profile's syncedAt, so the active one is reloaded then too
                combine(activeId, _profiles.filterNotNull()) { id, profiles -> profiles.firstOrNull { it.id == id } }
                    .distinctUntilChanged()
                    .collectLatest { profile ->
                        _active.value = profile?.let { p -> read(p.id)?.let { Active(p, it) } }
                    }
            }
        }

        suspend fun select(id: String?) {
            context.safeDataStoreEdit { it[TvActiveProfileKey] = id.orEmpty() }
        }

        suspend fun save(
            id: String,
            name: String,
            library: LibrarySnapshot,
        ) {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    dir.mkdirs()
                    writeAtomically(profileFile(id), json.encodeToString(library))
                    val profile = TvProfile(id, name, System.currentTimeMillis())
                    val profiles = listOf(profile) + readIndex().filterNot { it.id == id }
                    writeAtomically(indexFile, json.encodeToString(profiles))
                    _profiles.value = profiles
                }
            }
        }

        suspend fun delete(id: String) {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    profileFile(id).delete()
                    val profiles = readIndex().filterNot { it.id == id }
                    writeAtomically(indexFile, json.encodeToString(profiles))
                    _profiles.value = profiles
                }
            }
            if (activeId.value == id) select(null)
        }

        private fun read(id: String): LibrarySnapshot? =
            runCatching { profileFile(id).inputStream().buffered().use { decode(it) } }
                .onFailure { Timber.tag(TAG).w(it, "Couldn't read profile $id") }
                .getOrNull()

        private fun readIndex(): List<TvProfile> =
            runCatching { json.decodeFromString<List<TvProfile>>(indexFile.readText()) }.getOrDefault(emptyList())

        private fun profileFile(id: String) = File(dir, "${id.filter { it.isLetterOrDigit() || it == '-' }}.json")

        private fun writeAtomically(
            file: File,
            text: String,
        ) {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("Couldn't write ${file.name}")
            }
        }

        companion object {
            private const val TAG = "TvProfileStore"

            val json =
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }

            @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
            fun decode(input: InputStream): LibrarySnapshot = json.decodeFromStream(LibrarySnapshot.serializer(), input)
        }
    }

/** Fails once more than [limit] bytes were read, so an oversized (or zip-bomb) upload is rejected. */
class LimitedInputStream(
    input: InputStream,
    private val limit: Long,
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count(1)
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val n = super.read(b, off, len)
        if (n > 0) count(n.toLong())
        return n
    }

    private fun count(n: Long) {
        count += n
        if (count > limit) throw IOException("Too large")
    }
}
