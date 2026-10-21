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

    private val enginesCache get() = File(app.filesDir, "engines_cache.json")
    /** Engine catalogue (inspections + bulletin groups per engine family). */
    lateinit var engines: List<EngineType>
        private set
    private lateinit var bundledTypes: List<AircraftType>

    private val sponsorCache get() = File(app.filesDir, "sponsor_cache.json")
    private val _sponsor = MutableStateFlow(SponsorFeed())
    val sponsor: StateFlow<SponsorFeed> = _sponsor.asStateFlow()

    private val _news = MutableStateFlow(NewsFeed())
    val news: StateFlow<NewsFeed> = _news.asStateFlow()
    private val feedCache get() = File(app.filesDir, "bulletins_cache.json")

    fun init(context: Context) {
        synchronized(lock) {
            if (::app.isInitialized) return
            app = context.applicationContext
            bundledTypes = json.decodeFromString(readAsset("types.json"))
            types = withBundled(runCatching { json.decodeFromString<List<AircraftType>>(typesCache.readText()) }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: bundledTypes)
            val bundledEngines = json.decodeFromString<List<EngineType>>(readAsset("engines.json"))
            engines = runCatching { json.decodeFromString<List<EngineType>>(enginesCache.readText()) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { c -> c + bundledEngines.filter { b -> c.none { it.id == b.id } } } ?: bundledEngines
            _feed.value = loadFeed()
            runCatching { baseCache.readText().trim() }.getOrNull()?.takeIf { it.startsWith("https://") }?.let { dataBase = it }
            _examiners.value = runCatching { json.decodeFromString<ContactFeed>(examinersCache.readText()) }.getOrNull() ?: ContactFeed()
            _news.value = runCatching { json.decodeFromString<NewsFeed>(newsCache.readText()) }.getOrNull() ?: NewsFeed()
            _sponsor.value = runCatching { json.decodeFromString<SponsorFeed>(sponsorCache.readText()) }.getOrNull() ?: SponsorFeed()
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
            val text = downloadData("bulletins")
            val remote = json.decodeFromString<BulletinFeed>(text)
            val before = _feed.value.bulletins.map { it.id }.toSet()
            val merged = merge(_feed.value, remote)
            feedCache.writeText(json.encodeToString(BulletinFeed.serializer(), remote))
            _feed.value = merged
            merged.bulletins.count { it.id !in before }
        }
    }

    /** Data file from the configured source; if the owner's server fails, the GitHub copy is used. */
    private fun downloadData(name: String): String = try {
        download(url(name)).also { if (it.trimStart().firstOrNull() !in listOf('{', '[')) error("not JSON") }
    } catch (e: Exception) {
        if (dataBase == GITHUB_DATA) throw e
        download("$GITHUB_DATA/$name.json")
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
        refreshEngines()
        refreshNews()
        refreshSponsor()
        if (_state.value.examinersEnabled) refreshExaminers()
        pingIfDue()
    }

    /** True when apps read from the owner's server (not GitHub) – needed for submissions and the install counter. */
    val serverConnected: Boolean get() = dataBase != GITHUB_DATA

    private fun apiUrl(action: String) = dataBase.trimEnd('/').removeSuffix("/data") + "/admin/api.php?a=" + action

    private fun post(url: String, body: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    /** Random install id (no personal data); shown in "About" so the owner can find this install in the panel. */
    fun ensureInstallId(): String {
        _state.value.installId?.let { return it }
        val id = UUID.randomUUID().toString()
        setStateNoBackup { it.copy(installId = id) }
        return id
    }

    /** Short form shown to the user and searched in the panel. */
    fun installCode(): String = ensureInstallId().replace("-", "").take(8).uppercase()

    /**
     * Once a day (or when [force]d, e.g. "Restore purchase"): anonymous random install id, app version, language,
     * Android version, aircraft types. The answer says whether the owner granted Pro to this install.
     */
    private fun pingIfDue(force: Boolean = false) {
        if (!serverConnected) return
        val s = _state.value
        val today = java.time.LocalDate.now().toEpochDay()
        if (!force && s.lastPingEpochDay == today) return
        val id = ensureInstallId()
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: ""
        val body = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(id))
            put("v", kotlinx.serialization.json.JsonPrimitive(version))
            put("lang", kotlinx.serialization.json.JsonPrimitive(java.util.Locale.getDefault().language))
            put("sdk", kotlinx.serialization.json.JsonPrimitive(android.os.Build.VERSION.SDK_INT))
            put("pro", kotlinx.serialization.json.JsonPrimitive(Billing.pro.value.owned))
            put("ac", kotlinx.serialization.json.JsonPrimitive(s.aircraft.size))
            put("types", kotlinx.serialization.json.JsonArray(s.aircraft.mapNotNull { type(it.typeId)?.name }.distinct().take(5).map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString()
        val answer = runCatching { postRead(apiUrl("ping"), body) }.getOrNull() ?: return
        val granted = runCatching {
            (json.parseToJsonElement(answer) as kotlinx.serialization.json.JsonObject)["pro"]
                ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content == "true" } ?: false
        }.getOrDefault(false)
        setStateNoBackup { it.copy(lastPingEpochDay = today, proGranted = granted) }
    }

    /** Asks the server now whether Pro was granted to this install. */
    suspend fun checkProGrant() = withContext(Dispatchers.IO) {
        if (!serverConnected) resolveBase()
        lastGrantCheck = System.currentTimeMillis()
        pingIfDue(force = true)
    }

    @Volatile private var lastGrantCheck = 0L

    /** On every app start / resume without Pro: check the manual grant (at most every 10 minutes). */
    suspend fun checkProGrantIfStale() {
        if (_state.value.proGranted || System.currentTimeMillis() - lastGrantCheck < 10 * 60_000L) return
        checkProGrant()
    }

    /** POST that returns the body of a 200 answer (null otherwise). */
    private fun postRead(url: String, body: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Sends a contact proposal to the owner's server; it appears there as a submission to approve. */
    suspend fun submitContact(c: Contact, message: String, from: String, consent: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!serverConnected) error("no server")
            val contact = json.encodeToJsonElement(Contact.serializer(), c)
            val body = kotlinx.serialization.json.buildJsonObject {
                put("contact", contact)
                put("message", kotlinx.serialization.json.JsonPrimitive(message))
                put("from", kotlinx.serialization.json.JsonPrimitive(from))
                put("consent", kotlinx.serialization.json.JsonPrimitive(consent))
            }.toString()
            val code = post(apiUrl("submit"), body)
            if (code != 200) error("HTTP $code")
        }
    }

    suspend fun refreshSponsor(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = downloadData("sponsor")
            val remote = json.decodeFromString<SponsorFeed>(text)
            sponsorCache.writeText(text)
            _sponsor.value = remote
            remote.banners.size
        }
    }

    // ---------------------------------------------------------------- partner / sponsor counters and service requests

    private val viewedThisRun = java.util.Collections.synchronizedSet(HashSet<String>())
    private val trackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Anonymous counter for partners and sponsors on the owner's server: [type] is view, call, email, web, map,
     * sponsor_view or sponsor_click; [target] the contact id (or "sponsor:<id>"). Views count once per app run.
     */
    fun track(type: String, target: String) {
        if (!serverConnected) return
        if ((type == "view" || type == "sponsor_view") && !viewedThisRun.add("$type|$target")) return
        trackScope.launch {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("events", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("t", kotlinx.serialization.json.JsonPrimitive(type))
                        put("c", kotlinx.serialization.json.JsonPrimitive(target))
                    })
                })
            }.toString()
            runCatching { post(apiUrl("event"), body) }
        }
    }

    /** Can this contact receive an inspection request through the server (otherwise the app opens an e-mail)? */
    fun takesRequests(c: Contact) = serverConnected && c.requests && c.email.isNotBlank() && c.isPartner()

    /** Sends "Book an inspection" to a partner service via the owner's server. Result: true = e-mail sent to the service. */
    suspend fun sendServiceRequest(
        c: Contact, a: Aircraft, work: String, preferredDate: String,
        name: String, phone: String, email: String, message: String,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val P = { v: String -> kotlinx.serialization.json.JsonPrimitive(v) }
            val body = kotlinx.serialization.json.buildJsonObject {
                put("contactId", P(c.id))
                put("aircraft", kotlinx.serialization.json.buildJsonObject {
                    put("registration", P(a.registration))
                    put("type", P(type(a.typeId)?.name ?: a.typeId))
                    put("hours", P(String.format(java.util.Locale.US, "%.1f", a.hours)))
                    put("engineSerial", P(a.engineSerial))
                    put("year", P(a.yearBuilt?.toString() ?: ""))
                })
                put("work", P(work)); put("preferredDate", P(preferredDate))
                put("name", P(name)); put("phone", P(phone)); put("email", P(email)); put("message", P(message))
                put("consent", kotlinx.serialization.json.JsonPrimitive(true))
            }.toString()
            val conn = URL(apiUrl("request")).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000; conn.readTimeout = 20_000
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader().use { it.readText() }.contains("\"mailed\":true")
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Downloads the public ULC examiner lists (button in Contacts). */
    suspend fun refreshExaminers(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var text = downloadData("examiners")
            var remote = json.decodeFromString<ContactFeed>(text)
            // Server copy with nothing published yet → the public ULC list kept on GitHub.
            if (remote.contacts.isEmpty() && dataBase != GITHUB_DATA) {
                text = download("$GITHUB_DATA/examiners.json")
                remote = json.decodeFromString<ContactFeed>(text)
            }
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
            val text = downloadData("news")
            val remote = json.decodeFromString<NewsFeed>(text)
            newsCache.writeText(text)
            _news.value = remote
            remote.messages.size
        }
    }

    /** Downloads the online contacts list (replaces the previous online list; local edits stay). */
    suspend fun refreshContacts(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = downloadData("contacts")
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
            val text = downloadData("types")
            val remote = json.decodeFromString<List<AircraftType>>(text)
            if (remote.size < 2 || remote.any { it.speeds.isEmpty() }) error("suspicious types list")
            val used = _state.value.aircraft.map { it.typeId }.toSet()
            val kept = types.filter { it.id in used && remote.none { r -> r.id == it.id } }
            val merged = withBundled(remote + kept)
            typesCache.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AircraftType.serializer()), merged))
            types = merged
            _typesVersion.value++
            merged.size
        }
    }

    /**
     * Types from the server/cache plus every bundled type the list lacks (new trikes, gyroplanes…).
     * Old-format entries (no engine field) take engine, kind and bulletin groups from the bundled copy.
     */
    private fun withBundled(list: List<AircraftType>): List<AircraftType> {
        val byId = bundledTypes.associateBy { it.id }
        val fixed = list.map { t ->
            val b = byId[t.id]
            if (t.engine.isBlank() && b != null) t.copy(engine = b.engine, kind = b.kind, bulletinGroups = b.bulletinGroups) else t
        }
        return fixed + bundledTypes.filter { b -> fixed.none { it.id == b.id } }
    }

    suspend fun refreshEngines(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = downloadData("engines")
            val remote = json.decodeFromString<List<EngineType>>(text)
            if (remote.isEmpty() || remote.none { it.id == "rotax_912" }) error("suspicious engines list")
            enginesCache.writeText(text)
            engines = remote + engines.filter { e -> remote.none { it.id == e.id } }
            _typesVersion.value++
            remote.size
        }
    }

    private val _typesVersion = MutableStateFlow(0)
    /** Bumped when [types] changes so the UI recomposes. */
    val typesVersion: StateFlow<Int> = _typesVersion.asStateFlow()

    /** Online + user contacts. A user contact with the same id replaces the online one. */
    fun contacts(s: AppState = _state.value, feed: ContactFeed = _contacts.value, exams: ContactFeed = _examiners.value): List<Contact> {
        val local = s.localContacts.associateBy { it.id }
        val online = feed.contacts + (if (s.examinersEnabled) exams.contacts.filter { e -> feed.contacts.none { it.id == e.id } } else emptyList())
        val byId = online.associateBy { it.id }
        // A contact edited on the phone keeps the partner status that the server sets.
        val mine = s.localContacts.map { l ->
            byId[l.id]?.let { o -> l.copy(partnerUntil = o.partnerUntil, promo = o.promo, logo = o.logo, requests = o.requests) } ?: l
        }
        return (online.filter { it.id !in local } + mine).filter { it.id !in s.hiddenContactIds }
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
        }.onFailure { e -> update { it.copy(logbookError = (e as? Logbook.LogbookException)?.code ?: e.message ?: e.javaClass.simpleName) } }
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
        logbookUri = null, logbookError = null, logbookSyncedEpochDay = null, proCached = false, proGranted = false,
        installId = null, lastPingEpochDay = null,
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
                    disclaimerAccepted = true, proCached = cur.proCached, proGranted = cur.proGranted, lastPingEpochDay = null,
                    installId = cur.installId,
                )
            }
            restored.aircraft.size
        }
    }

    fun setProCached(owned: Boolean) {
        if (_state.value.proCached != owned) setStateNoBackup { it.copy(proCached = owned) }
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

    fun type(id: String): AircraftType? = types.firstOrNull { it.id == id } ?: _state.value.customTypes.firstOrNull { it.id == id }

    /** Catalogue types followed by the owner's own types. */
    fun allTypes(s: AppState = _state.value): List<AircraftType> = types + s.customTypes

    fun engine(id: String?): EngineType? = engines.firstOrNull { it.id == id }

    /** The engine of this aircraft: the owner's choice, else the type's default, else Rotax 912. */
    fun engineOf(a: Aircraft): EngineType =
        engine(a.engineId) ?: engine(type(a.typeId)?.engine) ?: engine("rotax_912") ?: engines.first()

    fun saveCustomType(t: AircraftType) = update { s -> s.copy(customTypes = s.customTypes.filterNot { it.id == t.id } + t) }

    fun deleteCustomType(id: String) = update { s -> s.copy(customTypes = s.customTypes.filterNot { it.id == id }) }

    /** Bulletin groups of this aircraft: airframe groups of the type + the engine's groups. */
    fun bulletinGroups(a: Aircraft): Set<String> {
        val t = type(a.typeId)
        val airframe = (t?.bulletinGroups ?: emptyList()).filterNot { it.startsWith("ROTAX_") }.flatMap { g ->
            // old server data: one EVEKTOR group for all models
            if (g == "EVEKTOR") listOf(evektorGroup(t?.name ?: "")) else listOf(g)
        }
        return (airframe + engineOf(a).bulletinGroups).toSet()
    }

    private fun evektorGroup(name: String) = name.lowercase().let {
        when { "harmony" in it -> "EVEKTOR_HARMONY"; "rtc" in it -> "EVEKTOR_RTC"; "sportstar" in it -> "EVEKTOR_SPORTSTAR"; else -> "EVEKTOR_EV97" }
    }

    /** Groups of a bulletin; old server copies still carry one EVEKTOR group – split by the document number. */
    private fun groupsOf(b: Bulletin): List<String> {
        if ("EVEKTOR" !in b.groups) return b.groups
        val id = b.id.uppercase()
        val spec = when {
            id.startsWith("EVEKTOREV97") -> listOf("EVEKTOR_EV97")
            id.startsWith("EVEKTORSPORTSTAR") -> listOf("EVEKTOR_SPORTSTAR")
            id.startsWith("EVEKTORRTC") -> listOf("EVEKTOR_RTC")
            id.startsWith("EVEKTORHARMONY") -> listOf("EVEKTOR_HARMONY")
            else -> listOf("EVEKTOR_EV97", "EVEKTOR_SPORTSTAR", "EVEKTOR_RTC", "EVEKTOR_HARMONY")
        }
        return b.groups - "EVEKTOR" + spec
    }

    fun saveOwnBulletin(aircraftId: String, b: Bulletin) = update { s ->
        s.copy(ownBulletins = s.ownBulletins.filterNot { it.aircraftId == aircraftId && it.bulletin.id == b.id } + OwnBulletin(aircraftId, b))
    }

    fun deleteOwnBulletin(aircraftId: String, id: String) = update { s ->
        s.copy(
            ownBulletins = s.ownBulletins.filterNot { it.aircraftId == aircraftId && it.bulletin.id == id },
            bulletinStatus = s.bulletinStatus.filterNot { it.aircraftId == aircraftId && it.bulletinId == id },
        )
    }

    fun isOwnBulletin(a: Aircraft, id: String, s: AppState = _state.value) = s.ownBulletins.any { it.aircraftId == a.id && it.bulletin.id == id }

    fun saveLogEntry(e: LogEntry) = update { s -> s.copy(logEntries = s.logEntries.filterNot { it.id == e.id } + e) }

    fun deleteLogEntry(id: String) = update { s -> s.copy(logEntries = s.logEntries.filterNot { it.id == id }) }

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
            logEntries = s.logEntries.filterNot { it.aircraftId == id },
            ownBulletins = s.ownBulletins.filterNot { it.aircraftId == id },
            selectedId = if (s.selectedId == id) s.aircraft.firstOrNull { it.id != id }?.id else s.selectedId,
        )
    }

    fun bulletinsFor(a: Aircraft, feed: BulletinFeed = _feed.value, s: AppState = _state.value): List<Bulletin> {
        val groups = bulletinGroups(a)
        val own = s.ownBulletins.filter { it.aircraftId == a.id }.map { it.bulletin }
        return (own + feed.bulletins.filter { b -> groupsOf(b).any { it in groups } && own.none { o -> o.id == b.id } })
            .sortedByDescending { it.date }
    }

    /** Bulletins (not plain documents) without DONE/NA status for this aircraft. */
    fun openBulletins(a: Aircraft, s: AppState = _state.value, feed: BulletinFeed = _feed.value): List<Bulletin> {
        val handled = s.bulletinStatus.filter { it.aircraftId == a.id }.map { it.bulletinId }.toSet()
        // Service Instructions / Letters marked not mandatory are information only – listed, not counted as open.
        return bulletinsFor(a, feed, s).filter { it.kind != "DOC" && !(it.kind == "SIB" && it.mandatory == false) && it.id !in handled }
    }
}
