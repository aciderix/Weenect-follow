package fr.alerteresidents.security

import java.security.SecureRandom
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
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
        return (salt + iv + body).toByteString().base64()
    }

    /** null si le code est faux ou les données corrompues. */
    fun decrypt(encoded: String, passphrase: String): String? = try {
        val all = encoded.trim().decodeBase64()?.toByteArray() ?: throw IllegalArgumentException("base64")
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
        return salt.toByteString().base64() + ":" + hash.toByteString().base64()
    }

    fun verifyPin(pin: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 2) return false
        return try {
            val salt = parts[0].decodeBase64()?.toByteArray() ?: return false
            val expected = parts[1].decodeBase64()?.toByteArray() ?: return false
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
