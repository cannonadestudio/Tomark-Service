@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cannonadestudio.tomarkpilot.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.Aircraft
import com.cannonadestudio.tomarkpilot.data.AppState
import com.cannonadestudio.tomarkpilot.data.Bulletin
import com.cannonadestudio.tomarkpilot.data.BulletinFeed
import com.cannonadestudio.tomarkpilot.data.BulletinStatus
import com.cannonadestudio.tomarkpilot.data.Repo
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun BulletinsScreen(state: AppState, feed: BulletinFeed, aircraft: Aircraft) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var onlyOpen by remember { mutableStateOf(false) }
    var hideDocs by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Bulletin?>(null) }
    val seenAtOpen = remember(aircraft.id) { state.seenBulletinIds }
    val all = Repo.bulletinsFor(aircraft, feed, state)
    var ownEdit by remember { mutableStateOf<Bulletin?>(null) }
    var ownNew by remember { mutableStateOf(false) }
    val statusMap = state.bulletinStatus.filter { it.aircraftId == aircraft.id }.associateBy { it.bulletinId }
    val list = all.filter { (!onlyOpen || (it.kind != "DOC" && it.id !in statusMap)) && (!hideDocs || it.kind != "DOC") }

    LaunchedEffect(aircraft.id, all.size) {
        Repo.update { it.copy(seenBulletinIds = it.seenBulletinIds + all.map { b -> b.id }) }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.bulletins_for, aircraft.registration), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.list_updated, feed.updated), style = MaterialTheme.typography.bodySmall)
                    EngineSerialLine(aircraft, MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { ownNew = true }) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Add, stringResource(R.string.own_bulletin_add))
                }
                if (loading) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    IconButton(onClick = {
                        loading = true
                        scope.launch {
                            val r = Repo.refreshBulletins()
                            loading = false
                            val msg = r.fold(
                                { ctx.getString(R.string.refresh_ok, it) },
                                { ctx.getString(R.string.refresh_fail) },
                            )
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(onlyOpen, { onlyOpen = !onlyOpen }, label = { Text(stringResource(R.string.filter_open)) })
                FilterChip(hideDocs, { hideDocs = !hideDocs }, label = { Text(stringResource(R.string.filter_hide_docs)) })
            }
            Text(stringResource(R.string.bulletins_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        items(list, key = { it.id }) { b ->
            BulletinCard(b, statusMap[b.id], b.id !in seenAtOpen, aircraft.yearBuilt) { open = b }
            Spacer(Modifier.height(8.dp))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    open?.let { b ->
        BulletinDialog(b, aircraft, statusMap[b.id], own = Repo.isOwnBulletin(aircraft, b.id, state), onEditOwn = { ownEdit = b; open = null }) { open = null }
    }
    if (ownNew) OwnBulletinDialog(aircraft, null) { ownNew = false }
    ownEdit?.let { b -> OwnBulletinDialog(aircraft, b) { ownEdit = null } }
}

@Composable
private fun BulletinCard(b: Bulletin, st: BulletinStatus?, isNew: Boolean, yearBuilt: Int?, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(b.id, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (isNew) Chip(stringResource(R.string.bl_new), Color(0xFF1565C0), Color.White)
                KindChip(b)
                b.url?.let { u ->
                    IconButton(onClick = { openUrl(ctx, u) }, modifier = Modifier.size(32.dp)) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open_document), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text(b.date, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(b.title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            val bYear = b.date.take(4).toIntOrNull()
            if (yearBuilt != null && bYear != null && bYear < yearBuilt) {
                Text(stringResource(R.string.bl_before_build, yearBuilt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (b.kind != "DOC") MandatoryChip(b)
                when (st?.status) {
                    "DONE" -> Chip(stringResource(R.string.bl_done) + " " + fmtEpochDay(st.epochDay), Color(0xFF2E7D32), Color.White)
                    "NA" -> Chip(stringResource(R.string.bl_na), Color(0xFF607D8B), Color.White)
                    else -> if (b.kind != "DOC") Chip(stringResource(R.string.bl_open), Color(0xFFEF6C00), Color.White)
                }
            }
        }
    }
}

@Composable
private fun KindChip(b: Bulletin) {
    val (bg, fg) = when (b.kind) {
        "AD" -> Color(0xFFC62828) to Color.White
        "ASB" -> Color(0xFFD84315) to Color.White
        "DOC" -> Color(0xFF78909C) to Color.White
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Chip(b.kind, bg, fg)
}

@Composable
private fun MandatoryChip(b: Bulletin) = when (b.mandatory) {
    true -> Chip(stringResource(R.string.bl_mandatory), Color(0xFF0B2545), Color.White)
    false -> Chip(stringResource(R.string.bl_info), Color(0xFF90A4AE), Color.White)
    null -> Chip(stringResource(R.string.bl_unknown), Color(0xFFBDBDBD), Color.Black)
}

@Composable
private fun BulletinDialog(b: Bulletin, a: Aircraft, st: BulletinStatus?, own: Boolean = false, onEditOwn: () -> Unit = {}, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var date by remember { mutableStateOf(st?.let { fmtEpochDay(it.epochDay) } ?: fmtDate(LocalDate.now())) }
    var hours by remember { mutableStateOf(st?.hours?.let { fmtH(it) } ?: fmtH(a.hours)) }
    var note by remember { mutableStateOf(st?.note ?: "") }

    fun save(status: String?) {
        Repo.update { s ->
            val rest = s.bulletinStatus.filterNot { it.aircraftId == a.id && it.bulletinId == b.id }
            s.copy(bulletinStatus = if (status == null) rest else rest + BulletinStatus(
                aircraftId = a.id, bulletinId = b.id, status = status,
                epochDay = parseDate(date)?.toEpochDay() ?: LocalDate.now().toEpochDay(),
                hours = parseNum(hours), note = note.trim(),
            ))
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(b.id) },
        text = {
            Column {
                Text(b.title, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { KindChip(b); if (b.kind != "DOC") MandatoryChip(b) }
                if (own) Row {
                    TextButton(onClick = onEditOwn) { Text(stringResource(R.string.edit)) }
                    TextButton(onClick = { Repo.deleteOwnBulletin(a.id, b.id); onDismiss() }) { Text(stringResource(R.string.delete), color = Color(0xFFC62828)) }
                }
                if (b.url != null) {
                    TextButton(onClick = {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(b.url)))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(ctx, b.url, Toast.LENGTH_LONG).show()
                        }
                    }) { Text(stringResource(R.string.bl_open_source)) }
                }
                if (b.kind != "DOC") {
                    DateField(date, { date = it }, stringResource(R.string.done_date), Modifier.fillMaxWidth())
                    NumberField(hours, { hours = it }, stringResource(R.string.done_hours), Modifier.fillMaxWidth())
                    OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.note)) }, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick = { save("NA") }) { Text(stringResource(R.string.bl_na)) }
                        if (st != null) TextButton(onClick = { save(null) }) { Text(stringResource(R.string.bl_clear)) }
                    }
                }
            }
        },
        confirmButton = {
            if (b.kind != "DOC") {
                TextButton(enabled = parseDate(date) != null, onClick = { save("DONE") }) { Text(stringResource(R.string.bl_mark_done)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}


/** Bulletin missing from the online list, added by the owner for this aircraft. */
@Composable
private fun OwnBulletinDialog(a: Aircraft, b: Bulletin?, onDismiss: () -> Unit) {
    var number by remember { mutableStateOf(b?.id ?: "") }
    var title by remember { mutableStateOf(b?.title ?: "") }
    var date by remember { mutableStateOf(b?.date ?: fmtDate(LocalDate.now())) }
    var kind by remember { mutableStateOf(b?.kind ?: "SB") }
    var mandatory by remember { mutableStateOf(b?.mandatory ?: true) }
    var url by remember { mutableStateOf(b?.url ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (b == null) R.string.own_bulletin_add else R.string.edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.own_bulletin_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(number, { number = it }, label = { Text(stringResource(R.string.own_bulletin_number)) }, singleLine = true,
                    enabled = b == null, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.own_bulletin_title)) }, modifier = Modifier.fillMaxWidth())
                DateField(date, { date = it }, stringResource(R.string.own_bulletin_date), Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("AD", "ASB", "SB", "SIB").forEach { k -> FilterChip(kind == k, { kind = k }, label = { Text(k) }) }
                }
                Row(Modifier.clickable { mandatory = !mandatory }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(mandatory, { mandatory = it })
                    Text(stringResource(R.string.bl_mandatory))
                }
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.own_bulletin_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = number.isNotBlank() && title.isNotBlank() && parseDate(date) != null, onClick = {
                Repo.saveOwnBulletin(a.id, Bulletin(
                    id = b?.id ?: number.trim(), date = parseDate(date)!!.toString(), title = title.trim(),
                    groups = listOf("OWN"), kind = kind, mandatory = mandatory,
                    url = url.trim().takeIf { it.startsWith("http") },
                ))
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
