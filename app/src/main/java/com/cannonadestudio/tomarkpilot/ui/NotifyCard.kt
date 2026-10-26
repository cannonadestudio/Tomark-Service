package com.cannonadestudio.tomarkpilot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.Repo

/** Push reminders: how early to announce maintenance, and whether to announce bulletins / announcements. */
@Composable
fun NotifyCard() {
    val st by Repo.state.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.notif_settings), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.notif_settings_hint), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    st.remindHours.toString(), { v -> v.filter { it.isDigit() }.take(3).toIntOrNull()?.let { h -> Repo.update { it.copy(remindHours = h.coerceIn(1, 200)) } } },
                    label = { Text(stringResource(R.string.notif_hours)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    st.remindDays.toString(), { v -> v.filter { it.isDigit() }.take(3).toIntOrNull()?.let { d -> Repo.update { it.copy(remindDays = d.coerceIn(1, 120)) } } },
                    label = { Text(stringResource(R.string.notif_days)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.notif_bulletins_switch), Modifier.weight(1f))
                Switch(st.notifyBulletins, { on -> Repo.update { it.copy(notifyBulletins = on) } })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.notif_news_switch), Modifier.weight(1f))
                Switch(st.notifyNews, { on -> Repo.update { it.copy(notifyNews = on) } })
            }
        }
    }
}
