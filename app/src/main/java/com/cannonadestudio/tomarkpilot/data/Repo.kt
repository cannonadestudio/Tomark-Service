package com.cannonadestudio.tomarkpilot.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Single source of truth: bundled aircraft data, bulletin feed and the owner's records. */
object Repo {
    /** Online bulletin list. Edit data/bulletins.json in the GitHub repo to publish new bulletins. */
    const val FEED_URL =
        "https://raw.githubusercontent.com/cannonadestudio/Tomark-Service/main/data/bulletins.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private lateinit var app: Context
    private val lock = Any()

    lateinit var types: List<AircraftType>
        private set

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _feed = MutableStateFlow(BulletinFeed())
    val feed: StateFlow<BulletinFeed> = _feed.asStateFlow()

    private val stateFile get() = File(app.filesDir, "state.json")
    private val feedCache get() = File(app.filesDir, "bulletins_cache.json")

    fun init(context: Context) {
        synchronized(lock) {
            if (::app.isInitialized) return
            app = context.applicationContext
            types = json.decodeFromString(readAsset("types.json"))
            _feed.value = loadFeed()
            val loaded = runCatching { json.decodeFromString<AppState>(stateFile.readText()) }.getOrNull()
            _state.value = loaded ?: AppState(
                // Bundled bulletins are not "new" on first install.
                seenBulletinIds = _feed.value.bulletins.map { it.id }.toSet(),
                notifiedBulletinIds = _feed.value.bulletins.map { it.id }.toSet(),
            )
        }
    }

    private fun readAsset(name: String) = app.assets.open(name).bufferedReader().use { it.readText() }

    private fun loadFeed(): BulletinFeed {
        val bundled = json.decodeFromString<BulletinFeed>(readAsset("bulletins.json"))
        val cached = runCatching { json.decodeFromString<BulletinFeed>(feedCache.readText()) }.getOrNull()
        return if (cached == null) bundled else merge(bundled, cached)
    }

    private fun merge(base: BulletinFeed, newer: BulletinFeed): BulletinFeed {
        val map = LinkedHashMap<String, Bulletin>()
        base.bulletins.forEach { map[it.id] = it }
        newer.bulletins.forEach { map[it.id] = it }
        return BulletinFeed(
            version = maxOf(base.version, newer.version),
            updated = maxOf(base.updated, newer.updated),
            bulletins = map.values.sortedByDescending { it.date },
        )
    }

    /** Downloads the online list. Returns the number of bulletins that were not known before. */
    suspend fun refreshBulletins(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(FEED_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Cache-Control", "no-cache")
            try {
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val remote = json.decodeFromString<BulletinFeed>(text)
                val before = _feed.value.bulletins.map { it.id }.toSet()
                val merged = merge(_feed.value, remote)
                feedCache.writeText(json.encodeToString(BulletinFeed.serializer(), remote))
                _feed.value = merged
                merged.bulletins.count { it.id !in before }
            } finally {
                conn.disconnect()
            }
        }
    }

    // ---------------------------------------------------------------- state helpers

    fun update(transform: (AppState) -> AppState) {
        synchronized(lock) {
            val next = transform(_state.value)
            _state.value = next
            val tmp = File(app.filesDir, "state.json.tmp")
            tmp.writeText(json.encodeToString(AppState.serializer(), next))
            tmp.renameTo(stateFile)
        }
    }

    fun type(id: String): AircraftType? = types.firstOrNull { it.id == id }

    fun selected(s: AppState = _state.value): Aircraft? =
        s.aircraft.firstOrNull { it.id == s.selectedId } ?: s.aircraft.firstOrNull()

    fun newId(): String = UUID.randomUUID().toString()

    fun upsertAircraft(a: Aircraft) = update { s ->
        val exists = s.aircraft.any { it.id == a.id }
        s.copy(
            aircraft = if (exists) s.aircraft.map { if (it.id == a.id) a else it } else s.aircraft + a,
            selectedId = a.id,
        )
    }

    fun deleteAircraft(id: String) = update { s ->
        s.copy(
            aircraft = s.aircraft.filterNot { it.id == id },
            completions = s.completions.filterNot { it.aircraftId == id },
            bulletinStatus = s.bulletinStatus.filterNot { it.aircraftId == id },
            selectedId = if (s.selectedId == id) s.aircraft.firstOrNull { it.id != id }?.id else s.selectedId,
        )
    }

    fun bulletinsFor(a: Aircraft, feed: BulletinFeed = _feed.value): List<Bulletin> {
        val groups = type(a.typeId)?.bulletinGroups ?: return emptyList()
        return feed.bulletins.filter { b -> b.groups.any { it in groups } }
    }

    /** Bulletins (not plain documents) without DONE/NA status for this aircraft. */
    fun openBulletins(a: Aircraft, s: AppState = _state.value, feed: BulletinFeed = _feed.value): List<Bulletin> {
        val handled = s.bulletinStatus.filter { it.aircraftId == a.id }.map { it.bulletinId }.toSet()
        return bulletinsFor(a, feed).filter { it.kind != "DOC" && it.id !in handled }
    }
}
