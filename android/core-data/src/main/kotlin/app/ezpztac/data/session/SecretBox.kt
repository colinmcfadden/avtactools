package app.ezpztac.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals bytes so only this app on this device can open them again, and so a changed byte is noticed rather than read.
 * [open] throws [GeneralSecurityException] for anything it cannot vouch for: a different key, a changed byte, a truncated file.
 */
public interface SecretBox {
    public fun seal(plain: ByteArray): ByteArray

    @Throws(GeneralSecurityException::class)
    public fun open(sealed: ByteArray): ByteArray
}

/** AES-256-GCM with a fresh random IV for every seal, the IV stored in front of the ciphertext, and a purpose bound in as authenticated data. */
internal class AesGcmBox(private val key: () -> SecretKey, private val purpose: String) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(purpose.toByteArray())
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "unexpected IV length ${iv.size}" }
        return byteArrayOf(FORMAT) + iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < 1 + IV_BYTES + TAG_BYTES || sealed[0] != FORMAT) throw GeneralSecurityException("not something this app sealed")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, sealed, 1, IV_BYTES))
        cipher.updateAAD(purpose.toByteArray())
        return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val FORMAT: Byte = 1
    }
}

/**
 * A [SecretBox] whose key lives in the Android Keystore: generated on first use, never readable by the app (only usable), and never
 * leaving the device. Without a user-authentication requirement, so background sync can open the session while the phone is locked.
 */
public class KeystoreSecretBox(alias: String = DEFAULT_ALIAS, purpose: String = "ezpz-session-v1") : SecretBox {
    private val box = AesGcmBox({ keyFor(alias) }, purpose)

    override fun seal(plain: ByteArray): ByteArray = box.seal(plain)
    override fun open(sealed: ByteArray): ByteArray = box.open(sealed)

    private fun keyFor(alias: String): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    public companion object {
        public const val DEFAULT_ALIAS: String = "ezpz.session"
        private const val PROVIDER = "AndroidKeyStore"
    }
}
