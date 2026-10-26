@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cannonadestudio.tomarkpilot.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.IconButton
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.foundation.background
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cannonadestudio.tomarkpilot.MainActivity
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.Aircraft
import com.cannonadestudio.tomarkpilot.data.AircraftType
import com.cannonadestudio.tomarkpilot.data.AppState
import com.cannonadestudio.tomarkpilot.data.BulletinFeed
import com.cannonadestudio.tomarkpilot.data.Maintenance
import com.cannonadestudio.tomarkpilot.data.Repo
import com.cannonadestudio.tomarkpilot.data.SpeedDef
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun NoAircraft(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Flight, null, Modifier.padding(8.dp).width(64.dp).height(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.no_aircraft_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.no_aircraft_text), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) { Text(stringResource(R.string.add_aircraft)) }
    }
}

@Composable
fun HomeScreen(
    state: AppState,
    feed: BulletinFeed,
    aircraft: Aircraft,
    type: AircraftType,
    onTab: (Int) -> Unit,
) {
    val lg = lang()
    var hoursDialog by remember { mutableStateOf(false) }
    var bookWork by remember { mutableStateOf<String?>(null) }
    val due = Maintenance.compute(aircraft, state.completions)
    val attention = due.filter { Maintenance.needsAttention(it) }
    val openBulletins = Repo.openBulletins(aircraft, state, feed)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        // Header – white on the gradient background, tight under the top bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(aircraft.registration, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = OnPage, lineHeight = 30.sp)
                Text(type.name, style = MaterialTheme.typography.titleSmall, color = OnPage, lineHeight = 18.sp)
                EngineSerialLine(aircraft, OnPage)
            }
            Chip(if (type.category == "EASA") "EASA ${type.tcds ?: ""}".trim() else "UL / LSA", HbgPink, Color.White)
        }
        // Hours left to the next hour-based inspection (hours follow the logbook when linked)
        due.filter { it.remainingHours != null && it.status != com.cannonadestudio.tomarkpilot.data.DueStatus.DONE }
            .minByOrNull { it.remainingHours!! }?.let { d ->
                NextInspectionCard(d, aircraft, state, lg, onBook = { bookWork = d.task.title.get(lg) }) { onTab(MainActivity.TAB_SERVICE) }
            } ?: run {
                // Nothing to count from yet (aircraft added with gaps in the records) – a hint instead of alarms.
                if (due.any { it.status == com.cannonadestudio.tomarkpilot.data.DueStatus.NO_DATA }) {
                    Card(Modifier.fillMaxWidth().padding(top = 10.dp).clickable { onTab(MainActivity.TAB_SERVICE) }) {
                        Column(Modifier.padding(14.dp)) {
                            Text(stringResource(R.string.next_insp_title), style = MaterialTheme.typography.labelLarge, color = HbgPink)
                            Text(stringResource(R.string.no_data_card), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        SponsorCard()
        // Flightradar24 – last flights of this registration
        val ctx = LocalContext.current
        Row(
            Modifier.clickable {
                val reg = aircraft.registration.trim().lowercase().replace(" ", "")
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.flightradar24.com/data/aircraft/" + Uri.encode(reg))))
                } catch (_: ActivityNotFoundException) {
                }
            }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.TravelExplore, null, tint = OnPage)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(stringResource(R.string.fr24), color = OnPage, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.fr24_hint), color = OnPage.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
            }
        }
        // Announcements from the management system
        val news by Repo.news.collectAsState()
        news.messages.filter { it.id !in state.dismissedNewsIds }.take(3).forEach { n ->
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.news_title) + (if (n.date.isNotBlank()) " · " + n.date else ""), style = MaterialTheme.typography.labelMedium, color = HbgPink)
                        Text(n.title, fontWeight = FontWeight.SemiBold)
                        if (n.text.isNotBlank()) Text(n.text, style = MaterialTheme.typography.bodySmall)
                        n.url?.let { u ->
                            Text(u, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: ActivityNotFoundException) {} })
                        }
                    }
                    IconButton(onClick = { Repo.update { it.copy(dismissedNewsIds = it.dismissedNewsIds + n.id) } }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.close))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Speeds – 3 equal tiles per row
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.speeds), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = !state.useKmh, onClick = { Repo.update { it.copy(useKmh = false) } },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("kt") }
                        SegmentedButton(
                            selected = state.useKmh, onClick = { Repo.update { it.copy(useKmh = true) } },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("km/h") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val known = type.speeds.filter { (aircraft.speedOverrides[it.key] ?: it.value) != null }
                val missing = type.speeds.filter { (aircraft.speedOverrides[it.key] ?: it.value) == null }
                known.chunked(3).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 6.dp),
                    ) {
                        row.forEach { SpeedTile(it, aircraft, state.useKmh, lg, Modifier.weight(1f).fillMaxHeight()) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (missing.isNotEmpty()) {
                    Text(
                        stringResource(R.string.missing_speeds, missing.joinToString(", ") { it.key }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onTab(MainActivity.TAB_MANUAL) }.padding(vertical = 4.dp),
                    )
                }
                Text(
                    stringResource(R.string.speeds_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // Hours
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.hours_now, fmtH(aircraft.hours)), style = MaterialTheme.typography.titleMedium)
                    aircraft.hoursUpdatedEpochDay?.let {
                        Text(stringResource(R.string.hours_updated, fmtEpochDay(it)), style = MaterialTheme.typography.bodySmall)
                    }
                    if (aircraft.logbookBaseHours != null) {
                        val err = state.logbookError
                        Text(
                            if (err != null) logbookErrorMsg(LocalContext.current, err) else stringResource(R.string.logbook_linked),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (err != null) Color(0xFFC62828) else MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                FilledTonalButton(onClick = { hoursDialog = true }) { Text(stringResource(R.string.update_hours)) }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Upcoming maintenance
        Card(Modifier.fillMaxWidth().clickable { onTab(MainActivity.TAB_SERVICE) }) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.next_work), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (attention.isEmpty()) {
                    Text(stringResource(R.string.all_ok))
                    due.firstOrNull { it.remainingHours != null || it.remainingDays != null }?.let { DueLine(it, lg) }
                } else {
                    attention.take(4).forEach { DueLine(it, lg) }
                    if (attention.size > 4) Text("+${attention.size - 4}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Bulletins
        if (openBulletins.isNotEmpty()) {
            val mandatory = openBulletins.count { it.mandatory == true }
            Card(
                Modifier.fillMaxWidth().clickable { onTab(MainActivity.TAB_BULLETINS) },
                colors = CardDefaults.cardColors(
                    containerColor = if (mandatory > 0) Color(0xFFFFE0B2) else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (mandatory > 0) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.NotificationsActive, null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.open_bulletins_n, openBulletins.size), fontWeight = FontWeight.SemiBold)
                        if (mandatory > 0) Text(stringResource(R.string.open_bulletins_mandatory, mandatory), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (hoursDialog) HoursDialog(aircraft) { hoursDialog = false }
    bookWork?.let { w -> ServiceRequestDialog(aircraft, w) { bookWork = null } }
}

@Composable
private fun SpeedTile(s: SpeedDef, a: Aircraft, kmh: Boolean, lg: String, modifier: Modifier) {
    val raw = a.speedOverrides[s.key] ?: s.value ?: return
    val overridden = a.speedOverrides.containsKey(s.key)
    val main = toDisplay(raw, s.unit, kmh).roundToInt()
    val other = toDisplay(raw, s.unit, !kmh).roundToInt()
    val dark = false   // light airline look
    val tile = Color.White
    val (bg, fg) = when (s.zone) {
        "red" -> Color(0xFFC62828) to Color.White
        "green" -> Color(0xFF2E7D32) to Color.White
        // Dark mode: black tiles, the zone shows in the text colour (battery saving on OLED).
        "white" -> if (dark) tile to Color(0xFFBFE0FF) else Color(0xFFE3F2FD) to Color(0xFF0B2545)
        "approach" -> if (dark) tile to Color(0xFFFF8DB6) else HbgPink to Color.White
        else -> if (dark) tile to Color.White else MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Card(modifier, colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.key, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (overridden) Text("✎", fontSize = 11.sp) else if (!s.verified) Text("⚠", fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$main", fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(2.dp))
                Text(if (kmh) "km/h" else "kt", fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("$other ${if (kmh) "kt" else "km/h"}", fontSize = 10.sp)
            Text(s.label.get(lg).removePrefix(s.key).trim(), fontSize = 10.sp, maxLines = 2, lineHeight = 12.sp)
        }
    }
}

@Composable
fun DueLine(d: com.cannonadestudio.tomarkpilot.data.DueInfo, lg: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.task.title.get(lg), style = MaterialTheme.typography.bodyMedium)
            Text(remainingText(d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusChip(d.status)
    }
}

@Composable
fun remainingText(d: com.cannonadestudio.tomarkpilot.data.DueInfo): String {
    val parts = mutableListOf<String>()
    d.remainingHours?.let {
        parts += if (it >= 0) stringResource(R.string.remaining_h, fmtH(it)) else stringResource(R.string.overdue_h, fmtH(-it))
    }
    d.remainingDays?.let {
        parts += if (it >= 0) stringResource(R.string.remaining_d, it.toInt()) else stringResource(R.string.overdue_d, (-it).toInt())
    }
    d.dueHours?.let { parts += stringResource(R.string.due_at, fmtH(it)) }
    d.dueDate?.let { parts += stringResource(R.string.due_on, fmtDate(it)) }
    if (d.status == com.cannonadestudio.tomarkpilot.data.DueStatus.NO_DATA) parts += stringResource(R.string.no_data_hint)
    else if (d.task.intervalMonths != null && d.dueDate == null && d.last == null) parts += stringResource(R.string.need_reference_date)
    return parts.joinToString(" · ")
}

@Composable
fun HoursDialog(a: Aircraft, onDismiss: () -> Unit) {
    var total by remember { mutableStateOf(fmtH(a.hours)) }
    var add by remember { mutableStateOf("") }
    val newTotal = parseNum(add)?.let { a.hours + it } ?: parseNum(total)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_hours)) },
        text = {
            Column {
                NumberField(total, { total = it; add = "" }, stringResource(R.string.total_hours), Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                NumberField(add, { add = it }, stringResource(R.string.add_flight_time), Modifier.fillMaxWidth())
                if (newTotal != null && newTotal < a.hours) {
                    Text(stringResource(R.string.hours_decrease_warning), color = Color(0xFFEF6C00), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.hours_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = newTotal != null && newTotal >= 0, onClick = {
                val h = newTotal ?: return@TextButton
                // When linked to the logbook, a manual correction shifts the base so the next sync keeps it.
                Repo.upsertAircraft(
                    a.copy(
                        hours = h, hoursUpdatedEpochDay = LocalDate.now().toEpochDay(),
                        logbookBaseHours = a.logbookBaseHours?.let { it + (h - a.hours) },
                    )
                )
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Engine S/N, gearbox S/N and year of manufacture – always visible, copyable; tap to edit. */
@Composable
fun EngineSerialLine(a: Aircraft, color: Color) {
    var edit by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    @Composable
    fun line(label: String, value: String?, copyable: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (value.isNullOrBlank()) label + ": " + stringResource(R.string.tap_to_enter) else "$label: $value",
                color = color, style = MaterialTheme.typography.bodyMedium, lineHeight = 18.sp,
                fontWeight = if (value.isNullOrBlank()) FontWeight.Normal else FontWeight.SemiBold,
                modifier = Modifier.clickable { edit = true },
            )
            if (copyable && !value.isNullOrBlank()) {
                IconButton(onClick = {
                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.copied, value), android.widget.Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy), tint = color, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
    line(stringResource(R.string.engine_sn_label), a.engineSerial, true)
    if (a.gearboxSerial.isNotBlank()) line(stringResource(R.string.gearbox_sn_label), a.gearboxSerial, true)
    line(stringResource(R.string.year_built), a.yearBuilt?.toString(), false)
    if (edit) {
        var sn by remember { mutableStateOf(a.engineSerial) }
        var gb by remember { mutableStateOf(a.gearboxSerial) }
        var yr by remember { mutableStateOf(a.yearBuilt?.toString() ?: "") }
        val yrOk = yr.isBlank() || yr.toIntOrNull()?.let { it in 1950..2100 } == true
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text(a.registration) },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(sn, { sn = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.engine_serial)) }, modifier = Modifier.fillMaxWidth())
                    androidx.compose.material3.OutlinedTextField(gb, { gb = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.gearbox_serial)) }, modifier = Modifier.fillMaxWidth())
                    androidx.compose.material3.OutlinedTextField(
                        yr, { yr = it.filter(Char::isDigit).take(4) }, singleLine = true, isError = !yrOk,
                        label = { Text(stringResource(R.string.year_built)) }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    )
                    Text(stringResource(R.string.engine_sn_hint), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = yrOk, onClick = {
                    Repo.upsertAircraft(a.copy(engineSerial = sn, gearboxSerial = gb, yearBuilt = yr.toIntOrNull()))
                    edit = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { edit = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}


@Composable
private fun NextInspectionCard(d: com.cannonadestudio.tomarkpilot.data.DueInfo, a: Aircraft, state: AppState, lg: String, onBook: () -> Unit, onClick: () -> Unit) {
    val rem = d.remainingHours ?: return
    val look = statusLook(d.status)
    val numColor = when (d.status) {
        com.cannonadestudio.tomarkpilot.data.DueStatus.OK -> Color(0xFF2E7D32)
        com.cannonadestudio.tomarkpilot.data.DueStatus.SOON -> Color(0xFFB26A00)
        else -> look.bg
    }
    // Collapsed: hours left + status. Tap to unfold the details.
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // Clearly framed, rounded box (like the ad card) – colour of the frame follows the status.
    Card(
        Modifier.fillMaxWidth().padding(top = 10.dp).clickable { open = !open },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, numColor),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color.White),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.next_insp_title), style = MaterialTheme.typography.labelLarge, color = HbgPink)
                    if (rem >= 0) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(fmtH(rem), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = numColor, lineHeight = 32.sp)
                            Text(" h", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = numColor, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    } else {
                        Text(stringResource(R.string.next_insp_over, fmtH(-rem)), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = numColor)
                    }
                }
                StatusChip(d.status)
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    stringResource(if (open) R.string.collapse else R.string.expand),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            androidx.compose.animation.AnimatedVisibility(open) {
                Column {
                    Text(stringResource(R.string.next_insp_at, d.task.title.get(lg), fmtH(d.dueHours ?: 0.0)), style = MaterialTheme.typography.bodyMedium)
                    val todo = com.cannonadestudio.tomarkpilot.data.Maintenance.itemsDue(d.task, d.dueHours)
                    if (todo.isNotEmpty()) {
                        todo.take(6).forEach { Text("• " + it.title.get(lg), style = MaterialTheme.typography.bodySmall) }
                        if (todo.size > 6) Text(stringResource(R.string.items_more, todo.size - 6), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    d.dueDate?.let { Text(stringResource(R.string.next_insp_date, fmtDate(it)), style = MaterialTheme.typography.bodySmall) }
                    val synced = state.logbookSyncedEpochDay
                    Text(
                        if (a.logbookBaseHours != null && synced != null && state.logbookError == null) stringResource(R.string.next_insp_logbook, fmtEpochDay(synced))
                        else stringResource(R.string.next_insp_manual),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.FilledTonalButton(onClick = onBook) { Text(stringResource(R.string.req_book)) }
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = onClick) { Text(stringResource(R.string.tab_service)) }
                    }
                }
            }
        }
    }
}


/**
 * Sponsor banners from the owner's server: just the picture with a small "AD" label on top.
 * Active banners take turns every [com.cannonadestudio.tomarkpilot.data.SponsorFeed.rotateSeconds].
 */
@Composable
private fun SponsorCard() {
    val feed by Repo.sponsor.collectAsState()
    // An image that failed to load (no network at start, Drive hiccup) is retried, never dropped for good.
    val failed = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    val live = feed.banners.filter { it.live() && (it.image.startsWith("https://") || it.text.isNotBlank() || it.title.isNotBlank()) }
    if (live.isEmpty()) return
    val start = remember(live.size) { if (live.size > 1) kotlin.random.Random.nextInt(live.size) else 0 }
    var step by remember(live.size) { mutableStateOf(0) }
    if (feed.rotateSeconds > 0 && live.size > 1) {
        androidx.compose.runtime.LaunchedEffect(live.size, feed.rotateSeconds) {
            while (true) { kotlinx.coroutines.delay(feed.rotateSeconds * 1000L); step++ }
        }
    }
    val b = live[(start + step) % live.size]
    val attempts = failed[b.id] ?: 0
    if (attempts > 0) androidx.compose.runtime.LaunchedEffect(b.id, attempts) {
        kotlinx.coroutines.delay(if (attempts < 3) 5_000L else 60_000L)
        failed[b.id] = -attempts   // negative = retry now, remembers the count
    }
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(b.id) { Repo.track("sponsor_view", "sponsor:" + b.id) }
    // The "AD" tab hangs just under the banner's bottom edge, drawn on top – it takes no space of its own.
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(top = 10.dp).zIndex(1f)) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = b.url.isNotBlank()) {
            Repo.track("sponsor_click", "sponsor:" + b.id)
            openUrl(ctx, b.url)
        },
    ) {
        androidx.compose.foundation.layout.Box {
            androidx.compose.animation.Crossfade(targetState = b, label = "sponsor") { cur ->
                val n = failed[cur.id] ?: 0
                if (cur.image.startsWith("https://") && n <= 0) {
                    coil.compose.AsyncImage(
                        // the fragment only makes a retry a new request; it is not sent to the server
                        model = directImageUrl(cur.image) + (if (n < 0) "#r$n" else ""), contentDescription = cur.title.ifBlank { null },
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                        onError = { failed[cur.id] = -(failed[cur.id] ?: 0) + 1 },
                    )
                } else {
                    Text(cur.text.ifBlank { cur.title }, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 70.dp, top = 12.dp, bottom = 12.dp))
                }
            }
        }
    }
    Text(
        stringResource(R.string.ad_label), fontSize = 8.sp, lineHeight = 10.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.align(Alignment.BottomEnd).offset(y = 12.dp).padding(end = 16.dp).zIndex(1f)
            .background(Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
    }
}
