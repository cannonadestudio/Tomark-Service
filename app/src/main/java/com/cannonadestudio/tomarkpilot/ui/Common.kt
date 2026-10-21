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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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

// Colours taken from horyzontbezgranic.pl: violet → teal gradient, pink labels, white cards.
val HbgViolet = Color(0xFF7A3E9D)
val HbgBlue = Color(0xFF2E8FC4)
val HbgTeal = Color(0xFF0FB3A6)
val HbgPink = Color(0xFFE84F8C)
val HbgInk = Color(0xFF2B2A33)

private val LightColors = lightColorScheme(
    primary = HbgViolet, onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE3F5), onPrimaryContainer = Color(0xFF3B1652),
    secondary = HbgTeal, onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F3F0), onSecondaryContainer = Color(0xFF00413B),
    tertiary = HbgPink, onTertiary = Color.White,
    background = Color(0xFFF4F1F8), surface = Color.White, onSurface = HbgInk,
    surfaceVariant = Color(0xFFF0EDF4), onSurfaceVariant = Color(0xFF5E5A66),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0A8EA), onPrimary = Color(0xFF3B1652),
    primaryContainer = Color(0xFF4E2768), onPrimaryContainer = Color(0xFFEDE3F5),
    secondary = Color(0xFF6FDCD1), onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF005048), onSecondaryContainer = Color(0xFFD5F3F0),
    tertiary = Color(0xFFFF8DB6),
    // Black / near-black cards (OLED, battery saving) instead of the tinted grey-pink Material defaults.
    background = Color(0xFF000000), surface = Color(0xFF0B0B0D), onSurface = Color(0xFFECEAF0),
    surfaceVariant = Color(0xFF17171A), onSurfaceVariant = Color(0xFFC9C6CF),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF060607),
    surfaceContainer = Color(0xFF0B0B0D), surfaceContainerHigh = Color(0xFF111114),
    surfaceContainerHighest = Color(0xFF141417), surfaceBright = Color(0xFF1C1C20), surfaceDim = Color(0xFF000000),
    surfaceTint = Color(0xFF000000),
)

/** The site's diagonal violet → blue → teal gradient. */
fun hbgGradient(dark: Boolean): Brush = Brush.linearGradient(
    if (dark) listOf(Color(0xFF3A1D4C), Color(0xFF15405A), Color(0xFF07544E))
    else listOf(HbgViolet, HbgBlue, HbgTeal)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

// ---------------------------------------------------------------- helpers

@Composable
fun lang(): String = androidx.core.os.ConfigurationCompat.getLocales(LocalConfiguration.current)[0]?.language ?: "en"

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

/**
 * Date field with a calendar picker. The value stays ISO (yyyy-MM-dd); tapping the field or the
 * calendar icon opens the Material date picker, the cross clears an optional date.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DateField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var show by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    androidx.compose.runtime.LaunchedEffect(pressed) { if (pressed) show = true }
    val d = parseDate(value)
    OutlinedTextField(
        value = d?.format(DISPLAY) ?: value, onValueChange = {}, readOnly = true, singleLine = true,
        label = { Text(label) }, interactionSource = src, modifier = modifier,
        trailingIcon = {
            androidx.compose.foundation.layout.Row {
                if (value.isNotBlank()) androidx.compose.material3.IconButton(onClick = { onChange("") }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Clear, stringResource(R.string.date_clear))
                }
                androidx.compose.material3.IconButton(onClick = { show = true }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.CalendarMonth, stringResource(R.string.date_pick))
                }
            }
        },
    )
    if (show) {
        val st = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = (d ?: LocalDate.now()).toEpochDay() * 86_400_000L,
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    st.selectedDateMillis?.let { onChange(fmtDate(LocalDate.ofEpochDay(Math.floorDiv(it, 86_400_000L)))) }
                    show = false
                }) { Text("OK") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { show = false }) { Text(stringResource(R.string.cancel)) } },
        ) { androidx.compose.material3.DatePicker(st) }
    }
}

private val DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Logbook error code (see Logbook.ERR_*) or raw message → text for the user. */
fun logbookErrorMsg(ctx: android.content.Context, err: String): String = when (err) {
    com.cannonadestudio.tomarkpilot.data.Logbook.ERR_BACKUP -> ctx.getString(R.string.logbook_err_backup)
    com.cannonadestudio.tomarkpilot.data.Logbook.ERR_FIELDS -> ctx.getString(R.string.logbook_err_fields)
    com.cannonadestudio.tomarkpilot.data.Logbook.ERR_EMPTY -> ctx.getString(R.string.logbook_err_empty)
    else -> ctx.getString(R.string.logbook_error, err)
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


/** Opens a bulletin / document link in the browser (or shows the address if no browser is installed). */
fun openUrl(ctx: android.content.Context, url: String) {
    try {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(ctx, url, android.widget.Toast.LENGTH_LONG).show()
    }
}


/**
 * Turns share links into direct image addresses: Google Drive ("…/file/d/ID/view", "open?id=ID", "uc?id=ID")
 * and Dropbox ("?dl=0"). The Drive file must be shared as "Anyone with the link".
 */
fun directImageUrl(u: String): String {
    val s = u.trim()
    if (s.contains("drive.google.com") || s.contains("docs.google.com")) {
        val id = Regex("""/d/([A-Za-z0-9_-]{10,})""").find(s)?.groupValues?.get(1)
            ?: Regex("""[?&]id=([A-Za-z0-9_-]{10,})""").find(s)?.groupValues?.get(1)
        if (id != null) return "https://lh3.googleusercontent.com/d/$id"
    }
    if (s.contains("dropbox.com")) return s.replace("dl=0", "raw=1").let { if ("raw=1" in it) it else it + (if ('?' in it) "&" else "?") + "raw=1" }
    return s
}
