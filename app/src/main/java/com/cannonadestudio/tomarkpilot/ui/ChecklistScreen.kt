@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cannonadestudio.tomarkpilot.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.Aircraft
import com.cannonadestudio.tomarkpilot.data.AppState
import com.cannonadestudio.tomarkpilot.data.DueInfo
import com.cannonadestudio.tomarkpilot.data.L10n
import com.cannonadestudio.tomarkpilot.data.Maintenance
import com.cannonadestudio.tomarkpilot.data.Protocol
import com.cannonadestudio.tomarkpilot.data.ProtocolItem
import com.cannonadestudio.tomarkpilot.data.Repo
import com.cannonadestudio.tomarkpilot.data.TaskItem
import kotlinx.coroutines.launch
import java.time.LocalDate

object Checklist {
    /** Draft in progress for this inspection (one per aircraft and task). */
    fun draft(s: AppState, a: Aircraft, taskId: String): Protocol? =
        s.protocols.firstOrNull { it.aircraftId == a.id && it.taskId == taskId && !it.finished }

    /** New checklist: the items due at this inspection (the aircraft's own list when it has one). */
    fun create(a: Aircraft, d: DueInfo, lg: String): Protocol = Protocol(
        id = Repo.newId(), aircraftId = a.id, taskId = d.task.id, taskTitle = d.task.title.get(lg), dueAtHours = d.dueHours,
        items = Maintenance.itemsDue(d.task, d.dueHours).map { toProtocolItem(it, lg) },
        source = listOf(d.task.source, d.task.itemsSource).filter { it.isNotBlank() }.joinToString(" · "),
    )

    fun toProtocolItem(it: TaskItem, lg: String): ProtocolItem {
        val (sec, title) = Maintenance.splitSection(it.title.get(lg))
        return ProtocolItem(
            title = title, section = sec, everyHours = it.everyHours, verified = it.verified,
            fields = it.fields.map { f -> f.get(lg) }, unit = it.unit, values = it.fields.map { "" },
        )
    }

    fun fullTitle(p: ProtocolItem) = if (p.section.isBlank()) p.title else p.section + ": " + p.title

    /** Item for the database / the aircraft's list, written in the language the user typed in (pl/en kept the same). */
    fun toTaskItem(p: ProtocolItem): TaskItem {
        val t = fullTitle(p)
        return TaskItem(
            title = L10n(t, t), everyHours = p.everyHours, verified = p.verified,
            fields = p.fields.map { L10n(it, it) }, unit = p.unit,
        )
    }
}

/** Full-screen checklist of one inspection (draft saved on every change, also synced to the account). */
@Composable
fun ChecklistDialog(state: AppState, a: Aircraft, d: DueInfo, onDismiss: () -> Unit) {
    val lg = lang()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fresh = remember(a.id, d.task.id) { Checklist.create(a, d, lg) }
    val p = Checklist.draft(state, a, d.task.id) ?: fresh
    var editing by remember { mutableStateOf<Int?>(null) }   // index, -1 = new item
    var finishing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    fun save(np: Protocol) = Repo.saveProtocol(np)
    fun setItem(i: Int, it: ProtocolItem) = save(p.copy(items = p.items.toMutableList().also { l -> l[i] = it }))
    val done = p.items.count { it.done || it.na }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = LotPage) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().background(LotNavy).statusBarsPadding().padding(start = 4.dp, end = 4.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close), tint = Color.White) }
                        Column(Modifier.weight(1f)) {
                            Text(p.taskTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 20.sp)
                            Text(
                                listOfNotNull(a.registration, Repo.type(a.typeId)?.name, p.dueAtHours?.let { stringResource(R.string.chk_at, fmtH(it)) },
                                    if (a.itemEdits.containsKey(p.taskId)) stringResource(R.string.chk_edited) else null).joinToString(" · "),
                                color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
                            )
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null, tint = Color.White) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (a.itemEdits.containsKey(p.taskId)) DropdownMenuItem(text = { Text(stringResource(R.string.chk_reset)) }, onClick = {
                                    menu = false
                                    Repo.saveItemEdits(a.id, p.taskId, null)
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.chk_discard)) }, onClick = {
                                    menu = false
                                    Repo.deleteProtocol(p.id)
                                    onDismiss()
                                })
                            }
                        }
                    }
                    Text(stringResource(R.string.chk_progress, done, p.items.size), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
                    LinearProgressIndicator(
                        progress = { if (p.items.isEmpty()) 0f else done.toFloat() / p.items.size },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(7.dp),
                        color = Color(0xFF6FA8F0), trackColor = Color.White.copy(alpha = 0.2f),
                    )
                }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    if (p.items.isEmpty()) item { Text(stringResource(R.string.chk_empty), Modifier.padding(16.dp)) }
                    itemsIndexed(p.items) { i, it ->
                        if (i == 0 || p.items[i - 1].section != it.section) {
                            val sec = p.items.filter { x -> x.section == it.section }
                            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 6.dp)) {
                                Text(it.section.ifBlank { p.taskTitle }.uppercase(), color = LotBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text("${sec.count { x -> x.done || x.na }}/${sec.size}", color = LotBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        ChecklistRow(it, onChange = { n -> setItem(i, n) }, onEdit = { editing = i })
                        Spacer(Modifier.height(7.dp))
                    }
                    item {
                        TextButton(onClick = { editing = -1 }) { Text("+ " + stringResource(R.string.chk_add_item)) }
                        if (p.source.isNotBlank()) Text(stringResource(R.string.chk_source, p.source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                        Spacer(Modifier.height(90.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(14.dp)) {
                    Button(
                        onClick = { finishing = true }, modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LotNavy),
                    ) { Text(stringResource(R.string.chk_finish), fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    editing?.let { idx ->
        val cur = if (idx >= 0) p.items[idx] else ProtocolItem(title = "", section = p.items.lastOrNull()?.section ?: "")
        ItemEditDialog(a, cur, isNew = idx < 0, onDismiss = { editing = null }) { newItem, toBase, comment, remove ->
            // the checklist in progress
            val items = p.items.toMutableList()
            when {
                remove && idx >= 0 -> items.removeAt(idx)
                idx >= 0 -> items[idx] = newItem.copy(done = cur.done, na = cur.na, note = cur.note, values = newItem.fields.indices.map { k -> cur.values.getOrElse(k) { "" } })
                else -> items += newItem.copy(values = newItem.fields.map { "" })
            }
            save(p.copy(items = items))
            // the aircraft's own list of this task (all items, not only those due now)
            val task = Maintenance.tasks(a).map { it.first }.firstOrNull { it.id == p.taskId }?.let { Maintenance.effective(it, a) }
            if (task != null) {
                val list = task.items.toMutableList()
                val pos = if (idx >= 0) list.indexOfFirst { Checklist.fullTitle(Checklist.toProtocolItem(it, lg)) == Checklist.fullTitle(cur) } else -1
                when {
                    remove && pos >= 0 -> list.removeAt(pos)
                    pos >= 0 -> list[pos] = Checklist.toTaskItem(newItem).copy(verified = true)
                    !remove -> list += Checklist.toTaskItem(newItem).copy(verified = true)
                }
                Repo.saveItemEdits(a.id, p.taskId, list)
            }
            if (toBase) {
                scope.launch {
                    val r = Repo.suggestChecklist(a, p.taskId, if (idx >= 0) Checklist.fullTitle(cur) else null, if (remove) null else Checklist.toTaskItem(newItem), comment)
                    Toast.makeText(ctx, if (r.isSuccess) R.string.chk_sent else R.string.chk_send_fail, Toast.LENGTH_LONG).show()
                }
            }
            editing = null
        }
    }
    if (finishing) FinishDialog(a, p, onDismiss = { finishing = false }) { fin, day, h ->
        Repo.finishProtocol(fin, day, h)
        finishing = false
        onDismiss()
        val s = Repo.state.value
        s.protocols.firstOrNull { it.id == fin.id }?.let { ProtocolPdf.share(ctx, a, it, s) }
    }
}

@Composable
private fun ChecklistRow(it: ProtocolItem, onChange: (ProtocolItem) -> Unit, onEdit: () -> Unit) {
    var noteOpen by remember { mutableStateOf(it.note.isNotBlank()) }
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(start = 2.dp, end = 4.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(it.done, { v -> onChange(it.copy(done = v, na = false)) }, colors = CheckboxDefaults.colors(checkedColor = LotNavy))
            Column(Modifier.weight(1f).padding(top = 12.dp)) {
                Text(
                    it.title, fontSize = 14.sp, lineHeight = 18.sp,
                    color = if (it.done || it.na) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable { onChange(it.copy(done = !it.done, na = false)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 3.dp)) {
                    it.everyHours?.let { h -> Chip(stringResource(R.string.chk_every_chip, fmtH(h)), Color(0xFFE6EEF8), LotNavy) }
                    if (!it.verified) Chip(stringResource(R.string.chk_unverified), Color(0xFFFFF1CF), Color(0xFF8A6100))
                    if (it.na) Chip(stringResource(R.string.chk_na_long), Color(0xFFECEFF3), Color(0xFF55606F))
                }
                if (it.fields.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        it.fields.forEachIndexed { k, label ->
                            OutlinedTextField(
                                value = it.values.getOrElse(k) { "" },
                                onValueChange = { v -> onChange(it.copy(values = it.fields.indices.map { j -> if (j == k) v else it.values.getOrElse(j) { "" } })) },
                                label = { Text(label + (if (it.unit.isNotBlank()) " [${it.unit}]" else ""), fontSize = 10.sp, maxLines = 1) },
                                singleLine = true, textStyle = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (noteOpen) {
                    OutlinedTextField(
                        it.note, { v -> onChange(it.copy(note = v)) }, label = { Text(stringResource(R.string.note)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                Row {
                    if (!noteOpen) TextButton(onClick = { noteOpen = true }) { Text("+ " + stringResource(R.string.note), fontSize = 12.sp) }
                    TextButton(onClick = { onChange(it.copy(na = !it.na, done = false)) }) { Text(stringResource(R.string.chk_na), fontSize = 12.sp) }
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, stringResource(R.string.chk_edit_item), tint = Color(0xFF55606F)) }
        }
    }
}

@Composable
private fun ItemEditDialog(
    a: Aircraft, item: ProtocolItem, isNew: Boolean, onDismiss: () -> Unit,
    onSave: (item: ProtocolItem, toBase: Boolean, comment: String, remove: Boolean) -> Unit,
) {
    var title by remember { mutableStateOf(item.title) }
    var section by remember { mutableStateOf(item.section) }
    var every by remember { mutableStateOf(item.everyHours?.let { fmtH(it) } ?: "") }
    var fields by remember { mutableStateOf(item.fields.joinToString(", ")) }
    var unit by remember { mutableStateOf(item.unit) }
    var toBase by remember { mutableStateOf(false) }
    var comment by remember { mutableStateOf("") }
    fun built() = item.copy(
        title = title.trim(), section = section.trim(), everyHours = parseNum(every)?.takeIf { it > 0 },
        fields = fields.split(',').map { it.trim() }.filter { it.isNotEmpty() }, unit = unit.trim(), verified = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.chk_add_item else R.string.chk_edit_item)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding()) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.chk_text)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(every, { every = it }, label = { Text(stringResource(R.string.chk_every_field)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fields, { fields = it }, label = { Text(stringResource(R.string.chk_fields)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(unit, { unit = it }, label = { Text(stringResource(R.string.chk_unit)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(Modifier.clickable { toBase = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!toBase, { toBase = false })
                    Column { Text(stringResource(R.string.chk_scope_here, a.registration), fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.chk_scope_here_hint), style = MaterialTheme.typography.bodySmall) }
                }
                Row(Modifier.clickable { toBase = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(toBase, { toBase = true })
                    Column { Text(stringResource(R.string.chk_scope_suggest), fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.chk_scope_suggest_hint), style = MaterialTheme.typography.bodySmall) }
                }
                if (toBase) OutlinedTextField(comment, { comment = it }, label = { Text(stringResource(R.string.chk_comment)) }, modifier = Modifier.fillMaxWidth())
                if (!isNew) TextButton(onClick = { onSave(built(), toBase, comment.trim(), true) }) { Text(stringResource(R.string.chk_remove), color = Color(0xFFC62828)) }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { onSave(built(), toBase, comment.trim(), false) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FinishDialog(a: Aircraft, p: Protocol, onDismiss: () -> Unit, onFinish: (Protocol, Long, Double) -> Unit) {
    var date by remember { mutableStateOf(fmtDate(LocalDate.now())) }
    var hours by remember { mutableStateOf(fmtH(a.hours)) }
    var place by remember { mutableStateOf(p.place) }
    var mech by remember { mutableStateOf(p.mechanic.ifBlank { Repo.state.value.protocols.lastOrNull { it.finished && it.mechanic.isNotBlank() }?.mechanic ?: "" }) }
    var lic by remember { mutableStateOf(p.licence.ifBlank { Repo.state.value.protocols.lastOrNull { it.finished && it.licence.isNotBlank() }?.licence ?: "" }) }
    var notes by remember { mutableStateOf(p.notes) }
    val open = p.items.count { !it.done && !it.na }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chk_finish)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding()) {
                if (open > 0) Text(stringResource(R.string.chk_not_all, open), color = Color(0xFFB26A00), style = MaterialTheme.typography.bodySmall)
                DateField(date, { date = it }, stringResource(R.string.done_date), Modifier.fillMaxWidth())
                NumberField(hours, { hours = it }, stringResource(R.string.done_hours), Modifier.fillMaxWidth())
                OutlinedTextField(place, { place = it }, label = { Text(stringResource(R.string.chk_place)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mech, { mech = it }, label = { Text(stringResource(R.string.chk_mechanic)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(lic, { lic = it }, label = { Text(stringResource(R.string.chk_licence)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.chk_remarks)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = parseDate(date) != null && parseNum(hours) != null, onClick = {
                onFinish(p.copy(place = place.trim(), mechanic = mech.trim(), licence = lic.trim(), notes = notes.trim()), parseDate(date)!!.toEpochDay(), parseNum(hours)!!)
            }) { Text(stringResource(R.string.chk_pdf)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
