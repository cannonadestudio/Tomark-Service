package com.cannonadestudio.tomarkpilot.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.Aircraft
import com.cannonadestudio.tomarkpilot.data.Contact
import com.cannonadestudio.tomarkpilot.data.Repo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Kinds that can do an inspection. */
val SERVICE_KINDS = setOf("rotax_service", "maintenance", "rotax_distributor", "dealer", "mechanic")

/**
 * "Book an inspection": partner services that take requests get it through the owner's server
 * (e-mail to the service + counted for the partner report); any other service with an e-mail
 * gets a ready e-mail from the phone's mail app.
 */
@Composable
fun ServiceRequestDialog(aircraft: Aircraft, work: String, preselected: Contact? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Repo.state.collectAsState()
    val feed by Repo.contactFeed.collectAsState()
    val loc = remember { lastLocationOrNull(ctx) }
    val services = remember(state, feed) {
        Repo.contacts(state, feed).filter { it.kind in SERVICE_KINDS && it.email.isNotBlank() }
            .sortedWith(compareBy({ if (Repo.takesRequests(it)) 0 else 1 }, { distanceKmOrNull(loc, it) ?: Double.MAX_VALUE }, { it.name }))
    }
    var chosen by remember { mutableStateOf(preselected ?: services.firstOrNull()) }
    var showAll by remember { mutableStateOf(false) }
    var workText by remember { mutableStateOf(work) }
    var date by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(state.reqName) }
    var phone by remember { mutableStateOf(state.reqPhone) }
    var email by remember { mutableStateOf(state.reqEmail) }
    var message by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val typeName = Repo.type(aircraft.typeId)?.name ?: aircraft.typeId

    fun mailBody() = buildString {
        appendLine("${aircraft.registration}  $typeName")
        appendLine("${ctx.getString(R.string.total_hours)}: ${fmtH(aircraft.hours)}")
        if (aircraft.engineSerial.isNotBlank()) appendLine("${ctx.getString(R.string.engine_serial)}: ${aircraft.engineSerial}")
        aircraft.yearBuilt?.let { appendLine("${ctx.getString(R.string.year_built)}: $it") }
        appendLine("${ctx.getString(R.string.req_work)}: $workText")
        if (date.isNotBlank()) appendLine("${ctx.getString(R.string.req_date)}: $date")
        appendLine()
        if (message.isNotBlank()) { appendLine(message); appendLine() }
        appendLine(name)
        if (phone.isNotBlank()) appendLine(phone)
        if (email.isNotBlank()) appendLine(email)
    }

    fun openMail(c: Contact) {
        val subject = ctx.getString(R.string.req_mail_subject, aircraft.registration)
        val uri = Uri.parse("mailto:" + c.email + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(mailBody()))
        try { ctx.startActivity(Intent(Intent.ACTION_SENDTO, uri)) } catch (_: Exception) {
            Toast.makeText(ctx, c.email, Toast.LENGTH_LONG).show()
        }
    }

    val contactOk = name.isNotBlank() && (phone.isNotBlank() || email.contains('@'))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.req_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${aircraft.registration} · $typeName · ${fmtH(aircraft.hours)} h", fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.req_service), style = MaterialTheme.typography.labelLarge, color = HbgPink, modifier = Modifier.padding(top = 8.dp))
                if (services.isEmpty()) Text(stringResource(R.string.req_no_services), style = MaterialTheme.typography.bodySmall)
                val shown = if (showAll) services else services.take(5).let { l -> if (chosen != null && chosen !in l) l + chosen!! else l }
                shown.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { chosen = c }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(chosen?.id == c.id, { chosen = c })
                        Column(Modifier.weight(1f)) {
                            Text((if (c.isPartner()) "★ " else "") + c.name, fontWeight = if (c.isPartner()) FontWeight.Bold else FontWeight.Normal)
                            val km = distanceKmOrNull(loc, c)?.roundToInt()
                            Text(
                                listOfNotNull(c.city.takeIf { it.isNotBlank() }, km?.let { "$it km" },
                                    stringResource(if (Repo.takesRequests(c)) R.string.req_via_app else R.string.req_via_mail)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (!showAll && services.size > 5) TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.req_more) + " (${services.size})") }
                OutlinedTextField(workText, { workText = it }, label = { Text(stringResource(R.string.req_work)) }, modifier = Modifier.fillMaxWidth())
                DateField(date, { date = it }, stringResource(R.string.req_date), Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.req_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(R.string.req_phone)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.req_message)) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.clickable { consent = !consent }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(consent, { consent = it })
                    Text(stringResource(R.string.req_consent), style = MaterialTheme.typography.bodySmall)
                }
                if (!contactOk && (name.isNotBlank() || phone.isNotBlank() || email.isNotBlank())) {
                    Text(stringResource(R.string.req_need_contact), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (sending) CircularProgressIndicator(Modifier.padding(8.dp)) else
            TextButton(enabled = chosen != null && consent && contactOk && workText.isNotBlank(), onClick = {
                val c = chosen ?: return@TextButton
                Repo.update { it.copy(reqName = name.trim(), reqPhone = phone.trim(), reqEmail = email.trim()) }
                if (!Repo.takesRequests(c)) { openMail(c); onDismiss(); return@TextButton }
                sending = true
                scope.launch {
                    val r = Repo.sendServiceRequest(c, aircraft, workText.trim(), date, name.trim(), phone.trim(), email.trim(), message.trim())
                    sending = false
                    r.onSuccess { mailed ->
                        Toast.makeText(ctx, ctx.getString(if (mailed) R.string.req_sent else R.string.req_sent_nomail), Toast.LENGTH_LONG).show()
                    }.onFailure { e ->
                        Toast.makeText(ctx, ctx.getString(R.string.req_fail, e.message ?: ""), Toast.LENGTH_LONG).show()
                        openMail(c)
                    }
                    onDismiss()
                }
            }) { Text(stringResource(R.string.req_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
