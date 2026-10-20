@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cannonadestudio.tomarkpilot.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.Aircraft
import com.cannonadestudio.tomarkpilot.data.AircraftType
import com.cannonadestudio.tomarkpilot.data.Repo
import com.cannonadestudio.tomarkpilot.data.SpeedDef
import kotlin.math.roundToInt

@Composable
fun ManualScreen(aircraft: Aircraft, type: AircraftType, kmh: Boolean) {
    val lg = lang()
    val ctx = LocalContext.current
    var editSpeed by remember { mutableStateOf<SpeedDef?>(null) }
    var expanded by remember { mutableStateOf(setOf("speeds")) }
    var notes by remember(aircraft.id) { mutableStateOf(aircraft.notes) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Repo.upsertAircraft(aircraft.copy(pohUri = uri.toString()))
        }
    }

    fun toggle(id: String) {
        expanded = if (id in expanded) expanded - id else expanded + id
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.my_poh), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.my_poh_hint), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        if (aircraft.pohUri != null) {
                            Button(onClick = {
                                val i = Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(Uri.parse(aircraft.pohUri), "application/pdf")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                try {
                                    ctx.startActivity(i)
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(ctx, R.string.no_pdf_viewer, Toast.LENGTH_LONG).show()
                                } catch (_: SecurityException) {
                                    Toast.makeText(ctx, R.string.poh_lost, Toast.LENGTH_LONG).show()
                                }
                            }) {
                                Icon(Icons.Filled.PictureAsPdf, null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.open_poh))
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                            Icon(Icons.Filled.AttachFile, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (aircraft.pohUri == null) R.string.attach_poh else R.string.replace_poh))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionHeader(stringResource(R.string.speeds), "speeds" in expanded) { toggle("speeds") }
        }
        if ("speeds" in expanded) {
            items(type.speeds, key = { "s_" + it.key }) { s ->
                SpeedRow(s, aircraft, kmh, lg) { editSpeed = s }
                HorizontalDivider()
            }
        }
        type.manual.forEach { sec ->
            item(key = "h_" + sec.id) {
                SectionHeader(sec.title.get(lg), sec.id in expanded) { toggle(sec.id) }
            }
            if (sec.id in expanded) {
                items(sec.items.size, key = { "i_${sec.id}_$it" }) { idx ->
                    val mi = sec.items[idx]
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row {
                            Text(mi.label.get(lg), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(mi.value.get(lg), Modifier.weight(1.3f), fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            stringResource(R.string.source_label, mi.source),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
        item {
            SectionHeader(stringResource(R.string.my_notes), "notes" in expanded) { toggle("notes") }
        }
        if ("notes" in expanded) {
            item {
                OutlinedTextField(
                    notes, { notes = it }, Modifier.fillMaxWidth().height(180.dp),
                    placeholder = { Text(stringResource(R.string.notes_hint)) },
                )
                TextButton(enabled = notes != aircraft.notes, onClick = {
                    Repo.upsertAircraft(aircraft.copy(notes = notes))
                }) { Text(stringResource(R.string.save)) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    editSpeed?.let { s -> SpeedEditDialog(s, aircraft, lg) { editSpeed = null } }
}

@Composable
private fun SectionHeader(title: String, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
    }
}

@Composable
private fun SpeedRow(s: SpeedDef, a: Aircraft, kmh: Boolean, lg: String, onEdit: () -> Unit) {
    val override = a.speedOverrides[s.key]
    val v = override ?: s.value
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.label.get(lg), style = MaterialTheme.typography.bodyMedium)
            if (override != null) {
                Text(stringResource(R.string.own_value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else {
                SourceLine(s.source, s.verified)
            }
        }
        Text(
            if (v == null) "—" else "${toDisplay(v, s.unit, kmh).roundToInt()} ${if (kmh) "km/h" else "kt"}",
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
    }
}

@Composable
private fun SpeedEditDialog(s: SpeedDef, a: Aircraft, lg: String, onDismiss: () -> Unit) {
    // Edited in the unit the definition uses, so the stored value never drifts through rounding.
    val unitLabel = if (s.unit == "kt") "kt" else "km/h"
    var text by remember { mutableStateOf(a.speedOverrides[s.key]?.let { fmtH(it) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.label.get(lg)) },
        text = {
            Column {
                Text(stringResource(R.string.speed_edit_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                s.value?.let { Text(stringResource(R.string.default_value, "${fmtH(it)} $unitLabel")) }
                NumberField(text, { text = it }, "$unitLabel (IAS)", Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = parseNum(text) != null, onClick = {
                Repo.upsertAircraft(a.copy(speedOverrides = a.speedOverrides + (s.key to parseNum(text)!!)))
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (a.speedOverrides.containsKey(s.key)) {
                    TextButton(onClick = {
                        Repo.upsertAircraft(a.copy(speedOverrides = a.speedOverrides - s.key))
                        onDismiss()
                    }) { Text(stringResource(R.string.reset)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
