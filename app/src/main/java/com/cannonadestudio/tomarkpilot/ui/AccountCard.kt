package com.cannonadestudio.tomarkpilot.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.Repo
import kotlinx.coroutines.launch

/**
 * Cannonade account (AeroHangar WordPress plugin on the owner's site): one key for AeroHangar and
 * Pilot Logbook. Keeps a copy of this phone's data on the account and brings Pro granted to the account.
 */
@Composable
fun AccountCard() {
    val st by Repo.state.collectAsState()
    if (!Repo.accountAvailable && st.accountKey == null) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmPull by remember { mutableStateOf(false) }
    fun err(e: Throwable) = when (e.message) {
        "401" -> ctx.getString(R.string.account_bad_key)
        "404" -> ctx.getString(R.string.account_no_backup)
        else -> ctx.getString(R.string.refresh_fail) + " (" + (e.message ?: "") + ")"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.account_title), style = MaterialTheme.typography.titleMedium)
            if (st.accountKey == null) {
                Text(stringResource(R.string.account_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.account_key)) }, singleLine = true,
                    placeholder = { Text("CNA-…") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(Modifier.padding(top = 6.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.padding(8.dp)) else Button(enabled = key.trim().length >= 10, onClick = {
                        busy = true
                        scope.launch {
                            val r = Repo.linkAccount(key)
                            busy = false
                            Toast.makeText(ctx, r.fold({ ctx.getString(R.string.account_linked, it) }, { err(it) }), Toast.LENGTH_LONG).show()
                        }
                    }) { Text(stringResource(R.string.account_link)) }
                }
            } else {
                Text(st.accountName ?: "", fontWeight = FontWeight.SemiBold, color = HbgPink)
                Text(
                    stringResource(R.string.account_synced, st.accountSyncMillis?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) } ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (st.accountPro) Text(stringResource(R.string.account_pro), style = MaterialTheme.typography.bodySmall, color = HbgPink)
                st.accountError?.let { Text(if (it == "401") stringResource(R.string.account_bad_key) else stringResource(R.string.backup_error, it), color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.account_auto), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 4.dp)) {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val r = Repo.pushAccount()
                            busy = false
                            Toast.makeText(ctx, r.fold({ ctx.getString(R.string.backup_done) }, { err(it) }), Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.backup_now)) }
                    TextButton(enabled = !busy, onClick = { confirmPull = true }) { Text(stringResource(R.string.account_restore)) }
                    TextButton(onClick = { Repo.unlinkAccount() }) { Text(stringResource(R.string.logbook_unlink)) }
                }
            }
        }
    }
    if (confirmPull) AlertDialog(
        onDismissRequest = { confirmPull = false },
        title = { Text(stringResource(R.string.account_restore)) },
        text = { Text(stringResource(R.string.account_restore_q)) },
        confirmButton = {
            TextButton(onClick = {
                confirmPull = false
                busy = true
                scope.launch {
                    val r = Repo.pullAccount()
                    busy = false
                    Toast.makeText(ctx, r.fold({ ctx.getString(R.string.account_restored, it) }, { err(it) }), Toast.LENGTH_LONG).show()
                }
            }) { Text(stringResource(R.string.account_restore)) }
        },
        dismissButton = { TextButton(onClick = { confirmPull = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
