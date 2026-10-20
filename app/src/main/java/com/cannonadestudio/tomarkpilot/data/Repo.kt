package com.cannonadestudio.tomarkpilot.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    /** Default data location (GitHub). data/config.json there can redirect every app to the owner's server. */
    const val GITHUB_DATA = "https://raw.githubusercontent.com/cannonadestudio/Tomark-Service/main/data"

    @Volatile
    var dataBase: String = GITHUB_DATA
        private set

    private fun url(name: String) = "${dataBase.trimEnd('/')}/$name.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private lateinit var app: Context
    private val lock = Any()

    lateinit var types: List<AircraftType>
        private set

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _feed = MutableStateFlow(BulletinFeed())
    val feed: StateFlow<BulletinFeed> = _feed.asStateFlow()

    private val _contacts = MutableStateFlow(ContactFeed())
    val contactFeed: StateFlow<ContactFeed> = _contacts.asStateFlow()

    val jsonCodec: Json get() = json

    private val stateFile get() = File(app.filesDir, "state.json")
    private val contactsCache get() = File(app.filesDir, "contacts_cache.json")
    private val typesCache get() = File(app.filesDir, "types_cache.json")
    private val newsCache get() = File(app.filesDir, "news_cache.json")
    private val baseCache get() = File(app.filesDir, "data_base.txt")

    private val examinersCache get() = File(app.filesDir, "examiners_cache.json")
    private val _examiners = MutableStateFlow(ContactFeed())
    /** Examiner lists (public ULC data) – empty until the user downloads them. */
    val examiners: StateFlow<ContactFeed> = _examiners.asStateFlow()

    private val _news = MutableStateFlow(NewsFeed())
    val news: StateFlow<NewsFeed> = _news.asStateFlow()
    private val feedCache get() = File(app.filesDir, "bulletins_cache.json")

    fun init(context: Context) {
        synchronized(lock) {
            if (::app.isInitialized) return
            app = context.applicationContext
            types = runCatching { json.decodeFromString<List<AircraftType>>(typesCache.readText()) }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: json.decodeFromString(readAsset("types.json"))
            _feed.value = loadFeed()
            runCatching { baseCache.readText().trim() }.getOrNull()?.takeIf { it.startsWith("https://") }?.let { dataBase = it }
            _examiners.value = runCatching { json.decodeFromString<ContactFeed>(examinersCache.readText()) }.getOrNull() ?: ContactFeed()
            _news.value = runCatching { json.decodeFromString<NewsFeed>(newsCache.readText()) }.getOrNull() ?: NewsFeed()
            _contacts.value = runCatching { json.decodeFromString<ContactFeed>(contactsCache.readText()) }.getOrNull()
                ?: json.decodeFromString(readAsset("contacts.json"))
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
            val conn = URL(url("bulletins")).openConnection() as HttpURLConnection
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

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Reads data/config.json on GitHub; a dataBaseUrl there moves all data downloads to that server. */
    private fun resolveBase() {
        runCatching {
            val cfg = json.decodeFromString<RemoteConfig>(download("$GITHUB_DATA/config.json"))
            val b = cfg.dataBaseUrl?.trim()?.takeIf { it.startsWith("https://") } ?: GITHUB_DATA
            dataBase = b
            baseCache.writeText(b)
        }
    }

    /** Everything at once: data location, bulletins, contacts, types, announcements. */
    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        resolveBase()
        refreshBulletins()
        refreshContacts()
        refreshTypes()
        refreshNews()
        if (_state.value.examinersEnabled) refreshExaminers()
    }

    /** Downloads the public ULC examiner lists (button in Contacts). */
    suspend fun refreshExaminers(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = download(url("examiners"))
            val remote = json.decodeFromString<ContactFeed>(text)
            examinersCache.writeText(text)
            _examiners.value = remote
            if (!_state.value.examinersEnabled) update { it.copy(examinersEnabled = true) }
            remote.contacts.size
        }
    }

    /** Removes the downloaded examiner data from the phone. */
    fun removeExaminers() {
        examinersCache.delete()
        _examiners.value = ContactFeed()
        update { it.copy(examinersEnabled = false) }
    }

    suspend fun refreshNews(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = download(url("news"))
            val remote = json.decodeFromString<NewsFeed>(text)
            newsCache.writeText(text)
            _news.value = remote
            remote.messages.size
        }
    }

    /** Downloads the online contacts list (replaces the previous online list; local edits stay). */
    suspend fun refreshContacts(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = download(url("contacts"))
            val remote = json.decodeFromString<ContactFeed>(text)
            if (remote.contacts.isEmpty()) error("empty list")
            contactsCache.writeText(text)
            _contacts.value = remote
            remote.contacts.size
        }
    }

    /** Downloads aircraft types from the web panel. Types removed online but still used by an aircraft are kept. */
    suspend fun refreshTypes(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = download(url("types"))
            val remote = json.decodeFromString<List<AircraftType>>(text)
            if (remote.size < 2 || remote.any { it.speeds.isEmpty() }) error("suspicious types list")
            val used = _state.value.aircraft.map { it.typeId }.toSet()
            val kept = types.filter { it.id in used && remote.none { r -> r.id == it.id } }
            val merged = remote + kept
            typesCache.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AircraftType.serializer()), merged))
            types = merged
            _typesVersion.value++
            merged.size
        }
    }

    private val _typesVersion = MutableStateFlow(0)
    /** Bumped when [types] changes so the UI recomposes. */
    val typesVersion: StateFlow<Int> = _typesVersion.asStateFlow()

    /** Online + user contacts. A user contact with the same id replaces the online one. */
    fun contacts(s: AppState = _state.value, feed: ContactFeed = _contacts.value, exams: ContactFeed = _examiners.value): List<Contact> {
        val local = s.localContacts.associateBy { it.id }
        val online = feed.contacts + (if (s.examinersEnabled) exams.contacts.filter { e -> feed.contacts.none { it.id == e.id } } else emptyList())
        return (online.filter { it.id !in local } + s.localContacts).filter { it.id !in s.hiddenContactIds }
    }

    fun saveContact(c: Contact) = update { s ->
        s.copy(localContacts = s.localContacts.filterNot { it.id == c.id } + c, hiddenContactIds = s.hiddenContactIds - c.id)
    }

    fun deleteContact(id: String) = update { s ->
        val isFeed = _contacts.value.contacts.any { it.id == id }
        s.copy(
            localContacts = s.localContacts.filterNot { it.id == id },
            hiddenContactIds = if (isFeed) s.hiddenContactIds + id else s.hiddenContactIds,
        )
    }

    fun exportContactsJson(list: List<Contact>): String =
        json.encodeToString(ContactFeed.serializer(), ContactFeed(1, java.time.LocalDate.now().toString(), list))

    /** Imports a contacts JSON (same format as data/contacts.json, or a plain array). */
    fun importContacts(text: String): Int {
        val t = text.trim()
        val list = if (t.startsWith("[")) json.decodeFromString<List<Contact>>(t) else json.decodeFromString<ContactFeed>(t).contacts
        list.forEach { saveContact(it) }
        return list.size
    }

    // ---------------------------------------------------------------- logbook

    /** Recalculates hours of every linked aircraft from the chosen logbook export file. */
    suspend fun syncLogbook(): Result<Int> = withContext(Dispatchers.IO) {
        val uri = _state.value.logbookUri ?: return@withContext Result.success(0)
        runCatching {
            val text = app.contentResolver.openInputStream(android.net.Uri.parse(uri))!!.bufferedReader().use { it.readText() }
            val res = Logbook.parse(text)
            var changed = 0
            update { s ->
                s.copy(
                    aircraft = s.aircraft.map { a ->
                        val base = a.logbookBaseHours ?: return@map a
                        val sum = res.hoursByReg[Logbook.normReg(a.registration)] ?: 0.0
                        val h = Math.round((base + sum) * 10.0) / 10.0
                        if (h != a.hours) { changed++; a.copy(hours = h, hoursUpdatedEpochDay = java.time.LocalDate.now().toEpochDay()) } else a
                    },
                    logbookSyncedEpochDay = java.time.LocalDate.now().toEpochDay(),
                    logbookError = null,
                )
            }
            changed
        }.onFailure { e -> update { it.copy(logbookError = e.message ?: e.javaClass.simpleName) } }
    }

    /** Hours the logbook currently holds for [reg] (used to compute the base when linking). */
    suspend fun logbookHoursFor(reg: String): Double? = withContext(Dispatchers.IO) {
        val uri = _state.value.logbookUri ?: return@withContext null
        runCatching {
            val text = app.contentResolver.openInputStream(android.net.Uri.parse(uri))!!.bufferedReader().use { it.readText() }
            Logbook.parse(text).hoursByReg[Logbook.normReg(reg)] ?: 0.0
        }.getOrNull()
    }

    // ---------------------------------------------------------------- backup (Google Drive or any file the user picks)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backupJob: Job? = null

    /** Fields that belong to this phone and must not travel with a backup. */
    private fun withoutDeviceFields(s: AppState) = s.copy(
        backupUri = null, lastBackupMillis = null, backupError = null,
        logbookUri = null, logbookError = null, logbookSyncedEpochDay = null,
        aircraft = s.aircraft.map { it.copy(pohUri = null) },
    )

    /** Writes the backup a few seconds after the last change, so a burst of edits makes one upload. */
    private fun scheduleBackup() {
        if (_state.value.backupUri == null) return
        backupJob?.cancel()
        backupJob = ioScope.launch {
            delay(4_000)
            backupNow()
        }
    }

    suspend fun backupNow(): Result<Unit> = withContext(Dispatchers.IO) {
        val uri = _state.value.backupUri ?: return@withContext Result.success(Unit)
        runCatching {
            val text = json.encodeToString(AppState.serializer(), withoutDeviceFields(_state.value))
            val u = android.net.Uri.parse(uri)
            val out = runCatching { app.contentResolver.openOutputStream(u, "wt") }.getOrNull()
                ?: app.contentResolver.openOutputStream(u, "w")
                ?: error("cannot open backup file")
            out.use { it.write(text.toByteArray()) }
        }.onSuccess {
            setStateNoBackup { it.copy(lastBackupMillis = System.currentTimeMillis(), backupError = null) }
        }.onFailure { e ->
            setStateNoBackup { it.copy(backupError = e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Reads a backup; keeps this phone's own links (backup file, logbook file). Returns aircraft count. */
    suspend fun restoreBackup(uri: android.net.Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val restored = json.decodeFromString<AppState>(text)
            val cur = _state.value
            update {
                restored.copy(
                    backupUri = cur.backupUri, lastBackupMillis = cur.lastBackupMillis, backupError = null,
                    logbookUri = cur.logbookUri, logbookSyncedEpochDay = cur.logbookSyncedEpochDay,
                    disclaimerAccepted = true,
                )
            }
            restored.aircraft.size
        }
    }

    private fun setStateNoBackup(transform: (AppState) -> AppState) {
        synchronized(lock) {
            val next = transform(_state.value)
            _state.value = next
            persist(next)
        }
    }

    private fun persist(next: AppState) {
        val tmp = File(app.filesDir, "state.json.tmp")
        tmp.writeText(json.encodeToString(AppState.serializer(), next))
        tmp.renameTo(stateFile)
    }

    // ---------------------------------------------------------------- state helpers

    fun update(transform: (AppState) -> AppState) {
        setStateNoBackup(transform)
        scheduleBackup()
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
