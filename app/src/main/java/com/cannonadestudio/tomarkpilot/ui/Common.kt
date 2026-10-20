package com.cannonadestudio.tomarkpilot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.DueStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------- theme

private val Navy = Color(0xFF0B2545)
private val Sky = Color(0xFF3E7CB1)
private val Amber = Color(0xFFF2A541)

private val LightColors = lightColorScheme(
    primary = Navy, onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4F5), onPrimaryContainer = Navy,
    secondary = Sky, tertiary = Amber,
    background = Color(0xFFF6F8FB), surface = Color.White,
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC3EC), onPrimary = Navy,
    primaryContainer = Color(0xFF1B3A60), onPrimaryContainer = Color(0xFFD6E4F5),
    secondary = Color(0xFF8DB7DB), tertiary = Amber,
    background = Color(0xFF0E141B), surface = Color(0xFF151D27),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

// ---------------------------------------------------------------- helpers

@Composable
fun lang(): String = LocalConfiguration.current.locales[0].language

const val KT_TO_KMH = 1.852

/** Converts a speed given in [unit] into the display unit. */
fun toDisplay(value: Double, unit: String, kmh: Boolean): Double = when {
    unit == "kt" && kmh -> value * KT_TO_KMH
    unit == "kmh" && !kmh -> value / KT_TO_KMH
    else -> value
}

fun fmtH(h: Double): String = if (h == h.roundToInt().toDouble()) h.roundToInt().toString()
else String.format(Locale.US, "%.1f", h)

private val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
fun fmtDate(d: LocalDate): String = d.format(DATE)
fun fmtEpochDay(day: Long): String = fmtDate(LocalDate.ofEpochDay(day))
fun parseDate(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim(), DATE) }.getOrNull()
fun parseNum(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

@Composable
fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = value.isNotBlank() && parseNum(value) == null, modifier = modifier,
    )
}

@Composable
fun DateField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        placeholder = { Text("2026-05-31") },
        isError = value.isNotBlank() && parseDate(value) == null, modifier = modifier,
    )
}

data class StatusLook(val bg: Color, val fg: Color, val label: Int)

fun statusLook(s: DueStatus): StatusLook = when (s) {
    DueStatus.OVERDUE -> StatusLook(Color(0xFFC62828), Color.White, R.string.status_overdue)
    DueStatus.DUE -> StatusLook(Color(0xFFEF6C00), Color.White, R.string.status_due)
    DueStatus.SOON -> StatusLook(Color(0xFFF9A825), Color.Black, R.string.status_soon)
    DueStatus.OK -> StatusLook(Color(0xFF2E7D32), Color.White, R.string.status_ok)
    DueStatus.DONE -> StatusLook(Color(0xFF607D8B), Color.White, R.string.status_done)
    DueStatus.NO_INTERVAL -> StatusLook(Color(0xFF9E9E9E), Color.White, R.string.status_noint)
}

@Composable
fun Chip(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun StatusChip(s: DueStatus) {
    val l = statusLook(s)
    Chip(stringResource(l.label), l.bg, l.fg)
}

@Composable
fun SourceLine(source: String, verified: Boolean) {
    Row {
        Text(
            stringResource(R.string.source_label, source),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (!verified) {
        Text(
            stringResource(R.string.unverified_long),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFEF6C00),
        )
    }
}

@Composable
fun categoryName(c: String): String = stringResource(
    when (c) {
        "airframe" -> R.string.cat_airframe
        "engine" -> R.string.cat_engine
        "propeller" -> R.string.cat_propeller
        "rescue" -> R.string.cat_rescue
        "regulatory" -> R.string.cat_regulatory
        "avionics" -> R.string.cat_avionics
        else -> R.string.cat_other
    }
)
