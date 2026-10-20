@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cannonadestudio.tomarkpilot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.Aircraft
import com.cannonadestudio.tomarkpilot.data.AppState
import com.cannonadestudio.tomarkpilot.data.Repo
import java.time.LocalDate

/** List of the owner's aircraft. */
@Composable
fun AircraftListScreen(state: AppState, onEdit: (Aircraft?) -> Unit, onSelected: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        state.aircraft.forEach { a ->
            val t = Repo.type(a.typeId)
            val sel = a.id == Repo.selected(state)?.id
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable {
                Repo.update { it.copy(selectedId = a.id) }
                onSelected()
            }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (sel) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.registration, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("${t?.name ?: a.typeId} · ${fmtH(a.hours)} h", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { onEdit(a) }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onEdit(null) }) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.add_aircraft))
        }
    }
}

/** Add / edit form. [existing] == null creates a new aircraft. */
@Composable
fun AircraftEditScreen(existing: Aircraft?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var typeId by remember { mutableStateOf(existing?.typeId ?: Repo.types.first().id) }
    var reg by remember { mutableStateOf(existing?.registration ?: "") }
    var serial by remember { mutableStateOf(existing?.serial ?: "") }
    var engine by remember { mutableStateOf(existing?.engineSerial ?: "") }
    var hours by remember { mutableStateOf(existing?.hours?.let { fmtH(it) } ?: "") }
    var ref by remember { mutableStateOf(existing?.referenceEpochDay?.let { fmtEpochDay(it) } ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    var typeQuery by remember { mutableStateOf("") }
    var linkLogbook by remember { mutableStateOf(existing?.logbookBaseHours != null) }
    val valid = reg.isNotBlank() && parseNum(hours) != null && (ref.isBlank() || parseDate(ref) != null)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.aircraft_type), style = MaterialTheme.typography.titleMedium)
        Text(Repo.type(typeId)?.name ?: "", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            typeQuery, { typeQuery = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(stringResource(R.string.type_search)) },
        )
        val shown = Repo.types.filter {
            typeQuery.isBlank() || it.name.contains(typeQuery.trim(), true) || it.manufacturer.contains(typeQuery.trim(), true)
        }
        listOf("EASA", "UL").forEach { cat ->
            val inCat = shown.filter { it.category == cat }
            if (inCat.isNotEmpty()) {
                Text(
                    stringResource(if (cat == "EASA") R.string.cat_easa_generic else R.string.cat_ul),
                    style = MaterialTheme.typography.labelLarge, color = HbgPink,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            inCat.groupBy { it.manufacturer }.forEach { (maker, list) ->
                Text(maker, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                list.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { typeId = t.id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(typeId == t.id, { typeId = t.id })
                        Text(t.name)
                    }
                }
            }
        }
        if (existing != null && existing.typeId != typeId) {
            Text(stringResource(R.string.type_change_warning), color = Color(0xFFEF6C00), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            reg, { reg = it.uppercase() }, label = { Text(stringResource(R.string.registration)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        )
        OutlinedTextField(serial, { serial = it }, label = { Text(stringResource(R.string.serial_number)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(engine, { engine = it }, label = { Text(stringResource(R.string.engine_serial)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        NumberField(hours, { hours = it }, stringResource(R.string.total_hours), Modifier.fillMaxWidth())
        DateField(ref, { ref = it }, stringResource(R.string.reference_date), Modifier.fillMaxWidth())
        Text(stringResource(R.string.reference_date_hint), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().clickable { linkLogbook = !linkLogbook }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(linkLogbook, { linkLogbook = it })
            Column {
                Text(stringResource(R.string.link_logbook))
                Text(stringResource(R.string.link_logbook_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (existing == null) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.setup_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Row {
            Button(enabled = valid, onClick = {
                val h = parseNum(hours)!!
                val base = existing ?: Aircraft(id = Repo.newId(), typeId = typeId, registration = "")
                scope.launch {
                    // Linking: keep today's total, count only logbook flights on top of it.
                    val lbBase = when {
                        !linkLogbook -> null
                        existing?.logbookBaseHours != null && existing.registration == reg.trim() && existing.hours == h -> existing.logbookBaseHours
                        else -> h - (Repo.logbookHoursFor(reg.trim()) ?: 0.0)
                    }
                    Repo.upsertAircraft(
                        base.copy(
                            typeId = typeId, registration = reg.trim(), serial = serial.trim(), engineSerial = engine.trim(),
                            hours = h,
                            hoursUpdatedEpochDay = if (existing?.hours == h) existing.hoursUpdatedEpochDay else LocalDate.now().toEpochDay(),
                            referenceEpochDay = parseDate(ref)?.toEpochDay(),
                            logbookBaseHours = lbBase,
                        )
                    )
                    onDone()
                }
            }) { Text(stringResource(R.string.save)) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.weight(1f))
            if (existing != null) {
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete), color = Color(0xFFC62828)) }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_aircraft_q, existing.registration)) },
            text = { Text(stringResource(R.string.delete_aircraft_text)) },
            confirmButton = {
                TextButton(onClick = { Repo.deleteAircraft(existing.id); confirmDelete = false; onDone() }) {
                    Text(stringResource(R.string.delete), color = Color(0xFFC62828))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
