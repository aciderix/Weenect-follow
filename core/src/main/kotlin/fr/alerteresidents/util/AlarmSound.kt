package fr.alerteresidents.util

/** Son joué lors d'une alarme de sortie de zone. */
enum class AlarmSound(val label: String, val description: String) {
    SIREN("Sirène incendie (intégrée)", "Très forte, identique sur tous les appareils"),
    PHONE("Sonnerie d'alarme de l'appareil", "Sonnerie d'alarme du téléphone ou de Windows"),
    BOTH("Les deux en même temps", "Sirène + sonnerie de l'appareil")
}
