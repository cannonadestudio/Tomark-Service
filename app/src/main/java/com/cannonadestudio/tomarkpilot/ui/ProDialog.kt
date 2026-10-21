package com.cannonadestudio.tomarkpilot.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.Billing
import kotlinx.coroutines.launch

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Shown when a free user wants a second aircraft. */
@Composable
fun ProDialog(onDismiss: () -> Unit) {
    val pro by Billing.pro.collectAsState()
    val st by com.cannonadestudio.tomarkpilot.data.Repo.state.collectAsState()
    if (pro.owned || st.proGranted) { onDismiss(); return }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pro_title)) },
        text = { ProBody() },
        confirmButton = { BuyButton(onDone = onDismiss) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ProBody() {
    val pro by Billing.pro.collectAsState()
    Column {
        Text(stringResource(R.string.pro_free, Billing.FREE_AIRCRAFT), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.pro_paid), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text(stringResource(R.string.pro_once), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        if (pro.pending) Text(stringResource(R.string.pro_pending), color = HbgPink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        if (pro.checked && !pro.available) Text(stringResource(R.string.pro_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun BuyButton(onDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    val pro by Billing.pro.collectAsState()
    Button(enabled = pro.available && !pro.pending, onClick = {
        val a = ctx.activity()
        if (a == null || !Billing.buy(a)) Toast.makeText(ctx, ctx.getString(R.string.pro_unavailable), Toast.LENGTH_LONG).show()
        onDone()
    }) { Text(pro.price?.let { stringResource(R.string.pro_buy_price, it) } ?: stringResource(R.string.pro_buy)) }
}

/** Plan card in "About": status, buy, restore. */
@Composable
fun ProCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pro by Billing.pro.collectAsState()
    var busy by remember { mutableStateOf(false) }
    val st by com.cannonadestudio.tomarkpilot.data.Repo.state.collectAsState()
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.pro_title), style = MaterialTheme.typography.titleMedium)
            if (pro.owned || st.proGranted) {
                Text(stringResource(R.string.pro_active), color = HbgPink, fontWeight = FontWeight.SemiBold)
                Text(stringResource(if (pro.owned) R.string.pro_thanks else R.string.pro_granted), style = MaterialTheme.typography.bodySmall)
            } else {
                ProBody()
                Row(Modifier.padding(top = 8.dp)) {
                    BuyButton()
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            Billing.refresh()
                            com.cannonadestudio.tomarkpilot.data.Repo.checkProGrant()
                            busy = false
                            Toast.makeText(ctx, ctx.getString(if (Billing.isPro()) R.string.pro_restored else R.string.pro_not_found), Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.pro_restore)) }
                }
            }
            // Install code – the owner finds this phone by it in the panel (e.g. to grant Pro by hand).
            val code = remember { com.cannonadestudio.tomarkpilot.data.Repo.installCode() }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.install_code, code), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    clip.setText(androidx.compose.ui.text.AnnotatedString(code))
                    Toast.makeText(ctx, code, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.copy)) }
            }
        }
    }
}
