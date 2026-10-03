package fr.alerteresidents

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import fr.alerteresidents.service.ResidentMonitoringService
import fr.alerteresidents.ui.components.AlarmDialog
import fr.alerteresidents.ui.components.PinDialog
import fr.alerteresidents.ui.screens.AlertsScreen
import fr.alerteresidents.ui.screens.DashboardScreen
import fr.alerteresidents.ui.screens.MapScreen
import fr.alerteresidents.ui.screens.MonitoringStatusScreen
import fr.alerteresidents.ui.screens.SettingsScreen
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.ui.theme.MyApplicationTheme
import fr.alerteresidents.ui.viewmodel.ResidentViewModel
import fr.alerteresidents.util.MapsNavigator

enum class NavDestination(val label: String) {
    DASHBOARD("Suivi"),
    MAP("Carte"),
    ALERTS("Alertes"),
    SETTINGS("Paramètres"),
    /** État de la surveillance / prise de poste (hors barre de navigation). */
    STATUS("État")
}

class MainActivity : ComponentActivity() {
    private val viewModel: ResidentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupLockscreenWakeup()
        enableEdgeToEdge()
        ResidentMonitoringService.start(this)
        handleIncomingIntent(intent)
        setContent {
            MyApplicationTheme {
                MainAppContent(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /** Afficher l'app par-dessus l'écran verrouillé pour les alertes. */
    private fun setupLockscreenWakeup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: return
        val residentId = intent.getLongExtra(EXTRA_RESIDENT_ID, -1L)
        when (action) {
            ACTION_SILENCE_ALARM -> viewModel.silenceAlarm(residentId.takeIf { it != -1L })
            ACTION_HANDLE_ALERT -> {
                viewModel.handleAlert(residentId)
                viewModel.navigateToAlert(residentId)
            }
            ACTION_VIEW_ALERT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.requestDismissKeyguard(this, null)
                }
                viewModel.navigateTo(NavDestination.DASHBOARD, residentId)
            }
            ACTION_VIEW_STATUS -> viewModel.navigateTo(NavDestination.STATUS)
            ACTION_VIEW_RESIDENT -> viewModel.navigateToAlert(residentId)
        }
        intent.removeExtra(EXTRA_ACTION)
    }

    companion object {
        const val EXTRA_ACTION = "EXTRA_ACTION"
        const val EXTRA_RESIDENT_ID = "EXTRA_RESIDENT_ID"
        const val ACTION_SILENCE_ALARM = "SILENCE_ALARM"
        const val ACTION_VIEW_ALERT = "VIEW_ALERT"
        const val ACTION_HANDLE_ALERT = "HANDLE_ALERT"
        const val ACTION_VIEW_STATUS = "VIEW_STATUS"
        const val ACTION_VIEW_RESIDENT = "VIEW_RESIDENT"
    }
}

@Composable
fun MainAppContent(viewModel: ResidentViewModel) {
    val context = LocalContext.current
    var currentDestination by rememberSaveable { androidx.compose.runtime.mutableStateOf(NavDestination.DASHBOARD) }
    var pinRequested by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val pendingNavigation by viewModel.pendingNavigation.collectAsState()
    val unacknowledgedAlerts by viewModel.unacknowledgedAlerts.collectAsState()
    val residents by viewModel.residents.collectAsState()
    val activeAlarms by viewModel.activeAlarms.collectAsState()
    val operationMessage by viewModel.operationMessage.collectAsState()
    val hasPin by viewModel.prefs.hasPin.collectAsState()
    val settingsUnlocked by viewModel.settingsUnlocked.collectAsState()
    val now by viewModel.now.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    fun go(dest: NavDestination) {
        if (dest == NavDestination.SETTINGS && hasPin && !settingsUnlocked) pinRequested = true
        else currentDestination = dest
    }

    LaunchedEffect(pendingNavigation) {
        pendingNavigation?.let { dest ->
            go(dest)
            viewModel.clearPendingNavigation()
        }
    }

    // Reverrouille les Paramètres après 2 min en arrière-plan (pas pendant un partage ou un réglage Android)
    var stoppedAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stoppedAt = System.currentTimeMillis() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 2 * 60_000L) {
            viewModel.lockSettings()
            if (currentDestination == NavDestination.SETTINGS && hasPin) currentDestination = NavDestination.DASHBOARD
        }
    }

    val startupPermissionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            startupPermissionsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    LaunchedEffect(operationMessage) {
        operationMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearOperationMessage()
        }
    }

    BackHandler(enabled = currentDestination != NavDestination.DASHBOARD) {
        currentDestination = NavDestination.DASHBOARD
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(modifier = Modifier.testTag("main_navigation_bar")) {
                NavigationBarItem(
                    selected = currentDestination == NavDestination.DASHBOARD || currentDestination == NavDestination.STATUS,
                    onClick = { go(NavDestination.DASHBOARD) },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text("Suivi") },
                    modifier = Modifier.testTag("nav_tab_dashboard")
                )
                NavigationBarItem(
                    selected = currentDestination == NavDestination.MAP,
                    onClick = { go(NavDestination.MAP) },
                    icon = { Icon(Icons.Default.Map, contentDescription = null) },
                    label = { Text("Carte") },
                    modifier = Modifier.testTag("nav_tab_map")
                )
                NavigationBarItem(
                    selected = currentDestination == NavDestination.ALERTS,
                    onClick = { go(NavDestination.ALERTS) },
                    icon = {
                        BadgedBox(badge = {
                            if (unacknowledgedAlerts.isNotEmpty()) {
                                Badge(containerColor = AppStatusColors.dangerSolid, contentColor = androidx.compose.ui.graphics.Color.White) { Text("${unacknowledgedAlerts.size}") }
                            }
                        }) { Icon(Icons.Default.NotificationsActive, contentDescription = null) }
                    },
                    label = { Text("Alertes") },
                    modifier = Modifier.testTag("nav_tab_alerts")
                )
                NavigationBarItem(
                    selected = currentDestination == NavDestination.SETTINGS,
                    onClick = { go(NavDestination.SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Paramètres") },
                    modifier = Modifier.testTag("nav_tab_settings")
                )
            }
        }
    ) { innerPadding ->
        val m = Modifier.padding(innerPadding)
        when (currentDestination) {
            NavDestination.DASHBOARD -> DashboardScreen(viewModel, onNavigateToMap = { currentDestination = NavDestination.MAP }, modifier = m)
            NavDestination.MAP -> MapScreen(viewModel, onBack = { currentDestination = NavDestination.DASHBOARD }, modifier = m)
            NavDestination.ALERTS -> AlertsScreen(viewModel, modifier = m)
            NavDestination.SETTINGS -> SettingsScreen(viewModel, onOpenStatus = { currentDestination = NavDestination.STATUS }, modifier = m)
            NavDestination.STATUS -> MonitoringStatusScreen(viewModel, onBack = { currentDestination = NavDestination.DASHBOARD }, modifier = m)
        }
    }

    if (pinRequested) {
        PinDialog(
            title = "Paramètres protégés",
            onDismiss = { pinRequested = false },
            onSubmit = { pin ->
                val ok = viewModel.unlockSettings(pin)
                if (ok) {
                    pinRequested = false
                    currentDestination = NavDestination.SETTINGS
                }
                ok
            }
        )
    }

    if (activeAlarms.isNotEmpty()) {
        AlarmDialog(
            alarms = activeAlarms.values.toList(),
            residentsById = residents.associateBy { it.id },
            now = now,
            onHandle = { id -> viewModel.handleAlert(id) },
            onSilence = { id -> viewModel.silenceAlarm(id) },
            onSilenceAll = { viewModel.silenceAlarm() },
            onNavigate = { r -> r.lastLatitude?.let { MapsNavigator.navigate(context, it, r.lastLongitude!!) } }
        )
    }
}
