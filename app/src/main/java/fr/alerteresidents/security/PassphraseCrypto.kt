package fr.alerteresidents.security

import java.security.SecureRandom
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement par code (PBKDF2 + AES-GCM) pour l'export de configuration et le code PIN.
 * Indépendant de l'appareil : un fichier exporté peut être importé sur un autre téléphone.
 */
object PassphraseCrypto {
    private const val ITERATIONS = 120_000
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private val random = SecureRandom()

    fun encrypt(plain: String, passphrase: String): String {
        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(salt + iv + body, Base64.NO_WRAP)
    }

    /** null si le code est faux ou les données corrompues. */
    fun decrypt(encoded: String, passphrase: String): String? = try {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val salt = all.copyOfRange(0, SALT_SIZE)
        val iv = all.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        String(cipher.doFinal(all, SALT_SIZE + IV_SIZE, all.size - SALT_SIZE - IV_SIZE), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    /** Empreinte salée d'un code PIN : "sel:hash" en Base64. */
    fun hashPin(pin: String, salt: ByteArray = ByteArray(SALT_SIZE).also { random.nextBytes(it) }): String {
        val hash = pbkdf2(pin, salt, 256)
        return Base64.encodeToString(salt, Base64.NO_WRAP) + ":" + Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun verifyPin(pin: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 2) return false
        return try {
            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val expected = Base64.decode(parts[1], Base64.NO_WRAP)
            java.security.MessageDigest.isEqual(pbkdf2(pin, salt, expected.size * 8), expected)
        } catch (_: Exception) {
            false
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray) =
        SecretKeySpec(pbkdf2(passphrase, salt, 256), "AES")

    private fun pbkdf2(secret: String, salt: ByteArray, bits: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(secret.toCharArray(), salt, ITERATIONS, bits))
            .encoded
}
