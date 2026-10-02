package fr.alerteresidents.security

/** Chiffre les mots de passe Weenect stockés localement (Keystore Android, DPAPI Windows). */
interface CredentialCipher {
    fun encrypt(plain: String): String
    /** null si le texte ne peut pas être déchiffré (clé perdue, donnée corrompue). */
    fun decrypt(encoded: String): String?
}
