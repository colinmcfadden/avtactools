package app.ezpztac.data.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyStore

/**
 * The Keystore-backed box. Robolectric has no hardware Keystore, so this only runs where the JVM offers an "AndroidKeyStore" provider;
 * otherwise it is skipped, and what is certain is the box it wraps (`EncryptedSessionStoreTest`). A device run is the real check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeystoreSecretBoxTest {
    @Test
    fun `a box on the Keystore seals and opens`() {
        val available = runCatching { KeyStore.getInstance("AndroidKeyStore") }.isSuccess
        assumeTrue("no AndroidKeyStore provider in this JVM", available)
        val box = KeystoreSecretBox(alias = "test.${System.nanoTime()}")
        val sealed = box.seal("hello".encodeToByteArray())
        assertNotEquals("hello".encodeToByteArray().toList(), sealed.toList())
        assertArrayEquals("hello".encodeToByteArray(), box.open(sealed))
    }
}
