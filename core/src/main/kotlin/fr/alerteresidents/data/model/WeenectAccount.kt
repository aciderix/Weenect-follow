package fr.alerteresidents.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Compte Weenect (souvent un seul compte pour toutes les balises de l'établissement).
 * Le mot de passe est chiffré avec une clé de l'Android Keystore.
 */
@Entity(tableName = "weenect_accounts")
data class WeenectAccount(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val label: String,
    val username: String,
    val encryptedPassword: String,
    /** Identifiant partagé entre appareils (synchronisation Supabase). */
    val syncId: String? = null
)
