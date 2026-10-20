@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cannonadestudio.tomarkpilot.ui

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
    val due = Maintenance.compute(aircraft, state.completions)
    val attention = due.filter { Maintenance.needsAttention(it) }
    val openBulletins = Repo.openBulletins(aircraft, state, feed)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Header – white on the gradient background
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(aircraft.registration, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Text(type.name, style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
            Chip(if (type.category == "EASA") "EASA ${type.tcds ?: ""}".trim() else "UL / LSA", HbgPink, Color.White)
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
                            if (err != null) stringResource(R.string.logbook_error, err) else stringResource(R.string.logbook_linked),
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
}

@Composable
private fun SpeedTile(s: SpeedDef, a: Aircraft, kmh: Boolean, lg: String, modifier: Modifier) {
    val raw = a.speedOverrides[s.key] ?: s.value ?: return
    val overridden = a.speedOverrides.containsKey(s.key)
    val main = toDisplay(raw, s.unit, kmh).roundToInt()
    val other = toDisplay(raw, s.unit, !kmh).roundToInt()
    val (bg, fg) = when (s.zone) {
        "red" -> Color(0xFFC62828) to Color.White
        "green" -> Color(0xFF2E7D32) to Color.White
        "white" -> Color(0xFFE3F2FD) to Color(0xFF0B2545)
        "approach" -> HbgPink to Color.White
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
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
    if (d.task.intervalMonths != null && d.dueDate == null && d.last == null) parts += stringResource(R.string.need_reference_date)
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
