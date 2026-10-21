package com.cannonadestudio.tomarkpilot.data

import kotlinx.serialization.Serializable

/** Text in the four app languages. Missing languages fall back to English. */
@Serializable
data class L10n(
    val pl: String,
    val en: String,
    val sk: String? = null,
    val fr: String? = null,
    val de: String? = null,
    val cs: String? = null,
) {
    fun get(lang: String): String = when (lang) {
        "pl" -> pl
        "sk" -> sk ?: cs ?: en
        "fr" -> fr ?: en
        "de" -> de ?: en
        "cs" -> cs ?: sk ?: en
        else -> en
    }

    companion object {
        fun same(text: String) = L10n(text, text, text, text)
    }
}

// ---------------------------------------------------------------- type data (assets/types.json)

@Serializable
data class SpeedDef(
    val key: String,
    val label: L10n,
    val value: Double? = null,
    val unit: String = "kt",          // "kt" or "kmh"
    val source: String,
    val verified: Boolean,
    val zone: String = "normal",      // white | green | red | approach | normal
)

@Serializable
data class ManualItem(val label: L10n, val value: L10n, val source: String)

@Serializable
data class ManualSection(val id: String, val title: L10n, val items: List<ManualItem>)

@Serializable
data class TaskDef(
    val id: String,
    val title: L10n,
    val category: String,
    val intervalHours: Double? = null,
    val intervalMonths: Int? = null,
    val oneTimeAtHours: Double? = null,
    val toleranceHours: Double? = null,
    val mandatory: Boolean = true,
    val source: String = "",
    val verified: Boolean = false,
    val note: L10n? = null,
)

@Serializable
data class AircraftType(
    val id: String,
    val name: String,
    val family: String,
    val manufacturer: String = "",
    val category: String,        // EASA | UL
    val tcds: String? = null,
    val bulletinGroups: List<String>,
    val speeds: List<SpeedDef>,
    val manual: List<ManualSection>,
    val tasks: List<TaskDef>,
)

// ---------------------------------------------------------------- bulletins feed

@Serializable
data class Bulletin(
    val id: String,
    val date: String,               // yyyy-MM-dd
    val title: String,
    val groups: List<String>,
    val kind: String,               // AD | ASB | SB | SIB | DOC
    val mandatory: Boolean? = null, // null = check the document
    val url: String? = null,
)

@Serializable
data class BulletinFeed(val version: Int = 0, val updated: String = "", val bulletins: List<Bulletin> = emptyList())

// ---------------------------------------------------------------- contacts feed

@Serializable
data class Contact(
    val id: String,
    val name: String,
    /** manufacturer | dealer | rotax_distributor | rotax_service | mechanic | instructor | examiner */
    val kind: String,
    val brands: List<String> = emptyList(),
    val country: String = "",
    val city: String = "",
    val address: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val phone: String = "",
    val email: String = "",
    val web: String = "",
    val source: String = "",
    val note: String = "",
    /** Paid listing (set by the app owner on the server): highlighted until this date (yyyy-MM-dd). */
    val partnerUntil: String? = null,
    val promo: String = "",
    /** https address of the partner's logo. */
    val logo: String = "",
    /** Partner accepts "Book an inspection" requests sent through the server. */
    val requests: Boolean = false,
) {
    fun isPartner(today: String = java.time.LocalDate.now().toString()): Boolean = partnerUntil != null && partnerUntil >= today
}

/** Sponsor banner on the home screen (data/sponsor.json, managed in the panel). */
@Serializable
data class SponsorBanner(
    val id: String,
    val title: String = "",
    val text: String = "",
    val image: String = "",
    val url: String = "",
    val from: String = "",
    val until: String = "",
    val active: Boolean = true,
) {
    fun live(today: String = java.time.LocalDate.now().toString()) =
        active && (from.isBlank() || from <= today) && (until.isBlank() || until >= today)
}

@Serializable
data class SponsorFeed(val version: Int = 0, val updated: String = "", val banners: List<SponsorBanner> = emptyList())

@Serializable
data class ContactFeed(val version: Int = 0, val updated: String = "", val contacts: List<Contact> = emptyList())

// ---------------------------------------------------------------- announcements from the management system

@Serializable
data class News(val id: String, val date: String = "", val title: String, val text: String = "", val url: String? = null)

@Serializable
data class NewsFeed(val version: Int = 0, val updated: String = "", val messages: List<News> = emptyList())

/** data/config.json on GitHub: points all apps to the owner's own server when set. */
@Serializable
data class RemoteConfig(val dataBaseUrl: String? = null)

// ---------------------------------------------------------------- user data (filesDir/state.json)

@Serializable
data class TaskOverride(
    val intervalHours: Double? = null,
    val intervalMonths: Int? = null,
    val hidden: Boolean = false,
)

@Serializable
data class Aircraft(
    val id: String,
    val typeId: String,
    val registration: String,
    val serial: String = "",
    val engineSerial: String = "",
    /** Gearbox (propeller reduction) serial – optional; some Rotax bulletins list gearbox S/N. */
    val gearboxSerial: String = "",
    /** Year of manufacture – bulletins often apply to given production years / S/N ranges. */
    val yearBuilt: Int? = null,
    val hours: Double = 0.0,
    val hoursUpdatedEpochDay: Long? = null,
    /** Reference date for calendar items never done yet (delivery / last overhaul). */
    val referenceEpochDay: Long? = null,
    val speedOverrides: Map<String, Double> = emptyMap(),   // value in the speed's own unit
    val taskOverrides: Map<String, TaskOverride> = emptyMap(),
    val customTasks: List<TaskDef> = emptyList(),
    val pohUri: String? = null,
    val notes: String = "",
    /** Hours before the logbook started; null = not linked to the logbook. hours = base + logbook sum. */
    val logbookBaseHours: Double? = null,
)

@Serializable
data class CompletionRecord(
    val id: String,
    val aircraftId: String,
    val taskId: String,
    val epochDay: Long,
    val hours: Double,
    /** Hours at which the task was due when it was done (used for SB_SD4-03-2023 tolerances). */
    val dueAtHours: Double? = null,
    val note: String = "",
)

@Serializable
data class BulletinStatus(
    val aircraftId: String,
    val bulletinId: String,
    val status: String,           // DONE | NA
    val epochDay: Long,
    val hours: Double? = null,
    val note: String = "",
)

@Serializable
data class AppState(
    val aircraft: List<Aircraft> = emptyList(),
    val selectedId: String? = null,
    val completions: List<CompletionRecord> = emptyList(),
    val bulletinStatus: List<BulletinStatus> = emptyList(),
    val seenBulletinIds: Set<String> = emptySet(),
    val notifiedBulletinIds: Set<String> = emptySet(),
    val useKmh: Boolean = false,
    val disclaimerAccepted: Boolean = false,
    /** Contacts added or edited by the user (an edited feed contact keeps its id). */
    val localContacts: List<Contact> = emptyList(),
    val hiddenContactIds: Set<String> = emptySet(),
    /** Logbook export file (content:// URI) chosen by the user. */
    val logbookUri: String? = null,
    val logbookSyncedEpochDay: Long? = null,
    val logbookError: String? = null,
    val dismissedNewsIds: Set<String> = emptySet(),
    /** Public ULC examiner lists are downloaded only after the user asks for them. */
    val examinersEnabled: Boolean = false,
    /** Random id for the anonymous install counter on the owner's server (no personal data). */
    val installId: String? = null,
    val lastPingEpochDay: Long? = null,
    /** Backup file chosen by the user (e.g. on Google Drive). Device-specific, never restored from a backup. */
    val backupUri: String? = null,
    val lastBackupMillis: Long? = null,
    val backupError: String? = null,
    /** Owner's contact details remembered for "Book an inspection" (stay on the phone and in the backup). */
    val reqName: String = "",
    val reqPhone: String = "",
    val reqEmail: String = "",
)
