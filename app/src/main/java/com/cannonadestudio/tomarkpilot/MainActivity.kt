@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cannonadestudio.tomarkpilot

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cannonadestudio.tomarkpilot.data.Aircraft
import com.cannonadestudio.tomarkpilot.data.Maintenance
import com.cannonadestudio.tomarkpilot.data.Repo
import com.cannonadestudio.tomarkpilot.ui.AircraftEditScreen
import com.cannonadestudio.tomarkpilot.ui.AircraftListScreen
import com.cannonadestudio.tomarkpilot.ui.AppTheme
import com.cannonadestudio.tomarkpilot.ui.BulletinsScreen
import com.cannonadestudio.tomarkpilot.ui.HomeScreen
import com.cannonadestudio.tomarkpilot.ui.ManualScreen
import com.cannonadestudio.tomarkpilot.ui.NoAircraft
import com.cannonadestudio.tomarkpilot.ui.ServiceScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.cannonadestudio.tomarkpilot.ui.ContactsScreen
import com.cannonadestudio.tomarkpilot.ui.hbgGradient
import com.cannonadestudio.tomarkpilot.ui.logbookErrorMsg
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var startTab = mutableIntStateOf(TAB_HOME)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        com.cannonadestudio.tomarkpilot.data.Billing.init(this)
        // white status-bar icons on the navy top bar, dark icons on the white bottom bar
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.BLACK),
        )
        startTab.intValue = intent.getIntExtra(EXTRA_TAB, TAB_HOME)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { Root(startTab.intValue) { startTab.intValue = it } } }
    }

    override fun onResume() {
        super.onResume()
        // Re-read the logbook export every time the app comes to the front.
        lifecycleScope.launch { Repo.syncLogbook() }
        // Fresh bulletins, contacts and types whenever the app is opened (the daily job covers the rest).
        lifecycleScope.launch { Repo.refreshAll() }
        lifecycleScope.launch { com.cannonadestudio.tomarkpilot.data.Billing.refresh() }
        lifecycleScope.launch { Repo.checkProGrantIfStale() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startTab.intValue = intent.getIntExtra(EXTRA_TAB, startTab.intValue)
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = 0
        const val TAB_MANUAL = 1
        const val TAB_SERVICE = 2
        const val TAB_BULLETINS = 3
        const val TAB_CONTACTS = 4
    }
}

private sealed interface Overlay {
    data object List : Overlay
    data class Edit(val aircraft: Aircraft?) : Overlay
    data object About : Overlay
}

@Composable
private fun Root(tab: Int, setTab: (Int) -> Unit) {
    val state by Repo.state.collectAsState()
    val feed by Repo.feed.collectAsState()
    val contactFeed by Repo.contactFeed.collectAsState()
    Repo.typesVersion.collectAsState().value  // read so the UI recomposes when types arrive from the panel
    val dark = isSystemInDarkTheme()
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val aircraft = Repo.selected(state)
    val type = aircraft?.let { Repo.type(it.typeId) }

    BackHandler(enabled = overlay != null || tab != MainActivity.TAB_HOME) {
        if (overlay != null) overlay = null else setTab(MainActivity.TAB_HOME)
    }

    Box(Modifier.fillMaxSize().background(hbgGradient(dark))) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                expandedHeight = 52.dp,
                title = {
                    Text(
                        when (val o = overlay) {
                            Overlay.List -> stringResource(R.string.aircraft)
                            is Overlay.Edit -> stringResource(if (o.aircraft == null) R.string.add_aircraft else R.string.edit_aircraft)
                            Overlay.About -> stringResource(R.string.about)
                            null -> stringResource(R.string.app_name)
                        }
                    )
                },
                navigationIcon = {
                    if (overlay != null) IconButton(onClick = { overlay = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (overlay == null) {
                        IconButton(onClick = { overlay = Overlay.List }) { Icon(Icons.Filled.Flight, stringResource(R.string.aircraft)) }
                        // Pilot Logbook: open the app, or its Google Play page when it is not installed
                        val c = androidx.compose.ui.platform.LocalContext.current
                        IconButton(onClick = { openPilotLogbook(c) }) { Icon(Icons.Filled.EditCalendar, stringResource(R.string.open_logbook)) }
                        IconButton(onClick = { overlay = Overlay.About }) { Icon(Icons.Filled.AccountCircle, stringResource(R.string.account_title)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = com.cannonadestudio.tomarkpilot.ui.LotNavy,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            if (overlay == null && aircraft != null) {
                val serviceBadge = Maintenance.compute(aircraft, state.completions).count { Maintenance.needsAttention(it) }
                val bulletinBadge = Repo.openBulletins(aircraft, state, feed).count { it.mandatory == true }
                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    NavItem(tab == MainActivity.TAB_HOME, { setTab(MainActivity.TAB_HOME) }, R.string.tab_home, 0) { Icon(Icons.Filled.Dashboard, null) }
                    NavItem(tab == MainActivity.TAB_MANUAL, { setTab(MainActivity.TAB_MANUAL) }, R.string.tab_manual, 0) { Icon(Icons.AutoMirrored.Filled.MenuBook, null) }
                    NavItem(tab == MainActivity.TAB_SERVICE, { setTab(MainActivity.TAB_SERVICE) }, R.string.tab_service, serviceBadge) { Icon(Icons.Filled.Build, null) }
                    NavItem(tab == MainActivity.TAB_BULLETINS, { setTab(MainActivity.TAB_BULLETINS) }, R.string.tab_bulletins, bulletinBadge) { Icon(Icons.Filled.Notifications, null) }
                    NavItem(tab == MainActivity.TAB_CONTACTS, { setTab(MainActivity.TAB_CONTACTS) }, R.string.tab_contacts, 0) { Icon(Icons.Filled.Contacts, null) }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            // Home and Contacts are cards on the gradient (like the website); the rest sits on a light sheet.
            val onGradient = overlay == null && aircraft != null && type != null && (tab == MainActivity.TAB_HOME || tab == MainActivity.TAB_CONTACTS)
            if (onGradient) {
                if (tab == MainActivity.TAB_CONTACTS) ContactsScreen(state, contactFeed, type)
                else HomeScreen(state, feed, aircraft!!, type!!, setTab)
            } else {
                Surface(
                    Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (val o = overlay) {
                        Overlay.List -> AircraftListScreen(state, onEdit = { overlay = Overlay.Edit(it) }, onSelected = { overlay = null })
                        is Overlay.Edit -> AircraftEditScreen(o.aircraft) { overlay = null }
                        Overlay.About -> AboutScreen(state)
                        null -> if (aircraft == null || type == null) {
                            NoAircraft { overlay = Overlay.Edit(null) }
                        } else when (tab) {
                            MainActivity.TAB_MANUAL -> ManualScreen(aircraft, type, state.useKmh)
                            MainActivity.TAB_SERVICE -> ServiceScreen(state, aircraft)
                            MainActivity.TAB_BULLETINS -> BulletinsScreen(state, feed, aircraft)
                            else -> HomeScreen(state, feed, aircraft, type, setTab)
                        }
                    }
                }
            }
        }
    }
    }

    if (!state.disclaimerAccepted) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.disclaimer_title)) },
            text = { Text(stringResource(R.string.disclaimer_text)) },
            confirmButton = {
                TextButton(onClick = { Repo.update { it.copy(disclaimerAccepted = true) } }) { Text(stringResource(R.string.accept)) }
            },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    selected: Boolean, onClick: () -> Unit, label: Int, badge: Int, icon: @Composable () -> Unit,
) {
    NavigationBarItem(
        selected = selected, onClick = onClick,
        icon = {
            if (badge > 0) BadgedBox(badge = { Badge { Text(badge.toString()) } }) { icon() } else icon()
        },
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun AboutScreen(state: com.cannonadestudio.tomarkpilot.data.AppState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: android.net.Uri? ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Repo.update { it.copy(logbookUri = uri.toString(), logbookError = null) }
            scope.launch { Repo.syncLogbook() }
        }
    }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val backupCreator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: android.net.Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            Repo.update { it.copy(backupUri = uri.toString(), backupError = null) }
            scope.launch {
                val r = Repo.backupNow()
                android.widget.Toast.makeText(ctx, if (r.isSuccess) ctx.getString(R.string.backup_done) else ctx.getString(R.string.backup_error, r.exceptionOrNull()?.message ?: ""), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: android.net.Uri? ->
        if (uri != null) confirmRestore = uri
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text("v" + BuildConfigCompat.versionName(), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        // Account key first – that is where a pilot links the app with the website.
        com.cannonadestudio.tomarkpilot.ui.AccountCard()
        Spacer(Modifier.height(12.dp))
        com.cannonadestudio.tomarkpilot.ui.ProCard()
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.backup_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    state.backupUri?.let { displayName(ctx, it) } ?: stringResource(R.string.backup_off),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                state.lastBackupMillis?.let {
                    Text(
                        stringResource(R.string.backup_last, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it))),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.backupError?.let { Text(stringResource(R.string.backup_error, it), color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { backupCreator.launch("AeroHangar-kopia.json") }) {
                        Text(stringResource(if (state.backupUri == null) R.string.backup_choose else R.string.backup_change))
                    }
                    if (state.backupUri != null) {
                        TextButton(onClick = {
                            scope.launch {
                                val r = Repo.backupNow()
                                android.widget.Toast.makeText(ctx, if (r.isSuccess) ctx.getString(R.string.backup_done) else ctx.getString(R.string.backup_error, r.exceptionOrNull()?.message ?: ""), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) { Text(stringResource(R.string.backup_now)) }
                    }
                }
                Row {
                    TextButton(onClick = { backupPicker.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Text(stringResource(R.string.backup_restore))
                    }
                    if (state.backupUri != null) {
                        TextButton(onClick = { Repo.update { it.copy(backupUri = null, backupError = null) } }) {
                            Text(stringResource(R.string.backup_disable))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.logbook_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.logbook_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    state.logbookUri?.let { displayName(ctx, it) } ?: stringResource(R.string.logbook_none),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                state.logbookSyncedEpochDay?.let {
                    Text(stringResource(R.string.logbook_synced, com.cannonadestudio.tomarkpilot.ui.fmtEpochDay(it)), style = MaterialTheme.typography.bodySmall)
                }
                state.logbookError?.let { Text(logbookErrorMsg(ctx, it), color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall) }
                Row {
                    TextButton(onClick = { picker.launch(arrayOf("text/*", "application/json", "text/csv", "*/*")) }) {
                        Text(stringResource(R.string.logbook_choose))
                    }
                    if (state.logbookUri != null) {
                        TextButton(onClick = {
                            scope.launch {
                                val r = Repo.syncLogbook()
                                android.widget.Toast.makeText(
                                    ctx,
                                    r.fold({ ctx.getString(R.string.logbook_sync_ok, it) }, { logbookErrorMsg(ctx, (it as? com.cannonadestudio.tomarkpilot.data.Logbook.LogbookException)?.code ?: it.message ?: "") }),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }) { Text(stringResource(R.string.logbook_sync_now)) }
                        TextButton(onClick = { Repo.update { it.copy(logbookUri = null, logbookError = null) } }) {
                            Text(stringResource(R.string.logbook_unlink))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.disclaimer_text))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.about_sources), style = MaterialTheme.typography.bodySmall)
    }
    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text(stringResource(R.string.backup_restore)) },
            text = { Text(stringResource(R.string.backup_restore_warn)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    scope.launch {
                        val r = Repo.restoreBackup(uri)
                        android.widget.Toast.makeText(
                            ctx,
                            r.fold({ ctx.getString(R.string.backup_restored, it) }, { ctx.getString(R.string.backup_error, it.message ?: "") }),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }) { Text(stringResource(R.string.backup_restore_ok)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Human file name of a content:// link (Drive shows document ids otherwise). */
private fun displayName(ctx: android.content.Context, uri: String): String = runCatching {
    ctx.contentResolver.query(android.net.Uri.parse(uri), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: "…"

private object BuildConfigCompat {
    @Composable
    fun versionName(): String {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        return runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }
}


/**
 * Opens Pilot Logbook installed on this phone: first by the id set in the server panel, then by the app
 * name among installed apps (e.g. a copy installed from GitHub with another id). Only when nothing is
 * found does it open the app's page in Google Play.
 */
fun openPilotLogbook(ctx: android.content.Context) {
    val pm = ctx.packageManager
    val pkg = Repo.logbookPackage
    fun start(i: Intent) = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: android.content.ActivityNotFoundException) { false }

    // 1. the configured id
    pm.getLaunchIntentForPackage(pkg)?.let { if (start(it)) return }
    // 2. any installed app called "Pilot Logbook" / "Logbook pilota" (own apps first)
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = runCatching { pm.queryIntentActivities(launcher, 0) }.getOrDefault(emptyList())
        .filter { it.activityInfo.packageName != ctx.packageName }
    val names = listOf("pilot logbook", "logbook pilota", "pilotlogbook")
    val found = apps.filter { r ->
        val label = r.loadLabel(pm).toString().lowercase()
        names.any { it in label } || r.activityInfo.packageName.lowercase().contains("logbook")
    }.sortedBy { if (it.activityInfo.packageName.startsWith("com.cannonadestudio")) 0 else 1 }.firstOrNull()
    if (found != null) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(found.activityInfo.packageName, found.activityInfo.name)
        if (start(i)) return
    }
    // 3. not installed: Google Play app, or the web store
    if (start(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg")))) return
    start(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
}
