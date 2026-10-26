@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cannonadestudio.tomarkpilot.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.AircraftType
import com.cannonadestudio.tomarkpilot.data.AppState
import com.cannonadestudio.tomarkpilot.data.Contact
import com.cannonadestudio.tomarkpilot.data.ContactFeed
import com.cannonadestudio.tomarkpilot.data.Repo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val CONTACT_KINDS = listOf("dealer", "manufacturer", "rotax_service", "rotax_distributor", "maintenance", "mechanic", "instructor", "school", "examiner", "ame", "aemc")

@Composable
fun kindName(k: String): String = stringResource(
    when (k) {
        "manufacturer" -> R.string.kind_manufacturer
        "dealer" -> R.string.kind_dealer
        "rotax_distributor" -> R.string.kind_rotax_distributor
        "rotax_service" -> R.string.kind_rotax_service
        "mechanic" -> R.string.kind_mechanic
        "maintenance" -> R.string.kind_maintenance
        "instructor" -> R.string.kind_instructor
        "school" -> R.string.kind_school
        "ame" -> R.string.kind_ame
        "aemc" -> R.string.kind_aemc
        else -> R.string.kind_examiner
    }
)

private fun hasLocationPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun lastLocation(ctx: Context): Location? {
    if (!hasLocationPermission(ctx)) return null
    val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
    return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
}

fun lastLocationOrNull(ctx: Context): Location? = lastLocation(ctx)
fun distanceKmOrNull(loc: Location?, c: Contact): Double? = distanceKm(loc, c)

/** Active partners within this distance (or with unknown distance) are listed first. */
private const val PARTNER_RADIUS_KM = 250.0

private fun distanceKm(loc: Location?, c: Contact): Double? {
    if (loc == null || c.lat == null || c.lon == null) return null
    val r = FloatArray(1)
    Location.distanceBetween(loc.latitude, loc.longitude, c.lat, c.lon, r)
    return r[0] / 1000.0
}

private fun open(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, intent.dataString ?: "", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun ContactsScreen(state: AppState, feed: ContactFeed, type: AircraftType?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var kinds by remember { mutableStateOf(setOf<String>()) }
    var onlyMyBrand by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var loc by remember { mutableStateOf(lastLocation(ctx)) }
    var editing by remember { mutableStateOf<Contact?>(null) }
    var creating by remember { mutableStateOf(false) }
    // Shared entries (partners, services, schools, doctors…) are read-only; only own entries can be edited.
    var viewing by remember { mutableStateOf<Contact?>(null) }
    var submitFor by remember { mutableStateOf<Contact?>(null) }
    var menu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) loc = lastLocation(ctx)
    }
    LaunchedEffect(Unit) { if (!hasLocationPermission(ctx)) permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val msg = runCatching {
                val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                ctx.getString(R.string.contacts_imported, Repo.importContacts(text))
            }.getOrElse { ctx.getString(R.string.contacts_import_fail, it.message ?: "") }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    val brandWords = remember(type) {
        listOfNotNull(type?.manufacturer, type?.name?.split(" ")?.firstOrNull()).map { it.lowercase() }.filter { it.length > 2 }
    }
    val localIds = state.localContacts.map { it.id }.toSet()
    val exams by Repo.examiners.collectAsState()
    var examLoading by remember { mutableStateOf(false) }
    var examMenu by remember { mutableStateOf(false) }
    val all = Repo.contacts(state, feed, exams)
    val list = all
        .filter { kinds.isEmpty() || it.kind in kinds }
        .filter { !onlyMyBrand || it.kind.startsWith("rotax") || it.brands.any { b -> brandWords.any { w -> b.lowercase().contains(w) } } }
        .filter {
            query.isBlank() || listOf(it.name, it.city, it.country, it.brands.joinToString(" ")).any { f -> f.contains(query.trim(), ignoreCase = true) }
        }
        .sortedWith(compareBy(
            { if (it.isPartner() && (distanceKm(loc, it) ?: 0.0) <= PARTNER_RADIUS_KM) 0 else 1 },
            { distanceKm(loc, it) ?: Double.MAX_VALUE }, { it.country }, { it.name },
        ))
    var requestFor by remember { mutableStateOf<Contact?>(null) }
    val selectedAircraft = Repo.selected(state)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.contacts_title), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (loc != null) stringResource(R.string.sorted_by_distance) else stringResource(R.string.no_location),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            // Public ULC lists (examiners, aeromedical examiners) – small menu instead of a whole card
                            Box {
                                TextButton(onClick = { examMenu = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                                    if (examLoading) CircularProgressIndicator(Modifier.size(16.dp)) else Text(stringResource(R.string.examiners_menu))
                                    Icon(Icons.Filled.ArrowDropDown, null)
                                }
                                DropdownMenu(examMenu, { examMenu = false }) {
                                    Text(
                                        stringResource(R.string.examiners_title) + "\n" + (if (state.examinersEnabled && exams.contacts.isNotEmpty())
                                            stringResource(R.string.examiners_loaded, exams.contacts.size, exams.updated) else stringResource(R.string.examiners_hint)),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(260.dp),
                                    )
                                    DropdownMenuItem(text = { Text(stringResource(if (state.examinersEnabled) R.string.examiners_refresh else R.string.examiners_download)) }, onClick = {
                                        examMenu = false; examLoading = true
                                        scope.launch {
                                            val r = Repo.refreshExaminers()
                                            examLoading = false
                                            Toast.makeText(ctx, r.fold({ ctx.getString(R.string.examiners_done, it) }, { ctx.getString(R.string.refresh_fail) + " (" + (it.message ?: it.javaClass.simpleName) + ")" }), Toast.LENGTH_LONG).show()
                                        }
                                    })
                                    if (state.examinersEnabled) DropdownMenuItem(text = { Text(stringResource(R.string.examiners_remove)) }, onClick = { examMenu = false; Repo.removeExaminers() })
                                    DropdownMenuItem(text = { Text("ULC – egzaminy / lekarze") }, onClick = {
                                        examMenu = false
                                        open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://ulc.gov.pl/personel-lotniczy/komisja-egzaminacyjna/egzaminy-praktyczne")))
                                    })
                                }
                            }
                            if (loading) CircularProgressIndicator(Modifier.size(24.dp)) else IconButton(onClick = {
                                loading = true
                                scope.launch {
                                    val r = Repo.refreshContacts()
                                    loading = false
                                    loc = lastLocation(ctx)
                                    Toast.makeText(
                                        ctx,
                                        r.fold({ ctx.getString(R.string.contacts_refreshed, it) }, { ctx.getString(R.string.refresh_fail) }),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
                            if (loc == null) IconButton(onClick = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                                Icon(Icons.Filled.MyLocation, null)
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.contacts_import)) }, onClick = {
                                        menu = false; importer.launch(arrayOf("application/json", "text/plain", "*/*"))
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.contacts_export)) }, onClick = {
                                        menu = false
                                        val send = Intent(Intent.ACTION_SEND).setType("application/json")
                                            .putExtra(Intent.EXTRA_SUBJECT, "contacts.json")
                                            .putExtra(Intent.EXTRA_TEXT, Repo.exportContactsJson(state.localContacts))
                                        open(ctx, Intent.createChooser(send, null))
                                    })
                                    if (state.hiddenContactIds.isNotEmpty()) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.contacts_unhide)) }, onClick = {
                                            menu = false; Repo.update { it.copy(hiddenContactIds = emptySet()) }
                                        })
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text(stringResource(R.string.contacts_search)) },
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (brandWords.isNotEmpty()) {
                                FilterChip(onlyMyBrand, { onlyMyBrand = !onlyMyBrand }, label = { Text(stringResource(R.string.only_my_type)) })
                            }
                            CONTACT_KINDS.forEach { k ->
                                FilterChip(k in kinds, { kinds = if (k in kinds) kinds - k else kinds + k }, label = { Text(kindName(k)) })
                            }
                        }
                    }
                }
                // Partners: always one compact line at the top, whatever category or search is chosen.
                val partners = all.filter { it.isPartner() }.sortedBy { distanceKm(loc, it) ?: Double.MAX_VALUE }
                val req = { c: Contact -> if (selectedAircraft != null && c.kind in SERVICE_KINDS && c.email.isNotBlank()) ({ requestFor = c }) else null }
                if (partners.size == 1) {
                    val c = partners[0]
                    PartnerLine(c, distanceKm(loc, c), Modifier.fillMaxWidth().padding(top = 8.dp), req(c)) { viewing = c }
                } else if (partners.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        partners.forEach { c -> PartnerLine(c, distanceKm(loc, c), Modifier.width(300.dp), req(c)) { viewing = c } }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            items(list.filter { !it.isPartner() }, key = { it.id }) { c ->
                ContactCard(c, distanceKm(loc, c), c.id in localIds, onRequest = if (selectedAircraft != null && c.kind in SERVICE_KINDS && c.email.isNotBlank() && c.isPartner()) ({ requestFor = c }) else null) { viewing = c }
                Spacer(Modifier.height(8.dp))
            }
            item {
                Text(
                    stringResource(R.string.contacts_hint),
                    style = MaterialTheme.typography.bodySmall, color = OnPage,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Spacer(Modifier.height(80.dp))
            }
        }
        androidx.compose.material3.SmallFloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, stringResource(R.string.contact_add)) }
    }

    viewing?.let { c -> ContactDetails(c, distanceKm(loc, c), onEdit = { viewing = null; editing = c }, onSubmit = { viewing = null; submitFor = c }) { viewing = null } }
    editing?.let { c -> ContactDialog(c, loc, isNew = false) { editing = null } }
    submitFor?.let { c -> SubmitDialog(c) { submitFor = null } }
    requestFor?.let { c -> selectedAircraft?.let { a -> ServiceRequestDialog(a, nextWorkText(a, state), c) { requestFor = null } } }
    if (creating) ContactDialog(null, loc, isNew = true, onSaved = { saved -> if (Repo.serverConnected) submitFor = saved }) { creating = false }
}

@Composable
private fun PartnerLine(c: Contact, km: Double?, modifier: Modifier, onRequest: (() -> Unit)?, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(c.id) { Repo.track("view", c.id) }
    Card(
        modifier.clickable(onClick = onOpen),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, HbgPink),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("★", color = HbgPink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
            if (c.logo.startsWith("https://")) {
                coil.compose.AsyncImage(
                    model = directImageUrl(c.logo), contentDescription = null,
                    modifier = Modifier.size(28.dp).padding(end = 6.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(c.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(listOfNotNull(kindName(c.kind), c.city.takeIf { it.isNotBlank() }, km?.let { if (it < 10) "%.1f km".format(it) else "${it.roundToInt()} km" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (c.phone.isNotBlank()) IconButton(onClick = { Repo.track("call", c.id); open(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.phone.filter { it.isDigit() || it == '+' }))) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Call, c.phone)
            }
            if (onRequest != null) androidx.compose.material3.Button(
                onClick = { Repo.track("request", c.id); onRequest() }, modifier = Modifier.height(32.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            ) {
                Icon(Icons.Filled.EventAvailable, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.request_short), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ContactCard(c: Contact, km: Double?, mine: Boolean, onRequest: (() -> Unit)? = null, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val partner = c.isPartner()
    if (partner) androidx.compose.runtime.LaunchedEffect(c.id) { Repo.track("view", c.id) }
    val tap = { what: String -> if (partner) Repo.track(what, c.id) }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onEdit),
        border = if (partner) androidx.compose.foundation.BorderStroke(2.dp, HbgPink) else null,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (partner && c.logo.startsWith("https://")) {
                    coil.compose.AsyncImage(
                        model = directImageUrl(c.logo), contentDescription = c.name,
                        modifier = Modifier.size(44.dp).padding(end = 8.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
                Text(c.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                km?.let { Text(if (it < 10) "%.1f km".format(it) else "${it.roundToInt()} km", style = MaterialTheme.typography.labelMedium) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                if (partner) Chip("★ " + stringResource(R.string.partner), Color(0xFF7A3E9D), Color.White)
                Chip(kindName(c.kind), HbgPink, Color.White)
                if (mine) Chip(stringResource(R.string.contact_mine), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            }
            if (partner && c.promo.isNotBlank()) Text(c.promo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 2.dp))
            val place = listOf(c.address, c.city, c.country).filter { it.isNotBlank() }.distinct().joinToString(", ")
            if (place.isNotBlank()) Text(place, style = MaterialTheme.typography.bodySmall)
            if (c.brands.isNotEmpty()) Text(c.brands.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.phone.isNotBlank()) IconButton(onClick = { tap("call"); open(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.phone.filter { it.isDigit() || it == '+' }))) }) {
                    Icon(Icons.Filled.Call, c.phone)
                }
                if (c.email.isNotBlank()) IconButton(onClick = { tap("email"); open(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + c.email))) }) {
                    Icon(Icons.Filled.Email, c.email)
                }
                if (c.web.isNotBlank()) IconButton(onClick = {
                    tap("web"); open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(if (c.web.startsWith("http")) c.web else "https://" + c.web)))
                }) { Icon(Icons.Filled.Language, c.web) }
                if (c.lat != null && c.lon != null) IconButton(onClick = {
                    tap("map"); open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:${c.lat},${c.lon}?q=${c.lat},${c.lon}(${Uri.encode(c.name)})")))
                }) { Icon(Icons.Filled.Map, null) }
            }
            if (onRequest != null) {
                androidx.compose.material3.Button(
                    onClick = onRequest, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(40.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(Icons.Filled.EventAvailable, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.req_book), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ContactDialog(c: Contact?, loc: Location?, isNew: Boolean, onSaved: (Contact) -> Unit = {}, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(c?.name ?: "") }
    var kind by remember { mutableStateOf(c?.kind ?: "mechanic") }
    var brands by remember { mutableStateOf(c?.brands?.joinToString(", ") ?: "") }
    var country by remember { mutableStateOf(c?.country ?: "") }
    var city by remember { mutableStateOf(c?.city ?: "") }
    var address by remember { mutableStateOf(c?.address ?: "") }
    var phone by remember { mutableStateOf(c?.phone ?: "") }
    var email by remember { mutableStateOf(c?.email ?: "") }
    var web by remember { mutableStateOf(c?.web ?: "") }
    var note by remember { mutableStateOf(c?.note ?: "") }
    var lat by remember { mutableStateOf(c?.lat?.toString() ?: "") }
    var lon by remember { mutableStateOf(c?.lon?.toString() ?: "") }
    var submitting by remember { mutableStateOf(false) }
    if (submitting && c != null) {
        SubmitDialog(c) { submitting = false }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.contact_add else R.string.contact_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (isNew) Text(stringResource(R.string.contact_new_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.contact_name)) }, modifier = Modifier.fillMaxWidth())
                CONTACT_KINDS.forEach { k ->
                    Row(Modifier.fillMaxWidth().clickable { kind = k }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(kind == k, { kind = k })
                        Text(kindName(k))
                    }
                }
                OutlinedTextField(brands, { brands = it }, label = { Text(stringResource(R.string.contact_brands)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(R.string.contact_phone)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(web, { web = it }, label = { Text("WWW") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.contact_address)) }, modifier = Modifier.fillMaxWidth())
                Row {
                    OutlinedTextField(city, { city = it }, label = { Text(stringResource(R.string.contact_city)) }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(country, { country = it.uppercase().take(2) }, label = { Text(stringResource(R.string.contact_country)) }, modifier = Modifier.width(90.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField(lat, { lat = it }, "Lat", Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    NumberField(lon, { lon = it }, "Lon", Modifier.weight(1f))
                    if (loc != null) IconButton(onClick = {
                        lat = "%.5f".format(java.util.Locale.US, loc.latitude); lon = "%.5f".format(java.util.Locale.US, loc.longitude)
                    }) { Icon(Icons.Filled.MyLocation, stringResource(R.string.use_my_location)) }
                }
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.note_plain)) }, modifier = Modifier.fillMaxWidth())
                if (c != null && c.source.isNotBlank()) {
                    Text(stringResource(R.string.source_label, c.source), style = MaterialTheme.typography.bodySmall)
                }
                if (!isNew && c != null) {
                    if (c.id.startsWith("my_") && Repo.serverConnected) {
                        TextButton(onClick = { submitting = true }) { Text(stringResource(R.string.submit_to_base)) }
                    }
                    TextButton(onClick = { Repo.deleteContact(c.id); onDismiss() }) {
                        Text(stringResource(R.string.delete), color = Color(0xFFC62828))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val saved = (
                    Contact(
                        id = c?.id ?: ("my_" + Repo.newId()), name = name.trim(), kind = kind,
                        brands = brands.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        country = country.trim(), city = city.trim(), address = address.trim(),
                        lat = parseNum(lat), lon = parseNum(lon), phone = phone.trim(), email = email.trim(),
                        web = web.trim(), source = c?.source ?: "", note = note.trim(),
                    )
                )
                Repo.saveContact(saved)
                onDismiss()
                if (isNew) onSaved(saved)
            }) { Text(stringResource(if (isNew && Repo.serverConnected) R.string.contact_save_send else R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DetailLink(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(label, maxLines = 1)
    }
}

/** Read-only card of a shared entry (partner, service, school, doctor…); own entries also get edit / send. */
@Composable
private fun ContactDetails(c: Contact, km: Double?, onEdit: () -> Unit, onSubmit: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val own = c.id.startsWith("my_")
    val tap = { what: String -> if (c.isPartner()) Repo.track(what, c.id) }
    val place = listOf(c.address, c.city, c.country).filter { it.isNotBlank() }.distinct().joinToString(", ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    if (c.isPartner()) Chip("★ " + stringResource(R.string.partner), HbgPink, Color.White)
                    Chip(kindName(c.kind), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    if (own) Chip(stringResource(R.string.contact_mine), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                }
                if (c.isPartner() && c.promo.isNotBlank()) Text(c.promo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
                if (place.isNotBlank()) Text(place + (km?.let { " · " + (if (it < 10) "%.1f km".format(it) else "${it.roundToInt()} km") } ?: ""), style = MaterialTheme.typography.bodyMedium)
                if (c.brands.isNotEmpty()) Text(c.brands.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                if (c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(6.dp))
                if (c.phone.isNotBlank()) DetailLink(c.phone, Icons.Filled.Call) { tap("call"); open(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.phone.filter { it.isDigit() || it == '+' }))) }
                if (c.email.isNotBlank()) DetailLink(c.email, Icons.Filled.Email) { tap("email"); open(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + c.email))) }
                if (c.web.isNotBlank()) DetailLink(c.web.removePrefix("https://").removePrefix("http://"), Icons.Filled.Language) {
                    tap("web"); open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(if (c.web.startsWith("http")) c.web else "https://" + c.web)))
                }
                val maps = if (c.lat != null && c.lon != null) "https://www.google.com/maps/search/?api=1&query=${c.lat},${c.lon}"
                    else "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(listOf(c.name, place).filter { it.isNotBlank() }.joinToString(", "))
                DetailLink(stringResource(R.string.open_in_maps), Icons.Filled.Map) { tap("map"); open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(maps))) }
                if (c.source.isNotBlank() && !own) Text(stringResource(R.string.source_label, c.source), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                if (own) {
                    Text(stringResource(R.string.contact_own_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    Row {
                        TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                        if (Repo.serverConnected) TextButton(onClick = onSubmit) { Text(stringResource(R.string.submit_to_base)) }
                        TextButton(onClick = { Repo.deleteContact(c.id); onDismiss() }) { Text(stringResource(R.string.delete), color = Color(0xFFC62828)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Proposal of an own contact for the shared base – the app owner approves it on the server. */
@Composable
private fun SubmitDialog(c: Contact, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.submit_to_base)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.submit_hint), style = MaterialTheme.typography.bodySmall)
                Text(c.name, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                OutlinedTextField(from, { from = it }, label = { Text(stringResource(R.string.submit_from)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.submit_message)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { consent = !consent }) {
                    androidx.compose.material3.Checkbox(consent, { consent = it })
                    Text(stringResource(R.string.submit_consent), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !sending, onClick = {
                sending = true
                scope.launch {
                    val r = Repo.submitContact(c, message.trim(), from.trim(), consent)
                    sending = false
                    Toast.makeText(ctx, if (r.isSuccess) ctx.getString(R.string.submit_ok) else ctx.getString(R.string.submit_fail), Toast.LENGTH_LONG).show()
                    if (r.isSuccess) onDismiss()
                }
            }) { Text(stringResource(R.string.submit_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}


/** Default "scope of work" for a request: the next due inspection of the aircraft. */
@Composable
fun nextWorkText(a: com.cannonadestudio.tomarkpilot.data.Aircraft, state: AppState): String {
    val lg = lang()
    val next = com.cannonadestudio.tomarkpilot.data.Maintenance.compute(a, state.completions)
        .filter { it.status != com.cannonadestudio.tomarkpilot.data.DueStatus.DONE && (it.remainingHours != null || it.remainingDays != null) }
        .minByOrNull { it.remainingHours ?: ((it.remainingDays ?: 9999L) / 3.0) }
    return next?.task?.title?.get(lg) ?: stringResource(R.string.req_default_work)
}
