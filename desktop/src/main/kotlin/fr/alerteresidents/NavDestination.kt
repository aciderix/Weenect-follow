package fr.alerteresidents

enum class NavDestination(val label: String) {
    DASHBOARD("Suivi"),
    MAP("Carte"),
    ALERTS("Journal"),
    SETTINGS("Paramètres"),
    STATUS("État")
}
