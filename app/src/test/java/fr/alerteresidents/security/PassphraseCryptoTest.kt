package fr.alerteresidents.security

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PassphraseCryptoTest {
    @Test
    fun `encrypt then decrypt with the right code`() {
        val blob = PassphraseCrypto.encrypt("""{"1":"secret"}""", "code-123")
        assertFalse(blob.contains("secret"))
        assertEquals("""{"1":"secret"}""", PassphraseCrypto.decrypt(blob, "code-123"))
    }

    @Test
    fun `wrong code or corrupted data gives null`() {
        val blob = PassphraseCrypto.encrypt("x", "code-123")
        assertNull(PassphraseCrypto.decrypt(blob, "autre"))
        assertNull(PassphraseCrypto.decrypt("pas du base64 !", "code-123"))
    }

    @Test
    fun `same text encrypts differently each time`() {
        assertNotEquals(PassphraseCrypto.encrypt("x", "c"), PassphraseCrypto.encrypt("x", "c"))
    }

    @Test
    fun `pin hash verification`() {
        val stored = PassphraseCrypto.hashPin("4321")
        assertFalse(stored.contains("4321"))
        assertTrue(PassphraseCrypto.verifyPin("4321", stored))
        assertFalse(PassphraseCrypto.verifyPin("1234", stored))
        assertFalse(PassphraseCrypto.verifyPin("4321", "corrompu"))
    }
}
