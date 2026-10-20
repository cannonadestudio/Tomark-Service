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

val CONTACT_KINDS = listOf("dealer", "manufacturer", "rotax_service", "rotax_distributor", "mechanic", "instructor", "examiner")

@Composable
fun kindName(k: String): String = stringResource(
    when (k) {
        "manufacturer" -> R.string.kind_manufacturer
        "dealer" -> R.string.kind_dealer
        "rotax_distributor" -> R.string.kind_rotax_distributor
        "rotax_service" -> R.string.kind_rotax_service
        "mechanic" -> R.string.kind_mechanic
        "instructor" -> R.string.kind_instructor
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
    val all = Repo.contacts(state, feed)
    val list = all
        .filter { kinds.isEmpty() || it.kind in kinds }
        .filter { !onlyMyBrand || it.kind.startsWith("rotax") || it.brands.any { b -> brandWords.any { w -> b.lowercase().contains(w) } } }
        .filter {
            query.isBlank() || listOf(it.name, it.city, it.country, it.brands.joinToString(" ")).any { f -> f.contains(query.trim(), ignoreCase = true) }
        }
        .sortedWith(compareBy({ distanceKm(loc, it) ?: Double.MAX_VALUE }, { it.country }, { it.name }))

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
                Spacer(Modifier.height(8.dp))
            }
            items(list, key = { it.id }) { c ->
                ContactCard(c, distanceKm(loc, c), c.id in localIds) { editing = c }
                Spacer(Modifier.height(8.dp))
            }
            item {
                Text(
                    stringResource(R.string.contacts_hint),
                    style = MaterialTheme.typography.bodySmall, color = Color.White,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Spacer(Modifier.height(80.dp))
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.contact_add)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { c -> ContactDialog(c, loc, isNew = false) { editing = null } }
    if (creating) ContactDialog(null, loc, isNew = true) { creating = false }
}

@Composable
private fun ContactCard(c: Contact, km: Double?, mine: Boolean, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                km?.let { Text(if (it < 10) "%.1f km".format(it) else "${it.roundToInt()} km", style = MaterialTheme.typography.labelMedium) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                Chip(kindName(c.kind), HbgPink, Color.White)
                if (mine) Chip(stringResource(R.string.contact_mine), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            }
            val place = listOf(c.address, c.city, c.country).filter { it.isNotBlank() }.distinct().joinToString(", ")
            if (place.isNotBlank()) Text(place, style = MaterialTheme.typography.bodySmall)
            if (c.brands.isNotEmpty()) Text(c.brands.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.bodySmall)
            Row {
                if (c.phone.isNotBlank()) IconButton(onClick = { open(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.phone.filter { it.isDigit() || it == '+' }))) }) {
                    Icon(Icons.Filled.Call, c.phone)
                }
                if (c.email.isNotBlank()) IconButton(onClick = { open(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + c.email))) }) {
                    Icon(Icons.Filled.Email, c.email)
                }
                if (c.web.isNotBlank()) IconButton(onClick = {
                    open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(if (c.web.startsWith("http")) c.web else "https://" + c.web)))
                }) { Icon(Icons.Filled.Language, c.web) }
                if (c.lat != null && c.lon != null) IconButton(onClick = {
                    open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:${c.lat},${c.lon}?q=${c.lat},${c.lon}(${Uri.encode(c.name)})")))
                }) { Icon(Icons.Filled.Map, null) }
            }
        }
    }
}

@Composable
private fun ContactDialog(c: Contact?, loc: Location?, isNew: Boolean, onDismiss: () -> Unit) {
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.contact_add else R.string.contact_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                    TextButton(onClick = { Repo.deleteContact(c.id); onDismiss() }) {
                        Text(stringResource(R.string.delete), color = Color(0xFFC62828))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                Repo.saveContact(
                    Contact(
                        id = c?.id ?: ("my_" + Repo.newId()), name = name.trim(), kind = kind,
                        brands = brands.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        country = country.trim(), city = city.trim(), address = address.trim(),
                        lat = parseNum(lat), lon = parseNum(lon), phone = phone.trim(), email = email.trim(),
                        web = web.trim(), source = c?.source ?: "", note = note.trim(),
                    )
                )
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
