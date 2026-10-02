package fr.alerteresidents.desktop

import fr.alerteresidents.desktop.platform.DesktopCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlatformTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `le mot de passe chiffre se relit apres redemarrage`() {
        val dir = tmp.newFolder()
        val encrypted = DesktopCipher(dir).encrypt("motDePasse-é€")
        assertFalse(encrypted.contains("motDePasse"))
        assertEquals("motDePasse-é€", DesktopCipher(dir).decrypt(encrypted))
    }

    @Test
    fun `une donnee alteree ou d un autre poste est refusee`() {
        val encrypted = DesktopCipher(tmp.newFolder()).encrypt("secret")
        assertNull(DesktopCipher(tmp.newFolder()).decrypt(encrypted))
        assertNull(DesktopCipher(tmp.newFolder()).decrypt("n'importe quoi"))
    }

    @Test
    fun `un second lancement reveille l instance existante`() {
        val dir = tmp.newFolder()
        val shown = CountDownLatch(1)
        val first = SingleInstance(dir)
        assertTrue(first.acquire { shown.countDown() })
        try {
            val second = SingleInstance(dir)
            assertFalse(second.acquire { })
            assertTrue(second.signalExisting())
            assertTrue(shown.await(5, TimeUnit.SECONDS))
        } finally {
            first.release()
        }
        assertTrue(SingleInstance(dir).acquire { })
    }
}
