package com.example

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.screens.AlertsScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.MapScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AlertRed
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.ResidentViewModel
import com.example.util.GeoUtils

enum class NavDestination(val label: String) {
    DASHBOARD("Tableau de bord"),
    MAP("Carte"),
    ALERTS("Alertes"),
    SETTINGS("Paramètres")
}

class MainActivity : ComponentActivity() {
    private val viewModel: ResidentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupLockscreenWakeup()
        enableEdgeToEdge()
        // Garantir que le service d'arrière-plan permanent est bien actif
        com.example.service.ResidentMonitoringService.start(this)
        handleIncomingIntent(intent)
        setContent {
            MyApplicationTheme {
                MainAppContent(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupLockscreenWakeup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setupLockscreenWakeup()
        handleIncomingIntent(intent)
    }

    private fun setupLockscreenWakeup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val action = intent?.getStringExtra("EXTRA_ACTION")
        val residentId = intent?.getLongExtra("EXTRA_RESIDENT_ID", -1L) ?: -1L
        if (action == "SILENCE_ALARM") {
            viewModel.silenceAlarm(residentId.takeIf { it != -1L })
        } else if (action == "VIEW_ALERT") {
            viewModel.navigateToAlert(residentId)
        }
    }
}

@Composable
fun MainAppContent(viewModel: ResidentViewModel) {
    val context = LocalContext.current
    var currentDestination by remember { mutableStateOf(NavDestination.DASHBOARD) }
    val pendingNavigation by viewModel.pendingNavigation.collectAsState()

    LaunchedEffect(pendingNavigation) {
        pendingNavigation?.let { dest ->
            currentDestination = dest
            viewModel.clearPendingNavigation()
        }
    }
    val unacknowledgedAlerts by viewModel.unacknowledgedAlerts.collectAsState()
    val outOfZoneResidents = viewModel.residents.collectAsState().value.filter { !it.isInZone }
    val isAlarmRinging by viewModel.isAlarmRinging.collectAsState()
    val facilityZone by viewModel.facilityZone.collectAsState()
    val operationMessage by viewModel.operationMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Demande de permissions au démarrage (Notifications & Localisation)
    val startupPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineLocationGranted) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            startupPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    LaunchedEffect(operationMessage) {
        operationMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearOperationMessage()
        }
    }

    // Handle back button to return to Dashboard if on a subscreen
    BackHandler(enabled = currentDestination != NavDestination.DASHBOARD) {
        currentDestination = NavDestination.DASHBOARD
    }

    fun launchNavigation(lat: Double, lon: Double) {
        val uri = Uri.parse("google.navigation:q=$lat,$lon&mode=w")
        val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        if (mapIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(mapIntent)
        } else {
            val browserUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")
            context.startActivity(Intent(Intent.ACTION_VIEW, browserUri))
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("main_navigation_bar")
            ) {
                NavigationBarItem(
                    selected = currentDestination == NavDestination.DASHBOARD,
                    onClick = { currentDestination = NavDestination.DASHBOARD },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Tableau de bord") },
                    label = { Text("Suivi") },
                    modifier = Modifier.testTag("nav_tab_dashboard")
                )

                NavigationBarItem(
                    selected = currentDestination == NavDestination.MAP,
                    onClick = { currentDestination = NavDestination.MAP },
                    icon = { Icon(Icons.Default.Map, contentDescription = "Carte radar") },
                    label = { Text("Carte") },
                    modifier = Modifier.testTag("nav_tab_map")
                )

                NavigationBarItem(
                    selected = currentDestination == NavDestination.ALERTS,
                    onClick = { currentDestination = NavDestination.ALERTS },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (unacknowledgedAlerts.isNotEmpty()) {
                                    Badge(containerColor = AlertRed) {
                                        Text("${unacknowledgedAlerts.size}")
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = "Alertes")
                        }
                    },
                    label = { Text("Alertes") },
                    modifier = Modifier.testTag("nav_tab_alerts")
                )

                NavigationBarItem(
                    selected = currentDestination == NavDestination.SETTINGS,
                    onClick = { currentDestination = NavDestination.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Paramètres") },
                    label = { Text("Paramètres") },
                    modifier = Modifier.testTag("nav_tab_settings")
                )
            }
        }
    ) { innerPadding ->
        when (currentDestination) {
            NavDestination.DASHBOARD -> {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToMap = { resident ->
                        currentDestination = NavDestination.MAP
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }
            NavDestination.MAP -> {
                MapScreen(
                    viewModel = viewModel,
                    onBack = { currentDestination = NavDestination.DASHBOARD },
                    modifier = Modifier.padding(innerPadding)
                )
            }
            NavDestination.ALERTS -> {
                AlertsScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            NavDestination.SETTINGS -> {
                SettingsScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }

    // Modal critique popup quand une alarme sonne
    if (isAlarmRinging && outOfZoneResidents.isNotEmpty()) {
        val resident = outOfZoneResidents.first()
        val distStr = GeoUtils.formatDistance(resident.distanceFromCenterMeters)

        AlertDialog(
            onDismissRequest = { /* Force l'équipe à agir via un bouton */ },
            icon = {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(AlertRed.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Urgence",
                        tint = AlertRed,
                        modifier = Modifier.size(36.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "🚨 SORTIE DE ZONE DÉTECTÉE !",
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    color = AlertRed
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = resident.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = "${resident.roomNumber} • Balise Weenect",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = AlertRed.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Position actuelle : à $distStr du centre de l'établissement.\nLe téléphone sonne à volume maximal pour alerter l'équipe.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AlertRed,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.silenceAlarm()
                        val lat = resident.lastLatitude ?: facilityZone.centerLatitude
                        val lon = resident.lastLongitude ?: facilityZone.centerLongitude
                        launchNavigation(lat, lon)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("btn_alarm_popup_guide")
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Guider vers le résident", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.silenceAlarm() },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("btn_alarm_popup_silence")
                ) {
                    Icon(Icons.Default.VolumeOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Couper sonnerie", fontSize = 13.sp)
                }
            }
        )
    }
}
