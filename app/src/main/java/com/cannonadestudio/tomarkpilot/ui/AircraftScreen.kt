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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
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
import com.cannonadestudio.tomarkpilot.data.TaskDef
import java.time.LocalDate

/** List of the owner's aircraft. */
@Composable
fun AircraftListScreen(state: AppState, onEdit: (Aircraft?) -> Unit, onSelected: () -> Unit) {
    var exportFor by remember { mutableStateOf<Aircraft?>(null) }
    exportFor?.let { a -> ExportDialog(a, state) { exportFor = null } }
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
                    IconButton(onClick = { exportFor = a }) { Icon(Icons.Filled.Share, stringResource(R.string.export_share)) }
                    IconButton(onClick = { onEdit(a) }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        var proAsk by remember { mutableStateOf(false) }
        if (proAsk) ProDialog { proAsk = false }
        val ctxA = androidx.compose.ui.platform.LocalContext.current
        Button(onClick = {
            when {
                com.cannonadestudio.tomarkpilot.data.Billing.canAddAircraft(state) -> onEdit(null)
                // limit set by the administrator for this account (Pro does not lift it)
                state.accountMaxAircraft != null -> android.widget.Toast.makeText(ctxA, ctxA.getString(R.string.account_limit_reached, state.accountMaxAircraft), android.widget.Toast.LENGTH_LONG).show()
                else -> proAsk = true
            }
        }) {
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
    // Engine / propeller / rescue system (they decide the maintenance programme and the bulletins)
    var engineSel by remember { mutableStateOf(existing?.engineId) }
    var pickEngine by remember { mutableStateOf(false) }
    var propeller by remember { mutableStateOf(existing?.propeller ?: "fixed") }
    var propInsp by remember { mutableStateOf(existing?.propInspection) }
    var rescue by remember { mutableStateOf(existing?.rescueSystem ?: false) }
    var rescueYears by remember { mutableStateOf(existing?.rescueMonths?.let { if (it % 12 == 0) (it / 12).toString() else fmtH(it / 12.0) } ?: "6") }
    var typeKind by remember { mutableStateOf(Repo.type(existing?.typeId ?: "")?.let { if (it.custom) "custom" else it.kind } ?: "airplane") }
    var customTypeDlg by remember { mutableStateOf<com.cannonadestudio.tomarkpilot.data.AircraftType?>(null) }
    var customTypeNew by remember { mutableStateOf(false) }
    // Previously done work entered right in the form: taskId -> (date, hours); ticked bulletin ids.
    val prevTasks = remember { mutableStateMapOf<String, Pair<String, String>>() }
    val prevBulletins = remember { mutableStateListOf<String>() }
    var prevBulDate by remember { mutableStateOf(fmtDate(LocalDate.now())) }
    val appState by Repo.state.collectAsState()
    val feed by Repo.feed.collectAsState()
    val rescueMonths = ((parseNum(rescueYears) ?: 6.0) * 12).toInt().coerceIn(6, 360)
    val draft = (existing ?: Aircraft(id = "draft", typeId = typeId, registration = "")).copy(
        typeId = typeId, engineId = engineSel, propeller = propeller, propInspection = propInsp,
        rescueSystem = rescue, rescueMonths = rescueMonths,
    )
    val sameType = existing != null && existing.typeId == typeId
    val openTasks = Maintenance.tasks(draft).map { it.first }.filter { !Maintenance.isHidden(it, draft) }.filter { t ->
        !(sameType && Maintenance.recordsFor(t, existing!!, appState.completions).isNotEmpty())
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
        // Airplanes / weight-shift trikes / gyroplanes / own types
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            listOf("airplane" to R.string.kind_airplanes, "trike" to R.string.kind_trikes, "gyro" to R.string.kind_gyros, "custom" to R.string.kind_custom).forEach { (k, r) ->
                androidx.compose.material3.FilterChip(typeKind == k, { typeKind = k }, label = { Text(stringResource(r)) })
            }
        }
        val shown = Repo.allTypes(appState).filter {
            (if (typeKind == "custom") it.custom else !it.custom && it.kind == typeKind) &&
                (typeQuery.isBlank() || it.name.contains(typeQuery.trim(), true) || it.manufacturer.contains(typeQuery.trim(), true))
        }
        val ctxT = androidx.compose.ui.platform.LocalContext.current
        TextButton(onClick = {
            // limit of own types set by the administrator for this account
            val max = appState.accountMaxTypes
            if (max != null && appState.customTypes.size >= max) android.widget.Toast.makeText(ctxT, ctxT.getString(R.string.account_types_reached, max), android.widget.Toast.LENGTH_LONG).show()
            else customTypeNew = true
        }) { Text("+ " + stringResource(R.string.custom_type_add)) }
        if (typeKind == "custom" && shown.isEmpty()) Text(stringResource(R.string.custom_type_hint), style = MaterialTheme.typography.bodySmall)
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
                        Text(t.name, modifier = Modifier.weight(1f))
                        if (t.custom) IconButton(onClick = { customTypeDlg = t }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                    }
                }
            }
        }
        }
        if (existing != null && existing.typeId != typeId) {
            Text(stringResource(R.string.type_change_warning), color = Color(0xFFEF6C00), style = MaterialTheme.typography.bodySmall)
        }
        // ---- engine
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.engine_type), style = MaterialTheme.typography.titleMedium)
        val eng = Repo.engineOf(draft)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(eng.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                if (engineSel == null) Text(stringResource(R.string.engine_default), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { pickEngine = !pickEngine }) { Text(stringResource(R.string.change_engine)) }
        }
        if (pickEngine) {
            Repo.engines.groupBy { it.manufacturer }.forEach { (maker, list) ->
                Text(maker, style = MaterialTheme.typography.labelMedium, color = HbgPink, modifier = Modifier.padding(top = 4.dp))
                list.forEach { e ->
                    Row(Modifier.fillMaxWidth().clickable { engineSel = e.id; pickEngine = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(eng.id == e.id, { engineSel = e.id; pickEngine = false })
                        Column {
                            Text(e.name)
                            val tbo = listOfNotNull(e.tboHours?.let { "TBO ${fmtH(it)} h" }, e.tboYears?.let { "$it lat" }).joinToString(" / ")
                            if (tbo.isNotBlank()) Text(tbo, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text(stringResource(R.string.engine_hint), style = MaterialTheme.typography.bodySmall)
        }
        // ---- propeller
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.propeller_type), style = MaterialTheme.typography.titleMedium)
        listOf("fixed" to R.string.prop_fixed, "ground" to R.string.prop_ground, "inflight" to R.string.prop_inflight).forEach { (k, r) ->
            Row(Modifier.fillMaxWidth().clickable { propeller = k }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(propeller == k, { propeller = k })
                Text(stringResource(r))
            }
        }
        val propOn = propInsp ?: (propeller == "inflight")
        Row(Modifier.fillMaxWidth().clickable { propInsp = !propOn }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(propOn, { propInsp = it })
            Column {
                Text(stringResource(R.string.prop_inspections))
                Text(stringResource(R.string.prop_inspections_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        // ---- rescue system
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().clickable { rescue = !rescue }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(rescue, { rescue = it })
            Text(stringResource(R.string.rescue_system), style = MaterialTheme.typography.titleMedium)
        }
        if (rescue) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                listOf("6", "8", "10", "12").forEach { y ->
                    androidx.compose.material3.FilterChip(rescueYears == y, { rescueYears = y }, label = { Text(stringResource(R.string.years_n, y)) })
                }
            }
            NumberField(rescueYears, { rescueYears = it }, stringResource(R.string.rescue_years), Modifier.fillMaxWidth())
            Text(stringResource(R.string.rescue_hint), style = MaterialTheme.typography.bodySmall)
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
                            engineId = engineSel, propeller = propeller, propInspection = propInsp,
                            rescueSystem = rescue, rescueMonths = rescueMonths,
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

    if (customTypeNew) CustomTypeDialog(null) { saved -> customTypeNew = false; saved?.let { typeId = it.id; typeKind = "custom" } }
    customTypeDlg?.let { t -> CustomTypeDialog(t) { saved -> customTypeDlg = null; saved?.let { typeId = it.id } } }
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
                    DateField(d, { v -> values[t.id] = v to h }, stringResource(R.string.prev_done_date), Modifier.weight(1.15f))
                    Spacer(Modifier.width(8.dp))
                    NumberField(h, { v -> values[t.id] = d to v }, stringResource(R.string.prev_done_hours), Modifier.weight(0.85f))
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


@Composable
private fun ExportDialog(a: Aircraft, state: AppState, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_share) + " – " + a.registration) },
        text = { Text(stringResource(R.string.export_share_hint)) },
        confirmButton = {
            Button(onClick = {
                runCatching { AircraftExport.sharePdf(ctx, a, state) }
                    .onFailure { android.widget.Toast.makeText(ctx, it.message ?: "PDF", android.widget.Toast.LENGTH_LONG).show() }
                onDismiss()
            }) { Text(stringResource(R.string.export_pdf)) }
        },
        dismissButton = {
            TextButton(onClick = { AircraftExport.shareText(ctx, a, state); onDismiss() }) { Text(stringResource(R.string.export_text)) }
        },
    )
}


/** Own aircraft type: name, kind, engine, key speeds and the airframe inspection interval. */
@Composable
private fun CustomTypeDialog(t: com.cannonadestudio.tomarkpilot.data.AircraftType?, onDone: (com.cannonadestudio.tomarkpilot.data.AircraftType?) -> Unit) {
    var name by remember { mutableStateOf(t?.name ?: "") }
    var maker by remember { mutableStateOf(t?.manufacturer ?: "") }
    var kind by remember { mutableStateOf(t?.kind ?: "airplane") }
    var cat by remember { mutableStateOf(t?.category ?: "UL") }
    var engineId by remember { mutableStateOf(t?.engine?.takeIf { it.isNotBlank() } ?: "rotax_912") }
    var kmh by remember { mutableStateOf(t?.speeds?.firstOrNull()?.unit != "kt") }
    val keys = listOf("VS0", "VS1", "VFE", "VAPP", "VY", "VA", "VNO", "VNE")
    val speedVals = remember { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { t?.speeds?.forEach { put(it.key, it.value?.let { v -> fmtH(v) } ?: "") } } }
    val af = t?.tasks?.firstOrNull { it.id == "ul_100h" }
    var ih by remember { mutableStateOf(af?.intervalHours?.let { fmtH(it) } ?: "100") }
    var im by remember { mutableStateOf(af?.intervalMonths?.toString() ?: "12") }
    var confirmDel by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(if (t == null) R.string.custom_type_add else R.string.edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.custom_type_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(maker, { maker = it }, label = { Text(stringResource(R.string.custom_type_maker)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    listOf("airplane" to R.string.kind_airplane, "trike" to R.string.kind_trike, "gyro" to R.string.kind_gyro).forEach { (k, r) ->
                        androidx.compose.material3.FilterChip(kind == k, { kind = k }, label = { Text(stringResource(r)) })
                    }
                }
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.FilterChip(cat == "UL", { cat = "UL" }, label = { Text("UL / LSA") })
                    androidx.compose.material3.FilterChip(cat == "EASA", { cat = "EASA" }, label = { Text("EASA") })
                }
                Text(stringResource(R.string.engine_type), style = MaterialTheme.typography.labelLarge, color = HbgPink, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    Repo.engines.forEach { e -> androidx.compose.material3.FilterChip(engineId == e.id, { engineId = e.id }, label = { Text(e.name) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text(stringResource(R.string.speeds), style = MaterialTheme.typography.labelLarge, color = HbgPink, modifier = Modifier.weight(1f))
                    androidx.compose.material3.FilterChip(!kmh, { kmh = false }, label = { Text("kt") })
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.material3.FilterChip(kmh, { kmh = true }, label = { Text("km/h") })
                }
                keys.chunked(2).forEach { row ->
                    Row {
                        row.forEach { k ->
                            NumberField(speedVals[k] ?: "", { speedVals[k] = it }, k, Modifier.weight(1f))
                            if (k != row.last()) Spacer(Modifier.width(6.dp))
                        }
                    }
                }
                Text(stringResource(R.string.custom_type_airframe), style = MaterialTheme.typography.labelLarge, color = HbgPink, modifier = Modifier.padding(top = 6.dp))
                Row {
                    NumberField(ih, { ih = it }, stringResource(R.string.interval_hours), Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    NumberField(im, { im = it }, stringResource(R.string.interval_months), Modifier.weight(1f))
                }
                if (t != null) TextButton(onClick = { confirmDel = true }) { Text(stringResource(R.string.delete), color = Color(0xFFC62828)) }
                if (confirmDel && t != null) {
                    val used = Repo.state.value.aircraft.any { it.typeId == t.id }
                    Text(stringResource(if (used) R.string.custom_type_in_use else R.string.custom_type_delete_q), style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828))
                    if (!used) TextButton(onClick = { Repo.deleteCustomType(t.id); onDone(null) }) { Text(stringResource(R.string.delete), color = Color(0xFFC62828)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val L = { pl: String, en: String -> com.cannonadestudio.tomarkpilot.data.L10n(pl, en, en, en) }
                val labels = mapOf(
                    "VS0" to L("VS0 przeciągnięcie z klapami", "VS0 stall, landing flaps"), "VS1" to L("VS1 przeciągnięcie bez klap", "VS1 stall, clean"),
                    "VFE" to L("VFE maks. z klapami", "VFE max flaps extended"), "VAPP" to L("Podejście", "Approach"),
                    "VY" to L("VY najlepsze wznoszenie", "VY best rate of climb"), "VA" to L("VA prędkość manewrowa", "VA manoeuvring speed"),
                    "VNO" to L("VNO maks. przelotowa", "VNO max structural cruising"), "VNE" to L("VNE nieprzekraczalna", "VNE never exceed"),
                )
                val zones = mapOf("VS0" to "white", "VS1" to "green", "VFE" to "white", "VAPP" to "approach", "VA" to "green", "VNO" to "green", "VNE" to "red")
                val speeds = keys.mapNotNull { k ->
                    parseNum(speedVals[k] ?: "")?.let { v ->
                        com.cannonadestudio.tomarkpilot.data.SpeedDef(k, labels[k]!!, v, if (kmh) "kmh" else "kt", "własne dane / own data", true, zones[k] ?: "normal")
                    }
                }
                val airframe = TaskDef(
                    id = "ul_100h",
                    title = L("Przegląd okresowy płatowca ${ih} h / ${im} mies.", "Airframe periodic inspection ${ih} h / ${im} months"),
                    category = "airframe", intervalHours = parseNum(ih), intervalMonths = parseNum(im)?.toInt(),
                    mandatory = true, source = "własne dane / own data", verified = true,
                )
                val nt = com.cannonadestudio.tomarkpilot.data.AircraftType(
                    id = t?.id ?: ("my_type_" + Repo.newId().take(8)), name = name.trim(),
                    family = maker.trim().lowercase().ifBlank { "custom" }, manufacturer = maker.trim().ifBlank { "—" },
                    category = cat, tcds = null, bulletinGroups = emptyList(), speeds = speeds,
                    manual = t?.manual ?: emptyList(),
                    tasks = if (airframe.intervalHours != null || airframe.intervalMonths != null) listOf(airframe) else emptyList(),
                    kind = kind, engine = engineId, custom = true,
                )
                Repo.saveCustomType(nt)
                onDone(nt)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}
