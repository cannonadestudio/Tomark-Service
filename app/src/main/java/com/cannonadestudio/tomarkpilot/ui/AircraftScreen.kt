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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandMore
import com.cannonadestudio.tomarkpilot.data.BulletinStatus
import com.cannonadestudio.tomarkpilot.data.CompletionRecord
import com.cannonadestudio.tomarkpilot.data.Maintenance
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
    val ctxNote = stringResource(R.string.prev_added_note)
    var typeId by remember { mutableStateOf(existing?.typeId ?: Repo.types.first().id) }
    var reg by remember { mutableStateOf(existing?.registration ?: "") }
    var serial by remember { mutableStateOf(existing?.serial ?: "") }
    var engine by remember { mutableStateOf(existing?.engineSerial ?: "") }
    var gearbox by remember { mutableStateOf(existing?.gearboxSerial ?: "") }
    var year by remember { mutableStateOf(existing?.yearBuilt?.toString() ?: "") }
    var hours by remember { mutableStateOf(existing?.hours?.let { fmtH(it) } ?: "") }
    var ref by remember { mutableStateOf(existing?.referenceEpochDay?.let { fmtEpochDay(it) } ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    var typeQuery by remember { mutableStateOf("") }
    // The full type list only when adding; when editing it opens on "Change type".
    var pickType by remember { mutableStateOf(existing == null) }
    var linkLogbook by remember { mutableStateOf(existing?.logbookBaseHours != null) }
    // Previously done work entered right in the form: taskId -> (date, hours); ticked bulletin ids.
    val prevTasks = remember { mutableStateMapOf<String, Pair<String, String>>() }
    val prevBulletins = remember { mutableStateListOf<String>() }
    var prevBulDate by remember { mutableStateOf(fmtDate(LocalDate.now())) }
    val appState by Repo.state.collectAsState()
    val feed by Repo.feed.collectAsState()
    val draft = (existing ?: Aircraft(id = "draft", typeId = typeId, registration = "")).copy(typeId = typeId)
    val sameType = existing != null && existing.typeId == typeId
    val openTasks = Maintenance.tasks(draft).map { it.first }.filter { draft.taskOverrides[it.id]?.hidden != true }.filter { t ->
        !(sameType && appState.completions.any { it.aircraftId == existing!!.id && it.taskId == t.id })
    }
    val handled = if (sameType) appState.bulletinStatus.filter { it.aircraftId == existing!!.id }.map { it.bulletinId }.toSet() else emptySet()
    val openBulletins = Repo.bulletinsFor(draft, feed).filter { it.kind != "DOC" && it.id !in handled }
        .sortedWith(compareBy<com.cannonadestudio.tomarkpilot.data.Bulletin> { if (it.mandatory == true) 0 else if (it.mandatory == null) 1 else 2 }.thenByDescending { it.date })
    fun taskEntryOk(t: com.cannonadestudio.tomarkpilot.data.TaskDef): Boolean {
        val (d, h) = prevTasks[t.id] ?: return true
        if (d.isBlank() && h.isBlank()) return true
        if (parseDate(d) == null) return false
        return if (h.isBlank()) t.intervalHours == null && t.oneTimeAtHours == null else parseNum(h) != null
    }
    val prevOk = openTasks.all { taskEntryOk(it) } && (prevBulletins.isEmpty() || parseDate(prevBulDate) != null)
    val valid = reg.isNotBlank() && parseNum(hours) != null && (ref.isBlank() || parseDate(ref) != null) && prevOk

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.aircraft_type), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Repo.type(typeId)?.name ?: "", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            if (existing != null && !pickType) TextButton(onClick = { pickType = true }) { Text(stringResource(R.string.change_type)) }
        }
        if (pickType) {
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
        OutlinedTextField(gearbox, { gearbox = it }, label = { Text(stringResource(R.string.gearbox_serial)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        NumberField(year, { year = it.filter(Char::isDigit).take(4) }, stringResource(R.string.year_built), Modifier.fillMaxWidth())
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
        if (openTasks.isNotEmpty()) {
            PrevServiceSection(openTasks, prevTasks) { taskEntryOk(it) }
        }
        if (openBulletins.isNotEmpty()) {
            PrevBulletinsSection(openBulletins, prevBulletins, prevBulDate) { prevBulDate = it }
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
                    val saved = 
                        base.copy(
                            typeId = typeId, registration = reg.trim(), serial = serial.trim(), engineSerial = engine.trim(),
                            gearboxSerial = gearbox.trim(), yearBuilt = year.toIntOrNull()?.takeIf { it in 1950..2100 },
                            hours = h,
                            hoursUpdatedEpochDay = if (existing?.hours == h) existing.hoursUpdatedEpochDay else LocalDate.now().toEpochDay(),
                            referenceEpochDay = parseDate(ref)?.toEpochDay(),
                            logbookBaseHours = lbBase,
                        )
                    Repo.upsertAircraft(saved)
                    val note = ctxNote
                    val recs = openTasks.mapNotNull { t ->
                        val (d, hh) = prevTasks[t.id] ?: return@mapNotNull null
                        val day = parseDate(d)?.toEpochDay() ?: return@mapNotNull null
                        CompletionRecord(
                            id = Repo.newId(), aircraftId = saved.id, taskId = t.id, epochDay = day,
                            hours = parseNum(hh) ?: h, note = note,
                        )
                    }
                    val bulDay = parseDate(prevBulDate)?.toEpochDay() ?: LocalDate.now().toEpochDay()
                    val bs = prevBulletins.filter { id -> openBulletins.any { it.id == id } }.map {
                        BulletinStatus(aircraftId = saved.id, bulletinId = it, status = "DONE", epochDay = bulDay, note = note)
                    }
                    if (recs.isNotEmpty() || bs.isNotEmpty()) {
                        Repo.update { st ->
                            st.copy(
                                completions = st.completions + recs,
                                bulletinStatus = st.bulletinStatus.filterNot { o -> bs.any { it.aircraftId == o.aircraftId && it.bulletinId == o.bulletinId } } + bs,
                                seenBulletinIds = st.seenBulletinIds + bs.map { it.bulletinId },
                            )
                        }
                    }
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


/** "Last completed inspections" – one row per task with date + hours. */
@Composable
private fun PrevServiceSection(
    tasks: List<com.cannonadestudio.tomarkpilot.data.TaskDef>,
    values: MutableMap<String, Pair<String, String>>,
    ok: (com.cannonadestudio.tomarkpilot.data.TaskDef) -> Boolean,
) {
    val lg = lang()
    var open by remember { mutableStateOf(true) }
    val filled = values.count { (id, v) -> tasks.any { it.id == id } && v.first.isNotBlank() }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.prev_service_title), style = MaterialTheme.typography.titleMedium)
            if (filled > 0) Text(stringResource(R.string.prev_count, filled), style = MaterialTheme.typography.bodySmall, color = HbgPink)
        }
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
    }
    if (!open) return
    Text(stringResource(R.string.prev_service_hint), style = MaterialTheme.typography.bodySmall)
    tasks.forEach { t ->
        val (d, h) = values[t.id] ?: ("" to "")
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text(t.title.get(lg), fontWeight = FontWeight.SemiBold)
                Text(intervalText(t), style = MaterialTheme.typography.bodySmall)
                Row {
                    DateField(d, { v -> values[t.id] = v to h }, stringResource(R.string.prev_done_date), Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    NumberField(h, { v -> values[t.id] = d to v }, stringResource(R.string.prev_done_hours), Modifier.weight(1f))
                }
                if (!ok(t) && parseDate(d) != null) {
                    Text(stringResource(R.string.prev_hours_needed), color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** "Bulletins already carried out" – checkboxes, mandatory first, with search. */
@Composable
private fun PrevBulletinsSection(
    list: List<com.cannonadestudio.tomarkpilot.data.Bulletin>,
    ticked: MutableList<String>,
    date: String,
    onDate: (String) -> Unit,
) {
    var open by remember { mutableStateOf(true) }
    var q by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf(25) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.prev_bulletins_title), style = MaterialTheme.typography.titleMedium)
            if (ticked.isNotEmpty()) Text(stringResource(R.string.prev_count, ticked.size), style = MaterialTheme.typography.bodySmall, color = HbgPink)
        }
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
    }
    if (!open) return
    Text(stringResource(R.string.prev_bulletins_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(q, { q = it; limit = 25 }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.prev_search)) })
    if (ticked.isNotEmpty()) DateField(date, onDate, stringResource(R.string.prev_bulletin_date), Modifier.fillMaxWidth())
    val shown = list.filter { q.isBlank() || it.id.contains(q.trim(), true) || it.title.contains(q.trim(), true) }
    shown.take(limit).forEach { b ->
        val on = b.id in ticked
        Row(
            Modifier.fillMaxWidth().clickable { if (on) ticked.remove(b.id) else ticked.add(b.id) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(on, { if (it) ticked.add(b.id) else ticked.remove(b.id) })
            Column(Modifier.weight(1f)) {
                Text(
                    (if (b.mandatory == true) "⚠ " else "") + b.id,
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                    color = if (b.mandatory == true) Color(0xFFC62828) else Color.Unspecified,
                )
                Text("${b.date} · ${b.title}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            b.url?.let { u ->
                IconButton(onClick = { openUrl(ctx, u) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open_document), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (shown.size > limit) TextButton(onClick = { limit += 50 }) { Text(stringResource(R.string.prev_more) + " (${shown.size - limit})") }
}
