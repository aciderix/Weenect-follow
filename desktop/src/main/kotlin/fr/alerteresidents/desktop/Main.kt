package fr.alerteresidents.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import fr.alerteresidents.NavDestination
import fr.alerteresidents.desktop.platform.Platform
import fr.alerteresidents.ui.components.AlarmDialog
import fr.alerteresidents.ui.components.PinDialog
import fr.alerteresidents.ui.screens.AlertsScreen
import fr.alerteresidents.ui.screens.DashboardScreen
import fr.alerteresidents.ui.screens.MapScreen
import fr.alerteresidents.ui.screens.MonitoringStatusScreen
import fr.alerteresidents.ui.screens.SettingsScreen
import fr.alerteresidents.ui.theme.MyApplicationTheme
import fr.alerteresidents.ui.theme.AppStatusColors
import fr.alerteresidents.ui.viewmodel.ResidentViewModel
import fr.alerteresidents.util.DesktopNotifier
import fr.alerteresidents.util.MapsNavigator
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.logging.Logger
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import kotlin.system.exitProcess

private const val APP_TITLE = "Alerte Résidents"

/** Demandes d'affichage de la fenêtre (alarme, second lancement, clic sur l'icône). */
private val showRequests = MutableStateFlow(0)
private fun requestShow() { showRequests.value++ }

fun main(args: Array<String>) {
    Platform.setupLogging()
    val instance = SingleInstance(Platform.dataDir)
    if (!instance.acquire(onShowRequest = ::requestShow)) {
        // Déjà ouverte (ex. démarrage automatique + clic sur le raccourci) : on l'affiche et on s'arrête.
        instance.signalExisting()
        exitProcess(0)
    }
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        Logger.getLogger("Main").severe("Erreur non gérée (${t.name}) : ${e.stackTraceToString()}")
    }

    val startMinimized = "--minimized" in args
    val app = DesktopApp()
    val viewModel = ResidentViewModel(app)
    app.start()

    application(exitProcessOnExit = true) {
        val trayState = rememberTrayState()
        val icon = remember { appIcon() }
        var visible by remember { mutableStateOf(!startMinimized || !isTraySupported) }
        val activeAlarms by viewModel.activeAlarms.collectAsState()
        val showCount by showRequests.collectAsState()

        remember {
            app.notifier = TrayNotifier(trayState)
            if (startMinimized) {
                trayState.sendNotification(
                    Notification(APP_TITLE, "Surveillance active en arrière-plan. Double-cliquez sur l'icône pour ouvrir.", Notification.Type.Info)
                )
            }
        }

        fun quit() {
            val answer = JOptionPane.showConfirmDialog(
                null,
                "Quitter arrête la surveillance des résidents sur ce poste :\naucune alarme ne sonnera plus ici.\n\nQuitter quand même ?",
                APP_TITLE, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
            )
            if (answer == JOptionPane.YES_OPTION) {
                app.monitor.stop()
                app.alarms.stopAlarm()
                instance.release()
                exitApplication()
            }
        }

        Tray(
            icon = icon,
            state = trayState,
            tooltip = if (activeAlarms.isEmpty()) "$APP_TITLE — surveillance active" else "$APP_TITLE — ${activeAlarms.size} ALARME(S)",
            onAction = ::requestShow,
            menu = {
                Item("Ouvrir", onClick = ::requestShow)
                Item("Couper l'alarme", enabled = activeAlarms.isNotEmpty(), onClick = { viewModel.silenceAlarm() })
                Item("Actualiser maintenant", onClick = { app.monitor.refreshNow() })
                Separator()
                Item("Quitter (arrête la surveillance)", onClick = ::quit)
            }
        )

        val windowState = rememberWindowState(size = DpSize(1360.dp, 860.dp), position = WindowPosition(androidx.compose.ui.Alignment.Center))
        Window(
            onCloseRequest = {
                if (isTraySupported) {
                    // Fermer ne quitte pas : la surveillance continue dans la zone de notification.
                    visible = false
                    viewModel.lockSettings()
                } else {
                    quit() // sans icône de notification, la fenêtre cachée ne pourrait plus être rouverte
                }
            },
            state = windowState,
            visible = visible,
            title = if (activeAlarms.isEmpty()) APP_TITLE else "🚨 ${activeAlarms.size} alarme(s) — $APP_TITLE",
            icon = icon,
            // Pendant une alarme, la fenêtre reste au-dessus des autres applications.
            alwaysOnTop = activeAlarms.isNotEmpty()
        ) {
            window.minimumSize = java.awt.Dimension(900, 600)
            LaunchedEffect(showCount) {
                if (showCount == 0) return@LaunchedEffect
                visible = true
                windowState.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
            MyApplicationTheme {
                AppContent(viewModel)
            }
        }
    }
}

/** Notifications Windows via l'icône de la zone de notification. */
private class TrayNotifier(private val tray: TrayState) : DesktopNotifier {
    override fun alert(title: String, message: String) = tray.sendNotification(Notification(title, message, Notification.Type.Error))
    override fun warning(title: String, message: String) = tray.sendNotification(Notification(title, message, Notification.Type.Warning))
    override fun info(title: String, message: String) = tray.sendNotification(Notification(title, message, Notification.Type.Info))
    override fun bringToFront() = requestShow()
}

private fun appIcon(): Painter {
    val image = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")!!.use { ImageIO.read(it) }
    return BitmapPainter(image.toComposeImageBitmap())
}

/** Écran principal : rail de navigation à gauche ; en grand écran, la liste et la carte côte à côte. */
@Composable
fun AppContent(viewModel: ResidentViewModel) {
    var current by remember { mutableStateOf(NavDestination.DASHBOARD) }
    var pinRequested by remember { mutableStateOf(false) }
    val pendingNavigation by viewModel.pendingNavigation.collectAsState()
    val unacknowledged by viewModel.unacknowledgedAlerts.collectAsState()
    val residents by viewModel.residents.collectAsState()
    val activeAlarms by viewModel.activeAlarms.collectAsState()
    val operationMessage by viewModel.operationMessage.collectAsState()
    val hasPin by viewModel.prefs.hasPin.collectAsState()
    val settingsUnlocked by viewModel.settingsUnlocked.collectAsState()
    val now by viewModel.now.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    fun go(dest: NavDestination) {
        if (dest == NavDestination.SETTINGS && hasPin && !settingsUnlocked) pinRequested = true else current = dest
    }

    LaunchedEffect(pendingNavigation) {
        pendingNavigation?.let { go(it); viewModel.clearPendingNavigation() }
    }
    LaunchedEffect(settingsUnlocked) {
        if (!settingsUnlocked && hasPin && current == NavDestination.SETTINGS) current = NavDestination.DASHBOARD
    }
    LaunchedEffect(operationMessage) {
        operationMessage?.let { snackbar.showSnackbar(it); viewModel.clearOperationMessage() }
    }

    Scaffold(modifier = Modifier.fillMaxSize(), snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 1150.dp
            // En grand écran la carte est toujours visible à côté de la liste : pas d'onglet Carte séparé.
            if (wide && current == NavDestination.MAP) current = NavDestination.DASHBOARD
            Row(Modifier.fillMaxSize()) {
                NavigationRail(Modifier.fillMaxHeight().testTag("main_navigation_rail")) {
                    RailItem(current == NavDestination.DASHBOARD, "Suivi", { Icon(Icons.Default.Dashboard, null) }) { go(NavDestination.DASHBOARD) }
                    if (!wide) RailItem(current == NavDestination.MAP, "Carte", { Icon(Icons.Default.Map, null) }) { go(NavDestination.MAP) }
                    RailItem(current == NavDestination.ALERTS, "Journal", {
                        BadgedBox(badge = {
                            if (unacknowledged.isNotEmpty()) Badge(containerColor = AppStatusColors.dangerSolid, contentColor = Color.White) { Text("${unacknowledged.size}") }
                        }) { Icon(Icons.Default.NotificationsActive, null) }
                    }) { go(NavDestination.ALERTS) }
                    RailItem(current == NavDestination.STATUS, "État", { Icon(Icons.Default.MonitorHeart, null) }) { go(NavDestination.STATUS) }
                    RailItem(current == NavDestination.SETTINGS, "Paramètres", { Icon(Icons.Default.Settings, null) }) { go(NavDestination.SETTINGS) }
                }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (current) {
                        NavDestination.DASHBOARD -> if (wide) {
                            Row(Modifier.fillMaxSize()) {
                                DashboardScreen(viewModel, onNavigateToMap = { }, modifier = Modifier.width(520.dp).fillMaxHeight())
                                VerticalDivider()
                                MapScreen(viewModel, onBack = null, modifier = Modifier.weight(1f).fillMaxHeight())
                            }
                        } else {
                            DashboardScreen(viewModel, onNavigateToMap = { current = NavDestination.MAP })
                        }
                        NavDestination.MAP -> MapScreen(viewModel, onBack = { current = NavDestination.DASHBOARD })
                        NavDestination.ALERTS -> AlertsScreen(viewModel)
                        NavDestination.SETTINGS -> SettingsScreen(viewModel, onOpenStatus = { current = NavDestination.STATUS })
                        NavDestination.STATUS -> MonitoringStatusScreen(viewModel, onBack = { current = NavDestination.DASHBOARD })
                    }
                }
            }
        }
    }

    if (pinRequested) {
        PinDialog(
            title = "Paramètres protégés",
            onDismiss = { pinRequested = false },
            onSubmit = { pin ->
                viewModel.unlockSettings(pin).also { ok ->
                    if (ok) {
                        pinRequested = false
                        current = NavDestination.SETTINGS
                    }
                }
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
            onNavigate = { r -> r.lastLatitude?.let { MapsNavigator.navigate(it, r.lastLongitude!!) } }
        )
    }
}

@Composable
private fun RailItem(selected: Boolean, label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.padding(vertical = 4.dp).testTag("nav_$label")
    )
}
