package fr.alerteresidents.desktop.platform

import com.sun.jna.platform.win32.Crypt32Util
import fr.alerteresidents.security.CredentialCipher
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Windows : DPAPI (chiffrement lié à la session Windows de l'utilisateur, comme le coffre-fort
 * de mots de passe du système). Ailleurs (développement) : AES-GCM avec une clé locale.
 */
class DesktopCipher(private val dataDir: File = Platform.dataDir) : CredentialCipher {

    override fun encrypt(plain: String): String =
        if (Platform.isWindows) "dpapi:" + Crypt32Util.cryptProtectData(plain.toByteArray(Charsets.UTF_8)).toByteString().base64()
        else "aes:" + aesEncrypt(plain)

    override fun decrypt(encoded: String): String? = try {
        when {
            encoded.startsWith("dpapi:") && Platform.isWindows ->
                String(Crypt32Util.cryptUnprotectData(encoded.removePrefix("dpapi:").decodeBase64()!!.toByteArray()), Charsets.UTF_8)
            encoded.startsWith("aes:") -> aesDecrypt(encoded.removePrefix("aes:"))
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private val key: SecretKeySpec by lazy {
        val f = File(dataDir, ".cle")
        val bytes = if (f.exists()) f.readBytes() else ByteArray(32).also { SecureRandom().nextBytes(it); f.writeBytes(it) }
        SecretKeySpec(bytes, "AES")
    }

    private fun aesEncrypt(plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return (iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))).toByteString().base64()
    }

    private fun aesDecrypt(b64: String): String {
        val all = b64.decodeBase64()!!.toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all, 0, 12)) }
        return String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }
}
