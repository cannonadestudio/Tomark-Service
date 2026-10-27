@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cannonadestudio.tomarkpilot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.Aircraft
import com.cannonadestudio.tomarkpilot.data.AppState
import com.cannonadestudio.tomarkpilot.data.CompletionRecord
import com.cannonadestudio.tomarkpilot.data.DueInfo
import com.cannonadestudio.tomarkpilot.data.L10n
import com.cannonadestudio.tomarkpilot.data.Maintenance
import com.cannonadestudio.tomarkpilot.data.Repo
import com.cannonadestudio.tomarkpilot.data.TaskDef
import com.cannonadestudio.tomarkpilot.data.TaskOverride
import java.time.LocalDate

@Composable
fun ServiceScreen(state: AppState, aircraft: Aircraft) {
    val lg = lang()
    var showHidden by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var checklist by remember { mutableStateOf<String?>(null) }
    val all = Maintenance.compute(aircraft, state.completions, includeHidden = true)
    val list = all.filter { showHidden || !Maintenance.isHidden(it.task, aircraft) }
    var logEdit by remember { mutableStateOf<com.cannonadestudio.tomarkpilot.data.LogEntry?>(null) }
    var logNew by remember { mutableStateOf(false) }
    val log = state.logEntries.filter { it.aircraftId == aircraft.id }.sortedWith(compareByDescending<com.cannonadestudio.tomarkpilot.data.LogEntry> { it.epochDay }.thenByDescending { it.hours ?: 0.0 })

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Spacer(Modifier.height(12.dp))
                EngineSerialLine(aircraft, MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.engine_label, Repo.engineOf(aircraft).name), style = MaterialTheme.typography.bodySmall, color = OnPage)
                Text(
                    stringResource(R.string.service_intro, fmtH(aircraft.hours)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.padding(vertical = 8.dp)) {
                    FilterChip(selected = showHidden, onClick = { showHidden = !showHidden }, label = { Text(stringResource(R.string.show_hidden)) })
                }
            }
            items(list, key = { it.task.id }) { d ->
                TaskCard(d, aircraft, lg) { open = d.task.id }
                Spacer(Modifier.height(8.dp))
            }
            // Own log entries: anything outside the programme (tyres, battery, repairs, modifications…)
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.log_title), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.log_hint), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { logNew = true }) { Text(stringResource(R.string.log_add)) }
                        }
                        if (log.isEmpty()) Text(stringResource(R.string.no_history), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        log.forEach { e ->
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Row(Modifier.fillMaxWidth().clickable { logEdit = e }, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.title, fontWeight = FontWeight.SemiBold)
                                    Text(fmtEpochDay(e.epochDay) + (e.hours?.let { " · ${fmtH(it)} h" } ?: ""), style = MaterialTheme.typography.bodySmall)
                                    if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(Icons.Filled.Edit, stringResource(R.string.edit))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.add_task)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    open?.let { id -> all.firstOrNull { it.task.id == id }?.let { TaskDialog(it, aircraft, state, lg, onChecklist = { open = null; checklist = id }) { open = null } } }
    checklist?.let { id -> all.firstOrNull { it.task.id == id }?.let { ChecklistDialog(state, aircraft, it) { checklist = null } } }
    if (adding) AddTaskDialog(aircraft) { adding = false }
    if (logNew) LogEntryDialog(aircraft, null) { logNew = false }
    logEdit?.let { e -> LogEntryDialog(aircraft, e) { logEdit = null } }
}

@Composable
private fun TaskCard(d: DueInfo, a: Aircraft, lg: String, onClick: () -> Unit) {
    val hidden = Maintenance.isHidden(d.task, a)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.task.title.get(lg), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusChip(d.status)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(categoryName(d.task.category), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                Chip(
                    stringResource(if (d.task.mandatory) R.string.mandatory else R.string.recommended),
                    if (d.task.mandatory) Color(0xFF0B2545) else Color(0xFF90A4AE), Color.White,
                )
                if (hidden) Chip(stringResource(R.string.hidden), Color(0xFF9E9E9E), Color.White)
            }
            Spacer(Modifier.height(6.dp))
            Text(intervalText(d.task), style = MaterialTheme.typography.bodySmall)
            val rem = remainingText(d)
            if (rem.isNotBlank()) Text(rem, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            // What to do at the next one (e.g. at 1100 h: oil + filter…, at 1200 h also plugs)
            val todo = Maintenance.itemsDue(d.task, d.dueHours)
            if (todo.isNotEmpty()) {
                Text(
                    d.dueHours?.let { stringResource(R.string.items_at, fmtH(it)) } ?: stringResource(R.string.items_scope),
                    style = MaterialTheme.typography.labelMedium, color = HbgPink, modifier = Modifier.padding(top = 4.dp),
                )
                todo.take(5).forEach { Text("• " + it.title.get(lg) + (if (!it.verified) " ⚠" else ""), style = MaterialTheme.typography.bodySmall) }
                if (todo.size > 5) Text(stringResource(R.string.items_more, todo.size - 5), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            d.last?.let {
                Text(
                    stringResource(R.string.last_done, fmtEpochDay(it.epochDay), fmtH(it.hours)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!d.task.verified || a.taskOverrides[d.task.id]?.let { it.intervalHours != null || it.intervalMonths != null } == true) {
                Text(
                    stringResource(if (d.isCustom) R.string.custom_task else R.string.check_interval),
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF6C00),
                )
            }
        }
    }
}

@Composable
fun intervalText(t: TaskDef): String {
    val parts = mutableListOf<String>()
    t.oneTimeAtHours?.let { parts += stringResource(R.string.once_at, fmtH(it)) }
    t.intervalHours?.let { parts += stringResource(R.string.every_h, fmtH(it)) }
    t.intervalMonths?.let { parts += stringResource(R.string.every_m, it) }
    t.toleranceHours?.let { parts += stringResource(R.string.tolerance, fmtH(it)) }
    if (parts.isEmpty()) parts += stringResource(R.string.status_noint)
    return parts.joinToString(" · ")
}

@Composable
private fun TaskDialog(d: DueInfo, a: Aircraft, state: AppState, lg: String, onChecklist: () -> Unit, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<CompletionRecord?>(null) }
    var date by remember { mutableStateOf(fmtDate(LocalDate.now())) }
    var hours by remember { mutableStateOf(fmtH(a.hours)) }
    var note by remember { mutableStateOf("") }
    val ov = a.taskOverrides[d.task.id] ?: TaskOverride()
    var ih by remember { mutableStateOf(d.task.intervalHours?.let { fmtH(it) } ?: "") }
    var im by remember { mutableStateOf(d.task.intervalMonths?.toString() ?: "") }
    val history = Maintenance.recordsFor(d.task, a, state.completions).sortedByDescending { it.hours }
    val hiddenNow = Maintenance.isHidden(d.task, a)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(d.task.title.get(lg)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Plain chips instead of TabRow: TabRow uses SubcomposeLayout, which dialogs may measure intrinsically.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(tab == 0, { tab = 0 }, label = { Text(stringResource(R.string.mark_done)) })
                    FilterChip(tab == 1, { tab = 1 }, label = { Text(stringResource(R.string.history)) })
                    FilterChip(tab == 2, { tab = 2 }, label = { Text(stringResource(R.string.settings)) })
                }
                Spacer(Modifier.height(12.dp))
                when (tab) {
                    0 -> {
                        if (editing != null) {
                            Text(stringResource(R.string.editing_entry), color = HbgPink, style = MaterialTheme.typography.bodySmall)
                        }
                        if (editing == null && (d.task.items.isNotEmpty() || Checklist.draft(state, a, d.task.id) != null)) {
                            androidx.compose.material3.Button(
                                onClick = onChecklist, modifier = Modifier.fillMaxWidth(),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = LotNavy),
                            ) { Text(stringResource(if (Checklist.draft(state, a, d.task.id) != null) R.string.chk_continue else R.string.chk_open)) }
                            Spacer(Modifier.height(8.dp))
                        }
                        DateField(date, { date = it }, stringResource(R.string.done_date), Modifier.fillMaxWidth())
                        NumberField(hours, { hours = it }, stringResource(R.string.done_hours), Modifier.fillMaxWidth())
                        OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.note)) }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        d.task.note?.let { Text(it.get(lg), style = MaterialTheme.typography.bodySmall) }
                        // Full scope: every item, with its own hour step
                        if (d.task.items.isNotEmpty()) {
                            Text(stringResource(R.string.items_scope), style = MaterialTheme.typography.labelLarge, color = HbgPink, modifier = Modifier.padding(top = 6.dp))
                            val now = Maintenance.itemsDue(d.task, d.dueHours).toSet()
                            d.task.items.forEach { it ->
                                Text(
                                    (if (it in now) "• " else "◦ ") + it.title.get(lg) +
                                        (it.everyHours?.let { h -> " — " + stringResource(R.string.every_h, fmtH(h)) } ?: "") + (if (!it.verified) " ⚠" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (it in now) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        SourceLine(d.task.source, d.task.verified)
                    }
                    1 -> {
                        val protos = state.protocols.filter { it.aircraftId == a.id && (it.taskId == d.task.id || it.taskId in d.task.aliases) }.sortedByDescending { it.epochDay ?: Long.MAX_VALUE }
                        if (protos.isNotEmpty()) {
                            Text(stringResource(R.string.chk_protocols), style = MaterialTheme.typography.labelLarge, color = HbgPink)
                            protos.forEach { pr ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (pr.finished) listOfNotNull(pr.epochDay?.let { fmtEpochDay(it) }, pr.hours?.let { fmtH(it) + " h" }).joinToString(" · ")
                                            else stringResource(R.string.chk_draft),
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(stringResource(R.string.chk_progress, pr.items.count { it.done || it.na }, pr.items.size) + (if (pr.mechanic.isNotBlank()) " · " + pr.mechanic else ""), style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (pr.finished) TextButton(onClick = { ProtocolPdf.share(ctx, a, pr, state) }) { Text("PDF") }
                                    else TextButton(onClick = onChecklist) { Text(stringResource(R.string.chk_open)) }
                                }
                                HorizontalDivider()
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        if (history.isEmpty()) Text(stringResource(R.string.no_history))
                        history.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${fmtEpochDay(r.epochDay)} · ${fmtH(r.hours)} h", fontWeight = FontWeight.SemiBold)
                                    if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = {
                                    editing = r
                                    date = fmtEpochDay(r.epochDay); hours = fmtH(r.hours); note = r.note
                                    tab = 0
                                }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                                IconButton(onClick = {
                                    Repo.update { s -> s.copy(completions = s.completions.filterNot { it.id == r.id }) }
                                }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                            }
                            HorizontalDivider()
                        }
                    }
                    else -> {
                        if (d.task.oneTimeAtHours == null) {
                            NumberField(ih, { ih = it }, stringResource(R.string.interval_hours), Modifier.fillMaxWidth())
                            NumberField(im, { im = it }, stringResource(R.string.interval_months), Modifier.fillMaxWidth())
                            Text(stringResource(R.string.interval_hint), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                saveOverride(a, d.task.id, ov.copy(intervalHours = parseNum(ih), intervalMonths = parseNum(im)?.toInt()), d.isCustom)
                            }) { Text(stringResource(R.string.save_interval)) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(hiddenNow, { saveOverride(a, d.task.id, ov.copy(hidden = it), false) })
                            Text(stringResource(R.string.hide_task))
                        }
                        if (d.isCustom) {
                            TextButton(onClick = {
                                Repo.upsertAircraft(a.copy(customTasks = a.customTasks.filterNot { it.id == d.task.id }))
                                onDismiss()
                            }) { Text(stringResource(R.string.delete_task), color = Color(0xFFC62828)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (tab == 0) {
                TextButton(enabled = parseDate(date) != null && parseNum(hours) != null, onClick = {
                    val ed = editing
                    if (ed != null) {
                        // Correcting an earlier entry keeps its id and the due time it was logged against.
                        val fixed = ed.copy(epochDay = parseDate(date)!!.toEpochDay(), hours = parseNum(hours)!!, note = note.trim())
                        Repo.update { s -> s.copy(completions = s.completions.map { if (it.id == ed.id) fixed else it }) }
                    } else {
                        val rec = CompletionRecord(
                            id = Repo.newId(), aircraftId = a.id, taskId = d.task.id,
                            epochDay = parseDate(date)!!.toEpochDay(), hours = parseNum(hours)!!,
                            dueAtHours = d.dueHours, note = note.trim(),
                        )
                        Repo.update { s -> s.copy(completions = s.completions + rec) }
                    }
                    onDismiss()
                }) { Text(stringResource(R.string.save)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = { if (tab == 0) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun saveOverride(a: Aircraft, taskId: String, o: TaskOverride, custom: Boolean) {
    if (custom && (o.intervalHours != null || o.intervalMonths != null)) {
        // Custom tasks are edited directly.
        Repo.upsertAircraft(a.copy(customTasks = a.customTasks.map {
            if (it.id == taskId) it.copy(intervalHours = o.intervalHours, intervalMonths = o.intervalMonths) else it
        }, taskOverrides = a.taskOverrides + (taskId to TaskOverride(hidden = o.hidden))))
    } else {
        Repo.upsertAircraft(a.copy(taskOverrides = a.taskOverrides + (taskId to o)))
    }
}

@Composable
private fun AddTaskDialog(a: Aircraft, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("airframe") }
    var ih by remember { mutableStateOf("") }
    var im by remember { mutableStateOf("") }
    var mandatory by remember { mutableStateOf(true) }
    val cats = listOf("airframe", "engine", "propeller", "rescue", "avionics", "regulatory", "other")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_task)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.task_name)) }, modifier = Modifier.fillMaxWidth())
                NumberField(ih, { ih = it }, stringResource(R.string.interval_hours), Modifier.fillMaxWidth())
                NumberField(im, { im = it }, stringResource(R.string.interval_months), Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(mandatory, { mandatory = it })
                    Text(stringResource(R.string.mandatory))
                }
                cats.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { cat = c }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(cat == c, { cat = c })
                        Spacer(Modifier.width(4.dp))
                        Text(categoryName(c))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && (parseNum(ih) != null || parseNum(im) != null), onClick = {
                val t = TaskDef(
                    id = "custom_" + Repo.newId(), title = L10n.same(name.trim()), category = cat,
                    intervalHours = parseNum(ih), intervalMonths = parseNum(im)?.toInt(),
                    mandatory = mandatory, source = "—", verified = true,
                )
                Repo.upsertAircraft(a.copy(customTasks = a.customTasks + t))
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}


/** Own maintenance log entry: date, optional hours, what was done. */
@Composable
private fun LogEntryDialog(a: Aircraft, e: com.cannonadestudio.tomarkpilot.data.LogEntry?, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(e?.title ?: "") }
    var date by remember { mutableStateOf(e?.let { fmtEpochDay(it.epochDay) } ?: fmtDate(LocalDate.now())) }
    var hours by remember { mutableStateOf(e?.hours?.let { fmtH(it) } ?: fmtH(a.hours)) }
    var note by remember { mutableStateOf(e?.note ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (e == null) R.string.log_add else R.string.edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.log_what)) }, modifier = Modifier.fillMaxWidth())
                Row {
                    DateField(date, { date = it }, stringResource(R.string.prev_done_date), Modifier.weight(1.15f))
                    Spacer(Modifier.width(6.dp))
                    NumberField(hours, { hours = it }, stringResource(R.string.prev_done_hours), Modifier.weight(0.85f))
                }
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.note)) }, modifier = Modifier.fillMaxWidth())
                if (e != null) TextButton(onClick = { Repo.deleteLogEntry(e.id); onDismiss() }) {
                    Text(stringResource(R.string.delete), color = Color(0xFFC62828))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && parseDate(date) != null && (hours.isBlank() || parseNum(hours) != null), onClick = {
                Repo.saveLogEntry(
                    com.cannonadestudio.tomarkpilot.data.LogEntry(
                        id = e?.id ?: Repo.newId(), aircraftId = a.id, epochDay = parseDate(date)!!.toEpochDay(),
                        hours = parseNum(hours), title = title.trim(), note = note.trim(),
                    )
                )
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
