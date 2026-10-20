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
import androidx.compose.material.icons.filled.Info
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

class MainActivity : ComponentActivity() {

    private var startTab = mutableIntStateOf(TAB_HOME)

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        enableEdgeToEdge()
        startTab.intValue = intent.getIntExtra(EXTRA_TAB, TAB_HOME)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { Root(startTab.intValue) { startTab.intValue = it } } }
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
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val aircraft = Repo.selected(state)
    val type = aircraft?.let { Repo.type(it.typeId) }

    BackHandler(enabled = overlay != null || tab != MainActivity.TAB_HOME) {
        if (overlay != null) overlay = null else setTab(MainActivity.TAB_HOME)
    }

    Scaffold(
        topBar = {
            TopAppBar(
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
                        IconButton(onClick = { overlay = Overlay.About }) { Icon(Icons.Filled.Info, stringResource(R.string.about)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        bottomBar = {
            if (overlay == null && aircraft != null) {
                val serviceBadge = Maintenance.compute(aircraft, state.completions).count { Maintenance.needsAttention(it) }
                val bulletinBadge = Repo.openBulletins(aircraft, state, feed).size
                NavigationBar {
                    NavItem(tab == MainActivity.TAB_HOME, { setTab(MainActivity.TAB_HOME) }, R.string.tab_home, 0) { Icon(Icons.Filled.Dashboard, null) }
                    NavItem(tab == MainActivity.TAB_MANUAL, { setTab(MainActivity.TAB_MANUAL) }, R.string.tab_manual, 0) { Icon(Icons.AutoMirrored.Filled.MenuBook, null) }
                    NavItem(tab == MainActivity.TAB_SERVICE, { setTab(MainActivity.TAB_SERVICE) }, R.string.tab_service, serviceBadge) { Icon(Icons.Filled.Build, null) }
                    NavItem(tab == MainActivity.TAB_BULLETINS, { setTab(MainActivity.TAB_BULLETINS) }, R.string.tab_bulletins, bulletinBadge) { Icon(Icons.Filled.Notifications, null) }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val o = overlay) {
                Overlay.List -> AircraftListScreen(state, onEdit = { overlay = Overlay.Edit(it) }, onSelected = { overlay = null })
                is Overlay.Edit -> AircraftEditScreen(o.aircraft) { overlay = null }
                Overlay.About -> AboutScreen()
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
private fun AboutScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text("v" + BuildConfigCompat.versionName(), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.disclaimer_text))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.about_text))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.about_sources), style = MaterialTheme.typography.bodySmall)
    }
}

private object BuildConfigCompat {
    @Composable
    fun versionName(): String {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        return runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }
}
